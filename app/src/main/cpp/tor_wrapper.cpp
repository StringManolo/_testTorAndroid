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

static JavaVM* gJvm = nullptr;
static jobject gLogCallback = nullptr;
static jmethodID gOnLogMethod = nullptr;

static pid_t tor_pid = -1;
static int tor_pipe_fd = -1;

/* ===================== UTIL ====================== */

static void safeLogToJava(const char* msg) {
    if (!gJvm || !gLogCallback || !gOnLogMethod) return;
    JNIEnv* env = nullptr;
    gJvm->AttachCurrentThread(&env, nullptr);
    jstring jmsg = env->NewStringUTF(msg);
    env->CallVoidMethod(gLogCallback, gOnLogMethod, jmsg);
    env->DeleteLocalRef(jmsg);
}

static void free_argv(char** argv) {
    if (!argv) return;
    for (int i = 0; argv[i]; ++i) free(argv[i]);
    free(argv);
}

/* ===================== JNI INIT ====================== */

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

/* ===================== CALLBACK ====================== */

extern "C" JNIEXPORT void JNICALL
Java_com_example_helloworld_TorProcessManager_setLogCallback(
        JNIEnv* env, jobject, jobject callback) {

    if (gLogCallback) {
        env->DeleteGlobalRef(gLogCallback);
        gLogCallback = nullptr;
    }

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
            close(in); close(fd);
            return -1;
        }
    }

    close(in);
    lseek(fd, 0, SEEK_SET);

    fexecve(fd, argv, envp);
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

    char logbuf[512];
    snprintf(logbuf, sizeof(logbuf), "[C++] argv[0]=%s", argv[0]);
    safeLogToJava(logbuf);
    for (int i = 1; i <= argc; ++i) {
        snprintf(logbuf, sizeof(logbuf), "[C++] argv[%d]=%s", i, argv[i]);
        safeLogToJava(logbuf);
    }

    int pipefd[2];
    if (pipe(pipefd) != 0) {
        snprintf(logbuf, sizeof(logbuf), "[C++] pipe error: %s", strerror(errno));
        safeLogToJava(logbuf);
        free_argv(argv);
        env->ReleaseStringUTFChars(torPath, tor_path);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        snprintf(logbuf, sizeof(logbuf), "[C++] fork failed: %s", strerror(errno));
        safeLogToJava(logbuf);
        close(pipefd[0]);
        close(pipefd[1]);
        free_argv(argv);
        env->ReleaseStringUTFChars(torPath, tor_path);
        return -1;
    }

    if (pid == 0) {
        // CHILD
        close(pipefd[0]);

        if (dup2(pipefd[1], STDOUT_FILENO) < 0) _exit(127);
        if (dup2(pipefd[1], STDERR_FILENO) < 0) _exit(127);
        close(pipefd[1]);

        extern char** environ;
        execve(tor_path, argv, environ);

        int e = errno;

        if (e == EACCES || e == ENOEXEC || e == EPERM || e == ETXTBSY) {
            do_memfd_exec(tor_path, argv, environ);
            e = errno;
        }

        dprintf(STDERR_FILENO,
                "[C++-CHILD] execve failed errno=%d (%s)\n",
                e, strerror(e));

        _exit(127);
    }

    // PARENT
    close(pipefd[1]);
    tor_pid = pid;
    tor_pipe_fd = pipefd[0];

    snprintf(logbuf, sizeof(logbuf), "[C++] Tor started pid=%d", pid);
    safeLogToJava(logbuf);

    // Early failure detection (3s)
    fd_set rfds;
    struct timeval tv;
    char buf[4096];
    int elapsed_ms = 0;

    while (elapsed_ms < 3000) {
        FD_ZERO(&rfds);
        FD_SET(tor_pipe_fd, &rfds);
        tv.tv_sec = 0;
        tv.tv_usec = 100 * 1000;

        int sel = select(tor_pipe_fd + 1, &rfds, nullptr, nullptr, &tv);
        if (sel > 0 && FD_ISSET(tor_pipe_fd, &rfds)) {
            ssize_t r = read(tor_pipe_fd, buf, sizeof(buf) - 1);
            if (r > 0) {
                buf[r] = 0;
                safeLogToJava("[Tor]");
                safeLogToJava(buf);
            }
        }

        int status;
        pid_t w = waitpid(pid, &status, WNOHANG);
        if (w == pid) {
            if (WIFEXITED(status)) {
                snprintf(logbuf, sizeof(logbuf),
                         "[C++] child exited code=%d",
                         WEXITSTATUS(status));
            } else if (WIFSIGNALED(status)) {
                snprintf(logbuf, sizeof(logbuf),
                         "[C++] child killed by signal=%d",
                         WTERMSIG(status));
            } else {
                snprintf(logbuf, sizeof(logbuf),
                         "[C++] child ended");
            }

            safeLogToJava(logbuf);
            close(tor_pipe_fd);
            tor_pid = -1;
            tor_pipe_fd = -1;

            free_argv(argv);
            env->ReleaseStringUTFChars(torPath, tor_path);
            return -1;
        }

        elapsed_ms += 100;
    }

    int flags = fcntl(tor_pipe_fd, F_GETFL, 0);
    fcntl(tor_pipe_fd, F_SETFL, flags | O_NONBLOCK);

    free_argv(argv);
    env->ReleaseStringUTFChars(torPath, tor_path);

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

