#include <jni.h>
#include <string.h>
#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/syscall.h>
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <linux/unistd.h>
#include <android/log.h>

static JavaVM* gJvm = nullptr;
static jobject gLogCallback = nullptr;
static jmethodID gOnLogMethod = nullptr;

static pid_t tor_pid = -1;
static int tor_pipe_fd = -1;

#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif

/* ===================== LOGGING ====================== */

static void safeLogToJava(const char* msg) {
    if (!gJvm || !gLogCallback || !gOnLogMethod) return;
    JNIEnv* env = nullptr;
    gJvm->AttachCurrentThread(&env, nullptr);
    jstring jmsg = env->NewStringUTF(msg);
    env->CallVoidMethod(gLogCallback, gOnLogMethod, jmsg);
    env->DeleteLocalRef(jmsg);
}

/* ===================== JNI ====================== */

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

/* ===================== CALLBACK ====================== */

extern "C" JNIEXPORT void JNICALL
Java_com_example_helloworld_TorProcessManager_setLogCallback(
        JNIEnv* env, jobject, jobject callback) {

    if (gLogCallback) env->DeleteGlobalRef(gLogCallback);

    gLogCallback = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    gOnLogMethod = env->GetMethodID(cls, "onLog", "(Ljava/lang/String;)V");

    safeLogToJava("[C++] Log callback registered");
}

/* ===================== MEMFD EXEC ====================== */

static int do_memfd_exec(const char* path, char* const argv[], char* const envp[]) {
    int fd = syscall(__NR_memfd_create, "tor_memfd", 0);
    if (fd < 0) return -1;

    int in = open(path, O_RDONLY);
    if (in < 0) {
        close(fd);
        return -1;
    }

    char buf[8192];
    ssize_t r;
    while ((r = read(in, buf, sizeof(buf))) > 0) {
        if (write(fd, buf, r) != r) {
            close(in);
            close(fd);
            return -1;
        }
    }

    close(in);
    lseek(fd, 0, SEEK_SET);

    syscall(__NR_execveat, fd, "", argv, envp, AT_EMPTY_PATH);
    close(fd);
    return -1;
}

/* ===================== START ====================== */

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helloworld_TorProcessManager_startTorNative(
        JNIEnv* env, jobject, jstring torPath, jobjectArray args) {

    const char* tor_path = env->GetStringUTFChars(torPath, nullptr);
    int argc = env->GetArrayLength(args);

    char** argv = (char**)calloc(argc + 2, sizeof(char*));
    argv[0] = strdup(tor_path);

    for (int i = 0; i < argc; ++i) {
        jstring jarg = (jstring)env->GetObjectArrayElement(args, i);
        const char* a = env->GetStringUTFChars(jarg, nullptr);
        argv[i + 1] = strdup(a);
        env->ReleaseStringUTFChars(jarg, a);
    }
    argv[argc + 1] = nullptr;

    int pipefd[2];
    if (pipe(pipefd) != 0) return -1;

    pid_t pid = fork();
    if (pid < 0) return -1;

    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);

        extern char** environ;
        execve(tor_path, argv, environ);

        int e = errno;

        if (e == EACCES || e == EPERM) {
            do_memfd_exec(tor_path, argv, environ);
            e = errno;
        }

        dprintf(STDERR_FILENO, "[C++] exec failed errno=%d (%s)\n", e, strerror(e));
        _exit(127);
    }

    close(pipefd[1]);
    tor_pid = pid;
    tor_pipe_fd = pipefd[0];

    safeLogToJava("[C++] Tor forked OK");

    int flags = fcntl(tor_pipe_fd, F_GETFL, 0);
    fcntl(tor_pipe_fd, F_SETFL, flags | O_NONBLOCK);

    return tor_pipe_fd;
}

/* ===================== READ ====================== */

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_helloworld_TorProcessManager_readOutputNative(
        JNIEnv* env, jobject, jint fd) {

    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    if (n <= 0) return env->NewStringUTF("");

    buf[n] = 0;
    return env->NewStringUTF(buf);
}

/* ===================== ALIVE ====================== */

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_helloworld_TorProcessManager_isProcessAlive(
        JNIEnv*, jobject) {

    if (tor_pid <= 0) return JNI_FALSE;
    int status;
    pid_t r = waitpid(tor_pid, &status, WNOHANG);
    return (r == 0) ? JNI_TRUE : JNI_FALSE;
}

/* ===================== STOP ====================== */

extern "C" JNIEXPORT void JNICALL
Java_com_example_helloworld_TorProcessManager_stopTorNative(
        JNIEnv*, jobject) {

    if (tor_pid > 0) {
        kill(tor_pid, SIGTERM);
        tor_pid = -1;
    }

    if (tor_pipe_fd >= 0) {
        close(tor_pipe_fd);
        tor_pipe_fd = -1;
    }

    safeLogToJava("[C++] Tor stopped");
}

