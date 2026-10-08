#include <archive.h>
#include <archive_entry.h>
#include <curl/curl.h>
#include <mbedtls/md.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#include "pdn_distros.h"
#include <sys/wait.h>

char *pdn_rootfs_base(void);
static volatile sig_atomic_t cancelled;

static void cancel_install(int signal_number)
{
    cancelled = signal_number;
}

static int error(const char *message)
{
    fprintf(stderr, "pdn: %s\n", message);
    return -1;
}

static int rootfs_error(const char *action, const char *path, int code)
{
    const char *configured = getenv("PDN_ROOTFS_DIR");
    fprintf(stderr, "pdn: cannot %s rootfs directory '%s': %s (errno=%d)\n",
            action, path, strerror(code), code);
    fprintf(stderr, "pdn: rootfs location selected by %s; set PDN_ROOTFS_DIR to a writable directory.\n",
            configured && *configured ? "PDN_ROOTFS_DIR" : "$HOME/.local/share/pdn/rootfs");
    return -1;
}

static int mkdirs(char *path)
{
    char *p;
    for (p = path + 1; ; p++) {
        char saved = *p;
        if (saved == '/' || saved == '\0') {
            *p = '\0';
            if (mkdir(path, 0700) < 0 && errno != EEXIST) { *p = saved; return -1; }
            *p = saved;
        }
        if (!saved) break;
    }
    return 0;
}

static int remove_entry(const char *path, const struct stat *st, int type, struct FTW *ftw)
{
    (void)st; (void)type; (void)ftw;
    return remove(path);
}

static int exists_distro(const char *name)
{
    DIR *dir = opendir(".");
    struct dirent *entry;
    int found = 0;
    if (!dir) return 1;
    while ((entry = readdir(dir))) {
        if (!strcasecmp(entry->d_name, name)) { found = 1; break; }
    }
    closedir(dir);
    return found;
}

struct transfer {
    FILE *file;
    size_t size;
    int percent;
    const struct distro *distro;
};

static size_t receive(void *data, size_t size, size_t count, void *context)
{
    struct transfer *transfer = context;
    size_t bytes = size * count;
    if (cancelled || bytes > transfer->distro->size - transfer->size) return 0;
    bytes = fwrite(data, 1, bytes, transfer->file);
    transfer->size += bytes;
    return bytes;
}

static int progress(void *context, curl_off_t total, curl_off_t current,
                    curl_off_t upload_total, curl_off_t uploaded)
{
    struct transfer *transfer = context;
    int percent = (int)(current * 100 / transfer->distro->size);
    (void)total; (void)upload_total; (void)uploaded;
    if (percent / 10 > transfer->percent / 10) {
        fprintf(stderr, "Downloading %s: %d%%\n", transfer->distro->name, percent);
        transfer->percent = percent;
    }
    return cancelled != 0;
}

static int download(const struct distro *distro, const char *url, const char *path)
{
    CURL *curl;
    CURLcode status;
    struct transfer transfer = {.distro = distro};
    char details[CURL_ERROR_SIZE] = {0};
    const char *ca = getenv("PDN_CA_BUNDLE");
    transfer.file = fopen(path, "wb");
    if (!transfer.file) return error(strerror(errno));
    curl = curl_easy_init();
    if (!curl) { fclose(transfer.file); return error("cannot initialize HTTPS"); }
    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_USERAGENT, "proot-distro-nolib/0.6.1");
    curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "https");
    curl_easy_setopt(curl, CURLOPT_REDIR_PROTOCOLS_STR, "https");
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_MAXREDIRS, 3L);
    curl_easy_setopt(curl, CURLOPT_FAILONERROR, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 8L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, distro->timeout);
    curl_easy_setopt(curl, CURLOPT_LOW_SPEED_LIMIT, 1024L);
    curl_easy_setopt(curl, CURLOPT_LOW_SPEED_TIME, 30L);
    curl_easy_setopt(curl, CURLOPT_MAXFILESIZE_LARGE, (curl_off_t)distro->size);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 1L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 2L);
    if (ca && *ca) curl_easy_setopt(curl, CURLOPT_CAINFO, ca);
    curl_easy_setopt(curl, CURLOPT_ERRORBUFFER, details);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, receive);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &transfer);
    curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);
    curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, progress);
    curl_easy_setopt(curl, CURLOPT_XFERINFODATA, &transfer);
    status = curl_easy_perform(curl);
    curl_easy_cleanup(curl);
    if (fclose(transfer.file) != 0 && status == CURLE_OK) status = CURLE_WRITE_ERROR;
    if (status != CURLE_OK) return error(*details ? details : curl_easy_strerror(status));
    return 0;
}

static int copy_archive(const struct distro *distro, const char *source, const char *destination)
{
    FILE *input = fopen(source, "rb"), *output;
    char buffer[65536];
    size_t size, total = 0;
    int result = 0;
    if (!input) return error("cannot open local archive");
    output = fopen(destination, "wb");
    if (!output) { fclose(input); return error("cannot stage local archive"); }
    while ((size = fread(buffer, 1, sizeof(buffer), input)) > 0) {
        if (cancelled || (total += size) > distro->size || fwrite(buffer, 1, size, output) != size) {
            result = -1; break;
        }
    }
    if (ferror(input)) result = -1;
    fclose(input);
    if (fclose(output) != 0) result = -1;
    if (result) return error("cannot copy local archive or archive is too large");
    return 0;
}

static int verify(const struct distro *distro, const char *path)
{
    unsigned char digest[32];
    char hex[65];
    struct stat st;
    size_t i;
    if (stat(path, &st) < 0 || st.st_size != (off_t)distro->size) return error("incorrect archive size");
    if (mbedtls_md_file(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), path, digest) != 0)
        return error("cannot hash archive");
    for (i = 0; i < sizeof(digest); i++) snprintf(hex + i * 2, 3, "%02x", digest[i]);
    if (strcmp(hex, distro->sha256) != 0) return error("SHA256 mismatch; archive rejected");
    return 0;
}

int pdn_available(void)
{
    size_t i;
    puts("Available ARM64 systems (compressed download size):");
    for (i = 0; i < sizeof(distro_names) / sizeof(distro_names[0]); i++) {
        struct distro distro;
        find_distro(distro_names[i], &distro);
        printf("%-8s %-30s %.1f MiB\n", distro.name, distro.version, distro.size / 1048576.0);
    }
    return 0;
}

int pdn_mirrors(const char *name)
{
    size_t i, j;
    struct distro distro;
    if (name && find_distro(name, &distro) < 0) return error("unknown distro; run pdn list --available") != 0;
    for (i = 0; i < sizeof(distro_names) / sizeof(distro_names[0]); i++) {
        if (name && strcasecmp(name, distro_names[i])) continue;
        find_distro(distro_names[i], &distro);
        printf("%s ARM64 rootfs mirrors (automatic fallback order):\n", distro.name);
        for (j = 0; j < 5 && distro.mirrors[j].name; j++)
            printf("%-10s %s\n", distro.mirrors[j].name, distro.mirrors[j].base);
    }
    return 0;
}

static int download_mirrors(const struct distro *distro, const struct mirror *mirrors, size_t count,
                            int selected, const char *path)
{
    size_t i;
    for (i = 0; i < count; i++) {
        if (selected >= 0 && i != (size_t)selected) continue;
        if (cancelled) return -1;
        printf("Trying mirror: %s\n", mirrors[i].name);
        fflush(stdout);
        if (download(distro, mirrors[i].url, path) == 0 && !cancelled && verify(distro, path) == 0)
            return (int)i;
        if (unlink(path) < 0 && errno != ENOENT) return error("cannot remove failed download");
        if (cancelled) return -1;
        if (selected >= 0) break;
        if (i + 1 < count) fprintf(stderr, "Mirror %s failed; trying the next source.\n", mirrors[i].name);
    }
    return error("no mirror provided a verified rootfs archive");
}

static const char *safe_archive_path(const char *path)
{
    const char *p, *start;
    if (!path) return NULL;
    while (!strncmp(path, "./", 2)) path += 2;
    if (!*path || *path == '/' || strlen(path) > 4096) return NULL;
    for (start = p = path; ; p++) {
        if (*p && *p != '/') continue;
        if ((p - start == 2 && !strncmp(start, "..", 2)) ||
            (p - start == 1 && *start == '.') || (p == start && *p)) return NULL;
        if (!*p) return path;
        start = p + 1;
    }
}

static int convert_hardlink(struct archive_entry *entry)
{
    const char *target = safe_archive_path(archive_entry_hardlink(entry));
    const char *name = safe_archive_path(archive_entry_pathname(entry)), *p;
    size_t depth = 0;
    char *relative;
    if (!target || !name || !strcmp(target, name)) return error("unsafe archive hardlink");
    for (p = name; *p; p++) if (*p == '/') depth++;
    relative = malloc(depth * 3 + strlen(target) + 1);
    if (!relative) return error("out of memory");
    relative[0] = '\0';
    while (depth--) strcat(relative, "../");
    strcat(relative, target);
    archive_entry_set_hardlink(entry, NULL);
    archive_entry_set_filetype(entry, AE_IFLNK);
    archive_entry_set_size(entry, 0);
    archive_entry_set_symlink(entry, relative);
    free(relative);
    return 0;
}

static int extract(const struct distro *distro, const char *path)
{
    struct archive *input = archive_read_new(), *output = archive_write_disk_new();
    struct archive_entry *entry;
    const void *block;
    size_t size;
    la_int64_t offset, total = 0;
    int status, result = -1, entries = 0;
    archive_read_support_filter_gzip(input);
    archive_read_support_format_tar(input);
    archive_write_disk_set_options(output, ARCHIVE_EXTRACT_PERM | ARCHIVE_EXTRACT_TIME |
        ARCHIVE_EXTRACT_SECURE_SYMLINKS | ARCHIVE_EXTRACT_SECURE_NODOTDOT |
        ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS | ARCHIVE_EXTRACT_NO_OVERWRITE);
    if (archive_read_open_filename(input, path, 65536) != ARCHIVE_OK) {
        error(archive_error_string(input)); goto done;
    }
    while ((status = archive_read_next_header(input, &entry)) == ARCHIVE_OK) {
        mode_t type = archive_entry_filetype(entry);
        if (cancelled) { error("installation interrupted"); goto done; }
        if (++entries > 100000 || archive_entry_size(entry) < 0 ||
            archive_entry_size(entry) > 256 * 1024 * 1024 ||
            (total += archive_entry_size(entry)) > distro->extracted_limit) {
            error("archive exceeds extraction limits"); goto done;
        }
        if (type != AE_IFREG && type != AE_IFDIR && type != AE_IFLNK &&
            !archive_entry_hardlink(entry)) { error("unsupported archive entry"); goto done; }
        if (archive_entry_hardlink(entry) && convert_hardlink(entry) < 0) goto done;
        archive_entry_set_perm(entry, (archive_entry_perm(entry) & 01777) | (type == AE_IFDIR ? 0700 : 0));
        if (archive_write_header(output, entry) != ARCHIVE_OK) {
            error(archive_error_string(output)); goto done;
        }
        while ((status = archive_read_data_block(input, &block, &size, &offset)) == ARCHIVE_OK) {
            if (cancelled || archive_write_data_block(output, block, size, offset) != ARCHIVE_OK) {
                error("cannot write extracted data"); goto done;
            }
        }
        if (status != ARCHIVE_EOF || archive_write_finish_entry(output) != ARCHIVE_OK) {
            error("corrupt archive or incomplete write"); goto done;
        }
    }
    if (status != ARCHIVE_EOF) { error(archive_error_string(input)); goto done; }
    result = 0;
done:
    archive_read_free(input);
    if (archive_write_free(output) != ARCHIVE_OK) result = -1;
    return result;
}

static int write_config(int parent, const char *name, const char *text)
{
    int fd = openat(parent, name, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0644);
    size_t size = strlen(text);
    int result;
    if (fd < 0) return -1;
    result = write(fd, text, size) == (ssize_t)size ? 0 : -1;
    if (close(fd) < 0) result = -1;
    return result;
}

static int open_directory_at(int parent, const char *name)
{
    if (mkdirat(parent, name, 0755) < 0 && errno != EEXIST) return -1;
    return openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
}

static int append_certificate(FILE *output, const char *path, size_t *total)
{
    char buffer[8192];
    FILE *input = fopen(path, "rb");
    size_t count;
    int result = 0;
    if (!input) return -1;
    while ((count = fread(buffer, 1, sizeof(buffer), input)) > 0) {
        if ((*total += count) > 8 * 1024 * 1024 || fwrite(buffer, 1, count, output) != count) {
            result = -1; break;
        }
    }
    if (ferror(input) || fputc('\n', output) == EOF) result = -1;
    fclose(input);
    return result;
}

static int seed_certificates(int etc)
{
    int ssl = open_directory_at(etc, "ssl"), certs = -1, fd = -1, result = -1;
    FILE *output = NULL;
    DIR *directory = NULL;
    struct dirent *entry;
    struct stat st;
    size_t total = 0;
    const char *bundle = getenv("PDN_CA_BUNDLE");
    if (ssl < 0) goto done;
    certs = open_directory_at(ssl, "certs");
    if (certs < 0) goto done;
    if (fstatat(certs, "ca-certificates.crt", &st, AT_SYMLINK_NOFOLLOW) == 0) {
        result = S_ISREG(st.st_mode) && st.st_size > 0 ? 0 : -1;
        goto done;
    }
    fd = openat(certs, "ca-certificates.crt", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0644);
    if (fd < 0) goto done;
    output = fdopen(fd, "wb");
    if (!output) { close(fd); goto done; }
    if (bundle && *bundle) {
        if (append_certificate(output, bundle, &total) < 0) goto done;
    } else {
        directory = opendir("/system/etc/security/cacerts");
        if (!directory) goto done;
        while ((entry = readdir(directory))) {
            char path[512];
            if (entry->d_name[0] == '.') continue;
            snprintf(path, sizeof(path), "/system/etc/security/cacerts/%s", entry->d_name);
            if (append_certificate(output, path, &total) < 0) goto done;
        }
    }
    result = total ? 0 : -1;
 done:
    if (directory) closedir(directory);
    if (output && fclose(output) != 0) result = -1;
    if (certs >= 0) close(certs);
    if (ssl >= 0) close(ssl);
    return result;
}

static int configure(const struct distro *distro, const struct mirror *mirror)
{
    char repositories[2048];
    const char *base = mirror->packages ? mirror->packages : mirror->base;
    int etc = open("etc", O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC), config = -1, extra = -1, result = -1, length;
    struct stat st;
    if (etc < 0) return error("missing etc directory");
    if (!strcmp(distro->name, "alpine")) {
        length = snprintf(repositories, sizeof(repositories), "%s/v3.24/main\n%s/v3.24/community\n", base, base);
        config = openat(etc, "apk", O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (length < 0 || (size_t)length >= sizeof(repositories) || config < 0) goto done;
        result = write_config(config, "repositories", repositories);
    } else if (!strcmp(distro->name, "arch")) {
        config = open_directory_at(etc, "pacman.d");
        length = snprintf(repositories, sizeof(repositories), "Server = %s/$arch/$repo\n", base);
        if (length < 0 || (size_t)length >= sizeof(repositories) || config < 0) goto done;
        size_t i, used = (size_t)length;
        for (i = 0; i < 5 && distro->mirrors[i].name; i++) {
            const struct mirror *next = &distro->mirrors[i];
            if (!strcmp(next->name, mirror->name)) continue;
            length = snprintf(repositories + used, sizeof(repositories) - used,
                              "Server = %s/$arch/$repo\n", next->packages ? next->packages : next->base);
            if (length < 0 || (size_t)length >= sizeof(repositories) - used) goto done;
            used += (size_t)length;
        }
        if (write_config(config, "mirrorlist", repositories) < 0) goto done;
        result = write_config(etc, "pacman.conf",
            "[options]\nArchitecture = aarch64\nHoldPkg = pacman glibc\nCheckSpace\n"
            "ParallelDownloads = 1\nDisableSandboxFilesystem\nDisableSandboxSyscalls\n"
            "SigLevel = Required DatabaseOptional\nLocalFileSigLevel = Optional\n"
            "[core]\nInclude = /etc/pacman.d/mirrorlist\n"
            "[extra]\nInclude = /etc/pacman.d/mirrorlist\n"
            "[alarm]\nInclude = /etc/pacman.d/mirrorlist\n"
            "[aur]\nInclude = /etc/pacman.d/mirrorlist\n");
    } else {
        config = open_directory_at(etc, "apt");
        if (config < 0 || seed_certificates(etc) < 0) goto done;
        extra = open_directory_at(config, "sources.list.d");
        if (extra < 0) goto done;
        int ubuntu = !strcmp(distro->name, "ubuntu");
        if (ubuntu)
            length = snprintf(repositories, sizeof(repositories),
                "Types: deb\nURIs: %s\nSuites: noble noble-updates noble-backports noble-security\n"
                "Components: main universe restricted multiverse\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n", base);
        else
            length = snprintf(repositories, sizeof(repositories),
                "Types: deb\nURIs: %s/debian\nSuites: trixie trixie-updates\nComponents: main\n"
                "Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp\n\n"
                "Types: deb\nURIs: %s/debian-security\nSuites: trixie-security\nComponents: main\n"
                "Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp\n", base, base);
        if (length < 0 || (size_t)length >= sizeof(repositories)) goto done;
        if (write_config(extra, ubuntu ? "ubuntu.sources" : "debian.sources", repositories) < 0) goto done;
        close(extra);
        extra = open_directory_at(config, "apt.conf.d");
        if (extra < 0) goto done;
        if (!ubuntu && unlinkat(extra, "docker-clean", 0) < 0 && errno != ENOENT) goto done;
        result = write_config(extra, "99pdn", "APT::Sandbox::User \"root\";\nAcquire::https::CaInfo \"/etc/ssl/certs/ca-certificates.crt\";\n");
    }
    if (result == 0 && fstatat(etc, "resolv.conf", &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(st.st_mode))
        result = unlinkat(etc, "resolv.conf", 0);
    if (result == 0) result = write_config(etc, "resolv.conf", "nameserver 223.5.5.5\nnameserver 1.1.1.1\n");
 done:
    if (extra >= 0) close(extra);
    if (config >= 0) close(config);
    close(etc);
    if (result != 0) return error("cannot write rootfs network configuration");
    return 0;
}

int pdn_login(int argc, char *const argv[]);

static int initialize_arch(void)
{
    char *root = realpath(".", NULL), *temp = realpath("../..", NULL);
    int status;
    pid_t child, waited;
    if (!root || !temp) { free(root); free(temp); return error("cannot resolve staging rootfs"); }
    puts("Initializing Arch Linux ARM package keys...");
    fflush(stdout);
    child = fork();
    if (child == 0) {
        const char *proot_tmp = getenv("PROOT_TMP_DIR"), *tmp = getenv("TMPDIR");
        if ((!proot_tmp || !*proot_tmp) && (!tmp || !*tmp)) setenv("PROOT_TMP_DIR", temp, 1);
        char *args[] = {"pdn", "login", "--rootfs", root, "--", "/bin/sh", "-c",
                       "pacman-key --init && pacman-key --populate archlinuxarm", NULL};
        _exit(pdn_login(8, args));
    }
    free(root); free(temp);
    if (child < 0) return error("cannot start keyring initialization");
    do {
        waited = waitpid(child, &status, 0);
        if (waited < 0 && errno == EINTR && cancelled) kill(child, SIGTERM);
    } while (waited < 0 && errno == EINTR);
    if (waited < 0 || !WIFEXITED(status) || WEXITSTATUS(status) != 0)
        return error("Arch keyring initialization failed");
    return 0;
}

int pdn_install(const char *name, const char *local_archive, const char *mirror_name)
{
    struct distro distro;
    if (find_distro(name, &distro) < 0) return error("unknown distro; run pdn list --available") != 0;
    const struct mirror *mirrors = distro.mirrors;
    size_t count = 0, i;
    while (count < 5 && mirrors[count].name) count++;
    int selected = -1, downloaded = 0;
    if (mirror_name) {
        for (i = 0; i < count; i++)
            if (!strcasecmp(mirror_name, mirrors[i].name)) { selected = (int)i; break; }
        if (selected < 0) return error("unknown mirror; run pdn mirrors") != 0;
    }
    char *base = pdn_rootfs_base(), *archive = NULL;
    char stage[64];
    snprintf(stage, sizeof(stage), ".pdn-%s-XXXXXX", distro.name);
    int cwd = -1, basefd = -1, lock = -1, staged = 0, result = 1;
    struct sigaction action = {0}, old_int, old_term;
    cancelled = 0;
    if (!base || !*base) { free(base); return error("set PDN_ROOTFS_DIR or HOME") != 0; }
    if (local_archive && !(archive = realpath(local_archive, NULL))) {
        free(base); return error("local archive not found") != 0;
    }
    cwd = open(".", O_DIRECTORY | O_CLOEXEC);
    if (cwd < 0) { error(strerror(errno)); goto done; }
    if (mkdirs(base) < 0) { rootfs_error("create", base, errno); goto done; }
    if (chdir(base) < 0) { rootfs_error("enter", base, errno); goto done; }
    basefd = open(".", O_DIRECTORY | O_CLOEXEC);
    if (basefd < 0) { rootfs_error("open", base, errno); goto done; }
    lock = open(".pdn-install.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0) { rootfs_error("create install lock in", base, errno); goto done; }
    if (flock(lock, LOCK_EX | LOCK_NB) < 0) {
        error("cannot acquire install lock; another install may be running"); goto done;
    }
    if (exists_distro(distro.name)) { error("rootfs already exists; no files changed"); goto done; }
    if (!mkdtemp(stage)) { error("cannot create installation directory"); goto done; }
    staged = 1;
    action.sa_handler = cancel_install;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, &old_int);
    sigaction(SIGTERM, &action, &old_term);
    if (chdir(stage) < 0) goto restore;
    printf("Installing %s %s (ARM64)...\n", distro.name, distro.version);
    fflush(stdout);
    if (archive) {
        if (copy_archive(&distro, archive, "rootfs.tar.gz") != 0) goto restore;
    } else {
        downloaded = download_mirrors(&distro, mirrors, count, selected, "rootfs.tar.gz");
        if (downloaded < 0) goto restore;
    }
    free(archive);
    archive = realpath("rootfs.tar.gz", NULL);
    puts("Verifying SHA256...");
    if (!archive || verify(&distro, archive) != 0 || cancelled) goto restore;
    if (mkdir("rootfs", 0700) < 0 || chdir("rootfs") < 0) goto restore;
    puts("Extracting rootfs...");
    if (extract(&distro, archive) != 0 || configure(&distro, &mirrors[downloaded]) != 0 || cancelled) goto restore;
    if (!strcmp(distro.name, "arch") && initialize_arch() < 0) goto restore;
    if (cancelled || fchdir(basefd) < 0) goto restore;
    if (exists_distro(distro.name)) { error("rootfs appeared during installation; refusing to replace it"); goto restore; }
    {
        char source[sizeof(stage) + 8];
        snprintf(source, sizeof(source), "%s/rootfs", stage);
        if (rename(source, distro.name) < 0) { error("cannot finish installation"); goto restore; }
    }
    printf("%s installed. Run: pdn login %s\n", distro.name, distro.name);
    result = 0;
restore:
    sigaction(SIGINT, &old_int, NULL);
    sigaction(SIGTERM, &old_term, NULL);
    if (cancelled) result = 128 + cancelled;
done:
    if (staged && fchdir(basefd) == 0 && nftw(stage, remove_entry, 16, FTW_DEPTH | FTW_PHYS) < 0)
        fprintf(stderr, "pdn: could not remove staging directory: %s\n", stage);
    if (cwd >= 0) { if (fchdir(cwd) < 0) result = 1; close(cwd); }
    if (lock >= 0) close(lock);
    if (basefd >= 0) close(basefd);
    free(archive); free(base);
    return result;
}
