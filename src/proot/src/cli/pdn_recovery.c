#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "pdn_events.h"
#include "pdn_instance.h"
#include "pdn_json.h"
#include "pdn_recovery.h"

char *pdn_rootfs_base(void);
static volatile sig_atomic_t interrupted;

struct operation {
    char id[33], target[129], phase[20], boot[37];
    long long pid, start, created;
};
struct entry {
    char directory[80];
    struct operation operation;
    struct stat stat;
    const char *status, *reason;
    int known, rootfd;
};
static int failure(const char *code, const char *message, int cause)
{
    const char *advice = !strcmp(code, "operation_busy") ? "Wait for installation or cleanup to finish, then retry" :
        "Inspect recovery list and storage permissions; unknown staging must be reviewed manually";
    if (cause) pdn_events_system_problem(code, message, advice, cause);
    else pdn_events_problem(code, message, advice);
    fprintf(stderr, "pdn: %s%s%s\n", message, cause ? ": " : "", cause ? strerror(cause) : "");
    return -1;
}
static int hexadecimal(const char *value, size_t length)
{
    if (strlen(value) != length) return 0;
    for (; *value; value++) if (!((*value >= '0' && *value <= '9') || (*value >= 'a' && *value <= 'f'))) return 0;
    return 1;
}
static int boot_valid(const char *value)
{
    if (strlen(value) != 36) return 0;
    for (int i = 0; i < 36; i++) {
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (value[i] != '-') return 0; }
        else if (!((value[i] >= '0' && value[i] <= '9') || (value[i] >= 'a' && value[i] <= 'f'))) return 0;
    }
    return 1;
}
static int number(const char *value, long long *output)
{
    if (!*value) return -1;
    for (const char *p = value; *p; p++) if (*p < '0' || *p > '9') return -1;
    char *tail;
    errno = 0;
    *output = strtoll(value, &tail, 10);
    return errno || *tail || *output < 0 ? -1 : 0;
}
static int process_start(long long pid, long long *start)
{
    char path[80], buffer[4096];
    snprintf(path, sizeof(path), "/proc/%lld/stat", pid);
    FILE *file = fopen(path, "r");
    if (!file) return -1;
    if (!fgets(buffer, sizeof(buffer), file)) { int saved = errno ? errno : EIO; fclose(file); errno = saved; return -1; }
    fclose(file);
    char *cursor = strrchr(buffer, ')');
    if (!cursor) { errno = EINVAL; return -1; }
    char *save = NULL, *value = strtok_r(cursor + 1, " \n", &save);
    for (int field = 3; value; field++, value = strtok_r(NULL, " \n", &save)) {
        if (field == 22) { if (!number(value, start) && *start > 0) return 0; break; }
    }
    errno = EINVAL;
    return -1;
}
static void boot_id(char output[37])
{
    output[0] = 0;
    FILE *file = fopen("/proc/sys/kernel/random/boot_id", "r");
    if (!file) return;
    char buffer[40];
    if (fgets(buffer, sizeof(buffer), file)) {
        buffer[strcspn(buffer, "\n")] = 0;
        if (boot_valid(buffer)) strcpy(output, buffer);
    }
    fclose(file);
}
static int uuid(char output[33])
{
    unsigned char bytes[16];
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    size_t used = 0;
    while (used < sizeof(bytes)) {
        ssize_t size = read(fd, bytes + used, sizeof(bytes) - used);
        if (size < 0 && errno == EINTR) continue;
        if (size <= 0) { int saved = errno ? errno : EIO; close(fd); errno = saved; return -1; }
        used += size;
    }
    close(fd);
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    for (int i = 0; i < 16; i++) snprintf(output + i * 2, 3, "%02x", bytes[i]);
    return 0;
}
static int phase_valid(const char *phase)
{
    const char *names[] = {"preparing", "copying", "downloading", "verifying", "extracting", "configuring", "publishing"};
    for (size_t i = 0; i < sizeof(names) / sizeof(*names); i++) if (!strcmp(phase, names[i])) return 1;
    return 0;
}
static int read_operation(int directory, struct operation *operation)
{
    char buffer[1025];
    struct stat stat;
    int fd = openat(directory, ".pdn-operation", O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    if (fstat(fd, &stat) < 0 || !S_ISREG(stat.st_mode) || stat.st_nlink != 1 || stat.st_uid != getuid() || (stat.st_mode & 022) || stat.st_size <= 0 || stat.st_size > 1024) {
        close(fd); errno = EINVAL; return -1;
    }
    size_t used = 0;
    while (used < (size_t)stat.st_size) {
        ssize_t size = read(fd, buffer + used, stat.st_size - used);
        if (size < 0 && errno == EINTR) continue;
        if (size <= 0) { close(fd); errno = EINVAL; return -1; }
        used += size;
    }
    close(fd);
    if (memchr(buffer, 0, used) || buffer[used - 1] != '\n') { errno = EINVAL; return -1; }
    buffer[used] = 0;
    memset(operation, 0, sizeof(*operation));
    unsigned seen = 0;
    const char *keys[] = {"version", "id", "operation", "target", "phase", "pid", "start_time", "boot_id", "created_at"};
    for (char *line = buffer; *line;) {
        char *end = strchr(line, '\n'), *value = strchr(line, '=');
        if (!end || !value || value > end) goto invalid;
        *end = 0; *value++ = 0;
        int key;
        for (key = 0; key < 9 && strcmp(line, keys[key]); key++);
        if (key == 9 || (seen & (1U << key))) goto invalid;
        seen |= 1U << key;
        if (key == 0) { if (strcmp(value, "1")) goto invalid; }
        else if (key == 1) { if (!hexadecimal(value, 32)) goto invalid; strcpy(operation->id, value); }
        else if (key == 2) { if (strcmp(value, "install")) goto invalid; }
        else if (key == 3) { if (!pdn_instance_valid_name(value)) goto invalid; strcpy(operation->target, value); }
        else if (key == 4) { if (!phase_valid(value)) goto invalid; strcpy(operation->phase, value); }
        else if (key == 5) { if (number(value, &operation->pid) || operation->pid <= 0 || operation->pid > INT32_MAX) goto invalid; }
        else if (key == 6) { if (number(value, &operation->start)) goto invalid; }
        else if (key == 7) { if (*value && !boot_valid(value)) goto invalid; strcpy(operation->boot, value); }
        else if (key == 8) { if (number(value, &operation->created)) goto invalid; }
        line = end + 1;
    }
    if (seen == 511) return 0;
invalid:
    errno = EINVAL;
    return -1;
}
static int write_operation(int directory, const struct operation *operation)
{
    char id[33], name[64];
    if (uuid(id) < 0) return -1;
    snprintf(name, sizeof(name), ".pdn-operation.%s", id);
    int fd = openat(directory, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    FILE *file = fdopen(fd, "w");
    if (!file) { int saved = errno; close(fd); unlinkat(directory, name, 0); errno = saved; return -1; }
    int result = fprintf(file, "version=1\nid=%s\noperation=install\ntarget=%s\nphase=%s\npid=%lld\nstart_time=%lld\nboot_id=%s\ncreated_at=%lld\n",
            operation->id, operation->target, operation->phase, operation->pid, operation->start, operation->boot, operation->created);
    int saved = result < 0 ? (errno ? errno : EIO) : 0;
    if (fflush(file) < 0 && !saved) saved = errno;
    if (!saved && fsync(fd) < 0) saved = errno;
    if (fclose(file) < 0 && !saved) saved = errno;
    if (!saved && renameat(directory, name, directory, ".pdn-operation") < 0) saved = errno;
    if (!saved && fsync(directory) < 0) saved = errno;
    unlinkat(directory, name, 0);
    errno = saved;
    return saved ? -1 : 0;
}
int pdn_recovery_begin(int basefd, char *stage, size_t size, const char *target)
{
    struct operation operation = {0};
    char candidate[64];
    stage[0] = 0;
    if (uuid(operation.id) < 0) return failure("recovery_io_failed", "cannot generate installation identity", errno);
    snprintf(candidate, sizeof(candidate), ".pdn-install-%s", operation.id);
    if (strlen(candidate) >= size) return failure("recovery_io_failed", "installation staging name too long", ENAMETOOLONG);
    if (mkdirat(basefd, candidate, 0700) < 0) return failure("recovery_io_failed", "cannot create installation staging", errno);
    strcpy(stage, candidate);
    strcpy(operation.target, target);
    strcpy(operation.phase, "preparing");
    operation.pid = getpid();
    process_start(operation.pid, &operation.start);
    boot_id(operation.boot);
    time_t created = time(NULL);
    operation.created = created < 0 ? 0 : created;
    int directory = openat(basefd, stage, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory < 0) return failure("recovery_io_failed", "cannot open installation staging", errno);
    int result = write_operation(directory, &operation), saved = errno;
    close(directory);
    if (!result && fsync(basefd) < 0) { result = -1; saved = errno; }
    return result ? failure("recovery_io_failed", "cannot persist installation identity", saved) : 0;
}
int pdn_recovery_phase(int basefd, const char *stage, const char *phase)
{
    if (!phase_valid(phase)) return failure("recovery_io_failed", "invalid installation phase", EINVAL);
    struct operation operation;
    int directory = openat(basefd, stage, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory < 0) return failure("recovery_io_failed", "cannot open installation state", errno);
    int result = read_operation(directory, &operation);
    if (!result) { strcpy(operation.phase, phase); result = write_operation(directory, &operation); }
    int saved = errno;
    close(directory);
    return result ? failure("recovery_io_failed", "cannot persist installation phase", saved) : 0;
}
static int staging_name(const char *name)
{
    if (!strncmp(name, ".pdn-install-", 13) && hexadecimal(name + 13, 32)) return 1;
    const char *prefixes[] = {".pdn-alpine-", ".pdn-ubuntu-", ".pdn-debian-", ".pdn-arch-"};
    for (size_t i = 0; i < sizeof(prefixes) / sizeof(*prefixes); i++) {
        size_t length = strlen(prefixes[i]);
        if (!strncmp(name, prefixes[i], length) && strlen(name + length) == 6) {
            int valid = 1;
            for (const char *p = name + length; *p; p++) if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') || (*p >= '0' && *p <= '9'))) valid = 0;
            if (valid) return 1;
        }
    }
    return 0;
}
static void inspect(int basefd, struct entry *entry, const char *boot)
{
    entry->known = 0;
    entry->status = "unknown"; entry->reason = "ownership_unproven";
    if (fstatat(basefd, entry->directory, &entry->stat, AT_SYMLINK_NOFOLLOW) < 0) return;
    if (!S_ISDIR(entry->stat.st_mode) || entry->stat.st_uid != getuid() || (entry->stat.st_mode & 077)) return;
    int directory = openat(basefd, entry->directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory < 0) return;
    struct stat stat;
    int valid = fstat(directory, &stat) == 0 && stat.st_dev == entry->stat.st_dev && stat.st_ino == entry->stat.st_ino &&
        !read_operation(directory, &entry->operation);
    close(directory);
    if (!valid || strncmp(entry->directory, ".pdn-install-", 13) || strcmp(entry->directory + 13, entry->operation.id)) return;
    entry->known = 1;
    if (*boot && *entry->operation.boot && strcmp(boot, entry->operation.boot)) {
        entry->status = "interrupted"; entry->reason = "boot_changed"; return;
    }
    if (!entry->operation.start) { entry->reason = "identity_unavailable"; return; }
    long long start;
    if (process_start(entry->operation.pid, &start) < 0) {
        if (kill((pid_t)entry->operation.pid, 0) < 0 && errno == ESRCH) {
            entry->status = "interrupted"; entry->reason = "process_missing";
        } else entry->reason = "process_unavailable";
        return;
    }
    if (start != entry->operation.start) { entry->status = "interrupted"; entry->reason = "pid_reused"; return; }
    if (!*boot || !*entry->operation.boot) { entry->reason = "boot_unavailable"; return; }
    entry->status = "active"; entry->reason = "owner_alive";
}
static long long mount_id(int fd)
{
    char path[80], line[256];
    snprintf(path, sizeof(path), "/proc/self/fdinfo/%d", fd);
    FILE *file = fopen(path, "r");
    if (!file) return -1;
    long long id = -1;
    while (fgets(line, sizeof(line), file)) if (sscanf(line, "mnt_id: %lld", &id) == 1) break;
    fclose(file);
    return id;
}
static int walk(int fd, dev_t device, long long mount, unsigned depth, size_t *count, int removing, int rootguard)
{
    if (interrupted) { errno = EINTR; return -1; }
    if (depth > 256 || mount < 0 || mount_id(fd) != mount) { errno = EINVAL; return -1; }
    if (flock(fd, LOCK_EX | LOCK_NB) < 0) return -1;
    DIR *directory = fdopendir(openat(fd, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (!directory) return -1;
    int result = -1;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(directory);
        if (!entry) { result = errno ? -1 : 0; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..") || (!depth && !strcmp(entry->d_name, ".pdn-operation"))) continue;
        if (interrupted) { errno = EINTR; break; }
        if (++*count > 1000000) { errno = EOVERFLOW; break; }
        struct stat before, after;
        if (fstatat(fd, entry->d_name, &before, AT_SYMLINK_NOFOLLOW) < 0) break;
        if (before.st_dev != device) { errno = EINVAL; break; }
        if (S_ISDIR(before.st_mode)) {
            int child = !depth && rootguard >= 0 && !strcmp(entry->d_name, "rootfs")
                ? fcntl(rootguard, F_DUPFD_CLOEXEC, 0) : openat(fd, entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (child < 0) break;
            int child_result = fstat(child, &after);
            if (!child_result && (before.st_ino != after.st_ino || before.st_dev != after.st_dev)) { errno = EINVAL; child_result = -1; }
            if (!child_result) child_result = walk(child, device, mount, depth + 1, count, removing, -1);
            int saved = errno; close(child); errno = saved;
            if (child_result < 0) break;
        }
        if (removing) {
            if (fstatat(fd, entry->d_name, &after, AT_SYMLINK_NOFOLLOW) < 0) break;
            if (before.st_ino != after.st_ino || before.st_dev != after.st_dev || (before.st_mode & S_IFMT) != (after.st_mode & S_IFMT)) { errno = EINVAL; break; }
            if (unlinkat(fd, entry->d_name, S_ISDIR(before.st_mode) ? AT_REMOVEDIR : 0) < 0) break;
        }
    }
    int saved = errno;
    closedir(directory);
    errno = saved;
    return result;
}
static void cancel(int signal_number) { interrupted = signal_number; }
static int compare(const void *left, const void *right) { return strcmp(((const struct entry *)left)->directory, ((const struct entry *)right)->directory); }
static void nullable(FILE *file, const char *value) { if (value && *value) pdn_json_string(file, value); else fputs("null", file); }
int pdn_recovery(const char *id, int clearing, int json)
{
    if (id && !hexadecimal(id, 32)) { failure("invalid_argument", "recovery ID must contain 32 lowercase hexadecimal characters", 0); return 2; }
    char *base = pdn_rootfs_base(), *resolved = NULL;
    int basefd = -1, lock = -1, result = 2;
    struct entry *entries = NULL;
    size_t count = 0, capacity = 0, scanned = 0, removed = 0;
    DIR *directory = NULL;
    struct sigaction action = {0}, old_int, old_term;
    interrupted = 0; action.sa_handler = cancel; sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, &old_int); sigaction(SIGTERM, &action, &old_term);
    if (!base || !*base) { failure("directory_missing", "set PDN_ROOTFS_DIR or HOME", 0); goto done; }
    resolved = realpath(base, NULL);
    if (!resolved && errno == ENOENT) {
        if (*base == '/') resolved = strdup(base);
        else { char *cwd = getcwd(NULL, 0); if (cwd) { if (asprintf(&resolved, "%s/%s", cwd, base) < 0) resolved = NULL; free(cwd); } }
        if (!resolved) { failure("recovery_io_failed", "cannot resolve recovery location", errno); goto done; }
        goto output;
    }
    if (!resolved || !strcmp(resolved, "/")) { failure("recovery_io_failed", "invalid recovery directory", errno ? errno : EINVAL); goto done; }
    basefd = open(resolved, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (basefd < 0) { failure("recovery_io_failed", "cannot open rootfs management directory", errno); goto done; }
    lock = openat(basefd, ".pdn-install.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0600);
    struct stat stat;
    if (lock < 0) { failure("lock_failed", "cannot open recovery lock", errno); goto done; }
    if (fstat(lock, &stat) < 0 || !S_ISREG(stat.st_mode) || stat.st_nlink != 1 || stat.st_uid != getuid()) { failure("recovery_unsafe", "invalid recovery lock", 0); goto done; }
    if (flock(lock, LOCK_EX | LOCK_NB) < 0) {
        failure(errno == EWOULDBLOCK || errno == EAGAIN ? "operation_busy" : "lock_failed", "cannot acquire recovery lock", errno); goto done;
    }
    directory = fdopendir(openat(basefd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (!directory) { failure("recovery_io_failed", "cannot inspect installation staging", errno); goto done; }
    char boot[37]; boot_id(boot);
    for (;;) {
        if (interrupted) goto done;
        errno = 0;
        struct dirent *row = readdir(directory);
        if (!row) { if (errno) { failure("recovery_io_failed", "cannot read staging directory", errno); goto done; } break; }
        if (++scanned > 100000) { failure("recovery_limit_exceeded", "rootfs directory has too many entries", 0); goto done; }
        if (!staging_name(row->d_name) || (id && (strncmp(row->d_name, ".pdn-install-", 13) || strcmp(row->d_name + 13, id)))) continue;
        if (count == capacity) {
            size_t next = capacity ? capacity * 2 : 16;
            struct entry *grown = realloc(entries, next * sizeof(*entries));
            if (!grown) { failure("out_of_memory", "cannot allocate recovery entries", ENOMEM); goto done; }
            entries = grown; capacity = next;
        }
        struct entry *entry = &entries[count++]; memset(entry, 0, sizeof(*entry));
        entry->rootfd = -1; strcpy(entry->directory, row->d_name); inspect(basefd, entry, boot);
    }
    closedir(directory); directory = NULL;
    if (count > 1) qsort(entries, count, sizeof(*entries), compare);
    if (clearing) {
        long long mount = mount_id(basefd);
        for (size_t i = 0; i < count; i++) {
            if (strcmp(entries[i].status, "interrupted")) {
                if (id) { failure("recovery_unsafe", "selected staging ownership or inactivity cannot be proven", 0); goto done; }
                continue;
            }
            int fd = openat(basefd, entries[i].directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            if (fd >= 0) {
                entries[i].rootfd = openat(fd, "rootfs", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
                if (entries[i].rootfd < 0 && errno != ENOENT && errno != ELOOP && errno != ENOTDIR) {
                    int saved = errno; close(fd); failure("recovery_unsafe", "cannot inspect staging rootfs", saved); goto done;
                }
                if (entries[i].rootfd >= 0 && flock(entries[i].rootfd, LOCK_EX | LOCK_NB) < 0) {
                    int saved = errno; close(fd); failure(saved == EWOULDBLOCK || saved == EAGAIN ? "operation_busy" : "lock_failed", "staging rootfs is in use", saved); goto done;
                }
            }
            size_t nodes = 0;
            int safe = fd >= 0 && fstat(fd, &stat) == 0 && stat.st_dev == entries[i].stat.st_dev && stat.st_ino == entries[i].stat.st_ino &&
                walk(fd, stat.st_dev, mount, 0, &nodes, 0, entries[i].rootfd) == 0;
            int saved = errno; if (fd >= 0) close(fd);
            if (!safe) { failure(saved == EWOULDBLOCK || saved == EAGAIN ? "operation_busy" : "recovery_unsafe", "staging tree cannot be safely cleaned; no staging was removed", saved); goto done; }
        }
        for (size_t i = 0; i < count; i++) {
            if (strcmp(entries[i].status, "interrupted")) continue;
            struct entry current = {0}; strcpy(current.directory, entries[i].directory); inspect(basefd, &current, boot);
            if (!current.known || strcmp(current.status, "interrupted") || current.stat.st_dev != entries[i].stat.st_dev || current.stat.st_ino != entries[i].stat.st_ino) {
                failure("recovery_unsafe", "staging changed; cleanup may be incomplete", 0); goto done;
            }
            int fd = openat(basefd, current.directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            size_t nodes = 0;
            int safe = fd >= 0 && fstat(fd, &stat) == 0 && stat.st_dev == current.stat.st_dev && stat.st_ino == current.stat.st_ino &&
                walk(fd, stat.st_dev, mount, 0, &nodes, 1, entries[i].rootfd) == 0;
            if (safe) safe = fstatat(basefd, current.directory, &stat, AT_SYMLINK_NOFOLLOW) == 0 && stat.st_dev == current.stat.st_dev && stat.st_ino == current.stat.st_ino;
            if (safe) safe = unlinkat(fd, ".pdn-operation", 0) == 0;
            int saved = errno; if (fd >= 0) close(fd);
            if (!safe || unlinkat(basefd, current.directory, AT_REMOVEDIR) < 0) {
                failure("recovery_io_failed", "cannot remove staging; cleanup may be incomplete", safe ? errno : saved); goto done;
            }
            removed++;
        }
        if (removed && fsync(basefd) < 0) { failure("recovery_io_failed", "cannot persist staging cleanup", errno); goto done; }
    }
output:
    if (id && !count) { failure("recovery_not_found", "recovery ID not found", 0); goto done; }
    if (clearing) printf("Removed %zu interrupted staging directories; retained %zu unknown or active entries.\n", removed, count - removed);
    else if (json) {
        fputs("{\"version\":1,\"path\":", stdout); pdn_json_string(stdout, resolved); fputs(",\"entries\":[", stdout);
        for (size_t i = 0; i < count; i++) {
            struct entry *entry = &entries[i]; struct operation *op = &entry->operation;
            if (i) fputc(',', stdout);
            fputs("{\"directory\":", stdout); pdn_json_string(stdout, entry->directory);
            fputs(",\"id\":", stdout); nullable(stdout, !strncmp(entry->directory, ".pdn-install-", 13) ? entry->directory + 13 : NULL);
            fputs(",\"target\":", stdout); nullable(stdout, entry->known ? op->target : NULL);
            fputs(",\"phase\":", stdout); nullable(stdout, entry->known ? op->phase : NULL);
            fputs(",\"pid\":", stdout); if (entry->known) fprintf(stdout, "%lld", op->pid); else fputs("null", stdout);
            fputs(",\"start_time\":", stdout); if (entry->known && op->start) fprintf(stdout, "%lld", op->start); else fputs("null", stdout);
            fputs(",\"boot_id\":", stdout); nullable(stdout, entry->known ? op->boot : NULL);
            fputs(",\"created_at\":", stdout); if (entry->known) fprintf(stdout, "%lld", op->created); else fputs("null", stdout);
            fputs(",\"status\":", stdout); pdn_json_string(stdout, entry->status);
            fputs(",\"reason\":", stdout); pdn_json_string(stdout, entry->reason); fputc('}', stdout);
        }
        fputs("]}\n", stdout);
    } else {
        printf("Installation staging: %s\n", resolved);
        for (size_t i = 0; i < count; i++) printf("%s  %s  %s  id=%s target=%s phase=%s\n", entries[i].directory, entries[i].status, entries[i].reason,
                !strncmp(entries[i].directory, ".pdn-install-", 13) ? entries[i].directory + 13 : "unknown", entries[i].known ? entries[i].operation.target : "unknown", entries[i].known ? entries[i].operation.phase : "unknown");
        if (!count) puts("No installation staging found.");
    }
    if (fflush(stdout) < 0 || ferror(stdout)) { failure("recovery_io_failed", "cannot write recovery output", errno ? errno : EIO); goto done; }
    result = 0;
done:
    if (directory) closedir(directory);
    if (lock >= 0) close(lock);
    if (basefd >= 0) close(basefd);
    for (size_t i = 0; i < count; i++) if (entries[i].rootfd >= 0) close(entries[i].rootfd);
    free(entries); free(resolved); free(base);
    sigaction(SIGINT, &old_int, NULL); sigaction(SIGTERM, &old_term, NULL);
    if (interrupted) { pdn_events_cancelled(interrupted); return 128 + interrupted; }
    return result;
}
