#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/openat2.h>
#include <linux/seccomp.h>
#include <linux/stat.h>
#include <netinet/in.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <stdlib.h>
#include <unistd.h>

static int probe_openat2(void)
{
    const char payload[] = "inherited openat2 fallback payload\n";
    const char *tmp = getenv("TMPDIR");
    char directory[PATH_MAX], content[sizeof(payload)] = {0};
    int status = 60, parent = -1, bin = -1, file = -1;
    if (!tmp || !*tmp) tmp = "/tmp";
    if (snprintf(directory, sizeof(directory), "%s/openat2-probe-XXXXXX", tmp) >= sizeof(directory) ||
        !mkdtemp(directory))
        return status;
    parent = open(directory, O_RDONLY | O_DIRECTORY);
    if (parent < 0 || mkdirat(parent, "bin", 0700) != 0) goto cleanup;
    struct open_how how[] = {
        { .flags = 0x28c000, .resolve = RESOLVE_BENEATH },
        { .flags = O_CREAT | O_EXCL | O_RDWR, .mode = 0600, .resolve = RESOLVE_BENEATH },
    };
    const char *paths[] = { "bin/", "unwanted", "invalid" };
    for (int i = 0; i < 3; i++) {
        errno = 0;
        int result = syscall(__NR_openat2, parent, paths[i],
                             i == 2 ? (void *)1 : &how[i], sizeof(struct open_how));
        if (result != -1 || errno != ENOSYS) {
            fprintf(stderr, "openat2 case %d failed: result=%d errno=%d\n", i, result, errno);
            if (result >= 0) close(result);
            status = 61 + i;
            goto cleanup;
        }
    }
    struct stat metadata;
    for (int i = 1; i < 3; i++) {
        errno = 0;
        if (fstatat(parent, paths[i], &metadata, 0) != -1 || errno != ENOENT) {
            status = 64;
            goto cleanup;
        }
    }
    bin = openat(parent, "bin/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (bin < 0) goto cleanup;
    file = openat(bin, "payload", O_CREAT | O_EXCL | O_RDWR, 0600);
    if (file < 0 || write(file, payload, sizeof(payload) - 1) != sizeof(payload) - 1 ||
        pread(file, content, sizeof(content), 0) != sizeof(payload) - 1 ||
        memcmp(content, payload, sizeof(payload)) != 0 || fstat(file, &metadata) != 0 ||
        !S_ISREG(metadata.st_mode) || (metadata.st_mode & 0777) != 0600) {
        status = 65;
        goto cleanup;
    }
    printf("openat2 directory, create and invalid-pointer returned ENOSYS; openat fallback passed; payload=%s", content);
    status = 0;
cleanup:
    if (file >= 0) close(file);
    if (bin >= 0) {
        if (unlinkat(bin, "payload", 0) != 0 && errno != ENOENT) status = 66;
        close(bin);
    }
    if (parent >= 0) {
        if (unlinkat(parent, "unwanted", 0) != 0 && errno != ENOENT) status = 67;
        if (unlinkat(parent, "invalid", 0) != 0 && errno != ENOENT) status = 68;
        if (unlinkat(parent, "bin", AT_REMOVEDIR) != 0 && errno != ENOENT) status = 69;
        close(parent);
    }
    if (rmdir(directory) != 0) status = 70;
    return status;
}

static int probe_statx(void)
{
    const char payload[] = "inherited statx payload\n";
    const char *tmp = getenv("TMPDIR");
    char directory[PATH_MAX], content[sizeof(payload)] = {0};
    int status = 50, parent = -1, file = -1, cwd = -1;
    if (!tmp || !*tmp) tmp = "/tmp";
    if (snprintf(directory, sizeof(directory), "%s/statx-probe-XXXXXX", tmp) >= sizeof(directory) ||
        !mkdtemp(directory))
        return status;
    cwd = open(".", O_RDONLY | O_DIRECTORY);
    parent = open(directory, O_RDONLY | O_DIRECTORY);
    if (cwd < 0 || parent < 0) goto cleanup;
    file = openat(parent, "payload", O_CREAT | O_EXCL | O_RDWR, 0600);
    if (file < 0 || write(file, payload, sizeof(payload) - 1) != sizeof(payload) - 1 ||
        fchdir(parent) != 0)
        goto cleanup;
    const int dirfds[] = { AT_FDCWD, parent, file };
    const char *paths[] = { "payload", "payload", "" };
    for (int i = 0; i < 3; i++) {
        struct statx result = {0};
        unsigned mask = STATX_TYPE | STATX_MODE | STATX_SIZE;
        errno = 0;
        if (syscall(__NR_statx, dirfds[i], paths[i], i == 2 ? AT_EMPTY_PATH : 0, mask, &result) != 0 ||
            (result.stx_mask & mask) != mask || !S_ISREG(result.stx_mode) ||
            (result.stx_mode & 0777) != 0600 || result.stx_size != sizeof(payload) - 1) {
            fprintf(stderr, "statx case %d failed: errno=%d mode=%o size=%llu mask=%x\n",
                    i, errno, result.stx_mode, (unsigned long long)result.stx_size, result.stx_mask);
            status = 51 + i;
            goto cleanup;
        }
    }
    if (pread(file, content, sizeof(content), 0) != sizeof(payload) - 1 ||
        memcmp(content, payload, sizeof(payload)) != 0) {
        status = 54;
        goto cleanup;
    }
    printf("statx cwd, dirfd and empty-path passed; size=%zu; payload=%s", sizeof(payload) - 1, content);
    status = 0;
cleanup:
    if (cwd >= 0) {
        if (fchdir(cwd) != 0) status = 55;
        close(cwd);
    }
    if (file >= 0) close(file);
    if (parent >= 0) {
        if (unlinkat(parent, "payload", 0) != 0 && errno != ENOENT) status = 56;
        close(parent);
    }
    if (rmdir(directory) != 0) status = 57;
    return status;
}

int main(int argc, char **argv)
{
    if (argc >= 3 && (strcmp(argv[1], "exec-inherited-filter") == 0 ||
                      strcmp(argv[1], "exec-inherited-statx-trap") == 0 ||
                      strcmp(argv[1], "exec-inherited-openat2-trap") == 0 ||
                      strcmp(argv[1], "exec-denied-seccomp-query") == 0)) {
        int deny_query = strcmp(argv[1], "exec-denied-seccomp-query") == 0;
        int trap_statx = strcmp(argv[1], "exec-inherited-statx-trap") == 0;
        int trap_openat2 = strcmp(argv[1], "exec-inherited-openat2-trap") == 0;
        struct sock_filter allow[] = {
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_filter deny[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_prctl, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_GET_SECCOMP, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_filter trap[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, trap_openat2 ? __NR_openat2 : __NR_statx, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program = {
            deny_query ? sizeof(deny) / sizeof(deny[0]) : trap_statx || trap_openat2 ? sizeof(trap) / sizeof(trap[0]) : sizeof(allow) / sizeof(allow[0]),
            deny_query ? deny : trap_statx || trap_openat2 ? trap : allow,
        };
        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 ||
            prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) != 0)
            return 40;
        errno = 0;
        int mode = prctl(PR_GET_SECCOMP, 0, 0, 0, 0);
        if (deny_query ? mode != -1 || errno != EPERM : mode != SECCOMP_MODE_FILTER)
            return 41;
        if (trap_statx || trap_openat2) {
            printf("inherited %s TRAP filter active; seccomp mode=2\n", trap_openat2 ? "openat2" : "statx");
            fflush(stdout);
        }
        execv(argv[2], argv + 2);
        return 42;
    }
    if (argc != 2)
        return 2;

    if (strcmp(argv[1], "statx") == 0)
        return probe_statx();

    if (strcmp(argv[1], "openat2") == 0)
        return probe_openat2();

    if (strcmp(argv[1], "groups-exec") == 0) {
        gid_t list[3];
        return syscall(__NR_getgroups, 3, list) == 3 && list[0] == 0 && list[1] == 1234 && list[2] == 65537 ? 0 : 20;
    }
    if (strcmp(argv[1], "groups") == 0) {
        gid_t list[4] = {65537, 0, 1234, 0}, output[4] = {0};
        int status;
        if (syscall(__NR_getgroups, 0, NULL) != 0) return 21;
        if (syscall(__NR_getgroups, -1, NULL) != -1 || errno != EINVAL) return 22;
        if (syscall(__NR_setgroups, 65537, list) != -1 || errno != EINVAL) return 23;
        if (syscall(__NR_setgroups, 1, NULL) != -1 || errno != EFAULT) return 24;
        gid_t invalid = (gid_t)-1;
        if (syscall(__NR_setgroups, 1, &invalid) != -1 || errno != EINVAL) return 25;
        if (syscall(__NR_setgroups, 3, list) != 0 || syscall(__NR_getgroups, 0, NULL) != 3) return 26;
        if (syscall(__NR_getgroups, 2, output) != -1 || errno != EINVAL) return 27;
        if (syscall(__NR_getgroups, 3, NULL) != -1 || errno != EFAULT) return 28;
        if (syscall(__NR_getgroups, 4, output) != 3 || output[0] != 0 || output[1] != 1234 || output[2] != 65537) return 29;
        pid_t child = fork();
        if (child < 0) return 30;
        if (!child) {
            if (syscall(__NR_setgroups, 0, NULL) != 0 || syscall(__NR_getgroups, 0, NULL) != 0) _exit(31);
            if (syscall(__NR_setuid, 1234) != 0) _exit(32);
            if (syscall(__NR_setgroups, 0, NULL) != -1 || errno != EPERM) _exit(33);
            _exit(0);
        }
        if (waitpid(child, &status, 0) != child || !WIFEXITED(status) || WEXITSTATUS(status)) return 34;
        child = fork();
        if (child < 0) return 35;
        if (!child) { execl(argv[0], argv[0], "groups-exec", NULL); _exit(36); }
        if (waitpid(child, &status, 0) != child || !WIFEXITED(status) || WEXITSTATUS(status)) return 37;
        if (syscall(__NR_setgroups, 0, NULL) != 0 || syscall(__NR_getgroups, 0, NULL) != 0) return 38;
        puts("guest groups isolated; setgroups, fork and exec passed");
        return 0;
    }

    if (strcmp(argv[1], "sigsys") == 0) {
        struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getpid, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program = { sizeof(filter) / sizeof(filter[0]), filter };
        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 ||
            prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) != 0)
            return 3;
        errno = 0;
        if (syscall(__NR_getpid) != -1 || errno != ENOSYS)
            return 4;
        puts("SIGSYS handled");
        return 0;
    }

    int ipv6 = strcmp(argv[1], "ipv6") == 0;
    int fd = socket(ipv6 ? AF_INET6 : AF_INET, SOCK_STREAM, 0);
    if (fd < 0)
        return 5;
    struct sockaddr_in addr4 = { .sin_family = AF_INET, .sin_port = htons(1023),
                                .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    struct sockaddr_in6 addr6 = { .sin6_family = AF_INET6, .sin6_port = htons(1023),
                                 .sin6_addr = IN6ADDR_LOOPBACK_INIT };
    void *addr = ipv6 ? (void *)&addr6 : (void *)&addr4;
    socklen_t size = ipv6 ? sizeof(addr6) : sizeof(addr4);
    if (bind(fd, addr, size) != 0 || getsockname(fd, addr, &size) != 0)
        return 6;
    int port = ntohs(ipv6 ? addr6.sin6_port : addr4.sin_port);
    close(fd);
    printf("bound=%d\n", port);
    return port == 3023 ? 0 : 7;
}
