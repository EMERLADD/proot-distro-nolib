#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <errno.h>
#include <signal.h>

#define JNI(name) Java_org_example_pdnsoleprobe_NativePty_##name

static void release(char **values) { if (values) { for (int i = 0; values[i]; i++) free(values[i]); free(values); } }

static char **strings(JNIEnv *env, jobjectArray values)
{
    if (!values) return NULL;
    jsize size = (*env)->GetArrayLength(env, values);
    char **result = calloc((size_t)size + 1, sizeof(char *));
    if (!result) return NULL;
    for (jsize i = 0; i < size; i++) {
        jstring value = (*env)->GetObjectArrayElement(env, values, i);
        if (!value || (*env)->ExceptionCheck(env)) { release(result); return NULL; }
        const char *text = (*env)->GetStringUTFChars(env, value, NULL);
        if (!text) { (*env)->DeleteLocalRef(env, value); release(result); return NULL; }
        result[i] = strdup(text);
        (*env)->ReleaseStringUTFChars(env, value, text);
        (*env)->DeleteLocalRef(env, value);
        if (!result[i]) { release(result); return NULL; }
    }
    return result;
}

JNIEXPORT jintArray JNICALL JNI(spawn)(JNIEnv *env, jclass cls, jobjectArray arguments, jobjectArray environment, jstring cwd, jint rows, jint cols)
{
    (void)cls;
    if (!arguments || !environment || !cwd || rows < 1 || cols < 1 || rows > 65535 || cols > 65535) return NULL;
    char **args = strings(env, arguments);
    if (!args || !args[0]) { release(args); return NULL; }
    char **vars = strings(env, environment);
    if (!vars) { release(args); return NULL; }
    const char *directory = (*env)->GetStringUTFChars(env, cwd, NULL);
    if (!directory) { release(args); release(vars); return NULL; }
    int master = posix_openpt(O_RDWR | O_CLOEXEC | O_NONBLOCK), slave = -1;
    jintArray result = NULL;
    char path[128];
    if (master < 0 || grantpt(master) || unlockpt(master) || ptsname_r(master, path, sizeof(path))) goto cleanup;
    slave = open(path, O_RDWR | O_CLOEXEC);
    if (slave < 0) goto cleanup;
    struct winsize size = { .ws_row = rows, .ws_col = cols };
    if (ioctl(slave, TIOCSWINSZ, &size)) goto cleanup;
    result = (*env)->NewIntArray(env, 2);
    if (!result) goto cleanup;
    pid_t pid = fork();
    if (pid == 0) {
        close(master);
        if (setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);
        if (dup2(slave, 0) < 0 || dup2(slave, 1) < 0 || dup2(slave, 2) < 0) _exit(126);
        if (slave > 2) close(slave);
        if (chdir(directory)) _exit(126);
        execve(args[0], args, vars); _exit(127);
    }
    if (pid < 0) { result = NULL; goto cleanup; }
    jint values[2] = {master, pid};
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    if ((*env)->ExceptionCheck(env)) {
        kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) { }
        result = NULL;
    }
cleanup:
    if (slave >= 0) close(slave);
    if (!result && master >= 0) close(master);
    release(args); release(vars); (*env)->ReleaseStringUTFChars(env, cwd, directory);
    return result;
}
JNIEXPORT jint JNICALL JNI(read)(JNIEnv *env, jclass cls, jint fd, jbyteArray data, jint offset, jint count)
{
    (void)cls;
    if (fd < 0 || !data || offset < 0 || count < 0) return -1;
    jsize size = (*env)->GetArrayLength(env, data);
    if (offset > size || count > size - offset) return -1;
    if (!count) return 0;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return -1;
    int n = read(fd, bytes + offset, count), saved = errno;
    (*env)->ReleaseByteArrayElements(env, data, bytes, 0);
    return n < 0 && (saved == EAGAIN || saved == EINTR) ? 0 : n;
}
JNIEXPORT jint JNICALL JNI(write)(JNIEnv *env, jclass cls, jint fd, jbyteArray data)
{
    (void)cls;
    if (fd < 0 || !data) return -1;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return -1;
    int n = write(fd, bytes, (*env)->GetArrayLength(env, data));
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT); return n;
}
JNIEXPORT jint JNICALL JNI(resize)(JNIEnv *env, jclass cls, jint fd, jint rows, jint cols)
{
    (void)env; (void)cls;
    if (fd < 0 || rows < 1 || cols < 1 || rows > 65535 || cols > 65535) return -1;
    struct winsize size = {.ws_row = rows, .ws_col = cols}; return ioctl(fd, TIOCSWINSZ, &size);
}
JNIEXPORT jint JNICALL JNI(waitPid)(JNIEnv *env, jclass cls, jint pid)
{
    (void)env; (void)cls;
    if (pid <= 0) return -1;
    int status; pid_t result;
    do { result = waitpid(pid, &status, WNOHANG); } while (result < 0 && errno == EINTR);
    if (result == 0) return -2;
    if (result < 0) return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
JNIEXPORT void JNICALL JNI(close)(JNIEnv *env, jclass cls, jint fd) { (void)env; (void)cls; close(fd); }

#ifdef PDN_PROBE_NATIVE_COVERAGE
extern void __llvm_profile_set_filename(const char *name);
extern int __llvm_profile_write_file(void);
#endif

JNIEXPORT jint JNICALL JNI(dumpCoverage)(JNIEnv *env, jclass cls, jstring path)
{
    (void)cls;
#ifdef PDN_PROBE_NATIVE_COVERAGE
    if (!path) return -1;
    const char *filename = (*env)->GetStringUTFChars(env, path, NULL);
    if (!filename) return -1;
    __llvm_profile_set_filename(filename);
    int result = __llvm_profile_write_file();
    (*env)->ReleaseStringUTFChars(env, path, filename);
    return result;
#else
    (void)env; (void)path;
    return -1;
#endif
}
