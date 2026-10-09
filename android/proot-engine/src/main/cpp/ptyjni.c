#define _GNU_SOURCE
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <android/log.h>
#include <signal.h>
#include <stdio.h>

#define TAG "PTY"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef PDN_PTY_COVERAGE
extern void __llvm_profile_set_filename(const char *name);
extern int __llvm_profile_write_file(void);
static char *coverage_path;
static void flush_child_coverage(void) {
    if (!coverage_path) return;
    char path[4096];
    snprintf(path, sizeof(path), "%s.%ld", coverage_path, (long)getpid());
    __llvm_profile_set_filename(path);
    __llvm_profile_write_file();
}
#else
#define flush_child_coverage() ((void)0)
#endif

static int open_ptm(void) {
    int fd = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (fd < 0) {
        int saved = errno;
        LOGE("open /dev/ptmx: %s", strerror(saved));
        errno = saved;
        return -1;
    }
    return fd;
}

static int setup_slave(int ptm_fd) {
    if (grantpt(ptm_fd) < 0) {
        int saved = errno;
        LOGE("grantpt: %s", strerror(saved));
        errno = saved;
        return -1;
    }
    if (unlockpt(ptm_fd) < 0) {
        int saved = errno;
        LOGE("unlockpt: %s", strerror(saved));
        errno = saved;
        return -1;
    }
    return 0;
}

static pid_t last_child_pid = -1;

extern char **environ;

static void free_strings(char **strings) {
    if (!strings) return;
    for (size_t i = 0; strings[i]; i++) free(strings[i]);
    free(strings);
}

static char *copy_string(JNIEnv *env, jstring value) {
    if (!value) { errno = EINVAL; return NULL; }
    jsize n = (*env)->GetStringLength(env, value);
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (!chars) { errno = ENOMEM; return NULL; }
    char *copy = malloc((size_t)n * 4 + 1);
    size_t out = 0;
    if (!copy) { (*env)->ReleaseStringChars(env, value, chars); errno = ENOMEM; return NULL; }
    for (jsize i = 0; i < n; i++) {
        unsigned int c = chars[i];
        if (!c) goto invalid;
        if (c >= 0xd800 && c <= 0xdbff) {
            if (++i >= n || chars[i] < 0xdc00 || chars[i] > 0xdfff) goto invalid;
            c = 0x10000 + ((c - 0xd800) << 10) + chars[i] - 0xdc00;
        } else if (c >= 0xdc00 && c <= 0xdfff) goto invalid;
        if (c < 0x80) copy[out++] = c;
        else if (c < 0x800) { copy[out++] = 0xc0 | (c >> 6); copy[out++] = 0x80 | (c & 63); }
        else if (c < 0x10000) { copy[out++] = 0xe0 | (c >> 12); copy[out++] = 0x80 | ((c >> 6) & 63); copy[out++] = 0x80 | (c & 63); }
        else { copy[out++] = 0xf0 | (c >> 18); copy[out++] = 0x80 | ((c >> 12) & 63); copy[out++] = 0x80 | ((c >> 6) & 63); copy[out++] = 0x80 | (c & 63); }
    }
    copy[out] = 0;
    (*env)->ReleaseStringChars(env, value, chars);
    return copy;
invalid:
    (*env)->ReleaseStringChars(env, value, chars);
    free(copy); errno = EINVAL; return NULL;
}

static char **copy_arguments(JNIEnv *env, jobjectArray values, const char *cmd) {
    jsize count = values ? (*env)->GetArrayLength(env, values) : 1;
    if (count < 1) return NULL;
    char **result = calloc((size_t)count + 1, sizeof(char *));
    if (!result) return NULL;
    for (jsize i = 0; i < count; i++) {
        if (values) {
            jstring value = (*env)->GetObjectArrayElement(env, values, i);
            result[i] = copy_string(env, value);
            if (value) (*env)->DeleteLocalRef(env, value);
        } else {
            result[i] = strdup(cmd);
        }
        if (!result[i]) { free_strings(result); return NULL; }
    }
    return result;
}

static char **copy_environment(JNIEnv *env, jobjectArray values, int inherit) {
    size_t count = 0;
    if (inherit) while (environ[count]) count++;
    jsize updates = values ? (*env)->GetArrayLength(env, values) : 0;
    if (updates % 2) return NULL;
    char **result = calloc(count + (size_t)updates / 2 + 1, sizeof(char *));
    if (!result) return NULL;
    for (size_t i = 0; i < count; i++) {
        result[i] = strdup(environ[i]);
        if (!result[i]) { free_strings(result); return NULL; }
    }
    for (jsize i = 0; i < updates; i += 2) {
        jstring jkey = (*env)->GetObjectArrayElement(env, values, i);
        jstring jvalue = (*env)->GetObjectArrayElement(env, values, i + 1);
        char *key = copy_string(env, jkey), *value = copy_string(env, jvalue);
        if (jkey) (*env)->DeleteLocalRef(env, jkey);
        if (jvalue) (*env)->DeleteLocalRef(env, jvalue);
        if (!key || !value || !*key || strchr(key, '=')) {
            free(key); free(value); free_strings(result); return NULL;
        }
        size_t length = strlen(key), slot = 0;
        while (slot < count && !(strncmp(result[slot], key, length) == 0 && result[slot][length] == '=')) slot++;
        char *entry = malloc(length + strlen(value) + 2);
        if (entry) {
            memcpy(entry, key, length);
            entry[length] = '=';
            strcpy(entry + length + 1, value);
        }
        free(key); free(value);
        if (!entry) { free_strings(result); return NULL; }
        free(result[slot]);
        result[slot] = entry;
        if (slot == count) count++;
    }
    return result;
}

static int reserve_fd(int fd) {
    if (fd < 0 || fd > 2) return fd;
    int copy = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    int saved = errno;
    close(fd);
    errno = saved;
    return copy;
}

static void child_failure(int fd) {
    int error = errno;
    flush_child_coverage();
    ssize_t result;
    do { result = write(fd, &error, sizeof(error)); } while (result < 0 && errno == EINTR);
    _exit(127);
}

static void spawn(JNIEnv *env, jstring jCmd, jobjectArray jArgs, jobjectArray jEnvVars,
                  jint rows, jint cols, jstring directory, int inherit, int result[3]) {
    result[0] = -1; result[1] = -1; result[2] = EINVAL;
    if (rows < 1 || cols < 1 || rows > 65535 || cols > 65535) return;
    errno = EINVAL;
    char *cmd = copy_string(env, jCmd);
    char **args = cmd ? copy_arguments(env, jArgs, cmd) : NULL;
    char **envp = copy_environment(env, jEnvVars, inherit);
    char *cwd = directory ? copy_string(env, directory) : NULL;
    char slave[256];
    int ptm_fd = -1, errors[2] = {-1, -1};
    pid_t pid = -1;
    if (!cmd || !args || !envp || (directory && !cwd)) goto failed;
    ptm_fd = reserve_fd(open_ptm());
    if (ptm_fd < 0 || setup_slave(ptm_fd) < 0) goto failed;
    if (fcntl(ptm_fd, F_SETFD, FD_CLOEXEC) < 0 || fcntl(ptm_fd, F_SETFL, O_NONBLOCK) < 0) goto failed;
    struct winsize ws = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols};
    if (ioctl(ptm_fd, TIOCSWINSZ, &ws) < 0) goto failed;
    int name_error = ptsname_r(ptm_fd, slave, sizeof(slave));
    if (name_error != 0) { errno = name_error; goto failed; }
    if (pipe2(errors, O_CLOEXEC) < 0) goto failed;
    errors[0] = reserve_fd(errors[0]);
    if (errors[0] < 0) goto failed;
    errors[1] = reserve_fd(errors[1]);
    if (errors[1] < 0) goto failed;
    pid = fork();
    if (pid < 0) goto failed;
    if (pid == 0) {
        close(errors[0]); close(ptm_fd);
        if (setsid() < 0) child_failure(errors[1]);
        int pts_fd = open(slave, O_RDWR);
        if (pts_fd < 0) child_failure(errors[1]);
        if (ioctl(pts_fd, TIOCSCTTY, 0) < 0) child_failure(errors[1]);
        if (dup2(pts_fd, 0) < 0 || dup2(pts_fd, 1) < 0 || dup2(pts_fd, 2) < 0) child_failure(errors[1]);
        if (pts_fd > 2) close(pts_fd);
        if (cwd && chdir(cwd) < 0) child_failure(errors[1]);
        flush_child_coverage();
        execve(cmd, args, envp);
        child_failure(errors[1]);
    }
    close(errors[1]); errors[1] = -1;
    int error = 0;
    ssize_t received;
    do { received = read(errors[0], &error, sizeof(error)); } while (received < 0 && errno == EINTR);
    if (received != 0) {
        int saved = received == sizeof(error) ? error : (received < 0 ? errno : EIO);
        kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
        errno = saved;
        goto failed;
    }
    result[0] = ptm_fd; result[1] = pid; result[2] = 0;
    close(errors[0]); free(cmd); free(cwd); free_strings(args); free_strings(envp);
    return;
failed:
    result[2] = errno ? errno : EINVAL;
    if (ptm_fd >= 0) close(ptm_fd);
    if (errors[0] >= 0) close(errors[0]);
    if (errors[1] >= 0) close(errors[1]);
    free(cmd); free(cwd); free_strings(args); free_strings(envp);
}

JNIEXPORT jintArray JNICALL Java_id_or_oo_pr_engine_PtyNative_nativeSpawn(
    JNIEnv *env, jclass cls, jstring cmd, jobjectArray args, jobjectArray vars,
    jint rows, jint cols, jstring directory) {
    (void)cls;
    int values[3]; spawn(env, cmd, args, vars, rows, cols, directory, 0, values);
    jintArray result = (*env)->NewIntArray(env, 3);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    else if (values[0] >= 0) { close(values[0]); kill(values[1], SIGKILL); while (waitpid(values[1], NULL, 0) < 0 && errno == EINTR) {} }
    return result;
}

JNIEXPORT jint JNICALL Java_id_or_oo_pr_engine_PtyNative_nativeForkPty(
    JNIEnv *env, jclass cls, jstring cmd, jobjectArray args, jobjectArray vars, jint rows, jint cols) {
    (void)cls;
    int values[3]; spawn(env, cmd, args, vars, rows, cols, NULL, 1, values);
    if (values[0] >= 0) {
        last_child_pid = values[1];
        int flags = fcntl(values[0], F_GETFL);
        if (flags >= 0) fcntl(values[0], F_SETFL, flags & ~O_NONBLOCK);
    }
    return values[0];
}

JNIEXPORT jintArray JNICALL Java_id_or_oo_pr_engine_PtyNative_nativePoll(JNIEnv *env, jclass cls, jint pid) {
    (void)cls;
    int status = 0, values[3] = {0, 0, 0};
    pid_t result;
    if (pid <= 0) { values[0] = 3; values[2] = EINVAL; }
    else {
        do { result = waitpid(pid, &status, WNOHANG); } while (result < 0 && errno == EINTR);
        if (result < 0) { values[0] = 3; values[2] = errno; }
        else if (result > 0) {
            if (WIFEXITED(status)) { values[0] = 1; values[1] = WEXITSTATUS(status); }
            else if (WIFSIGNALED(status)) { values[0] = 2; values[1] = WTERMSIG(status); }
        }
    }
    jintArray array = (*env)->NewIntArray(env, 3);
    if (array) (*env)->SetIntArrayRegion(env, array, 0, 3, values);
    return array;
}

JNIEXPORT jint JNICALL Java_id_or_oo_pr_engine_PtyNative_nativeSignal(JNIEnv *env, jclass cls, jint pid, jint sig) {
    (void)env; (void)cls;
    if (pid <= 0 || (sig != SIGTERM && sig != SIGKILL)) return -EINVAL;
    return kill(pid, sig) < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeGetPid(JNIEnv *env, jclass cls) {
    return (jint) last_child_pid;
}

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeRead(
    JNIEnv *env, jclass cls, jint fd, jbyteArray jBuf, jint offset, jint length) {

    if (!jBuf || offset < 0 || length < 0 || offset > (*env)->GetArrayLength(env, jBuf) - length) return -EINVAL;
    jbyte *buf = (*env)->GetByteArrayElements(env, jBuf, NULL);
    if (!buf) return -ENOMEM;
    ssize_t n;
    do { n = read(fd, buf + offset, length); } while (n < 0 && errno == EINTR);
    int saved = errno;
    (*env)->ReleaseByteArrayElements(env, jBuf, buf, 0);

    if (n < 0) {
        if (saved == EAGAIN) return 0;
        return -saved;
    }
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeWrite(
    JNIEnv *env, jclass cls, jint fd, jbyteArray jBuf, jint offset, jint length) {

    if (!jBuf || offset < 0 || length < 0 || offset > (*env)->GetArrayLength(env, jBuf) - length) return -EINVAL;
    jbyte *buf = (*env)->GetByteArrayElements(env, jBuf, NULL);
    if (!buf) return -ENOMEM;
    ssize_t n = write(fd, buf + offset, length);
    int saved = errno;
    (*env)->ReleaseByteArrayElements(env, jBuf, buf, JNI_ABORT);

    if (n < 0) {
        if (saved == EAGAIN || saved == EINTR) return 0;
        return -saved;
    }
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeResize(
    JNIEnv *env, jclass cls, jint fd, jint rows, jint cols) {

    if (rows < 1 || cols < 1 || rows > 65535 || cols > 65535) return -EINVAL;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    return ioctl(fd, TIOCSWINSZ, &ws) < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeWaitPid(JNIEnv *env, jclass cls, jint pid) {
    if (pid <= 0) return -1;
    int status;
    pid_t result;
    do { result = waitpid(pid, &status, WNOHANG); } while (result < 0 && errno == EINTR);
    if (result < 0) return -1;
    if (result == 0) return 0;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return -2;
}

JNIEXPORT void JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeClose(JNIEnv *env, jclass cls, jint fd) {
    close(fd);
}

JNIEXPORT jint JNICALL Java_id_or_oo_pr_engine_PtyNative_nativeDumpCoverage(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
#ifdef PDN_PTY_COVERAGE
    char *value = copy_string(env, path);
    if (!value) return -1;
    free(coverage_path);
    coverage_path = value;
    __llvm_profile_set_filename(coverage_path);
    return __llvm_profile_write_file();
#else
    (void)env; (void)path;
    return -1;
#endif
}
