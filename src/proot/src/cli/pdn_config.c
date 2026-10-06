#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "cli/pdn_config.h"

#define CONFIG_LIMIT 65536

static int error(const char *message)
{
    fprintf(stderr, "pdn: %s\n", message);
    return 2;
}

static int env_valid(const char *value)
{
    const unsigned char *p = (const unsigned char *)value;
    if (!((*p >= 'A' && *p <= 'Z') || (*p >= 'a' && *p <= 'z') || *p == '_')) return 0;
    for (p++; *p && *p != '='; p++)
        if (!((*p >= 'A' && *p <= 'Z') || (*p >= 'a' && *p <= 'z') ||
              (*p >= '0' && *p <= '9') || *p == '_')) return 0;
    return *p == '=';
}

static int number(const char *value, unsigned long *out)
{
    const char *p;
    char *end;
    if (!*value) return 0;
    for (p = value; *p; p++) if (*p < '0' || *p > '9') return 0;
    errno = 0;
    *out = strtoul(value, &end, 10);
    return !errno && !*end && *out < UINT32_MAX;
}

static int user_valid(const char *value)
{
    const unsigned char *p = (const unsigned char *)value;
    unsigned long id;
    char *first;
    int ok;
    const char *colon = strchr(value, ':');
    if (!*value) return 0;
    if (colon) {
        first = strndup(value, (size_t)(colon - value));
        if (!first) return 0;
        ok = user_valid(first) && number(colon + 1, &id);
        free(first);
        return ok;
    }
    if (*p >= '0' && *p <= '9') return number(value, &id);
    if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') || *p == '_')) return 0;
    for (p++; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '_' || *p == '-' || *p == '.' || *p == '$')) return 0;
    return 1;
}

static int bind_valid(const char *value)
{
    const char *colon = strchr(value, ':');
    return value[0] == '/' && colon && colon[1] == '/' && colon[-1] != '!' &&
           !strchr(colon + 1, ':') && value[strlen(value) - 1] != '!';
}

int pdn_option_add(PdnOptions *options, char kind, const char *value)
{
    char *copy;
    int i;
    size_t key;
    if (strlen(value) > CONFIG_LIMIT / 2) return error("startup option too long");
    if ((kind == 'e' && !env_valid(value)) || (kind == 'u' && !user_valid(value)) ||
        (kind == 'w' && value[0] != '/') ||
        (kind == 'b' && !bind_valid(value)))
        return error("invalid startup option value");
    copy = strdup(value);
    if (!copy) return error("out of memory");
    if (kind == 'u' || kind == 'w') {
        char **destination = kind == 'u' ? &options->user : &options->workdir;
        free(*destination);
        *destination = copy;
        return 0;
    }
    if (kind == 'e') {
        key = (size_t)(strchr(value, '=') - value) + 1;
        for (i = 0; i < options->env_count; i++) {
            if (!strncmp(options->env[i], value, key)) {
                free(options->env[i]);
                options->env[i] = copy;
                return 0;
            }
        }
        if (options->env_count < PDN_OPTION_MAX) {
            options->env[options->env_count++] = copy;
            return 0;
        }
    } else if (kind == 'b' && options->bind_count < PDN_OPTION_MAX) {
        options->binds[options->bind_count++] = copy;
        return 0;
    }
    free(copy);
    return error("too many startup options");
}

void pdn_options_free(PdnOptions *options)
{
    int i;
    for (i = 0; i < options->bind_count; i++) free(options->binds[i]);
    for (i = 0; i < options->env_count; i++) free(options->env[i]);
    free(options->user);
    free(options->workdir);
    memset(options, 0, sizeof(*options));
}

static int config_status(int rootfd)
{
    struct stat st;
    if (fstatat(rootfd, ".pdn-config", &st, AT_SYMLINK_NOFOLLOW) < 0)
        return errno == ENOENT ? 0 : error("cannot inspect .pdn-config");
    return S_ISREG(st.st_mode) ? 0 : error(".pdn-config must be a regular file, not a symlink");
}

static int unhex(unsigned char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

int pdn_options_load(int rootfd, PdnOptions *options)
{
    int fd, result = 2;
    struct stat st;
    char *data = NULL, *p, *end;
    size_t length = 0;
    ssize_t got;
    if (config_status(rootfd)) return 2;
    fd = openat(rootfd, ".pdn-config", O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? 0 : error("cannot open .pdn-config");
    if (fstat(fd, &st) < 0 || !S_ISREG(st.st_mode) || st.st_size < 5 || st.st_size > CONFIG_LIMIT) goto done;
    data = malloc(CONFIG_LIMIT + 1);
    if (!data) goto done;
    while (length < CONFIG_LIMIT + 1) {
        got = read(fd, data + length, CONFIG_LIMIT + 1 - length);
        if (got < 0 && errno == EINTR) continue;
        if (got < 0) goto done;
        if (!got) break;
        length += (size_t)got;
    }
    if (length > CONFIG_LIMIT || memchr(data, 0, length) || length < 5 || memcmp(data, "PDN1\n", 5)) goto done;
    data[length] = 0;
    p = data + 5;
    while (*p) {
        char kind = *p, *decoded;
        size_t size, i;
        end = strchr(p, '\n');
        if (!end || end - p < 2 || p[1] != ':' || !strchr("beuw", kind)) goto done;
        if ((kind == 'u' && options->user) || (kind == 'w' && options->workdir)) goto done;
        size = (size_t)(end - p - 2);
        if (size % 2) goto done;
        decoded = malloc(size / 2 + 1);
        if (!decoded) goto done;
        for (i = 0; i < size; i += 2) {
            int hi = unhex((unsigned char)p[i + 2]), lo = unhex((unsigned char)p[i + 3]);
            if (hi < 0 || lo < 0 || !(hi || lo)) break;
            decoded[i / 2] = (char)(hi * 16 + lo);
        }
        decoded[size / 2] = 0;
        if (i != size || pdn_option_add(options, kind, decoded)) { free(decoded); goto done; }
        free(decoded);
        p = end + 1;
    }
    result = 0;
done:
    close(fd);
    free(data);
    return result ? error("invalid or unreadable .pdn-config; use --no-config to bypass") : 0;
}

static int write_record(FILE *file, char kind, const char *value, size_t *total)
{
    const unsigned char *p;
    size_t length = strlen(value);
    *total += length * 2 + 3;
    if (*total > CONFIG_LIMIT) return 2;
    if (fprintf(file, "%c:", kind) < 0) return 2;
    for (p = (const unsigned char *)value; *p; p++) if (fprintf(file, "%02x", *p) < 0) return 2;
    return fputc('\n', file) == EOF ? 2 : 0;
}

int pdn_options_save(int rootfd, const PdnOptions *options)
{
    char temporary[80];
    unsigned int attempt;
    int fd = -1, i, result = 2;
    FILE *file;
    size_t total = 5;
    if (config_status(rootfd)) return 2;
    for (attempt = 0; attempt < 100; attempt++) {
        snprintf(temporary, sizeof(temporary), ".pdn-config.%ld.%u", (long)getpid(), attempt);
        fd = openat(rootfd, temporary, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (fd >= 0 || errno != EEXIST) break;
    }
    if (fd < 0) return error("cannot create temporary config");
    file = fdopen(fd, "w");
    if (!file) { close(fd); unlinkat(rootfd, temporary, 0); return error("cannot open temporary config"); }
    if (fchmod(fd, 0600) < 0 || fputs("PDN1\n", file) == EOF) goto done;
    for (i = 0; i < options->bind_count; i++) if (write_record(file, 'b', options->binds[i], &total)) goto done;
    for (i = 0; i < options->env_count; i++) if (write_record(file, 'e', options->env[i], &total)) goto done;
    if (options->user && write_record(file, 'u', options->user, &total)) goto done;
    if (options->workdir && write_record(file, 'w', options->workdir, &total)) goto done;
    if (fflush(file) || fsync(fd) || config_status(rootfd) || renameat(rootfd, temporary, rootfd, ".pdn-config")) goto done;
    if (fsync(rootfd)) goto done;
    result = 0;
done:
    if (fclose(file)) result = 2;
    unlinkat(rootfd, temporary, 0);
    return result ? error("cannot atomically save config (limit 65536 bytes)") : 0;
}

int pdn_options_clear(int rootfd)
{
    if (config_status(rootfd)) return 2;
    if (unlinkat(rootfd, ".pdn-config", 0) < 0 && errno != ENOENT) return error("cannot clear .pdn-config");
    return fsync(rootfd) ? error("cannot sync config directory") : 0;
}

static void json_string(const char *value)
{
    const unsigned char *p;
    if (!value) { fputs("null", stdout); return; }
    putchar('"');
    for (p = (const unsigned char *)value; *p; p++) {
        if (*p == '"' || *p == '\\') { putchar('\\'); putchar(*p); }
        else if (*p < 32) printf("\\u%04x", *p);
        else putchar(*p);
    }
    putchar('"');
}

void pdn_options_show(const PdnOptions *options)
{
    int i;
    fputs("{\"bind\":[", stdout);
    for (i = 0; i < options->bind_count; i++) { if (i) putchar(','); json_string(options->binds[i]); }
    fputs("],\"env\":[", stdout);
    for (i = 0; i < options->env_count; i++) { if (i) putchar(','); json_string(options->env[i]); }
    fputs("],\"user\":", stdout); json_string(options->user);
    fputs(",\"work-dir\":", stdout); json_string(options->workdir);
    puts("}");
}

void pdn_identity_free(PdnIdentity *identity)
{
    free(identity->name);
    free(identity->home);
    free(identity->shell);
    memset(identity, 0, sizeof(*identity));
}

int pdn_identity(int rootfd, const char *user, PdnIdentity *identity)
{
    int etcfd = -1, fd = -1, numeric, found = 0, result = 2;
    unsigned long uid = 0, gid = 0, override_gid = 0;
    char *requested = strdup(user), *colon, *data = NULL, *line, *next;
    FILE *file = NULL;
    struct stat st;
    if (!requested) return error("out of memory");
    colon = strchr(requested, ':');
    if (colon) { *colon++ = 0; if (!number(colon, &override_gid)) goto done; }
    numeric = number(requested, &uid);
    gid = uid;
    etcfd = openat(rootfd, "etc", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (etcfd >= 0) fd = openat(etcfd, "passwd", O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        if (errno != ENOENT || !numeric) goto done;
    } else {
        size_t length;
        if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size < 0 || st.st_size > 1024 * 1024) goto done;
        file = fdopen(fd, "r");
        if (!file) goto done;
        fd = -1;
        data = malloc((size_t)st.st_size + 2);
        if (!data) goto done;
        length = fread(data, 1, (size_t)st.st_size + 1, file);
        if (ferror(file) || length > (size_t)st.st_size || memchr(data, 0, length)) goto done;
        data[length] = 0;
        for (line = data; *line; line = next) {
            char *fields[7], *part;
            unsigned long row_uid, row_gid;
            int i;
            next = strchr(line, '\n');
            if (next) *next++ = 0; else next = line + strlen(line);
            if (!*line) continue;
            part = line;
            for (i = 0; i < 7; i++) fields[i] = strsep(&part, ":");
            if (!fields[6] || part || !*fields[0] || !number(fields[2], &row_uid) || !number(fields[3], &row_gid)) goto done;
            if (numeric ? row_uid != uid : strcmp(requested, fields[0]) != 0) continue;
            if (found || fields[5][0] != '/' || fields[6][0] != '/') goto done;
            identity->name = strdup(fields[0]);
            identity->home = strdup(fields[5]);
            identity->shell = strdup(fields[6]);
            uid = row_uid;
            gid = row_gid;
            found = 1;
        }
    }
    if (!found) {
        if (!numeric) goto done;
        identity->name = strdup(requested);
        identity->home = strdup("/");
        identity->shell = strdup("/bin/sh");
    }
    if (!identity->name || !identity->home || !identity->shell) goto done;
    snprintf(identity->ids, sizeof(identity->ids), "%lu:%lu", uid, colon ? override_gid : gid);
    result = 0;
done:
    if (file) fclose(file);
    if (fd >= 0) close(fd);
    if (etcfd >= 0) close(etcfd);
    free(data);
    free(requested);
    return result ? error("guest user or passwd is invalid or unavailable") : 0;
}
