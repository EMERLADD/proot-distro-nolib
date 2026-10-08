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

char *pdn_rootfs_base(void);
static volatile sig_atomic_t stopped;
static const int64_t total_limit = INT64_C(64) * 1024 * 1024 * 1024;
static const int64_t file_limit = INT64_C(8) * 1024 * 1024 * 1024;

static void interrupt_archive(int number) { stopped = number; }

static int fail(const char *message)
{
    pdn_events_error(message);
    fprintf(stderr, "pdn: %s\n", message);
    return -1;
}

static void rootfs_problem(int code)
{
    const char *kind = code == EACCES || code == EPERM ? "directory_permission" :
                       code == EROFS ? "directory_read_only" :
                       code == ENOTDIR ? "directory_not_directory" :
                       code == ENOENT ? "directory_missing" : "directory_unavailable";
    pdn_events_problem(kind, "Cannot access rootfs directory",
                       "Set PDN_ROOTFS_DIR to a writable directory");
}

static int valid_name(const char *name)
{
    const unsigned char *p = (const unsigned char *)name;
    if (!*p || *p == '.') return 0;
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
    DIR *dir = fdopendir(openat(fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    struct dirent *entry;
    char *found = NULL;
    *count = 0;
    if (!dir) { *count = -1; return NULL; }
    errno = 0;
    while ((entry = readdir(dir))) {
        if (strcasecmp(name, entry->d_name)) continue;
        (*count)++;
        free(found);
        found = strdup(entry->d_name);
        if (!found) { *count = -1; break; }
    }
    if (errno) *count = -1;
    closedir(dir);
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

struct limits { int64_t total; unsigned entries; int64_t processed; };

static int pack(struct archive *out, int dirfd, const char *prefix, dev_t device,
                struct limits *limits, unsigned depth, const char *rootpath)
{
    DIR *dir;
    struct dirent *item;
    int result = -1;
    if (depth > 256) return fail("backup directory nesting exceeds limit");
    dir = fdopendir(openat(dirfd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (!dir) return fail("cannot read backup directory");
    for (;;) {
        struct stat st, opened;
        struct archive_entry *entry = NULL;
        char *path = NULL, link[4097], buffer[65536];
        int fd = -1, ok = 0;
        ssize_t size;
        errno = 0;
        item = readdir(dir);
        if (!item) { if (!errno) result = 0; break; }
        if (!strcmp(item->d_name, ".") || !strcmp(item->d_name, "..")) continue;
        if (asprintf(&path, "%s%s%s", prefix, *prefix ? "/" : "", item->d_name) < 0) break;
        if (excluded(path)) { free(path); continue; }
        if (stopped || fstatat(dirfd, item->d_name, &st, AT_SYMLINK_NOFOLLOW) < 0) goto entry_done;
        if (S_ISSOCK(st.st_mode) || S_ISFIFO(st.st_mode) || S_ISCHR(st.st_mode) || S_ISBLK(st.st_mode)) {
            free(path); continue;
        }
        if ((!(S_ISDIR(st.st_mode) && runtime_dir(path)) && st.st_dev != device) || ++limits->entries > 1000000 || st.st_size < 0 ||
            (S_ISREG(st.st_mode) && (st.st_size > file_limit ||
                (limits->total += st.st_size) > total_limit))) goto entry_done;
        entry = archive_entry_new();
        if (!entry) goto entry_done;
        archive_entry_set_pathname(entry, path);
        archive_entry_set_mode(entry, st.st_mode & (S_IFMT | 01777));
        archive_entry_set_mtime(entry, st.st_mtim.tv_sec, st.st_mtim.tv_nsec);
        archive_entry_set_size(entry, S_ISREG(st.st_mode) ? st.st_size : 0);
        if (S_ISLNK(st.st_mode)) {
            size = readlinkat(dirfd, item->d_name, link, sizeof(link) - 1);
            if (size < 0 || size >= (ssize_t)sizeof(link) - 1) goto entry_done;
            link[size] = 0;
            const char *target = link;
            size_t root_length = strlen(rootpath);
            if (!strncmp(link, rootpath, root_length) && link[root_length] == '/')
                target = link + root_length;
            else if (link[0] == '/' && l2s_target(link)) {
                fail("hardlink backing data is outside rootfs; cannot make a portable backup");
                goto entry_done;
            }
            archive_entry_set_symlink(entry, target);
        } else if (!(S_ISDIR(st.st_mode) && runtime_dir(path))) {
            fd = openat(dirfd, item->d_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC |
                        (S_ISDIR(st.st_mode) ? O_DIRECTORY : O_NONBLOCK));
            if (fd < 0 || fstat(fd, &opened) < 0 || opened.st_dev != st.st_dev ||
                opened.st_ino != st.st_ino || opened.st_size != st.st_size) goto entry_done;
        }
        if (archive_write_header(out, entry) != ARCHIVE_OK) goto entry_done;
        if (S_ISREG(st.st_mode)) {
            off_t remaining = st.st_size;
            while (remaining > 0) {
                if (stopped) goto entry_done;
                size = read(fd, buffer, remaining < (off_t)sizeof(buffer) ? (size_t)remaining : sizeof(buffer));
                if (size <= 0 || archive_write_data(out, buffer, (size_t)size) != size) goto entry_done;
                remaining -= size;
                limits->processed += size;
                pdn_events_progress(limits->processed, -1);
            }
            if (fstat(fd, &opened) < 0 || opened.st_size != st.st_size ||
                opened.st_mtim.tv_sec != st.st_mtim.tv_sec || opened.st_mtim.tv_nsec != st.st_mtim.tv_nsec)
                goto entry_done;
        }
        if (archive_write_finish_entry(out) != ARCHIVE_OK) goto entry_done;
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
    if (!in || !out) goto done;
    archive_read_support_filter_gzip(in);
    archive_read_support_format_tar(in);
    archive_write_disk_set_options(out, ARCHIVE_EXTRACT_PERM | ARCHIVE_EXTRACT_TIME |
        ARCHIVE_EXTRACT_SECURE_SYMLINKS | ARCHIVE_EXTRACT_SECURE_NODOTDOT |
        ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS | ARCHIVE_EXTRACT_NO_OVERWRITE);
    if (archive_read_open_fd(in, fd, 65536) != ARCHIVE_OK) goto done;
    while ((status = archive_read_next_header(in, &entry)) == ARCHIVE_OK) {
        const char *name = archive_entry_pathname(entry), *path;
        const void *block;
        size_t size;
        la_int64_t offset, end = 0, declared = archive_entry_size(entry);
        mode_t type = archive_entry_filetype(entry);
        if (stopped || ++limits.entries > 1000000 || declared < 0 || declared > file_limit ||
            (limits.total += declared) > total_limit) goto done;
        if ((!strcmp(name, ".") || !strcmp(name, "./")) && type == AE_IFDIR) continue;
        path = safe_path(name);
        if (!path) goto done;
        if (excluded(path)) { if (archive_read_data_skip(in) != ARCHIVE_OK) goto done; continue; }
        if (type != AE_IFREG && type != AE_IFDIR && type != AE_IFLNK && !archive_entry_hardlink(entry)) goto done;
        if (archive_entry_hardlink(entry)) {
            const char *target = safe_path(archive_entry_hardlink(entry)), *p;
            char *relative;
            size_t depth = 0;
            if (!target || excluded(target) || !strcmp(path, target)) goto done;
            for (p = path; *p; p++) if (*p == '/') depth++;
            relative = malloc(depth * 3 + strlen(target) + 1);
            if (!relative) goto done;
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
            if (!safe_path(target + 1) || excluded(target + 1) ||
                asprintf(&rebased, "%s%s", rootpath, target) < 0) goto done;
            archive_entry_set_symlink(entry, rebased);
            free(rebased);
        }
        archive_entry_set_perm(entry, (archive_entry_perm(entry) & 01777) | (type == AE_IFDIR ? 0700 : 0));
        if (archive_write_header(out, entry) != ARCHIVE_OK) goto done;
        while ((status = archive_read_data_block(in, &block, &size, &offset)) == ARCHIVE_OK) {
            if (stopped || offset < end || offset > declared || size > (uint64_t)(declared - offset) ||
                archive_write_data_block(out, block, size, offset) != ARCHIVE_OK) goto done;
            end = offset + (la_int64_t)size;
            limits.processed += (int64_t)size;
            pdn_events_progress(limits.processed, -1);
        }
        if (status != ARCHIVE_EOF || archive_write_finish_entry(out) != ARCHIVE_OK) goto done;
    }
    if (status != ARCHIVE_EOF || !limits.entries) goto done;
    result = 0;
done:
    if (in && archive_read_free(in) != ARCHIVE_OK) result = -1;
    if (out && archive_write_free(out) != ARCHIVE_OK) result = -1;
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
    if (!valid_name(name) || !*file) { fail("invalid name or archive path"); return 2; }
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
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0) { fail("cannot acquire archive/install lock"); goto done; }
    found = lookup(basefd, name, &count);
    if (count < 0 || (restoring ? count != 0 : count != 1)) {
        fail(restoring ? "rootfs already exists; restore requires a new name" : "rootfs missing or ambiguous"); goto done;
    }
    cwd = open(".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (cwd < 0) goto done;
    if (restoring) {
        pdn_events_stage("restoring");
        pdn_events_progress(0, -1);
        fd = open(file, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0 || fstat(fd, &st) < 0 || !S_ISREG(st.st_mode)) { fail("archive must be a readable regular file"); goto done; }
        if (fchdir(basefd) < 0 || !mkdtemp(stage)) goto done;
        staged = 1;
        if (asprintf(&rootpath, "%s/%s", resolved, name) < 0 ||
            chdir(stage) < 0 || unpack(fd, rootpath) < 0) { fail("invalid, unsafe or incomplete rootfs archive"); goto done; }
        if (lstat("bin", &st) < 0 || (!S_ISDIR(st.st_mode) && !S_ISLNK(st.st_mode))) {
            fail("archive does not contain a Linux rootfs at its top level"); goto done;
        }
        if (stopped || fchdir(basefd) < 0) goto done;
        pdn_events_stage("publishing");
        free(found); found = lookup(basefd, name, &count);
        if (count || syscall(SYS_renameat2, basefd, stage, basefd, name, 1) < 0) {
            fail("cannot publish rootfs without replacing an existing entry"); goto done;
        }
        staged = 0;
        printf("Restored %s. Run: pdn login %s\n", name, name);
    } else {
        rootfd = openat(basefd, found, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (rootfd < 0 || fstat(rootfd, &st) < 0 || flock(rootfd, LOCK_EX | LOCK_NB) < 0) {
            fail("rootfs unavailable or in use; exit its sessions before backup"); goto done;
        }
        if (asprintf(&rootpath, "%s/%s", resolved, found) < 0) goto done;
        destination = strdup(file);
        if (!destination) goto done;
        char *slash = strrchr(destination, '/');
        const char *leaf = slash ? slash + 1 : destination;
        if (!*leaf || !strcmp(leaf, ".") || !strcmp(leaf, "..")) goto done;
        if (slash) { *slash = 0; parent = realpath(*destination ? destination : "/", NULL); }
        else parent = realpath(".", NULL);
        if (!parent || (!strncmp(parent, rootpath, strlen(rootpath)) &&
            (!parent[strlen(rootpath)] || parent[strlen(rootpath)] == '/'))) {
            fail("backup output must be outside the rootfs"); goto done;
        }
        char *target = NULL;
        if (asprintf(&target, "%s/%s", parent, leaf) < 0) goto done;
        free(destination); destination = target;
        if (lstat(destination, &st) == 0 || errno != ENOENT) { fail("backup file already exists"); goto done; }
        outputfd = open(parent, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        temp = strdup(".pdn-backup-XXXXXX");
        if (outputfd < 0 || !temp || fchdir(outputfd) < 0) goto done;
        fd = mkstemp(temp);
        if (fd < 0) goto done;
        pdn_events_stage("backing_up");
        pdn_events_progress(0, -1);
        out = archive_write_new();
        if (!out || archive_write_set_format_pax_restricted(out) != ARCHIVE_OK ||
            archive_write_add_filter_gzip(out) != ARCHIVE_OK || archive_write_open_fd(out, fd) != ARCHIVE_OK ||
            fstat(rootfd, &st) < 0 || pack(out, rootfd, "", st.st_dev, &limits, 0, rootpath) < 0 ||
            archive_write_close(out) != ARCHIVE_OK || fsync(fd) < 0 || stopped) {
            fail("backup failed; source may be changing or unreadable"); goto done;
        }
        pdn_events_stage("publishing");
        if (syscall(SYS_renameat2, outputfd, temp, outputfd, strrchr(destination, '/') + 1, 1) < 0) {
            fail("cannot publish backup without replacing an existing file"); goto done;
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
    if (result) fprintf(stderr, "pdn: %s failed; existing files were not replaced\n", restoring ? "restore" : "backup");
    return result;
}
