#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <netinet/in.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    if (argc != 2)
        return 2;

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
