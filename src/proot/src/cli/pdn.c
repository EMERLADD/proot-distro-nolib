#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#include "cli/pdn_config.h"
#include "cli/pdn_events.h"
#include "cli/pdn_json.h"

#ifdef PDN_WITH_INSTALL
int pdn_install(const char *name, const char *local_archive, const char *mirror_name);
int pdn_mirrors(const char *name);
int pdn_available(void);
int pdn_available_json(void);
int pdn_mirrors_json(const char *name);
int pdn_archive(const char *name, const char *file, int restoring);
#endif

int proot_main(int argc, char *const argv[]);
int pdn_remove(const char *requested, int yes);

static int fail(const char *message, const char *value)
{
    fprintf(stderr, "pdn: %s: %s\n", message, value);
    pdn_events_problem("invalid_argument", message, "Check the selected rootfs and arguments; see pdn help");
    return 2;
}

static int coded_fail(const char *code, const char *message, const char *value)
{
    fprintf(stderr, "pdn: %s: %s\n", message, value);
    const char *advice = !strcmp(code, "operation_busy") ? "Wait for the operation to finish or exit the rootfs sessions, then retry" :
        !strcmp(code, "lock_failed") ? "Check filesystem locking support and permissions; inspect stderr" :
        !strcmp(code, "out_of_memory") ? "Free memory and retry" :
        !strcmp(code, "name_ambiguous") ? "Use --rootfs with an explicit path or choose distinct rootfs names" :
        !strcmp(code, "directory_missing") ? "Install the distro or select an existing rootfs parent directory" :
        "Check the selected paths and retry";
    pdn_events_problem(code, message, advice);
    return 2;
}

static const char *directory_code(int code, const char *missing)
{
    if (code == ENOENT) return missing;
    if (code == ENOTDIR) return "directory_not_directory";
    if (code == EACCES || code == EPERM) return "directory_permission";
    if (code == EROFS) return "directory_read_only";
    return NULL;
}

static int root_error(const char *message, const char *value, int code)
{
    fprintf(stderr, "pdn: %s: %s\n", message, value);
    const char *classification = directory_code(code, "rootfs_missing");
    char detail[1024];
    snprintf(detail, sizeof(detail), "%s: %s", value, message);
    if (classification)
        pdn_events_problem(classification, detail, "Check PDN_ROOTFS_DIR or --rootfs; install the distro and select an accessible Linux rootfs directory");
    else pdn_events_system_problem("directory_unavailable", detail,
        "Check PDN_ROOTFS_DIR or --rootfs; install the distro and select an accessible Linux rootfs directory", code);
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
        pdn_events_problem("out_of_memory", "Could not allocate startup paths", "Free memory and retry");
        pdn_events_finish(2);
        exit(2);
    }
    return result;
}

static const char *nonempty(const char *name)
{
    const char *value = getenv(name);
    return value && *value ? value : NULL;
}

static int temp_error(const char *path, const char *source, int code)
{
    fprintf(stderr, "pdn: temporary directory unavailable: %s: %s (errno=%d)\n",
            path, strerror(code), code);
    fprintf(stderr, "pdn: temporary directory selected by %s.\n", source);
    if (code == ENOENT)
        fputs("pdn: create this directory with mkdir -p before retrying.\n", stderr);
    fputs("pdn: set PROOT_TMP_DIR to an existing writable directory with search permission.\n", stderr);
    const char *classification = directory_code(code, "directory_missing");
    char detail[1024];
    const char *advice = code == ENOENT ? "Create the selected temporary directory with mkdir -p, then retry" :
        "Set PROOT_TMP_DIR to an existing writable directory with search permission";
    snprintf(detail, sizeof(detail), "%s: %s", path, strerror(code));
    if (classification) pdn_events_problem(classification, detail, advice);
    else pdn_events_system_problem("directory_unavailable", detail, advice, code);
    return 2;
}

static int help(void)
{
    puts("pdn - proot-distro-nolib\n"
         "Usage:\n"
#ifdef PDN_WITH_INSTALL
         "  pdn install NAME [--mirror NAME | --archive PATH]\n"
         "  pdn mirrors [NAME] [--json]\n  pdn list --available [--json]\n"
         "  pdn backup NAME FILE.tar.gz\n  pdn restore NAME FILE.tar.gz\n"
#endif
         "  pdn login NAME|--rootfs PATH [OPTIONS] [-- COMMAND ARG...]\n"
         "  pdn exec NAME|--rootfs PATH [OPTIONS] -- COMMAND ARG...\n"
         "  pdn config NAME|--rootfs PATH [--show | --clear | OPTIONS]\n"
         "  OPTIONS: --bind/-b HOST[:GUEST], --env/-e KEY=VALUE (repeatable),\n"
         "           --user/-u NAME|UID[:GID], --work-dir/-w /GUEST/PATH\n"
         "  Login/exec: --no-config bypasses saved defaults.\n"
         "  Config replaces all saved options; no options shows defaults as JSON.\n"
         "  pdn list [--json] (alias: ls)\n"
         "  pdn uninstall NAME [--yes | -y] (alias: remove)\n"
         "  pdn version\n"
         "  pdn proot [PROOT OPTIONS...]\n\n"
         "Rootfs directory: PDN_ROOTFS_DIR or $HOME/.local/share/pdn/rootfs\n"
         "Names and commands ignore ASCII case; paths and guest arguments do not.\n"
         "Use -- /bin/sh -c 'COMMAND' for shell expressions.\n"
#ifdef PDN_WITH_INSTALL
         "Install: alpine, ubuntu, debian, arch (ARM64); --archive uses a pinned local tar.gz."
#else
         "Rootfs must already be extracted."
#endif
    );
    return 0;
}

char *pdn_rootfs_base(void)
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

static int parse_bind(const char *spec, char **binding)
{
    const char *colon = strchr(spec, ':');
    char *host, *resolved;
    struct stat st;
    int result;
    if (!*spec || colon == spec) return fail("missing bind host", spec);
    if (colon && !colon[1]) return fail("missing bind destination", spec);
    if (colon && (colon[1] != '/' || strchr(colon + 1, ':')))
        return fail("bind destination must be an absolute path without colons", spec);
    if (spec[strlen(spec) - 1] == '!' || (colon && colon[-1] == '!'))
        return fail("bind ! suffix is unsupported", spec);
    host = colon ? strndup(spec, (size_t)(colon - spec)) : strdup(spec);
    if (!host) return coded_fail("out_of_memory", "out of memory", "bind");
    resolved = realpath(host, NULL);
    int saved = errno;
    free(host);
    if (!resolved) {
        fprintf(stderr, "pdn: bind host unavailable: %s\n", spec);
        char detail[1024];
        snprintf(detail, sizeof(detail), "%s: %s", spec, strerror(saved));
        if (saved == ENOENT) pdn_events_problem("bind_source_missing", detail, "Select an existing accessible bind source");
        else pdn_events_system_problem("bind_source_unavailable", detail, "Select an existing accessible bind source", saved);
        return 2;
    }
    if (strchr(resolved, ':') || resolved[strlen(resolved) - 1] == '!') {
        free(resolved);
        return fail("bind host contains unsupported syntax", spec);
    }
    if (stat(resolved, &st) < 0) {
        int saved = errno;
        free(resolved);
        fprintf(stderr, "pdn: bind host must be a directory or regular file: %s\n", spec);
        char detail[1024];
        snprintf(detail, sizeof(detail), "%s: %s", spec, strerror(saved));
        if (saved == ENOENT) pdn_events_problem("bind_source_missing", detail, "Select an existing accessible bind source");
        else pdn_events_system_problem("bind_source_unavailable", detail, "Select an existing accessible bind source", saved);
        return 2;
    }
    if (!S_ISDIR(st.st_mode) && !S_ISREG(st.st_mode)) {
        free(resolved);
        return coded_fail("bind_source_unavailable", "bind host must be a directory or regular file", spec);
    }
    result = asprintf(binding, "%s:%s", resolved, colon ? colon + 1 : resolved);
    free(resolved);
    if (result < 0) { *binding = NULL; return coded_fail("out_of_memory", "out of memory", "bind"); }
    return 0;
}

static int resolve_root(int argc, char *const argv[], char **root, int *command)
{
    char *base = NULL, *candidate = NULL;
    const char *requested;
    int result = 0;
    if (argc < 3) return fail("missing rootfs", "use NAME or --rootfs PATH");
    if (equal(argv[2], "--rootfs")) {
        if (argc < 4 || !*argv[3]) return fail("missing path", "--rootfs");
        requested = argv[3];
        candidate = strdup(requested);
        *command = 4;
    } else {
        DIR *dir;
        struct dirent *entry;
        requested = argv[2];
        if (!valid_name(requested)) return fail("invalid name", requested);
        base = pdn_rootfs_base();
        if (!base) return coded_fail(nonempty("PDN_ROOTFS_DIR") || nonempty("HOME") ? "out_of_memory" : "invalid_argument", "set PDN_ROOTFS_DIR or HOME", requested);
        dir = opendir(base);
        if (!dir) {
            int saved = errno;
            if (saved == ENOENT) result = coded_fail("directory_missing", strerror(saved), base);
            else result = root_error(strerror(saved), base, saved);
            free(base);
            return result;
        }
        for (;;) {
            errno = 0;
            entry = readdir(dir);
            if (!entry) {
                if (errno) {
                    int saved = errno;
                    result = root_error(strerror(saved), base, saved);
                    closedir(dir); free(candidate); free(base);
                    return result;
                }
                break;
            }
            if (!equal(entry->d_name, requested)) continue;
            if (candidate) {
                closedir(dir); free(candidate); free(base);
                return coded_fail("name_ambiguous", "ambiguous name; use --rootfs", requested);
            }
            candidate = join(base, entry->d_name);
        }
        closedir(dir);
        free(base);
        if (!candidate) return root_error("rootfs not found", requested, ENOENT);
        *command = 3;
    }
    if (!candidate) return coded_fail("out_of_memory", "out of memory", requested);
    *root = realpath(candidate, NULL);
    int saved = errno;
    free(candidate);
    if (!*root) return root_error(strerror(saved), requested, saved);
    struct stat st;
    if (stat(*root, &st) < 0) result = root_error("rootfs must be a Linux directory other than /", requested, errno);
    else if (!S_ISDIR(st.st_mode)) result = coded_fail("directory_not_directory", "rootfs must be a Linux directory other than /", requested);
    else if (!strcmp(*root, "/")) result = fail("rootfs must be a Linux directory other than /", requested);
    return result;
}

int pdn_login(int argc, char *const argv[])
{
    char *root = NULL, *temp = NULL;
    const char *tmp;
    char **args = NULL;
    int command = 0, n = 0, result, rootfd = -1, i;
    int require_command = equal(argv[1], "exec"), config = equal(argv[1], "config");
    int no_config = 0, action = 0, option_count = 0, shell_override = 0;
    PdnOptions options = {0}, cli = {0};
    PdnIdentity identity = {0};
    if (argc == 3 && (equal(argv[2], "--help") || equal(argv[2], "-h"))) return help();
    result = resolve_root(argc, argv, &root, &command);
    if (result) goto done;
    while (command < argc && strcmp(argv[command], "--") != 0) {
        const char *option = argv[command++];
        char kind = 0, *binding = NULL;
        if (equal(option, "--bind") || equal(option, "-b")) kind = 'b';
        else if (equal(option, "--env") || equal(option, "-e")) kind = 'e';
        else if (equal(option, "--user") || equal(option, "-u")) kind = 'u';
        else if (equal(option, "--work-dir") || equal(option, "-w")) kind = 'w';
        else if (!config && equal(option, "--no-config")) { no_config = 1; continue; }
        else if (config && (equal(option, "--show") || equal(option, "--clear"))) {
            if (action || option_count) { result = fail("config actions cannot be combined", option); goto done; }
            action = equal(option, "--show") ? 1 : 2;
            continue;
        } else { result = fail("unexpected startup option", option); goto done; }
        if (action || command == argc || !strcmp(argv[command], "--")) {
            result = fail("missing value or incompatible config action", option);
            goto done;
        }
        if (kind == 'b') {
            result = parse_bind(argv[command++], &binding);
            if (!result) result = pdn_option_add(&cli, kind, binding);
            free(binding);
        } else result = pdn_option_add(&cli, kind, argv[command++]);
        if (result) goto done;
        option_count++;
    }
    if (config) {
        if (command < argc) { result = fail("config cannot save a guest command", "--"); goto done; }
        if (!action && !option_count) action = 1;
    } else if (command < argc) {
        if (++command == argc) { result = fail("missing guest command", "--"); goto done; }
    } else if (require_command) {
        result = fail("missing guest command", "exec requires -- COMMAND ARG...");
        goto done;
    }
    rootfd = open(root, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (rootfd < 0) {
        result = root_error("rootfs unavailable or locked by another operation", root, errno);
        goto done;
    }
    if (flock(rootfd, (config && action != 1 ? LOCK_EX : LOCK_SH) | LOCK_NB) < 0) {
        int saved = errno;
        if (saved == EWOULDBLOCK || saved == EAGAIN)
            result = coded_fail("operation_busy", "rootfs unavailable or locked by another operation", root);
        else {
            fprintf(stderr, "pdn: rootfs unavailable or locked by another operation: %s\n", root);
            pdn_events_system_problem("lock_failed", "Cannot lock the selected rootfs",
                "Check filesystem locking support and permissions; inspect stderr", saved);
            result = 2;
        }
        goto done;
    }
    if (config && action == 2) { result = pdn_options_clear(rootfd); goto done; }
    if ((!config && !no_config) || (config && action == 1)) {
        result = pdn_options_load(rootfd, &options);
        if (result) goto done;
    }
    if (config && action == 1) { pdn_options_show(&options); result = 0; goto done; }
    for (i = 0; i < cli.bind_count; i++) if ((result = pdn_option_add(&options, 'b', cli.binds[i]))) goto done;
    for (i = 0; i < cli.env_count; i++) if ((result = pdn_option_add(&options, 'e', cli.env[i]))) goto done;
    if (cli.user && (result = pdn_option_add(&options, 'u', cli.user))) goto done;
    if (cli.workdir && (result = pdn_option_add(&options, 'w', cli.workdir))) goto done;
    if (options.user && (result = pdn_identity(rootfd, options.user, &identity))) goto done;
    if (config) { result = pdn_options_save(rootfd, &options); goto done; }
    for (i = 0; i < options.bind_count; i++) {
        char *binding = NULL;
        result = parse_bind(options.binds[i], &binding);
        if (result) goto done;
        free(options.binds[i]);
        options.binds[i] = binding;
    }
    tmp = nonempty("PROOT_TMP_DIR");
    const char *temp_source = "PROOT_TMP_DIR";
    if (!tmp) { tmp = nonempty("TMPDIR"); temp_source = "TMPDIR"; }
    if (!tmp) temp_source = "rootfs/.pdn-tmp";
    temp = tmp ? realpath(tmp, NULL) : join(root, ".pdn-tmp");
    if (!temp) { result = temp_error(tmp, temp_source, errno); goto done; }
    if (!tmp && mkdir(temp, 0700) < 0 && errno != EEXIST) {
        result = temp_error(temp, temp_source, errno); goto done;
    }
    struct stat temp_stat;
    if (stat(temp, &temp_stat) < 0) { result = temp_error(temp, temp_source, errno); goto done; }
    if (!S_ISDIR(temp_stat.st_mode)) { result = temp_error(temp, temp_source, ENOTDIR); goto done; }
    if (access(temp, W_OK | X_OK) < 0) { result = temp_error(temp, temp_source, errno); goto done; }
    setenv("PROOT_TMP_DIR", temp, 1);
    if (!nonempty("PROOT_NO_SECCOMP")) setenv("PROOT_NO_SECCOMP", "1", 1);
    unsetenv("LD_PRELOAD");
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("ENV");
    unsetenv("BASH_ENV");
    setenv("HOME", identity.home ? identity.home : "/root", 1);
    setenv("USER", identity.name ? identity.name : "root", 1);
    setenv("LOGNAME", identity.name ? identity.name : "root", 1);
    setenv("SHELL", identity.shell ? identity.shell : "/bin/sh", 1);
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("TMPDIR", "/tmp", 1);
    args = calloc((size_t)argc + (size_t)options.bind_count * 2 + (size_t)options.env_count + 48, sizeof(*args));
    if (!args) { result = coded_fail("out_of_memory", "out of memory", argv[1]); goto done; }
    args[n++] = argv[0];
    args[n++] = "-0";
    if (options.user && strcmp(identity.ids, "0:0")) { args[n++] = "-i"; args[n++] = identity.ids; }
    args[n++] = "--link2symlink";
    args[n++] = "-L";
    args[n++] = "--kernel-release=6.17.0-pr";
    args[n++] = "--kill-on-exit";
    args[n++] = "-r"; args[n++] = root;
    args[n++] = "-b"; args[n++] = "/dev";
    args[n++] = "-b"; args[n++] = "/proc";
    args[n++] = "-b"; args[n++] = "/sys";
    for (i = 0; i < options.bind_count; i++) {
        args[n++] = "-b"; args[n++] = options.binds[i];
    }
    args[n++] = "-w"; args[n++] = "/";
    args[n++] = "/bin/sh";
    args[n++] = "-c";
    args[n++] = "while [ \"$1\" != -- ]; do export \"$1\" || exit 2; shift; done; shift; "
                "if [ -n \"$1\" ]; then cd \"$1\" || exit 2; fi; "
                "if [ \"$3\" = command ]; then shift 4; exec \"$@\"; fi; "
                "if [ -z \"$1\" ]; then cd \"$HOME\" 2>/dev/null || cd / || exit 2; fi; "
                "if [ -n \"$2\" ]; then exec \"$2\" -l; fi; "
                "if [ -x /bin/bash ]; then "
                "if [ \"$4\" = default ]; then SHELL=/bin/bash; export SHELL; fi; exec /bin/bash -l; fi; "
                "exec /bin/sh -l";
    args[n++] = "pdn";
    for (i = 0; i < options.env_count; i++) {
        if (!strncmp(options.env[i], "SHELL=", 6)) shell_override = 1;
        args[n++] = options.env[i];
    }
    args[n++] = "--";
    args[n++] = options.workdir ? options.workdir : "";
    args[n++] = identity.shell ? identity.shell : "";
    args[n++] = command < argc ? "command" : "interactive";
    args[n++] = shell_override ? "override" : "default";
    pdn_events_login_shell(command >= argc);
    for (; command < argc; command++) args[n++] = argv[command];
    pdn_events_stage("starting");
    result = proot_main(n, args);
done:
    if (rootfd >= 0) close(rootfd);
    pdn_options_free(&options);
    pdn_options_free(&cli);
    pdn_identity_free(&identity);
    free(args); free(root); free(temp);
    return result;
}


static int list(int json)
{
    char *base = pdn_rootfs_base();
    struct dirent **entries;
    int count, i, emitted = 0;
    if (!base) return coded_fail(nonempty("PDN_ROOTFS_DIR") || nonempty("HOME") ? "out_of_memory" : "invalid_argument", "set PDN_ROOTFS_DIR or HOME", "list");
    count = scandir(base, &entries, NULL, alphasort);
    if (count < 0) {
        int saved = errno;
        free(base);
        if (saved == ENOENT) { puts(json ? "{\"version\":1,\"distributions\":[]}" : "No local rootfs found."); return 0; }
        return root_error(strerror(saved), "rootfs directory", saved);
    }
    if (json) fputs("{\"version\":1,\"distributions\":[", stdout);
    for (i = 0; i < count; i++) {
        char *path = join(base, entries[i]->d_name);
        if (valid_name(entries[i]->d_name) && directory(path)) {
            if (json) {
                if (emitted++) fputc(',', stdout);
                fputs("{\"name\":", stdout);
                pdn_json_string(stdout, entries[i]->d_name);
                fputs(",\"rootfs\":", stdout);
                pdn_json_string(stdout, path);
                fputc('}', stdout);
            } else puts(entries[i]->d_name);
        }
        free(path); free(entries[i]);
    }
    if (json) puts("]}");
    free(entries); free(base);
    return 0;
}

static int dispatch(int argc, char *const argv[])
{
    const char *name = strrchr(argv[0], '/');
    int named_pdn = equal(name ? name + 1 : argv[0], "pdn");
    if (argc > 1) {
#ifdef PDN_WITH_INSTALL
        if (equal(argv[1], "backup") || equal(argv[1], "restore")) {
            if (argc == 3 && (equal(argv[2], "--help") || equal(argv[2], "-h"))) return help();
            if (argc != 4) return fail("usage", "backup|restore NAME FILE.tar.gz");
            return pdn_archive(argv[2], argv[3], equal(argv[1], "restore"));
        }
        if (equal(argv[1], "mirrors")) {
            int json = 0;
            const char *distro = NULL;
            for (int i = 2; i < argc; i++) {
                if (equal(argv[i], "--json") && !json) json = 1;
                else if (argv[i][0] != '-' && !distro) distro = argv[i];
                else return fail("unexpected argument", argv[i]);
            }
            return json ? pdn_mirrors_json(distro) : pdn_mirrors(distro);
        }
        if (equal(argv[1], "install")) {
            if (argc == 3 && (equal(argv[2], "--help") || equal(argv[2], "-h"))) return help();
            if (argc < 3) return fail("missing distro", "run list --available");
            if (argc == 3) return pdn_install(argv[2], NULL, NULL);
            if (argc == 5 && equal(argv[3], "--archive")) return pdn_install(argv[2], argv[4], NULL);
            if (argc == 5 && equal(argv[3], "--mirror")) return pdn_install(argv[2], NULL, argv[4]);
            return fail("usage", "install NAME [--mirror NAME | --archive PATH]");
        }
#endif
        if (equal(argv[1], "login") || equal(argv[1], "exec") || equal(argv[1], "config")) return pdn_login(argc, argv);
        if (equal(argv[1], "uninstall") || equal(argv[1], "remove")) {
            if (argc == 3 && (equal(argv[2], "--help") || equal(argv[2], "-h"))) return help();
            if (argc < 3 || argc > 4 || (argc == 4 && !equal(argv[3], "--yes") && !equal(argv[3], "-y")))
                return fail("usage", "uninstall NAME [--yes | -y]");
            if (!valid_name(argv[2])) return fail("invalid name", argv[2]);
            return pdn_remove(argv[2], argc == 4);
        }
        if (equal(argv[1], "list") || equal(argv[1], "ls")) {
            int json = 0, available = 0;
            for (int i = 2; i < argc; i++) {
                if (equal(argv[i], "--json") && !json) json = 1;
#ifdef PDN_WITH_INSTALL
                else if (equal(argv[i], "--available") && !available) available = 1;
#endif
                else return fail("unexpected argument", argv[i]);
            }
#ifdef PDN_WITH_INSTALL
            if (available) return json ? pdn_available_json() : pdn_available();
#else
            (void)available;
#endif
            return list(json);
        }
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

int main(int argc, char *const argv[])
{
    const char *operation = "proot";
    const char *names[] = {"install", "login", "exec", "config", "backup", "restore", "mirrors", "list", "remove", "help", "version"};
    if (argc > 1) {
        operation = argv[1][0] == '-' ? "proot" : "unknown";
        for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) if (equal(argv[1], names[i])) operation = names[i];
        if (equal(argv[1], "--version") || equal(argv[1], "-V")) operation = "version";
        if (equal(argv[1], "--help") || equal(argv[1], "-h")) operation = "help";
        if (equal(argv[1], "ls")) operation = "list";
        if (equal(argv[1], "uninstall")) operation = "remove";
    }
    int status = pdn_events_begin(operation);
    if (status) return status;
    pdn_events_stage("preparing");
    status = dispatch(argc, argv);
    pdn_events_finish(status);
    return status;
}
