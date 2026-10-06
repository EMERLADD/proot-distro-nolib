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

#define ALPINE_FILE "/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz"

struct mirror {
    const char *name;
    const char *base;
    const char *url;
};

#ifndef ALPINE_MIRRORS
#define ALPINE_MIRRORS \
    {"tuna", "https://mirrors.tuna.tsinghua.edu.cn/alpine", "https://mirrors.tuna.tsinghua.edu.cn/alpine" ALPINE_FILE}, \
    {"ustc", "https://mirrors.ustc.edu.cn/alpine", "https://mirrors.ustc.edu.cn/alpine" ALPINE_FILE}, \
    {"nju", "https://mirrors.nju.edu.cn/alpine", "https://mirrors.nju.edu.cn/alpine" ALPINE_FILE}, \
    {"official", "https://dl-cdn.alpinelinux.org/alpine", "https://dl-cdn.alpinelinux.org/alpine" ALPINE_FILE}, \
    {"dotsrc", "https://mirrors.dotsrc.org/alpine", "https://mirrors.dotsrc.org/alpine" ALPINE_FILE}
#endif
#ifndef ALPINE_SHA256
#define ALPINE_SHA256 "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773"
#endif
#ifndef ALPINE_SIZE
#define ALPINE_SIZE 4028030
#endif

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

static int exists_alpine(void)
{
    DIR *dir = opendir(".");
    struct dirent *entry;
    int found = 0;
    if (!dir) return 1;
    while ((entry = readdir(dir))) {
        if (!strcasecmp(entry->d_name, "alpine")) { found = 1; break; }
    }
    closedir(dir);
    return found;
}

struct transfer {
    FILE *file;
    size_t size;
    int percent;
};

static size_t receive(void *data, size_t size, size_t count, void *context)
{
    struct transfer *transfer = context;
    size_t bytes = size * count;
    if (cancelled || bytes > ALPINE_SIZE - transfer->size) return 0;
    bytes = fwrite(data, 1, bytes, transfer->file);
    transfer->size += bytes;
    return bytes;
}

static int progress(void *context, curl_off_t total, curl_off_t current,
                    curl_off_t upload_total, curl_off_t uploaded)
{
    struct transfer *transfer = context;
    int percent = (int)(current * 100 / ALPINE_SIZE);
    (void)total; (void)upload_total; (void)uploaded;
    if (percent / 10 > transfer->percent / 10) {
        fprintf(stderr, "Downloading Alpine: %d%%\n", percent);
        transfer->percent = percent;
    }
    return cancelled != 0;
}

static int download(const char *url, const char *path)
{
    CURL *curl;
    CURLcode status;
    struct transfer transfer = {0};
    char details[CURL_ERROR_SIZE] = {0};
    const char *ca = getenv("PDN_CA_BUNDLE");
    transfer.file = fopen(path, "wb");
    if (!transfer.file) return error(strerror(errno));
    curl = curl_easy_init();
    if (!curl) { fclose(transfer.file); return error("cannot initialize HTTPS"); }
    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_USERAGENT, "proot-distro-nolib/0.3.1");
    curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "https");
    curl_easy_setopt(curl, CURLOPT_REDIR_PROTOCOLS_STR, "https");
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_MAXREDIRS, 3L);
    curl_easy_setopt(curl, CURLOPT_FAILONERROR, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 8L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 90L);
    curl_easy_setopt(curl, CURLOPT_LOW_SPEED_LIMIT, 1024L);
    curl_easy_setopt(curl, CURLOPT_LOW_SPEED_TIME, 30L);
    curl_easy_setopt(curl, CURLOPT_MAXFILESIZE_LARGE, (curl_off_t)ALPINE_SIZE);
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

static int copy_archive(const char *source, const char *destination)
{
    FILE *input = fopen(source, "rb"), *output;
    char buffer[65536];
    size_t size, total = 0;
    int result = 0;
    if (!input) return error("cannot open local archive");
    output = fopen(destination, "wb");
    if (!output) { fclose(input); return error("cannot stage local archive"); }
    while ((size = fread(buffer, 1, sizeof(buffer), input)) > 0) {
        if (cancelled || (total += size) > ALPINE_SIZE || fwrite(buffer, 1, size, output) != size) {
            result = -1; break;
        }
    }
    if (ferror(input)) result = -1;
    fclose(input);
    if (fclose(output) != 0) result = -1;
    if (result) return error("cannot copy local archive or archive is too large");
    return 0;
}

static int verify(const char *path)
{
    unsigned char digest[32];
    char hex[65];
    struct stat st;
    size_t i;
    if (stat(path, &st) < 0 || st.st_size != ALPINE_SIZE) return error("incorrect archive size");
    if (mbedtls_md_file(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), path, digest) != 0)
        return error("cannot hash archive");
    for (i = 0; i < sizeof(digest); i++) snprintf(hex + i * 2, 3, "%02x", digest[i]);
    if (strcmp(hex, ALPINE_SHA256) != 0) return error("SHA256 mismatch; archive rejected");
    return 0;
}

int pdn_mirrors(void)
{
    const struct mirror mirrors[] = { ALPINE_MIRRORS };
    size_t i;
    puts("Alpine ARM64 rootfs mirrors (automatic fallback order):");
    for (i = 0; i < sizeof(mirrors) / sizeof(mirrors[0]); i++)
        printf("%-10s %s\n", mirrors[i].name, mirrors[i].base);
    return 0;
}

static int download_mirrors(const struct mirror *mirrors, size_t count,
                            int selected, const char *path)
{
    size_t i;
    for (i = 0; i < count; i++) {
        if (selected >= 0 && i != (size_t)selected) continue;
        if (cancelled) return -1;
        printf("Trying mirror: %s\n", mirrors[i].name);
        fflush(stdout);
        if (download(mirrors[i].url, path) == 0 && !cancelled && verify(path) == 0)
            return (int)i;
        if (unlink(path) < 0 && errno != ENOENT) return error("cannot remove failed download");
        if (cancelled) return -1;
        if (selected >= 0) break;
        if (i + 1 < count) fprintf(stderr, "Mirror %s failed; trying the next source.\n", mirrors[i].name);
    }
    return error("no mirror provided a verified Alpine archive");
}

static int extract(const char *path)
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
            (total += archive_entry_size(entry)) > 512 * 1024 * 1024) {
            error("archive exceeds extraction limits"); goto done;
        }
        if (type != AE_IFREG && type != AE_IFDIR && type != AE_IFLNK &&
            !archive_entry_hardlink(entry)) { error("unsupported archive entry"); goto done; }
        archive_entry_set_perm(entry, archive_entry_perm(entry) & 01777);
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

static int configure(const char *mirror_base)
{
    char repositories[1024];
    int length = snprintf(repositories, sizeof(repositories), "%s/v3.24/main\n%s/v3.24/community\n", mirror_base, mirror_base);
    if (length < 0 || (size_t)length >= sizeof(repositories)) return error("mirror URL too long");
    int etc = open("etc", O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC), apk, result;
    if (etc < 0) return error("missing etc directory");
    apk = openat(etc, "apk", O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    result = apk < 0 ? -1 : write_config(apk, "repositories", repositories);
    if (apk >= 0) close(apk);
    if (result == 0) result = write_config(etc, "resolv.conf", "nameserver 223.5.5.5\nnameserver 1.1.1.1\n");
    close(etc);
    if (result != 0) return error("cannot write Alpine network configuration");
    return 0;
}

int pdn_install(const char *local_archive, const char *mirror_name)
{
    const struct mirror mirrors[] = { ALPINE_MIRRORS };
    size_t count = sizeof(mirrors) / sizeof(mirrors[0]), i;
    int selected = -1, downloaded = 0;
    if (mirror_name) {
        for (i = 0; i < count; i++)
            if (!strcasecmp(mirror_name, mirrors[i].name)) { selected = (int)i; break; }
        if (selected < 0) return error("unknown mirror; run pdn mirrors") != 0;
    }
    char *base = pdn_rootfs_base(), *archive = NULL;
    char stage[] = ".pdn-alpine-XXXXXX";
    int cwd = -1, basefd = -1, lock = -1, staged = 0, result = 1;
    struct sigaction action = {0}, old_int, old_term;
    cancelled = 0;
    if (!base || !*base) { free(base); return error("set PDN_ROOTFS_DIR or HOME") != 0; }
    if (local_archive && !(archive = realpath(local_archive, NULL))) {
        free(base); return error("local archive not found") != 0;
    }
    cwd = open(".", O_DIRECTORY | O_CLOEXEC);
    if (cwd < 0 || mkdirs(base) < 0 || chdir(base) < 0) { error("cannot access rootfs directory"); goto done; }
    basefd = open(".", O_DIRECTORY | O_CLOEXEC);
    lock = open(".pdn-install.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (basefd < 0 || lock < 0 || flock(lock, LOCK_EX | LOCK_NB) < 0) {
        error("cannot acquire install lock; another install may be running"); goto done;
    }
    if (exists_alpine()) { error("Alpine already exists; no files changed"); goto done; }
    if (!mkdtemp(stage)) { error("cannot create installation directory"); goto done; }
    staged = 1;
    action.sa_handler = cancel_install;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, &old_int);
    sigaction(SIGTERM, &action, &old_term);
    if (chdir(stage) < 0) goto restore;
    puts("Installing Alpine 3.24.2 (ARM64)...");
    fflush(stdout);
    if (archive) {
        if (copy_archive(archive, "rootfs.tar.gz") != 0) goto restore;
    } else {
        downloaded = download_mirrors(mirrors, count, selected, "rootfs.tar.gz");
        if (downloaded < 0) goto restore;
    }
    free(archive);
    archive = realpath("rootfs.tar.gz", NULL);
    puts("Verifying SHA256...");
    if (!archive || verify(archive) != 0 || cancelled) goto restore;
    if (mkdir("rootfs", 0700) < 0 || chdir("rootfs") < 0) goto restore;
    puts("Extracting rootfs...");
    if (extract(archive) != 0 || configure(mirrors[downloaded].base) != 0 || cancelled) goto restore;
    if (fchdir(basefd) < 0) goto restore;
    if (exists_alpine()) { error("Alpine appeared during installation; refusing to replace it"); goto restore; }
    {
        char source[sizeof(stage) + 8];
        snprintf(source, sizeof(source), "%s/rootfs", stage);
        if (rename(source, "alpine") < 0) { error("cannot finish installation"); goto restore; }
    }
    puts("Alpine installed. Run: pdn login alpine");
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
