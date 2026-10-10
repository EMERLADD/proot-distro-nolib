#include <archive.h>
#include <archive_entry.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "pdn_events.h"
#include "pdn_instance.h"
#include "pdn_config.h"

char *pdn_rootfs_base(void);
static volatile sig_atomic_t stopped;
static const int64_t total_limit = INT64_C(64) * 1024 * 1024 * 1024;
static const int64_t file_limit = INT64_C(8) * 1024 * 1024 * 1024;

static void interrupt_archive(int number) { stopped = number; }

static int fail(const char *message)
{
    if (!pdn_events_has_error()) pdn_events_error(message);
    fprintf(stderr, "pdn: %s\n", message);
    return -1;
}

static int problem(const char *code, const char *message)
{
    pdn_events_problem(code, message, "Check the selected archive and destination, then retry");
    return fail(message);
}

static int system_error(const char *code, const char *message, int saved_errno)
{
    pdn_events_system_problem(code, message, "Check the reported cause and retry", saved_errno);
    return fail(message);
}

static int archive_problem(struct archive *archive, int writing)
{
    const char *message = archive_error_string(archive);
    if (!message) message = writing ? "cannot write archive data" : "corrupt archive";
    if (writing && (!strncmp(message, "Cannot extract through symlink ", 31) ||
                    !strcmp(message, "Path contains '..'") || !strcmp(message, "Path is absolute")))
        return problem("archive_unsafe", message);
    return system_error(writing == 2 ? "extraction_failed" : writing ? "file_io_failed" : "archive_corrupt", message, archive_errno(archive));
}

static int acquire_lock(int fd, const char *message)
{
    if (flock(fd, LOCK_EX | LOCK_NB) == 0) return 0;
    int saved = errno;
    return saved == EWOULDBLOCK || saved == EAGAIN ? problem("operation_busy", message) :
        system_error("lock_failed", message, saved);
}

static void rootfs_problem(int code)
{
    const char *kind = code == EACCES || code == EPERM ? "directory_permission" :
                       code == EROFS ? "directory_read_only" :
                       code == ENOTDIR ? "directory_not_directory" :
                       code == ENOENT ? "directory_missing" :
                       code == ENOSPC || code == EDQUOT ? "storage_full" :
                       code == ENOMEM ? "out_of_memory" : "directory_unavailable";
    pdn_events_problem(kind, "Cannot access rootfs directory",
                       !strcmp(kind, "storage_full") ? "Free storage or quota in the selected rootfs location, then retry" :
                       !strcmp(kind, "out_of_memory") ? "Free memory and retry" :
                       "Set PDN_ROOTFS_DIR to a writable directory");
}

static int valid_backup_name(const char *name)
{
    const unsigned char *p = (const unsigned char *)name;
    if (!p || !*p || *p == '.') return 0;
    for (; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '.' || *p == '_' || *p == '-')) return 0;
    return 1;
}

static int mkdirs(char *path)
{
    char *p;
    for (p = path + 1; ; p++) {
        char saved = *p;
        if (!saved || saved == '/') {
            *p = 0;
            if (mkdir(path, 0700) < 0 && errno != EEXIST) { *p = saved; return -1; }
            *p = saved;
        }
        if (!saved) break;
    }
    return 0;
}

static char *lookup(int fd, const char *name, int *count)
{
    int scanfd = openat(fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC), saved = 0;
    DIR *dir;
    struct dirent *entry;
    char *found = NULL;
    *count = 0;
    if (scanfd < 0) { *count = -1; return NULL; }
    dir = fdopendir(scanfd);
    if (!dir) { saved = errno; close(scanfd); *count = -1; errno = saved; return NULL; }
    for (;;) {
        errno = 0;
        entry = readdir(dir);
        if (!entry) { if (errno) { saved = errno; *count = -1; } break; }
        if (strcasecmp(name, entry->d_name)) continue;
        (*count)++;
        free(found);
        found = strdup(entry->d_name);
        if (!found) { saved = errno; *count = -1; break; }
    }
    closedir(dir);
    errno = saved;
    return found;
}

static int excluded(const char *path)
{
    size_t n = strcspn(path, "/");
    return (n == 8 && !strncmp(path, ".pdn-tmp", n)) ||
           (n >= 11 && !strncmp(path, ".pdn-config", 11));
}

static int runtime_dir(const char *path)
{
    return !strcmp(path, "dev") || !strcmp(path, "proc") || !strcmp(path, "sys");
}

static int l2s_target(const char *path)
{
    const char *leaf = strrchr(path, '/');
    leaf = leaf ? leaf + 1 : path;
    return !strncmp(leaf, ".l2s.", 5) || !strncmp(leaf, ".proot.l2s.", 11);
}

static const char *owned_suffix(const char *path, const char *root)
{
    size_t length = strlen(root), size = strlen(path);
    if (path[0] != '/') return NULL;
    if (!strncmp(path, root, length) && (!path[length] || path[length] == '/')) return path + length;
    if (size > 4096) return NULL;
    char prefix[4097];
    int saved = errno;
    for (size_t end = 1; end <= size; end++) {
        if (path[end] && path[end] != '/') continue;
        memcpy(prefix, path, end);
        prefix[end] = 0;
        char *resolved = realpath(prefix, NULL);
        int match = resolved && !strcmp(resolved, root);
        free(resolved);
        if (match) { errno = saved; return path + end; }
    }
    errno = saved;
    return NULL;
}

struct limits { int64_t total; unsigned entries; int64_t processed; };

static int pack(struct archive *out, int dirfd, const char *prefix, dev_t device,
                struct limits *limits, unsigned depth, const char *rootpath)
{
    DIR *dir;
    struct dirent *item;
    int result = -1;
    if (depth > 256) return problem("archive_limit_exceeded", "backup directory nesting exceeds limit");
    dir = fdopendir(openat(dirfd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (!dir) return system_error("file_io_failed", "cannot read backup directory", errno);
    for (;;) {
        struct stat st, opened;
        struct archive_entry *entry = NULL;
        char *path = NULL, link[4097], buffer[65536];
        int fd = -1, ok = 0;
        ssize_t size;
        errno = 0;
        item = readdir(dir);
        if (!item) { if (!errno) result = 0; else system_error("file_io_failed", "cannot read backup directory", errno); break; }
        if (!strcmp(item->d_name, ".") || !strcmp(item->d_name, "..")) continue;
        if (asprintf(&path, "%s%s%s", prefix, *prefix ? "/" : "", item->d_name) < 0) {
            problem("out_of_memory", "cannot allocate backup entry path");
            break;
        }
        if (excluded(path)) { free(path); continue; }
        if (stopped) goto entry_done;
        if (fstatat(dirfd, item->d_name, &st, AT_SYMLINK_NOFOLLOW) < 0) { system_error("file_io_failed", "cannot inspect backup entry", errno); goto entry_done; }
        if (S_ISSOCK(st.st_mode) || S_ISFIFO(st.st_mode) || S_ISCHR(st.st_mode) || S_ISBLK(st.st_mode)) {
            free(path); continue;
        }
        if ((!(S_ISDIR(st.st_mode) && runtime_dir(path)) && st.st_dev != device) || ++limits->entries > 1000000 || st.st_size < 0 ||
            (S_ISREG(st.st_mode) && (st.st_size > file_limit ||
                (limits->total += st.st_size) > total_limit))) { problem("archive_limit_exceeded", "backup exceeds archive limits"); goto entry_done; }
        entry = archive_entry_new();
        if (!entry) { problem("out_of_memory", "cannot allocate archive entry"); goto entry_done; }
        archive_entry_set_pathname(entry, path);
        archive_entry_set_mode(entry, st.st_mode & (S_IFMT | 01777));
        archive_entry_set_mtime(entry, st.st_mtim.tv_sec, st.st_mtim.tv_nsec);
        archive_entry_set_size(entry, S_ISREG(st.st_mode) ? st.st_size : 0);
        if (S_ISLNK(st.st_mode)) {
            size = readlinkat(dirfd, item->d_name, link, sizeof(link) - 1);
            if (size < 0) { system_error("file_io_failed", "cannot read backup symlink", errno); goto entry_done; }
            if (size >= (ssize_t)sizeof(link) - 1) { problem("archive_limit_exceeded", "backup symlink exceeds limit"); goto entry_done; }
            link[size] = 0;
            const char *target = link;
            const char *suffix = owned_suffix(link, rootpath);
            if (suffix)
                target = *suffix ? suffix : "/";
            else if (link[0] == '/' && l2s_target(link)) {
                problem("archive_unsafe", "hardlink backing data is outside rootfs; cannot make a portable backup");
                goto entry_done;
            }
            archive_entry_set_symlink(entry, target);
        } else if (!(S_ISDIR(st.st_mode) && runtime_dir(path))) {
            fd = openat(dirfd, item->d_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC |
                        (S_ISDIR(st.st_mode) ? O_DIRECTORY : O_NONBLOCK));
            if (fd < 0 || fstat(fd, &opened) < 0) { system_error("file_io_failed", "cannot read backup entry", errno); goto entry_done; }
            if (opened.st_dev != st.st_dev ||
                opened.st_ino != st.st_ino || opened.st_size != st.st_size) goto entry_done;
        }
        if (archive_write_header(out, entry) != ARCHIVE_OK) { archive_problem(out, 1); goto entry_done; }
        if (S_ISREG(st.st_mode)) {
            off_t remaining = st.st_size;
            while (remaining > 0) {
                if (stopped) goto entry_done;
                size = read(fd, buffer, remaining < (off_t)sizeof(buffer) ? (size_t)remaining : sizeof(buffer));
                if (size < 0) { system_error("file_io_failed", "cannot read backup data", errno); goto entry_done; }
                if (!size) { problem("file_io_failed", "backup source changed while reading"); goto entry_done; }
                if (archive_write_data(out, buffer, (size_t)size) != size) { archive_problem(out, 1); goto entry_done; }
                remaining -= size;
                limits->processed += size;
                pdn_events_progress(limits->processed, -1);
            }
            if (fstat(fd, &opened) < 0 || opened.st_size != st.st_size ||
                opened.st_mtim.tv_sec != st.st_mtim.tv_sec || opened.st_mtim.tv_nsec != st.st_mtim.tv_nsec)
                goto entry_done;
        }
        if (archive_write_finish_entry(out) != ARCHIVE_OK) { archive_problem(out, 1); goto entry_done; }
        if (S_ISDIR(st.st_mode) && !runtime_dir(path) && pack(out, fd, path, device, limits, depth + 1, rootpath) < 0)
            goto entry_done;
        ok = 1;
entry_done:
        if (fd >= 0) close(fd);
        archive_entry_free(entry);
        free(path);
        if (!ok) break;
    }
    closedir(dir);
    return result;
}

static const char *safe_path(const char *path)
{
    const char *p, *part;
    if (!path) return NULL;
    while (!strncmp(path, "./", 2)) path += 2;
    if (!*path || *path == '/' || strlen(path) > 4096) return NULL;
    for (p = part = path; ; p++) {
        if (*p && *p != '/') continue;
        if ((p - part == 2 && !strncmp(part, "..", 2)) ||
            (p - part == 1 && *part == '.') || (p == part && *p)) return NULL;
        if (!*p) return path;
        part = p + 1;
    }
}

static int unpack(int fd, const char *rootpath)
{
    struct archive *in = archive_read_new(), *out = archive_write_disk_new();
    struct archive_entry *entry;
    struct limits limits = {0};
    int status, result = -1;
    if (!in || !out) { problem("out_of_memory", "cannot allocate archive reader"); goto done; }
    archive_read_support_filter_gzip(in);
    archive_read_support_format_tar(in);
    archive_write_disk_set_options(out, ARCHIVE_EXTRACT_PERM | ARCHIVE_EXTRACT_TIME |
        ARCHIVE_EXTRACT_SECURE_SYMLINKS | ARCHIVE_EXTRACT_SECURE_NODOTDOT |
        ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS | ARCHIVE_EXTRACT_NO_OVERWRITE);
    if (archive_read_open_fd(in, fd, 65536) != ARCHIVE_OK) { archive_problem(in, 0); goto done; }
    while ((status = archive_read_next_header(in, &entry)) == ARCHIVE_OK) {
        const char *name = archive_entry_pathname(entry), *path;
        const void *block;
        size_t size;
        la_int64_t offset, end = 0, declared = archive_entry_size(entry);
        mode_t type = archive_entry_filetype(entry);
        if (stopped) goto done;
        if (++limits.entries > 1000000 || declared < 0 || declared > file_limit ||
            (limits.total += declared) > total_limit) { problem("archive_limit_exceeded", "archive exceeds extraction limits"); goto done; }
        if ((!strcmp(name, ".") || !strcmp(name, "./")) && type == AE_IFDIR) continue;
        path = safe_path(name);
        if (!path) { problem("archive_unsafe", "unsafe archive path"); goto done; }
        if (excluded(path)) { if (archive_read_data_skip(in) != ARCHIVE_OK) { archive_problem(in, 0); goto done; } continue; }
        if (type != AE_IFREG && type != AE_IFDIR && type != AE_IFLNK && !archive_entry_hardlink(entry)) { problem("archive_unsupported", "unsupported archive entry"); goto done; }
        if (archive_entry_hardlink(entry)) {
            const char *target = safe_path(archive_entry_hardlink(entry)), *p;
            char *relative;
            size_t depth = 0;
            if (!target || excluded(target) || !strcmp(path, target)) { problem("archive_unsafe", "unsafe archive hardlink"); goto done; }
            for (p = path; *p; p++) if (*p == '/') depth++;
            relative = malloc(depth * 3 + strlen(target) + 1);
            if (!relative) { problem("out_of_memory", "cannot allocate archive hardlink"); goto done; }
            relative[0] = 0;
            while (depth--) strcat(relative, "../");
            strcat(relative, target);
            archive_entry_set_hardlink(entry, NULL);
            archive_entry_set_filetype(entry, AE_IFLNK);
            archive_entry_set_size(entry, 0);
            archive_entry_set_symlink(entry, relative);
            free(relative);
        }
        const char *target = archive_entry_symlink(entry);
        if (target && target[0] == '/' && l2s_target(target)) {
            char *rebased;
            if (!safe_path(target + 1) || excluded(target + 1)) { problem("archive_unsafe", "unsafe archive symlink"); goto done; }
            if (asprintf(&rebased, "%s%s", rootpath, target) < 0) { problem("out_of_memory", "cannot allocate archive symlink"); goto done; }
            archive_entry_set_symlink(entry, rebased);
            free(rebased);
        }
        archive_entry_set_perm(entry, (archive_entry_perm(entry) & 01777) | (type == AE_IFDIR ? 0700 : 0));
        if (archive_write_header(out, entry) != ARCHIVE_OK) { archive_problem(out, 2); goto done; }
        while ((status = archive_read_data_block(in, &block, &size, &offset)) == ARCHIVE_OK) {
            if (stopped) goto done;
            if (offset < end || offset > declared || size > (uint64_t)(declared - offset)) { problem("archive_corrupt", "invalid archive data block"); goto done; }
            if (archive_write_data_block(out, block, size, offset) != ARCHIVE_OK) { archive_problem(out, 2); goto done; }
            end = offset + (la_int64_t)size;
            limits.processed += (int64_t)size;
            pdn_events_progress(limits.processed, -1);
        }
        if (status != ARCHIVE_EOF) { archive_problem(in, 0); goto done; }
        if (archive_write_finish_entry(out) != ARCHIVE_OK) { archive_problem(out, 2); goto done; }
    }
    if (status != ARCHIVE_EOF) { archive_problem(in, 0); goto done; }
    if (!limits.entries) { problem("archive_invalid", "empty rootfs archive"); goto done; }
    result = 0;
done:
    if (in && archive_read_free(in) != ARCHIVE_OK) result = -1;
    if (out && archive_write_close(out) != ARCHIVE_OK) { if (!pdn_events_has_error()) archive_problem(out, 2); result = -1; }
    if (out) archive_write_free(out);
    return result;
}

static int remove_stage(const char *path, const struct stat *st, int type, struct FTW *walk)
{
    (void)st; (void)type; (void)walk;
    return remove(path);
}

int pdn_archive(const char *name, const char *file, int restoring)
{
    char *base = NULL, *resolved = NULL, *found = NULL, *rootpath = NULL;
    char *destination = NULL, *parent = NULL, *temp = NULL;
    char stage[] = ".pdn-restore-XXXXXX";
    int basefd = -1, lock = -1, rootfd = -1, fd = -1, cwd = -1, outputfd = -1;
    int count, staged = 0, result = 2;
    struct stat st;
    struct sigaction action = {0}, old_int, old_term;
    struct archive *out = NULL;
    struct limits limits = {0};
    pdn_events_stage("preparing");
    if (!(restoring ? pdn_instance_valid_name(name) : valid_backup_name(name)) || !*file) { problem("archive_invalid", "invalid name or archive path"); return 2; }
    stopped = 0;
    action.sa_handler = interrupt_archive;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, &old_int);
    sigaction(SIGTERM, &action, &old_term);
    base = pdn_rootfs_base();
    if (!base || !*base) { rootfs_problem(ENOENT); goto done; }
    if (restoring && mkdirs(base) < 0) { rootfs_problem(errno); goto done; }
    resolved = realpath(base, NULL);
    if (!resolved || !strcmp(resolved, "/")) { rootfs_problem(resolved ? EINVAL : errno); goto done; }
    basefd = open(resolved, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (basefd < 0) { rootfs_problem(errno); goto done; }
    lock = openat(basefd, ".pdn-install.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0) { system_error("lock_failed", "cannot acquire archive/install lock", errno); goto done; }
    if (acquire_lock(lock, "cannot acquire archive/install lock") < 0) goto done;
    found = lookup(basefd, name, &count);
    if (count < 0 || (restoring ? count != 0 : count != 1)) {
        if (count < 0) system_error("file_io_failed", "rootfs missing or ambiguous", errno);
        else problem(count > 1 ? "name_ambiguous" : restoring ? "rootfs_exists" : "rootfs_missing",
                     restoring ? "rootfs already exists; restore requires a new name" : "rootfs missing or ambiguous"); goto done;
    }
    cwd = open(".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (cwd < 0) { system_error("file_io_failed", "cannot open current directory", errno); goto done; }
    if (restoring) {
        pdn_events_stage("restoring");
        pdn_events_progress(0, -1);
        fd = open(file, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0 || fstat(fd, &st) < 0) { system_error("file_io_failed", "archive must be a readable regular file", errno); goto done; }
        if (!S_ISREG(st.st_mode)) { problem("archive_invalid", "archive must be a readable regular file"); goto done; }
        if (fchdir(basefd) < 0 || !mkdtemp(stage)) { system_error("file_io_failed", "cannot create restore directory", errno); goto done; }
        staged = 1;
        if (asprintf(&rootpath, "%s/%s", resolved, name) < 0) { problem("out_of_memory", "cannot allocate restore path"); goto done; }
        if (chdir(stage) < 0) { system_error("file_io_failed", "cannot enter restore directory", errno); goto done; }
        if (unpack(fd, rootpath) < 0) { fail("invalid, unsafe or incomplete rootfs archive"); goto done; }
        if (lstat("bin", &st) < 0 || (!S_ISDIR(st.st_mode) && !S_ISLNK(st.st_mode))) {
            problem("archive_invalid", "archive does not contain a Linux rootfs at its top level"); goto done;
        }
        int instancefd = open(".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (instancefd < 0) { system_error("instance_metadata_failed", "cannot open restored instance", errno); goto done; }
        int metadata_result = pdn_instance_restore(instancefd, name);
        close(instancefd);
        if (metadata_result < 0) goto done;
        if (stopped) goto done;
        if (fchdir(basefd) < 0) { system_error("publish_failed", "cannot enter rootfs directory", errno); goto done; }
        pdn_events_stage("publishing");
        free(found); found = lookup(basefd, name, &count);
        if (count < 0) { system_error("publish_failed", "cannot publish rootfs without replacing an existing entry", errno); goto done; }
        if (count || syscall(SYS_renameat2, basefd, stage, basefd, name, 1) < 0) {
            if (count > 0 || errno == EEXIST) problem("rootfs_exists", "cannot publish rootfs without replacing an existing entry");
            else system_error("publish_failed", "cannot publish rootfs without replacing an existing entry", errno); goto done;
        }
        staged = 0;
        printf("Restored %s. Run: pdn login %s\n", name, name);
    } else {
        rootfd = openat(basefd, found, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (rootfd < 0 || fstat(rootfd, &st) < 0) { system_error("file_io_failed", "rootfs unavailable or in use; exit its sessions before backup", errno); goto done; }
        if (acquire_lock(rootfd, "rootfs unavailable or in use; exit its sessions before backup") < 0) goto done;
        struct pdn_instance instance;
        if (pdn_instance_read(rootfd, &instance) < 0) { system_error("instance_metadata_failed", "cannot read backup instance metadata", errno); goto done; }
        if (asprintf(&rootpath, "%s/%s", resolved, found) < 0) { problem("out_of_memory", "cannot allocate backup path"); goto done; }
        destination = strdup(file);
        if (!destination) { problem("out_of_memory", "cannot allocate backup path"); goto done; }
        char *slash = strrchr(destination, '/');
        const char *leaf = slash ? slash + 1 : destination;
        if (!*leaf || !strcmp(leaf, ".") || !strcmp(leaf, "..")) { problem("archive_invalid", "invalid backup path"); goto done; }
        if (slash) { *slash = 0; parent = realpath(*destination ? destination : "/", NULL); }
        else parent = realpath(".", NULL);
        if (!parent) { system_error("file_io_failed", "backup output must be outside the rootfs", errno); goto done; }
        if ((!strncmp(parent, rootpath, strlen(rootpath)) &&
            (!parent[strlen(rootpath)] || parent[strlen(rootpath)] == '/'))) {
            problem("archive_unsafe", "backup output must be outside the rootfs"); goto done;
        }
        char *target = NULL;
        if (asprintf(&target, "%s/%s", parent, leaf) < 0) { problem("out_of_memory", "cannot allocate backup path"); goto done; }
        free(destination); destination = target;
        if (lstat(destination, &st) == 0) { problem("file_exists", "backup file already exists"); goto done; }
        if (errno != ENOENT) { system_error("file_io_failed", "backup file already exists", errno); goto done; }
        outputfd = open(parent, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (outputfd < 0) { system_error("file_io_failed", "cannot open backup output directory", errno); goto done; }
        temp = strdup(".pdn-backup-XXXXXX");
        if (!temp) { problem("out_of_memory", "cannot allocate backup filename"); goto done; }
        if (fchdir(outputfd) < 0) { system_error("file_io_failed", "cannot enter backup output directory", errno); goto done; }
        fd = mkstemp(temp);
        if (fd < 0) { system_error("file_io_failed", "cannot create backup output", errno); goto done; }
        pdn_events_stage("backing_up");
        pdn_events_progress(0, -1);
        out = archive_write_new();
        if (!out) { problem("out_of_memory", "backup failed; source may be changing or unreadable"); goto done; }
        if (archive_write_set_format_pax_restricted(out) != ARCHIVE_OK ||
            archive_write_add_filter_gzip(out) != ARCHIVE_OK || archive_write_open_fd(out, fd) != ARCHIVE_OK) {
            archive_problem(out, 1); goto done;
        }
        if (fstat(rootfd, &st) < 0) { system_error("file_io_failed", "backup failed; source may be changing or unreadable", errno); goto done; }
        if (pack(out, rootfd, "", st.st_dev, &limits, 0, rootpath) < 0) { fail("backup failed; source may be changing or unreadable"); goto done; }
        if (archive_write_close(out) != ARCHIVE_OK) { archive_problem(out, 1); goto done; }
        if (fsync(fd) < 0) { system_error("file_io_failed", "backup failed; source may be changing or unreadable", errno); goto done; }
        if (stopped) goto done;
        pdn_events_stage("publishing");
        if (syscall(SYS_renameat2, outputfd, temp, outputfd, strrchr(destination, '/') + 1, 1) < 0) {
            if (errno == EEXIST) problem("file_exists", "cannot publish backup without replacing an existing file");
            else system_error("publish_failed", "cannot publish backup without replacing an existing file", errno); goto done;
        }
        printf("Backup saved: %s\n", destination);
    }
    result = 0;
done:
    if (out) archive_write_free(out);
    if (fd >= 0) close(fd);
    if (temp && fd >= 0 && outputfd >= 0) unlinkat(outputfd, temp, 0);
    if (outputfd >= 0) close(outputfd);
    if (staged && fchdir(basefd) == 0 && nftw(stage, remove_stage, 16, FTW_DEPTH | FTW_PHYS) < 0)
        fprintf(stderr, "pdn: could not clean restore staging directory: %s\n", stage);
    if (cwd >= 0) { if (fchdir(cwd) < 0) result = 2; close(cwd); }
    if (rootfd >= 0) close(rootfd);
    if (lock >= 0) close(lock);
    if (basefd >= 0) close(basefd);
    free(base); free(resolved); free(found); free(rootpath); free(destination); free(parent); free(temp);
    sigaction(SIGINT, &old_int, NULL);
    sigaction(SIGTERM, &old_term, NULL);
    if (stopped) { pdn_events_cancelled(stopped); return 128 + stopped; }
    if (result && !pdn_events_has_error()) pdn_events_problem("file_io_failed", "Archive operation failed", "Check the selected archive and destination, then retry");
    if (result) fprintf(stderr, "pdn: %s failed; existing files were not replaced\n", restoring ? "restore" : "backup");
    return result;
}

struct moved_link {
    char *path;
    char *original;
    char *replacement;
    struct moved_link *next;
    int changed;
};


static int collect_links(int fd, const char *prefix, const char *oldroot,
                         const char *newroot, struct moved_link **links,
                         size_t *bytes, unsigned *entries, dev_t device, unsigned depth)
{
    DIR *dir = fdopendir(openat(fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    int result = -1;
    if (!dir) return system_error("file_io_failed", "cannot inspect instance links", errno);
    if (depth > 256) { closedir(dir); return problem("archive_limit_exceeded", "instance nesting exceeds limit"); }
    for (;;) {
        struct dirent *entry;
        struct stat st;
        errno = 0;
        entry = readdir(dir);
        if (!entry) { result = errno ? system_error("file_io_failed", "cannot read instance directory", errno) : 0; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        if (stopped) break;
        if (++*entries > 1000000) { problem("archive_limit_exceeded", "instance entry count exceeds limit"); break; }
        char *path = NULL;
        if (asprintf(&path, "%s%s%s", prefix, *prefix ? "/" : "", entry->d_name) < 0) { problem("out_of_memory", "cannot allocate link path"); break; }
        if (fstatat(fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) < 0) { free(path); system_error("file_io_failed", "cannot inspect instance entry", errno); break; }
        if (st.st_dev != device && !runtime_dir(path)) { free(path); problem("archive_unsafe", "instance contains a mounted directory"); break; }
        if (S_ISDIR(st.st_mode) && !runtime_dir(path) && strcmp(path, ".pdn-tmp")) {
            int child = openat(fd, entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            int status = child < 0 ? system_error("file_io_failed", "cannot open instance directory", errno) : collect_links(child, path, oldroot, newroot, links, bytes, entries, device, depth + 1);
            if (child >= 0) close(child);
            free(path);
            if (status < 0) break;
            continue;
        }
        if (S_ISLNK(st.st_mode)) {
            char target[4097];
            ssize_t count = readlinkat(fd, entry->d_name, target, sizeof(target) - 1);
            if (count < 0 || count >= (ssize_t)sizeof(target) - 1) { free(path); system_error("file_io_failed", "cannot read instance symlink", count < 0 ? errno : ENAMETOOLONG); break; }
            target[count] = 0;
            const char *suffix = owned_suffix(target, oldroot);
            if (suffix) {
                struct moved_link *link = calloc(1, sizeof(*link));
                if (!link) { free(path); problem("out_of_memory", "cannot allocate link journal"); break; }
                link->path = path;
                link->original = strdup(target);
                if (!link->original || asprintf(&link->replacement, "%s%s", newroot, suffix) < 0) {
                    free(link->original); free(path); free(link); problem("out_of_memory", "cannot allocate link journal"); break;
                }
                *bytes += sizeof(*link) + strlen(path) + strlen(target) + strlen(link->replacement) + 3;
                link->next = *links;
                *links = link;
                if (*bytes > 64 * 1024 * 1024 || strlen(link->replacement) >= 4096) { problem("archive_limit_exceeded", "instance link journal exceeds limit"); break; }
                continue;
            }
        }
        free(path);
    }
    closedir(dir);
    return result;
}

static int replace_link(int rootfd, struct moved_link *link, int restoring)
{
    char *path = strdup(link->path), *save = NULL, *part;
    int parent = dup(rootfd), result = -1;
    char temporary[80];
    if (!path || parent < 0) { free(path); if (parent >= 0) close(parent); return -1; }
    part = strtok_r(path, "/", &save);
    while (part) {
        char *next = strtok_r(NULL, "/", &save);
        if (!next) break;
        int child = openat(parent, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        close(parent); parent = child;
        if (parent < 0) goto done;
        part = next;
    }
    char current[4097];
    ssize_t length = readlinkat(parent, part, current, sizeof(current) - 1);
    if (length < 0 || length >= (ssize_t)sizeof(current) - 1) goto done;
    current[length] = 0;
    if (strcmp(current, restoring ? link->replacement : link->original)) { errno = EBUSY; goto done; }
    for (unsigned attempt = 0; attempt < 100; attempt++) {
        snprintf(temporary, sizeof(temporary), ".pdn-link.%ld.%u", (long)getpid(), attempt);
        if (symlinkat(restoring ? link->original : link->replacement, parent, temporary) == 0) {
            result = renameat(parent, temporary, parent, part);
            int saved = errno;
            unlinkat(parent, temporary, 0);
            errno = saved;
            break;
        }
        if (errno != EEXIST) break;
    }
done:
    { int saved = errno; if (parent >= 0) close(parent); free(path); errno = saved; }
    return result;
}

static int migrate_options(PdnOptions *destination, const PdnOptions *source,
                           const char *oldroot, const char *newroot)
{
    for (int i = 0; i < source->bind_count; i++) {
        const char *binding = source->binds[i], *colon = strchr(binding, ':');
        char *host = colon ? strndup(binding, (size_t)(colon - binding)) : strdup(binding);
        char *replacement = NULL;
        if (!host) return problem("out_of_memory", "cannot allocate instance configuration");
        const char *suffix = owned_suffix(host, oldroot);
        if (suffix) {
            if (asprintf(&replacement, "%s%s%s", newroot, suffix, colon ? colon : "") < 0) replacement = NULL;
        } else replacement = strdup(binding);
        free(host);
        if (!replacement) return problem("out_of_memory", "cannot allocate instance configuration");
        int status = pdn_option_add(destination, 'b', replacement);
        free(replacement);
        if (status) return -1;
    }
    for (int i = 0; i < source->env_count; i++) if (pdn_option_add(destination, 'e', source->env[i])) return -1;
    if (source->user && pdn_option_add(destination, 'u', source->user)) return -1;
    if (source->workdir && pdn_option_add(destination, 'w', source->workdir)) return -1;
    return 0;
}

static int snapshot_file(int rootfd, const char *leaf, char temporary[80], size_t limit)
{
    int source = openat(rootfd, leaf, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    int target = -1, result = -1;
    struct stat st;
    if (source < 0) return -1;
    if (fstat(source, &st) < 0) goto done;
    if (!S_ISREG(st.st_mode) || st.st_size < 0 || (uint64_t)st.st_size > limit) { errno = EINVAL; goto done; }
    for (unsigned attempt = 0; attempt < 100; attempt++) {
        snprintf(temporary, 80, ".pdn-rollback.%ld.%u", (long)getpid(), attempt);
        target = openat(rootfd, temporary, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (target >= 0 || errno != EEXIST) break;
    }
    if (target < 0) goto done;
    char buffer[4096];
    off_t remaining = st.st_size;
    while (remaining) {
        ssize_t count = read(source, buffer, remaining < (off_t)sizeof(buffer) ? (size_t)remaining : sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { if (!count) errno = EIO; goto done; }
        size_t written = 0;
        while (written < (size_t)count) {
            ssize_t bytes = write(target, buffer + written, (size_t)count - written);
            if (bytes < 0 && errno == EINTR) continue;
            if (bytes <= 0) { if (!bytes) errno = EIO; goto done; }
            written += (size_t)bytes;
        }
        remaining -= count;
    }
    if (fchmod(target, st.st_mode & 0777) < 0 || fsync(target) < 0) goto done;
    result = 0;
done:
    { int saved = errno;
      close(source);
      if (target >= 0) { if (close(target) < 0 && !result) { result = -1; saved = errno; } if (result) unlinkat(rootfd, temporary, 0); }
      if (result) temporary[0] = 0;
      errno = saved;
    }
    return result;
}

static int move_instance(const char *source, const char *target, int cloning)
{
    char *base = NULL, *resolved = NULL, *found = NULL, *destination = NULL, *rootpath = NULL;
    int basefd = -1, lock = -1, rootfd = -1, cwd = -1, archivefd = -1, stagefd = -1;
    int result = 2, count, staged = 0, config_changed = 0, metadata_changed = 0, keep_snapshots = 0;
    char stage[] = ".pdn-clone-XXXXXX";
    char config_snapshot[80] = {0}, metadata_snapshot[80] = {0};
    struct stat st;
    struct moved_link *links = NULL;
    size_t journal_bytes = 0;
    unsigned entries = 0;
    PdnOptions options = {0}, migrated = {0};
    struct sigaction action = {0}, old_int, old_term;
    struct archive *out = NULL;
    struct limits limits = {0};
    if (!valid_backup_name(source) || !pdn_instance_valid_name(target)) { problem("invalid_argument", "invalid source or destination instance name"); return 2; }
    stopped = 0; action.sa_handler = interrupt_archive; sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, &old_int); sigaction(SIGTERM, &action, &old_term);
    pdn_events_stage("preparing");
    base = pdn_rootfs_base();
    if (!base || !(resolved = realpath(base, NULL)) || !strcmp(resolved, "/")) { rootfs_problem(base ? errno : ENOENT); goto done; }
    basefd = open(resolved, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (basefd < 0) { rootfs_problem(errno); goto done; }
    lock = openat(basefd, ".pdn-install.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0) { system_error("lock_failed", "cannot open instance lock", errno); goto done; }
    if (acquire_lock(lock, "another instance operation is running") < 0) goto done;
    found = lookup(basefd, source, &count);
    if (count != 1) { if (count < 0) system_error("file_io_failed", "cannot find source instance", errno); else problem(count ? "name_ambiguous" : "rootfs_missing", "source instance missing or ambiguous"); goto done; }
    char *existing = lookup(basefd, target, &count);
    int self = count == 1 && existing && !strcmp(existing, found);
    free(existing);
    if (count < 0) { system_error("file_io_failed", "cannot inspect destination instance", errno); goto done; }
    if (count && !(self && !cloning && strcmp(found, target))) { problem(self && !cloning ? "invalid_argument" : "rootfs_exists", "destination instance already exists"); goto done; }
    rootfd = openat(basefd, found, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (rootfd < 0) { system_error("file_io_failed", "cannot open source instance", errno); goto done; }
    if (acquire_lock(rootfd, "exit source instance sessions before copying or renaming") < 0) goto done;
    struct pdn_instance instance;
    int has_metadata = pdn_instance_read(rootfd, &instance);
    if (has_metadata < 0 || (has_metadata && strcmp(instance.name, found))) { system_error("instance_metadata_failed", "invalid source instance metadata", has_metadata < 0 ? errno : EINVAL); goto done; }
    if (pdn_options_load(rootfd, &options)) goto done;
    int has_config = fstatat(rootfd, ".pdn-config", &st, AT_SYMLINK_NOFOLLOW) == 0;
    if (asprintf(&rootpath, "%s/%s", resolved, found) < 0 || asprintf(&destination, "%s/%s", resolved, target) < 0) { problem("out_of_memory", "cannot allocate instance paths"); goto done; }
    if (migrate_options(&migrated, &options, rootpath, destination) < 0) goto done;
    if (cloning) {
        cwd = open(".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (cwd < 0 || fchdir(basefd) < 0 || !mkdtemp(stage)) { system_error("file_io_failed", "cannot create clone staging directory", errno); goto done; }
        staged = 1;
        stagefd = openat(basefd, stage, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (stagefd < 0) { system_error("file_io_failed", "cannot open clone staging directory", errno); goto done; }
        archivefd = openat(stagefd, ".pdn-copy.tar", O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        out = archive_write_new();
        if (archivefd < 0 || !out) { system_error("file_io_failed", "cannot create clone archive", errno); goto done; }
        if (unlinkat(stagefd, ".pdn-copy.tar", 0) < 0) { system_error("file_io_failed", "cannot remove clone archive name", errno); goto done; }
        if (archive_write_set_format_pax_restricted(out) != ARCHIVE_OK || archive_write_open_fd(out, archivefd) != ARCHIVE_OK) { archive_problem(out, 1); goto done; }
        pdn_events_stage("cloning"); pdn_events_progress(0, -1);
        if (fstat(rootfd, &st) < 0 || pack(out, rootfd, "", st.st_dev, &limits, 0, rootpath) < 0) goto done;
        if (archive_write_close(out) != ARCHIVE_OK) { archive_problem(out, 1); goto done; }
        archive_write_free(out); out = NULL;
        if (lseek(archivefd, 0, SEEK_SET) < 0 || fchdir(stagefd) < 0) { system_error("file_io_failed", "cannot read clone archive", errno); goto done; }
        if (unpack(archivefd, destination) < 0) goto done;
        if (pdn_instance_relocate(stagefd, target, 1) < 0 || (has_config && pdn_options_save(stagefd, &migrated))) goto done;
        if (stopped) goto done;
        pdn_events_stage("publishing");
        existing = lookup(basefd, target, &count); free(existing);
        if (count || syscall(SYS_renameat2, basefd, stage, basefd, target, 1) < 0) { if (count > 0 || errno == EEXIST) problem("rootfs_exists", "destination instance already exists"); else system_error("publish_failed", "cannot publish cloned instance", errno); goto done; }
        staged = 0;
    } else {
        if (fstat(rootfd, &st) < 0) { system_error("file_io_failed", "cannot inspect source instance", errno); goto done; }
        if (collect_links(rootfd, "", rootpath, destination, &links, &journal_bytes, &entries, st.st_dev, 0) < 0 || stopped) goto done;
        if ((has_config && snapshot_file(rootfd, ".pdn-config", config_snapshot, 65536) < 0) ||
            (has_metadata && snapshot_file(rootfd, ".pdn-instance", metadata_snapshot, 4096) < 0)) {
            system_error("file_io_failed", "cannot prepare instance rollback", errno); goto done;
        }
        pdn_events_stage("renaming");
        for (struct moved_link *link = links; link; link = link->next) {
            if (stopped) goto done;
            if (replace_link(rootfd, link, 0) < 0) { system_error("file_io_failed", "cannot migrate instance symlink", errno); goto done; }
            link->changed = 1;
        }
        if (has_config) { config_changed = 1; if (pdn_options_save(rootfd, &migrated)) goto done; }
        if (has_metadata) { metadata_changed = 1; if (pdn_instance_relocate(rootfd, target, 0) < 0) goto done; }
        if (stopped) goto done;
        pdn_events_stage("publishing");
        existing = lookup(basefd, target, &count);
        self = count == 1 && existing && !strcmp(existing, found); free(existing);
        if ((count && !self) || count < 0 || syscall(SYS_renameat2, basefd, found, basefd, target, 1) < 0) { if (count > 0 && !self) problem("rootfs_exists", "destination instance already exists"); else system_error("publish_failed", "cannot publish renamed instance", errno); goto done; }
    }
    result = 0;
    printf("%s %s to %s. Run: pdn login %s\n", cloning ? "Cloned" : "Renamed", found, target, target);
done:
    if (result && !cloning && rootfd >= 0) {
        int rollback_failed = 0;
        if (metadata_changed && renameat(rootfd, metadata_snapshot, rootfd, ".pdn-instance") < 0) rollback_failed = 1;
        if (config_changed && renameat(rootfd, config_snapshot, rootfd, ".pdn-config") < 0) rollback_failed = 1;
        for (struct moved_link *link = links; link; link = link->next) if (link->changed && replace_link(rootfd, link, 1) < 0) rollback_failed = 1;
        if (rollback_failed) {
            keep_snapshots = 1;
            pdn_events_problem("rollback_failed", "Instance rename rollback incomplete", "Inspect source instance links, metadata and configuration before retrying; restore a backup if needed");
            fprintf(stderr, "pdn: rollback incomplete; inspect source instance before retrying\n");
        }
    }
    if (!keep_snapshots && rootfd >= 0 && *metadata_snapshot) unlinkat(rootfd, metadata_snapshot, 0);
    if (!keep_snapshots && rootfd >= 0 && *config_snapshot) unlinkat(rootfd, config_snapshot, 0);
    if (out) archive_write_free(out);
    if (archivefd >= 0) close(archivefd);
    if (stagefd >= 0) close(stagefd);
    if (staged && fchdir(basefd) == 0 && nftw(stage, remove_stage, 16, FTW_DEPTH | FTW_PHYS) < 0) fprintf(stderr, "pdn: cannot clean clone staging directory: %s\n", stage);
    if (cwd >= 0) { if (fchdir(cwd) < 0) result = 2; close(cwd); }
    pdn_options_free(&options); pdn_options_free(&migrated);
    while (links) { struct moved_link *next = links->next; free(links->path); free(links->original); free(links->replacement); free(links); links = next; }
    if (rootfd >= 0) close(rootfd);
    if (lock >= 0) close(lock);
    if (basefd >= 0) close(basefd);
    free(base); free(resolved); free(found); free(destination); free(rootpath);
    sigaction(SIGINT, &old_int, NULL); sigaction(SIGTERM, &old_term, NULL);
    if (stopped && result) { pdn_events_cancelled(stopped); return 128 + stopped; }
    if (result && !pdn_events_has_error()) problem("file_io_failed", "instance operation failed; inspect source and storage");
    return result;
}

int pdn_clone(const char *source, const char *target) { return move_instance(source, target, 1); }
int pdn_rename(const char *source, const char *target) { return move_instance(source, target, 0); }
