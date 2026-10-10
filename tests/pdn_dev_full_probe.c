#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/sendfile.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "%s:%d: %s (errno=%d)\n", __func__, __LINE__, #x, errno); exit(1); } } while (0)
#define ERROR(call, code) do { errno = 0; CHECK((call) == -1); CHECK(errno == (code)); } while (0)

static int full(int flags) { int fd = open("/dev/full", flags); CHECK(fd >= 0); return fd; }
static void zeros(const unsigned char *buf, size_t size) { for (size_t i = 0; i < size; i++) CHECK(buf[i] == 0); }
static void identity(const struct stat *st) { CHECK(S_ISCHR(st->st_mode)); CHECK(major(st->st_rdev) == 1); CHECK(minor(st->st_rdev) == 7); }

static void scalar(void) {
    unsigned char buf[32];
    int fd = full(O_RDWR);
    memset(buf, 42, sizeof(buf));
    CHECK(read(fd, buf, sizeof(buf)) == sizeof(buf)); zeros(buf, sizeof(buf));
    CHECK(read(fd, buf, 0) == 0);
    ERROR(write(fd, "x", 1), ENOSPC); ERROR(write(fd, "", 0), ENOSPC);
    ERROR(read(fd, (void *)1, 1), EFAULT);
    void *memory = mmap(NULL, 4096, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(memory != MAP_FAILED); ERROR(read(fd, memory, 1), EFAULT); CHECK(munmap(memory, 4096) == 0);
    close(fd);
    fd = full(O_RDONLY); ERROR(write(fd, "x", 1), EBADF); close(fd);
    fd = full(O_WRONLY); ERROR(read(fd, buf, 1), EBADF); close(fd);
    fd = full(O_PATH); ERROR(read(fd, buf, 1), EBADF); ERROR(write(fd, "x", 1), EBADF); close(fd);
    fd = full(O_RDWR);
    unsigned char *large = malloc(131072); CHECK(large != NULL); memset(large, 42, 131072);
    ssize_t count = read(fd, large, 131072); CHECK(count > 0 && count <= 131072); zeros(large, count); free(large); close(fd);
}

static void vectors(void) {
    int fd = full(O_RDWR); unsigned char a[13], b[19];
    memset(a, 42, sizeof(a)); memset(b, 42, sizeof(b));
    struct iovec vec[] = {{a, sizeof(a)}, {b, sizeof(b)}};
    CHECK(readv(fd, vec, 2) == 32); zeros(a, sizeof(a)); zeros(b, sizeof(b));
    CHECK(readv(fd, vec, 0) == 0); ERROR(writev(fd, vec, 2), ENOSPC); CHECK(writev(fd, vec, 0) == 0);
    ERROR(syscall(SYS_readv, fd, (void *)1, 1), EFAULT);
    ERROR(syscall(SYS_writev, fd, (void *)1, 1), EFAULT);
    ERROR(syscall(SYS_readv, fd, vec, -1), EINVAL);
    struct iovec bad = {(void *)1, 1}; ERROR(readv(fd, &bad, 1), EFAULT);
    memset(a, 42, sizeof(a)); memset(b, 42, sizeof(b));
    CHECK(preadv(fd, vec, 2, 123) == 32); zeros(a, sizeof(a)); zeros(b, sizeof(b));
    ERROR(pwritev(fd, vec, 2, 123), ENOSPC);
    ERROR(preadv(fd, vec, 2, -1), EINVAL); ERROR(pwritev(fd, vec, 2, -1), EINVAL);
    ERROR(syscall(SYS_readv, fd, vec, 1025), EINVAL);
    ERROR(syscall(SYS_writev, fd, vec, 1025), EINVAL);
    struct iovec overflow[] = {{a, (size_t)SSIZE_MAX}, {b, 1}};
    ERROR(readv(fd, overflow, 2), EINVAL); ERROR(writev(fd, overflow, 2), EINVAL);
    long page = sysconf(_SC_PAGESIZE); CHECK(page > 0);
    void *table = mmap(NULL, page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(table != MAP_FAILED); ERROR(readv(fd, table, 1), EFAULT); ERROR(writev(fd, table, 1), EFAULT);
    CHECK(munmap(table, page) == 0);
    struct iovec partial[] = {{a, sizeof(a)}, {(void *)1, 1}};
    memset(a, 42, sizeof(a)); CHECK(readv(fd, partial, 2) == sizeof(a)); zeros(a, sizeof(a));
    unsigned char *memory = mmap(NULL, page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(memory != MAP_FAILED); CHECK(mprotect(memory + page, page, PROT_NONE) == 0);
    struct iovec boundary = {memory + page - 4, 8}; memset(memory + page - 4, 42, 4);
    CHECK(readv(fd, &boundary, 1) == 4); zeros(memory + page - 4, 4);
    struct iovec protected = {memory + page, 1}; ERROR(readv(fd, &protected, 1), EFAULT);
    CHECK(mprotect(memory + page, page, PROT_READ | PROT_WRITE) == 0);
    memset(memory + page - 4, 42, 8); CHECK(readv(fd, &boundary, 1) == 8); zeros(memory + page - 4, 8);
    CHECK(munmap(memory, page * 2) == 0);
    close(fd);
}

static void positioned(void) {
    int fd = full(O_RDWR); unsigned char buf[32]; memset(buf, 42, sizeof(buf));
    CHECK(pread(fd, buf, sizeof(buf), 123) == sizeof(buf)); zeros(buf, sizeof(buf));
    ERROR(pwrite(fd, "x", 1, 123), ENOSPC);
    ERROR(pread(fd, buf, 1, -1), EINVAL); ERROR(pwrite(fd, "x", 1, -1), EINVAL);
    ERROR(pread(fd, (void *)1, 1, 0), EFAULT);
    CHECK(lseek(fd, 123, SEEK_SET) == 0); CHECK(lseek(fd, -7, SEEK_CUR) == 0); CHECK(lseek(fd, 7, SEEK_END) == 0);
    close(fd);
}

static void metadata(void) {
    struct stat st; struct statx sx; int fd = full(O_RDWR);
    CHECK(stat("/dev/full", &st) == 0); identity(&st);
    CHECK(lstat("/dev/full", &st) == 0); identity(&st);
    CHECK(fstat(fd, &st) == 0); identity(&st);
    CHECK(syscall(SYS_statx, AT_FDCWD, "/dev/full", 0, STATX_BASIC_STATS, &sx) == 0);
    CHECK(S_ISCHR(sx.stx_mode)); CHECK(sx.stx_rdev_major == 1 && sx.stx_rdev_minor == 7);
    CHECK(syscall(SYS_statx, fd, "", AT_EMPTY_PATH, STATX_BASIC_STATS, &sx) == 0);
    CHECK(S_ISCHR(sx.stx_mode)); CHECK(sx.stx_rdev_major == 1 && sx.stx_rdev_minor == 7);
    ERROR(syscall(SYS_fstat, fd, (void *)1), EFAULT);
    ERROR(syscall(SYS_newfstatat, AT_FDCWD, "/dev/full", (void *)1, 0), EFAULT);
    ERROR(syscall(SYS_newfstatat, AT_FDCWD, "/dev/full", &st, 0x40000000), EINVAL);
    ERROR(syscall(SYS_statx, AT_FDCWD, "/dev/full", 0, STATX_BASIC_STATS, (void *)1), EFAULT);
    ERROR(syscall(SYS_statx, AT_FDCWD, "/dev/full", 0x40000000, STATX_BASIC_STATS, &sx), EINVAL);
    long page = sysconf(_SC_PAGESIZE); CHECK(page > 0);
    void *output = mmap(NULL, page, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(output != MAP_FAILED);
    ERROR(syscall(SYS_statx, AT_FDCWD, "/dev/full", 0, STATX_BASIC_STATS, output), EFAULT);
    CHECK(munmap(output, page) == 0);
    errno = 0; CHECK(mmap(NULL, 4096, PROT_READ, MAP_PRIVATE, fd, 0) == MAP_FAILED); CHECK(errno == ENODEV);
    ERROR(ftruncate(fd, 0), EINVAL); ERROR(truncate("/dev/full", 0), EINVAL);
    ERROR(ioctl(fd, FIONREAD, &st), ENOTTY); ERROR(fsync(fd), EINVAL); close(fd);
}

static void duplicate(void) {
    int fd = full(O_RDWR), copy = dup(fd); CHECK(copy >= 0); ERROR(write(copy, "x", 1), ENOSPC);
    int ordinary = open("ordinary", O_RDWR | O_CREAT | O_TRUNC, 0600); CHECK(ordinary >= 0);
    CHECK(dup2(fd, ordinary) == ordinary); ERROR(write(ordinary, "x", 1), ENOSPC);
    CHECK(dup2(fd, fd) == fd);
    int third = ordinary + 30; CHECK(dup3(fd, third, O_CLOEXEC) == third);
    CHECK(fcntl(third, F_GETFD) & FD_CLOEXEC); ERROR(write(third, "x", 1), ENOSPC);
    int fourth = fcntl(fd, F_DUPFD, 70); CHECK(fourth >= 70); ERROR(write(fourth, "x", 1), ENOSPC);
    int fifth = fcntl(fd, F_DUPFD_CLOEXEC, 90); CHECK(fifth >= 90); CHECK(fcntl(fifth, F_GETFD) & FD_CLOEXEC);
    ERROR(write(fifth, "x", 1), ENOSPC);
    close(copy); close(ordinary); close(third); close(fourth); close(fifth);
    ordinary = open("ordinary", O_RDWR | O_TRUNC); CHECK(ordinary >= 0);
    CHECK(dup2(ordinary, fd) == fd); CHECK(write(fd, "normal", 6) == 6);
    CHECK(lseek(fd, 0, SEEK_SET) == 0); char buf[6]; CHECK(read(fd, buf, 6) == 6); CHECK(memcmp(buf, "normal", 6) == 0);
    close(fd); close(ordinary);
    fd = full(O_RDWR); int previous = fd; close(fd);
    ordinary = open("ordinary", O_RDWR | O_TRUNC); CHECK(ordinary == previous); CHECK(write(ordinary, "reuse", 5) == 5); CHECK(lseek(ordinary, 0, SEEK_SET) == 0);
    CHECK(read(ordinary, buf, 5) == 5); CHECK(memcmp(buf, "reuse", 5) == 0); close(ordinary);
}

static void inherit(const char *self) {
    int fd = full(O_RDWR); pid_t child = fork(); CHECK(child >= 0);
    if (!child) { ERROR(write(fd, "x", 1), ENOSPC); close(fd); _exit(0); }
    int status; CHECK(waitpid(child, &status, 0) == child); CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    ERROR(write(fd, "x", 1), ENOSPC);
    int clo = full(O_RDWR | O_CLOEXEC); char first[32], second[32];
    snprintf(first, sizeof(first), "%d", fd); snprintf(second, sizeof(second), "%d", clo);
    execl(self, self, "after-exec", first, second, NULL); CHECK(0);
}

static void paths(void) {
    int dir = open("/dev", O_PATH | O_DIRECTORY); CHECK(dir >= 0);
    int fd = openat(dir, "full", O_RDWR); CHECK(fd >= 0); ERROR(write(fd, "x", 1), ENOSPC); close(fd); close(dir);
    CHECK(symlink("/dev/full", "full-alias") == 0); fd = open("full-alias", O_RDWR); CHECK(fd >= 0);
    ERROR(write(fd, "x", 1), ENOSPC); close(fd); unlink("full-alias");
    fd = open("/dev/zero", O_RDWR); CHECK(fd >= 0); unsigned char buf[17]; memset(buf, 42, sizeof(buf));
    CHECK(read(fd, buf, sizeof(buf)) == sizeof(buf)); zeros(buf, sizeof(buf)); CHECK(write(fd, "x", 1) == 1); close(fd);
}

static void transfers(void) {
    int fd = full(O_RDWR); unsigned char buf[8]; struct iovec vec = {buf, sizeof(buf)};
    ERROR(syscall(SYS_preadv2, fd, &vec, 1, 0, 0, 0), EOPNOTSUPP);
    ERROR(syscall(SYS_pwritev2, fd, &vec, 1, 0, 0, 0), EOPNOTSUPP);
    int ordinary = open("transfer", O_RDWR | O_CREAT | O_TRUNC, 0600); CHECK(ordinary >= 0);
    CHECK(write(ordinary, "payload", 7) == 7); CHECK(lseek(ordinary, 0, SEEK_SET) == 0);
    ERROR(sendfile(fd, ordinary, NULL, 1), EINVAL); ERROR(sendfile(ordinary, fd, NULL, 1), EINVAL);
    int pipes[2]; CHECK(pipe(pipes) == 0); CHECK(write(pipes[1], "x", 1) == 1);
    ERROR(splice(pipes[0], NULL, fd, NULL, 1, 0), EINVAL);
    ERROR(splice(fd, NULL, pipes[1], NULL, 1, 0), EINVAL);
    ERROR(syscall(SYS_copy_file_range, ordinary, NULL, fd, NULL, 1, 0), EINVAL);
    ERROR(syscall(SYS_copy_file_range, fd, NULL, ordinary, NULL, 1, 0), EINVAL);
    close(pipes[0]); close(pipes[1]); close(ordinary); close(fd);
}

static void descriptor_transfer(void) {
    int sockets[2]; CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    int fd = full(O_RDWR); pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        close(sockets[0]); close(fd);
        char byte; struct iovec vec = {&byte, 1};
        union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(int))]; } control;
        memset(&control, 0, sizeof(control));
        struct msghdr message = {.msg_iov = &vec, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control)};
        CHECK(recvmsg(sockets[1], &message, 0) == 1);
        struct cmsghdr *header = CMSG_FIRSTHDR(&message); CHECK(header != NULL);
        CHECK(header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS);
        int received; memcpy(&received, CMSG_DATA(header), sizeof(received));
        ERROR(write(received, "x", 1), ENOSPC);
        unsigned char bytes[8]; CHECK(read(received, bytes, sizeof(bytes)) == sizeof(bytes)); zeros(bytes, sizeof(bytes));
        struct stat st; CHECK(fstat(received, &st) == 0); identity(&st);
        close(received); close(sockets[1]); _exit(0);
    }
    close(sockets[1]); char byte = 'x'; struct iovec vec = {&byte, 1};
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(int))]; } control;
    memset(&control, 0, sizeof(control));
    struct msghdr message = {.msg_iov = &vec, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    struct cmsghdr *header = CMSG_FIRSTHDR(&message); header->cmsg_level = SOL_SOCKET; header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int)); memcpy(CMSG_DATA(header), &fd, sizeof(fd));
    CHECK(sendmsg(sockets[0], &message, 0) == 1); close(sockets[0]);
    int status; CHECK(waitpid(child, &status, 0) == child); CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    ERROR(write(fd, "x", 1), ENOSPC); close(fd);
}

int main(int argc, char **argv) {
    CHECK(argc >= 2);
    if (!strcmp(argv[1], "scalar")) scalar();
    else if (!strcmp(argv[1], "vectors")) vectors();
    else if (!strcmp(argv[1], "positioned")) positioned();
    else if (!strcmp(argv[1], "metadata")) metadata();
    else if (!strcmp(argv[1], "duplicate")) duplicate();
    else if (!strcmp(argv[1], "inherit")) inherit(argv[0]);
    else if (!strcmp(argv[1], "after-exec")) { CHECK(argc == 4); ERROR(write(atoi(argv[2]), "x", 1), ENOSPC); ERROR(fcntl(atoi(argv[3]), F_GETFD), EBADF); }
    else if (!strcmp(argv[1], "paths")) paths();
    else if (!strcmp(argv[1], "transfers")) transfers();
    else if (!strcmp(argv[1], "descriptor-transfer")) descriptor_transfer();
    else if (!strcmp(argv[1], "zero-override")) { int fd = full(O_RDWR); unsigned char buf[8]; CHECK(write(fd, "x", 1) == 1); CHECK(read(fd, buf, sizeof(buf)) == sizeof(buf)); zeros(buf, sizeof(buf)); struct stat st; CHECK(fstat(fd, &st) == 0); CHECK(S_ISCHR(st.st_mode)); CHECK(major(st.st_rdev) == 1 && minor(st.st_rdev) == 5); close(fd); }
    else if (!strcmp(argv[1], "disabled")) { CHECK(open("/dev/full", O_RDWR) == -1); CHECK(errno == ENOENT || errno == EACCES); }
    else if (!strcmp(argv[1], "override")) { int fd = full(O_RDWR); CHECK(write(fd, "override", 8) == 8); close(fd); }
    else CHECK(0);
    puts("passed"); return 0;
}
