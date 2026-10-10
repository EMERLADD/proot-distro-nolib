#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "pdn_events.h"
#include "pdn_instance.h"
#include "pdn_json.h"

int pdn_instance_valid_name(const char *name)
{
    const unsigned char *p = (const unsigned char *)name;
    if (!p || !*p || !((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
        (*p >= '0' && *p <= '9') || *p == '_') || strlen(name) > 128) return 0;
    for (; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
              (*p >= '0' && *p <= '9') || *p == '.' || *p == '_' || *p == '-')) return 0;
    return 1;
}

static int hex(const char *value, size_t size)
{
    if (strlen(value) != size) return 0;
    for (; *value; value++)
        if (!((*value >= '0' && *value <= '9') || (*value >= 'a' && *value <= 'f'))) return 0;
    return 1;
}

static int valid(const struct pdn_instance *instance)
{
    return hex(instance->id, 32) && pdn_instance_valid_name(instance->name) &&
        (!*instance->distro || pdn_instance_valid_name(instance->distro)) &&
        !strcmp(instance->architecture, "aarch64") &&
        (!strcmp(instance->source, "archive") || !strcmp(instance->source, "mirror") ||
         !strcmp(instance->source, "restore") || !strcmp(instance->source, "clone")) &&
        (!*instance->sha256 || hex(instance->sha256, 64)) && instance->created_at >= 0 &&
        (!strcmp(instance->source, "restore") || !strcmp(instance->source, "clone") ||
         (*instance->distro && *instance->distro_version && *instance->sha256)) &&
        (strcmp(instance->source, "mirror") || *instance->source_url);
}

static int copy(char *destination, size_t size, const char *value)
{
    if (strlen(value) >= size) { errno = EINVAL; return -1; }
    for (const unsigned char *p = (const unsigned char *)value; *p; p++)
        if (*p < 32 || *p > 126) { errno = EINVAL; return -1; }
    strcpy(destination, value);
    return 0;
}

int pdn_instance_read(int rootfd, struct pdn_instance *instance)
{
    char buffer[4097];
    struct stat st;
    size_t used = 0;
    unsigned int seen = 0;
    int fd = openat(rootfd, ".pdn-instance", O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? 0 : -1;
    if (fstat(fd, &st) < 0) goto failed;
    if (!S_ISREG(st.st_mode) || st.st_size <= 0 || st.st_size > 4096) { errno = EINVAL; goto failed; }
    while (used < sizeof(buffer) - 1) {
        ssize_t bytes = read(fd, buffer + used, sizeof(buffer) - 1 - used);
        if (bytes < 0) { if (errno == EINTR) continue; goto failed; }
        if (!bytes) break;
        used += (size_t)bytes;
    }
    if (used != (size_t)st.st_size || used == 4096 || memchr(buffer, 0, used) || buffer[used - 1] != '\n') {
        errno = EINVAL; goto failed;
    }
    close(fd);
    buffer[used] = 0;
    memset(instance, 0, sizeof(*instance));
    const char *keys[] = {"version", "id", "name", "distro", "distro_version", "architecture", "source", "source_url", "sha256", "created_at"};
    char *fields[] = {NULL, instance->id, instance->name, instance->distro, instance->distro_version,
                      instance->architecture, instance->source, instance->source_url, instance->sha256, NULL};
    size_t sizes[] = {0, sizeof(instance->id), sizeof(instance->name), sizeof(instance->distro),
                      sizeof(instance->distro_version), sizeof(instance->architecture), sizeof(instance->source),
                      sizeof(instance->source_url), sizeof(instance->sha256), 0};
    for (char *line = buffer; *line;) {
        char *end = strchr(line, '\n'), *value = strchr(line, '=');
        if (!end || !value || value > end) goto invalid;
        *end = 0;
        *value++ = 0;
        size_t key;
        for (key = 0; key < 10 && strcmp(line, keys[key]); key++);
        if (key == 10 || (seen & (1U << key))) goto invalid;
        seen |= 1U << key;
        if (key == 0) { if (strcmp(value, "1")) goto invalid; }
        else if (key == 9) {
            if (!*value) goto invalid;
            for (const char *p = value; *p; p++) if (*p < '0' || *p > '9') goto invalid;
            char *tail;
            errno = 0;
            long long timestamp = strtoll(value, &tail, 10);
            if (errno || *tail || timestamp < 0) goto invalid;
            instance->created_at = timestamp;
        } else if (copy(fields[key], sizes[key], value) < 0) goto invalid;
        line = end + 1;
    }
    if (seen != 1023 || !valid(instance)) goto invalid;
    return 1;
invalid:
    errno = EINVAL;
    return -1;
failed:
    {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
}

static int failure(const char *message, int cause)
{
    pdn_events_system_problem("instance_metadata_failed", message,
                              "Check instance metadata and storage permissions, then retry", cause);
    fprintf(stderr, "pdn: %s: %s\n", message, strerror(cause));
    errno = cause;
    return -1;
}

static int serialize_instance(int rootfd, struct pdn_instance *instance)
{
    char temporary[80];
    int fd = -1;
    for (unsigned attempt = 0; attempt < 100; attempt++) {
        snprintf(temporary, sizeof(temporary), ".pdn-instance.%ld.%u", (long)getpid(), attempt);
        fd = openat(rootfd, temporary, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (fd >= 0 || errno != EEXIST) break;
    }
    if (fd < 0) return failure("cannot create instance metadata", errno);
    FILE *stream = fdopen(fd, "w");
    if (!stream) { int saved = errno; close(fd); unlinkat(rootfd, temporary, 0); return failure("cannot open instance metadata", saved); }
    int result = fprintf(stream,
        "version=1\nid=%s\nname=%s\ndistro=%s\ndistro_version=%s\narchitecture=%s\nsource=%s\nsource_url=%s\nsha256=%s\ncreated_at=%lld\n",
        instance->id, instance->name, instance->distro, instance->distro_version, instance->architecture,
        instance->source, instance->source_url, instance->sha256, (long long)instance->created_at);
    int saved = result < 0 ? (errno ? errno : EIO) : 0;
    if (fflush(stream) < 0 && !saved) saved = errno;
    if (!saved && fsync(fd) < 0) saved = errno;
    if (fclose(stream) < 0 && !saved) saved = errno;
    if (!saved && renameat(rootfd, temporary, rootfd, ".pdn-instance") < 0) saved = errno;
    if (!saved && fsync(rootfd) < 0) saved = errno;
    unlinkat(rootfd, temporary, 0);
    return saved ? failure("cannot write instance metadata", saved) : 0;
}

static int write_instance(int rootfd, struct pdn_instance *instance, const char *name)
{
    unsigned char random[16];
    size_t used = 0;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return failure("cannot generate instance identity", errno);
    while (used < sizeof(random)) {
        ssize_t bytes = read(fd, random + used, sizeof(random) - used);
        if (bytes < 0 && errno == EINTR) continue;
        if (bytes <= 0) { int saved = bytes < 0 ? errno : EIO; close(fd); return failure("cannot generate instance identity", saved); }
        used += (size_t)bytes;
    }
    close(fd);
    for (size_t i = 0; i < sizeof(random); i++) snprintf(instance->id + 2 * i, 3, "%02x", random[i]);
    if (copy(instance->name, sizeof(instance->name), name) < 0 || !pdn_instance_valid_name(name))
        return failure("invalid instance name", EINVAL);
    time_t timestamp = time(NULL);
    if (timestamp < 0) return failure("cannot obtain instance creation time", errno ? errno : EIO);
    instance->created_at = (int64_t)timestamp;
    if (!valid(instance)) return failure("invalid instance metadata", EINVAL);
    return serialize_instance(rootfd, instance);
}

int pdn_instance_create(int rootfd, const char *name, const char *distro,
                        const char *version, const char *sha256,
                        const char *source, const char *url)
{
    struct pdn_instance instance = {0};
    if (copy(instance.distro, sizeof(instance.distro), distro ? distro : "") < 0 ||
        copy(instance.distro_version, sizeof(instance.distro_version), version ? version : "") < 0 ||
        copy(instance.sha256, sizeof(instance.sha256), sha256 ? sha256 : "") < 0 ||
        copy(instance.source, sizeof(instance.source), source) < 0 ||
        copy(instance.source_url, sizeof(instance.source_url), url ? url : "") < 0)
        return failure("invalid instance provenance", EINVAL);
    strcpy(instance.architecture, "aarch64");
    return write_instance(rootfd, &instance, name);
}

int pdn_instance_restore(int rootfd, const char *name)
{
    struct pdn_instance instance = {0};
    if (pdn_instance_read(rootfd, &instance) < 0) return failure("cannot read restored instance metadata", errno);
    strcpy(instance.architecture, "aarch64");
    strcpy(instance.source, "restore");
    return write_instance(rootfd, &instance, name);
}

static void nullable(FILE *stream, const char *value)
{
    if (*value) pdn_json_string(stream, value);
    else fputs("null", stream);
}

void pdn_instance_json(FILE *stream, const struct pdn_instance *instance)
{
    fputs("{\"version\":1,\"id\":", stream); pdn_json_string(stream, instance->id);
    fputs(",\"name\":", stream); pdn_json_string(stream, instance->name);
    fputs(",\"distro\":", stream); nullable(stream, instance->distro);
    fputs(",\"distro_version\":", stream); nullable(stream, instance->distro_version);
    fputs(",\"architecture\":", stream); pdn_json_string(stream, instance->architecture);
    fputs(",\"source\":", stream); pdn_json_string(stream, instance->source);
    fputs(",\"source_url\":", stream); nullable(stream, instance->source_url);
    fputs(",\"sha256\":", stream); nullable(stream, instance->sha256);
    fprintf(stream, ",\"created_at\":%lld}", (long long)instance->created_at);
}

int pdn_instance_relocate(int rootfd, const char *name, int cloning)
{
    struct pdn_instance instance = {0};
    int found = pdn_instance_read(rootfd, &instance);
    if (found < 0) return failure("cannot read instance metadata", errno);
    if (!cloning && !found) return 0;
    if (!pdn_instance_valid_name(name) || copy(instance.name, sizeof(instance.name), name) < 0)
        return failure("invalid instance name", EINVAL);
    if (cloning) {
        strcpy(instance.architecture, "aarch64");
        strcpy(instance.source, "clone");
        return write_instance(rootfd, &instance, name);
    }
    return serialize_instance(rootfd, &instance);
}
