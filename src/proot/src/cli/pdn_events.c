#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "pdn_events.h"

static int event_fd = -1, finished, loaded, guest_status = -1, guest_signal, cancelled;
static unsigned sequence, progress_count;
static char operation[24], identifier[65], stage[32], error_code[64], error_message[513], suggestion[513];
static long long last_current = -1, last_tick;
static int last_percent = -1;

struct record { char data[8192]; size_t length; };

static void append(struct record *record, const char *format, ...)
{
    va_list args;
    va_start(args, format);
    int n = vsnprintf(record->data + record->length, sizeof(record->data) - record->length, format, args);
    va_end(args);
    if (n > 0 && (size_t)n < sizeof(record->data) - record->length) record->length += (size_t)n;
}

static int utf8_width(const unsigned char *p)
{
    int width = *p >= 0xc2 && *p <= 0xdf ? 2 : *p >= 0xe0 && *p <= 0xef ? 3 :
                *p >= 0xf0 && *p <= 0xf4 ? 4 : 0;
    for (int i = 1; i < width; i++) if (!p[i] || (p[i] & 0xc0) != 0x80) return 0;
    if ((width == 3 && ((*p == 0xe0 && p[1] < 0xa0) || (*p == 0xed && p[1] >= 0xa0))) ||
        (width == 4 && ((*p == 0xf0 && p[1] < 0x90) || (*p == 0xf4 && p[1] >= 0x90)))) return 0;
    return width;
}

static void text(struct record *record, const char *value)
{
    append(record, "\"");
    for (const unsigned char *p = (const unsigned char *)value; *p; p++) {
        if (*p == '"' || *p == '\\') append(record, "\\%c", *p);
        else if (*p < 32) append(record, "\\u%04x", *p);
        else if (*p < 128) append(record, "%c", *p);
        else {
            int width = utf8_width(p);
            if (!width) append(record, "\\ufffd");
            else { append(record, "%.*s", width, p); p += width - 1; }
        }
    }
    append(record, "\"");
}

static void field(struct record *record, const char *name, const char *value)
{
    append(record, ",\"%s\":", name);
    text(record, value);
}

static struct record start(const char *type)
{
    struct record record = {0};
    append(&record, "{\"version\":1,\"sequence\":%u", ++sequence);
    field(&record, "operation_id", identifier);
    field(&record, "operation", operation);
    field(&record, "type", type);
    if (*stage) field(&record, "stage", stage);
    return record;
}

static void send(struct record *record)
{
    int saved_errno = errno;
    if (event_fd < 0) return;
    append(record, "}\n");
    size_t offset = 0;
    while (offset < record->length) {
        ssize_t n = write(event_fd, record->data + offset, record->length - offset);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) {
            fprintf(stderr, "pdn: cannot write event channel: %s\n", strerror(errno));
            close(event_fd); event_fd = -1;
            errno = saved_errno;
            return;
        }
        offset += (size_t)n;
    }
    errno = saved_errno;
}

static void copy(char *target, size_t capacity, const char *source)
{
    size_t n = strlen(source);
    if (n >= capacity) {
        n = capacity - 1;
        while (n && ((unsigned char)source[n] & 0xc0) == 0x80) n--;
    }
    memcpy(target, source, n);
    target[n] = 0;
}

static void unexpected_exit(void)
{
    if (!finished) {
        if (!pdn_events_has_error())
            pdn_events_problem("manager_failed", "Operation stopped before normal completion", "Read stderr for details and retry the operation");
        pdn_events_finish(1);
    }
}

int pdn_events_begin(const char *name)
{
    const char *path = getenv("PDN_EVENT_FILE"), *id = getenv("PDN_OPERATION_ID");
    if (!path || !*path) { unsetenv("PDN_EVENT_FILE"); unsetenv("PDN_OPERATION_ID"); return 0; }
    if (id) {
        size_t length = strlen(id);
        if (!length || length > 64) goto invalid_id;
        for (const char *p = id; *p; p++)
            if (!((*p >= 'A' && *p <= 'Z') || (*p >= 'a' && *p <= 'z') ||
                  (*p >= '0' && *p <= '9') || *p == '_' || *p == '-' || *p == '.')) goto invalid_id;
        copy(identifier, sizeof(identifier), id);
    } else snprintf(identifier, sizeof(identifier), "pdn-%ld", (long)getpid());
    copy(operation, sizeof(operation), name);
    event_fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK, 0600);
    if (event_fd < 0 && errno == EEXIST)
        event_fd = open(path, O_WRONLY | O_APPEND | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    struct stat st;
    if (event_fd < 0 || fstat(event_fd, &st) < 0 || !S_ISREG(st.st_mode) ||
        st.st_uid != getuid() || st.st_nlink != 1 || st.st_size != 0 || (st.st_mode & 077) != 0) {
        fprintf(stderr, "pdn: event channel unavailable; use a private empty regular file owned by this process\n");
        if (event_fd >= 0) close(event_fd);
        event_fd = -1;
        return 2;
    }
    unsetenv("PDN_EVENT_FILE"); unsetenv("PDN_OPERATION_ID");
    atexit(unexpected_exit);
    struct record record = start("started");
    send(&record);
    return event_fd < 0 ? 2 : 0;
invalid_id:
    fputs("pdn: invalid PDN_OPERATION_ID; use 1-64 ASCII letters, digits, '.', '_' or '-'\n", stderr);
    return 2;
}

void pdn_events_stage(const char *value)
{
    if (event_fd < 0) return;
    copy(stage, sizeof(stage), value);
    last_percent = -1; last_current = -1; last_tick = 0;
    struct record record = start("stage");
    send(&record);
}

void pdn_events_progress(long long current, long long total)
{
    if (event_fd < 0 || current < 0 || progress_count >= 1024) return;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    long long tick = (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
    int percent = total > 0 ? (int)((double)current * 100 / total) : -1;
    if (percent > 100) percent = 100;
    if (last_current >= 0 && ((total > 0 && (percent == last_percent || (percent != 100 && tick - last_tick < 100))) ||
        (total <= 0 && (current - last_current < 1048576 || tick - last_tick < 250)))) return;
    struct record record = start("progress");
    append(&record, ",\"current\":%lld,\"total\":%lld", current, total);
    if (percent >= 0) append(&record, ",\"percent\":%d", percent);
    send(&record);
    last_current = current; last_percent = percent; last_tick = tick; progress_count++;
}

void pdn_events_problem(const char *code, const char *message, const char *advice)
{
    if (event_fd < 0) return;
    copy(error_code, sizeof(error_code), code);
    copy(error_message, sizeof(error_message), message);
    copy(suggestion, sizeof(suggestion), advice);
    struct record record = start("error");
    field(&record, "code", error_code); field(&record, "message", error_message); field(&record, "suggestion", suggestion);
    send(&record);
}

int pdn_events_has_error(void) { return *error_code != 0; }

void pdn_events_clear_error(void)
{
    *error_code = *error_message = *suggestion = 0;
}

void pdn_events_system_problem(const char *fallback, const char *message, const char *advice, int saved_errno)
{
    int previous_errno = errno;
    const char *code = fallback;
    if (saved_errno == EACCES || saved_errno == EPERM) {
        code = "file_permission";
        advice = "Check access permissions and select a permitted location";
    } else if (saved_errno == EROFS) {
        code = "file_read_only";
        advice = "Select a writable filesystem for this operation";
    } else if (saved_errno == ENOSPC || saved_errno == EDQUOT) {
        code = "storage_full";
        advice = "Free storage or quota in the selected location, then retry";
    } else if (saved_errno == ENOENT) {
        code = "file_missing";
        advice = "Check that the selected file and its parent directory exist";
    } else if (saved_errno == ENOMEM) {
        code = "out_of_memory";
        advice = "Free memory and retry";
    }
    char detail[513];
    if (saved_errno)
        snprintf(detail, sizeof(detail), "%s: %s (errno=%d)", message, strerror(saved_errno), saved_errno);
    else snprintf(detail, sizeof(detail), "%s", message);
    pdn_events_problem(code, detail, advice);
    errno = previous_errno;
}

void pdn_events_error(const char *message)
{
    if (pdn_events_has_error()) return;
    const char *code = "manager_failed", *advice = "Read stderr for details and correct the operation settings";
    if (!strcmp(stage, "downloading")) { code = "download_failed"; advice = "Check network access or choose another listed mirror"; }
    else if (!strcmp(stage, "verifying")) { code = "verification_failed"; advice = "Retry with a listed mirror or the exact pinned archive"; }
    else if (!strcmp(stage, "extracting")) { code = "extraction_failed"; advice = "Check available storage and use a verified archive"; }
    else if (!strcmp(stage, "configuring")) { code = "configuration_failed"; advice = "Check the rootfs directory permissions and archive contents"; }
    else if (!strcmp(stage, "backing_up") || !strcmp(stage, "restoring")) { code = "archive_failed"; advice = "Check archive integrity, storage and directory permissions"; }
    pdn_events_problem(code, message, advice);
}

void pdn_events_guest_loaded(void)
{
    if (loaded || (strcmp(operation, "exec") && strcmp(operation, "login"))) return;
    loaded = 1;
    pdn_events_stage("running");
}

void pdn_events_guest_exit(int status, int signal)
{
    if (!loaded) return;
    guest_status = status; guest_signal = signal;
}

void pdn_events_cancelled(int signal) { cancelled = signal; }

void pdn_events_finish(int status)
{
    if (finished) return;
    finished = 1;
    if (event_fd < 0) return;
    const char *outcome = status == 0 ? "success" : "manager_error";
    if (cancelled) outcome = "cancelled";
    else if (loaded && (guest_status >= 0 || guest_signal) && (status != 0 || guest_status != 0 || guest_signal)) outcome = "guest_exit";
    if (status != 0 && !strcmp(outcome, "manager_error") && !*error_code)
        pdn_events_error("PDN could not complete the operation; inspect stderr for details");
    struct record record = start("result");
    field(&record, "outcome", outcome);
    append(&record, ",\"exit_code\":%d", status & 255);
    if (loaded && guest_status >= 0) append(&record, ",\"guest_exit_code\":%d", guest_status);
    if (guest_signal) append(&record, ",\"guest_signal\":%d", guest_signal);
    if (cancelled) append(&record, ",\"signal\":%d", cancelled);
    if (status != 0 && *error_code && !strcmp(outcome, "manager_error")) {
        field(&record, "code", error_code); field(&record, "message", error_message); field(&record, "suggestion", suggestion);
    }
    send(&record);
    if (event_fd >= 0) close(event_fd);
    event_fd = -1;
}

void pdn_events_disable(void)
{
    if (event_fd >= 0) close(event_fd);
    event_fd = -1;
    finished = 1;
}
