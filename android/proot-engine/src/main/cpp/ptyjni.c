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

#define TAG "PTY"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int open_ptm(void) {
    int fd = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (fd < 0) {
        LOGE("open /dev/ptmx: %s", strerror(errno));
        return -1;
    }
    return fd;
}

static int setup_slave(int ptm_fd) {
    if (grantpt(ptm_fd) < 0) {
        LOGE("grantpt: %s", strerror(errno));
        return -1;
    }
    if (unlockpt(ptm_fd) < 0) {
        LOGE("unlockpt: %s", strerror(errno));
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
    if (!value) return NULL;
    const char *text = (*env)->GetStringUTFChars(env, value, NULL);
    if (!text) return NULL;
    char *copy = strdup(text);
    (*env)->ReleaseStringUTFChars(env, value, text);
    return copy;
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

static char **copy_environment(JNIEnv *env, jobjectArray values) {
    size_t count = 0;
    while (environ[count]) count++;
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

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeForkPty(
    JNIEnv *env, jclass cls, jstring jCmd, jobjectArray jArgs, jobjectArray jEnvVars,
    jint rows, jint cols) {

    (void)cls;
    char *cmd = copy_string(env, jCmd);
    char **args = cmd ? copy_arguments(env, jArgs, cmd) : NULL;
    char **envp = copy_environment(env, jEnvVars);
    char *slave = NULL;
    int ptm_fd = -1;
    pid_t pid;
    if (!cmd || !args || !envp || rows < 1 || cols < 1) goto failed;

    ptm_fd = open_ptm();
    if (ptm_fd < 0 || setup_slave(ptm_fd) < 0) goto failed;
    struct winsize ws = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols};
    if (ioctl(ptm_fd, TIOCSWINSZ, &ws) < 0) goto failed;
    const char *slave_name = ptsname(ptm_fd);
    if (!slave_name || !(slave = strdup(slave_name))) goto failed;
    pid = fork();
    if (pid < 0) goto failed;
    if (pid == 0) {
        close(ptm_fd);
        if (setsid() < 0) _exit(127);
        int pts_fd = open(slave, O_RDWR);
        if (pts_fd < 0) _exit(127);
        if (dup2(pts_fd, STDIN_FILENO) < 0 || dup2(pts_fd, STDOUT_FILENO) < 0 ||
            dup2(pts_fd, STDERR_FILENO) < 0) _exit(127);
        if (pts_fd > 2) close(pts_fd);
        execve(cmd, args, envp);
        static const char message[] = "Cannot start terminal process\n";
        write(STDERR_FILENO, message, sizeof(message) - 1);
        _exit(127);
    }
    last_child_pid = pid;
    free(cmd); free(slave); free_strings(args); free_strings(envp);
    return ptm_fd;
failed:
    if (ptm_fd >= 0) close(ptm_fd);
    free(cmd); free(slave); free_strings(args); free_strings(envp);
    return -1;
}

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeGetPid(JNIEnv *env, jclass cls) {
    return (jint) last_child_pid;
}

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeRead(
    JNIEnv *env, jclass cls, jint fd, jbyteArray jBuf, jint offset, jint length) {

    jbyte *buf = (*env)->GetByteArrayElements(env, jBuf, NULL);
    ssize_t n = read(fd, buf + offset, length);
    (*env)->ReleaseByteArrayElements(env, jBuf, buf, 0);

    if (n < 0) {
        if (errno == EAGAIN || errno == EINTR) return 0;
        return -1;
    }
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeWrite(
    JNIEnv *env, jclass cls, jint fd, jbyteArray jBuf, jint offset, jint length) {

    jbyte *buf = (*env)->GetByteArrayElements(env, jBuf, NULL);
    ssize_t n = write(fd, buf + offset, length);
    (*env)->ReleaseByteArrayElements(env, jBuf, buf, JNI_ABORT);

    if (n < 0) {
        if (errno == EAGAIN || errno == EINTR) return 0;
        return -1;
    }
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeResize(
    JNIEnv *env, jclass cls, jint fd, jint rows, jint cols) {

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    return ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeWaitPid(JNIEnv *env, jclass cls, jint pid) {
    int status;
    pid_t result = waitpid(pid, &status, WNOHANG);
    if (result < 0) return -1;
    if (result == 0) return 0;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return -2;
}

JNIEXPORT void JNICALL
Java_id_or_oo_pr_engine_PtyNative_nativeClose(JNIEnv *env, jclass cls, jint fd) {
    close(fd);
}
