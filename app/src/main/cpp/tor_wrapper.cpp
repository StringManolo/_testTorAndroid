#include <jni.h>
#include <string>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <pthread.h>
#include <errno.h>

static JavaVM* gJvm = nullptr;
static jobject gLogCallback = nullptr;
static jmethodID gOnLogMethod = nullptr;
static int gPipeFd = -1;
static pid_t gChildPid = -1;

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

static void logToJava(const std::string& msg) {
    if (!gJvm || !gLogCallback || !gOnLogMethod) return;

    JNIEnv* env = nullptr;
    gJvm->AttachCurrentThread(&env, nullptr);

    jstring jmsg = env->NewStringUTF(msg.c_str());
    env->CallVoidMethod(gLogCallback, gOnLogMethod, jmsg);
    env->DeleteLocalRef(jmsg);
}

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

    logToJava("[C++] Log callback registered");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helloworld_TorProcessManager_startTorNative(
        JNIEnv* env,
        jobject,
        jstring torPath,
        jobjectArray args) {

    const char* path = env->GetStringUTFChars(torPath, nullptr);

    int pipefd[2];
    if (pipe(pipefd) != 0) {
        logToJava("[C++] pipe() failed");
        return -1;
    }

    pid_t pid = fork();
    if (pid == 0) {
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[0]);

        std::vector<char*> argv;
        argv.push_back(const_cast<char*>(path));

        jsize argc = env->GetArrayLength(args);
        for (jsize i = 0; i < argc; i++) {
            jstring arg = (jstring) env->GetObjectArrayElement(args, i);
            const char* cstr = env->GetStringUTFChars(arg, nullptr);
            argv.push_back(const_cast<char*>(cstr));
        }
        argv.push_back(nullptr);

        extern char** environ;
        execve(path, argv.data(), environ);

        _exit(127);
    }

    close(pipefd[1]);
    gPipeFd = pipefd[0];
    gChildPid = pid;

    logToJava("[C++] Tor forked, pid=" + std::to_string(pid));
    env->ReleaseStringUTFChars(torPath, path);

    return gPipeFd;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_helloworld_TorProcessManager_readOutputNative(
        JNIEnv* env, jobject, jint fd) {

    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);

    if (n <= 0) return env->NewStringUTF("");

    buf[n] = 0;
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_helloworld_TorProcessManager_isProcessAlive(
        JNIEnv*, jobject) {

    if (gChildPid <= 0) return JNI_FALSE;

    int status;
    pid_t r = waitpid(gChildPid, &status, WNOHANG);
    return (r == 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_helloworld_TorProcessManager_stopTorNative(
        JNIEnv*, jobject) {

    if (gChildPid > 0) kill(gChildPid, SIGTERM);
    gChildPid = -1;

    if (gPipeFd >= 0) close(gPipeFd);
    gPipeFd = -1;

    logToJava("[C++] Tor stopped");
}

