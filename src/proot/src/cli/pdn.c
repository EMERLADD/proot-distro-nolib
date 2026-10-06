#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int proot_main(int argc, char *const argv[]);

static int fail(const char *message, const char *value)
{
    fprintf(stderr, "pdn: %s: %s\n", message, value);
    return 2;
}

static int equal(const char *a, const char *b)
{
    while (*a && *b) {
        unsigned char x = *a++, y = *b++;
        if (x >= 'A' && x <= 'Z') x += 'a' - 'A';
        if (y >= 'A' && y <= 'Z') y += 'a' - 'A';
        if (x != y) return 0;
    }
    return *a == *b;
}

static char *join(const char *a, const char *b)
{
    char *result;
    if (asprintf(&result, "%s/%s", a, b) < 0) {
        perror("pdn");
        exit(2);
    }
    return result;
}

static const char *nonempty(const char *name)
{
    const char *value = getenv(name);
    return value && *value ? value : NULL;
}

static int help(void)
{
    puts("pdn - proot-distro-nolib\n"
         "Usage:\n"
         "  pdn login NAME [-- COMMAND ARG...]\n"
         "  pdn login --rootfs PATH [-- COMMAND ARG...]\n"
         "  pdn list (alias: ls)\n"
         "  pdn version\n"
         "  pdn proot [PROOT OPTIONS...]\n\n"
         "Rootfs directory: PDN_ROOTFS_DIR or $HOME/.local/share/pdn/rootfs\n"
         "Names and commands ignore ASCII case; paths and guest arguments do not.\n"
         "Use -- /bin/sh -c 'COMMAND' for shell expressions.\n"
         "Rootfs must already be extracted. Downloads are not included.");
    return 0;
}

static char *rootfs_base(void)
{
    const char *base = nonempty("PDN_ROOTFS_DIR");
    if (base) return strdup(base);
    base = nonempty("HOME");
    return base ? join(base, ".local/share/pdn/rootfs") : NULL;
}

static int valid_name(const char *name)
{
    const unsigned char *p = (const unsigned char *)name;
    if (!*p || *p == '.') return 0;
    for (; *p; p++) {
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '-' || *p == '_' || *p == '.'))
            return 0;
    }
    return 1;
}

static int directory(const char *path)
{
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

static int login(int argc, char *const argv[])
{
    char *root = NULL, *base = NULL, *candidate = NULL, *temp;
    const char *requested, *tmp;
    char **args;
    int command, n = 0, result;
    if (argc == 3 && (equal(argv[2], "--help") || equal(argv[2], "-h"))) return help();
    if (argc < 3) return fail("missing rootfs", "use login NAME or login --rootfs PATH");
    if (equal(argv[2], "--rootfs")) {
        if (argc < 4 || !*argv[3]) return fail("missing path", "--rootfs");
        requested = argv[3];
        candidate = strdup(requested);
        command = 4;
    } else {
        DIR *dir;
        struct dirent *entry;
        requested = argv[2];
        if (!valid_name(requested)) return fail("invalid name", requested);
        base = rootfs_base();
        if (!base) return fail("set PDN_ROOTFS_DIR or HOME", requested);
        dir = opendir(base);
        if (!dir) { result = fail(strerror(errno), base); free(base); return result; }
        while ((entry = readdir(dir))) {
            if (!equal(entry->d_name, requested)) continue;
            if (candidate) {
                closedir(dir); free(candidate); free(base);
                return fail("ambiguous name; use --rootfs", requested);
            }
            candidate = join(base, entry->d_name);
        }
        closedir(dir);
        free(base);
        if (!candidate) return fail("rootfs not found", requested);
        command = 3;
    }
    if (command < argc && strcmp(argv[command], "--") != 0) {
        free(candidate);
        return fail("expected -- before guest command", argv[command]);
    }
    if (command < argc && ++command == argc) {
        free(candidate);
        return fail("missing guest command", "--");
    }
    root = realpath(candidate, NULL);
    free(candidate);
    if (!root) return fail(strerror(errno), requested);
    if (!directory(root) || strcmp(root, "/") == 0) {
        free(root);
        return fail("rootfs must be a Linux directory other than /", requested);
    }
    tmp = nonempty("PROOT_TMP_DIR");
    if (!tmp) tmp = nonempty("TMPDIR");
    temp = tmp ? realpath(tmp, NULL) : join(root, ".pdn-tmp");
    if (!temp || (!tmp && mkdir(temp, 0700) < 0 && errno != EEXIST) ||
        !directory(temp) || access(temp, W_OK | X_OK) < 0) {
        free(root); free(temp);
        return fail("temporary directory unavailable", tmp ? tmp : ".pdn-tmp");
    }
    setenv("PROOT_TMP_DIR", temp, 1);
    if (!nonempty("PROOT_NO_SECCOMP")) setenv("PROOT_NO_SECCOMP", "1", 1);
    unsetenv("LD_PRELOAD");
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("ENV");
    unsetenv("BASH_ENV");
    setenv("HOME", "/root", 1);
    setenv("USER", "root", 1);
    setenv("LOGNAME", "root", 1);
    setenv("SHELL", "/bin/sh", 1);
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("TMPDIR", "/tmp", 1);
    args = calloc((size_t)argc + 32, sizeof(*args));
    if (!args) { free(root); free(temp); return fail("out of memory", "login"); }
    args[n++] = argv[0];
    args[n++] = "-0";
    args[n++] = "--kernel-release=6.17.0-pr";
    args[n++] = "--kill-on-exit";
    args[n++] = "-r"; args[n++] = root;
    args[n++] = "-b"; args[n++] = "/dev";
    args[n++] = "-b"; args[n++] = "/proc";
    args[n++] = "-b"; args[n++] = "/sys";
    args[n++] = "-w"; args[n++] = "/";
    args[n++] = "/bin/sh";
    args[n++] = "-c";
    args[n++] = "if [ \"$#\" -gt 0 ]; then exec \"$@\"; fi; "
                "cd /root 2>/dev/null || cd /; "
                "if [ -x /bin/bash ]; then SHELL=/bin/bash; export SHELL; exec /bin/bash -l; fi; "
                "exec /bin/sh -l";
    args[n++] = "pdn";
    for (; command < argc; command++) args[n++] = argv[command];
    result = proot_main(n, args);
    free(args); free(root); free(temp);
    return result;
}

static int list(void)
{
    char *base = rootfs_base();
    struct dirent **entries;
    int count, i;
    if (!base) return fail("set PDN_ROOTFS_DIR or HOME", "list");
    count = scandir(base, &entries, NULL, alphasort);
    if (count < 0) {
        int saved = errno;
        free(base);
        if (saved == ENOENT) { puts("No local rootfs found."); return 0; }
        return fail(strerror(saved), "rootfs directory");
    }
    for (i = 0; i < count; i++) {
        char *path = join(base, entries[i]->d_name);
        if (valid_name(entries[i]->d_name) && directory(path)) puts(entries[i]->d_name);
        free(path); free(entries[i]);
    }
    free(entries); free(base);
    return 0;
}

int main(int argc, char *const argv[])
{
    const char *name = strrchr(argv[0], '/');
    int named_pdn = equal(name ? name + 1 : argv[0], "pdn");
    if (argc > 1) {
        if (equal(argv[1], "login")) return login(argc, argv);
        if (equal(argv[1], "list") || equal(argv[1], "ls")) return argc == 2 ? list() : fail("unexpected argument", argv[2]);
        if (equal(argv[1], "help")) return help();
        if (equal(argv[1], "version")) {
            char *version_args[] = {argv[0], "--version", NULL};
            return proot_main(2, version_args);
        }
        if (equal(argv[1], "proot")) return proot_main(argc - 1, argv + 1);
        if (named_pdn && (equal(argv[1], "--help") || equal(argv[1], "-h"))) return help();
        if (named_pdn && argv[1][0] != '-') return fail("unknown command", argv[1]);
    } else if (named_pdn) return help();
    return proot_main(argc, argv);
}
