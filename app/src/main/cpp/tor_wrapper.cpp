#include <jni.h>
#include <string>
#include <unistd.h>
#include <android/log.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <vector>

#define LOG_TAG "TorWrapper"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Variable global para almacenar el PID de Tor
static pid_t tor_pid = -1;

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helloworld_TorProcessManager_startTorNative(
        JNIEnv* env,
        jobject /* this */,
        jstring torPath,
        jobjectArray args) {

    const char* tor_path = env->GetStringUTFChars(torPath, nullptr);
    
    LOGD("Iniciando Tor con execve desde JNI...");
    LOGD("Ruta del binario: %s", tor_path);

    // Convertir argumentos Java a C
    int argc = env->GetArrayLength(args);
    std::vector<char*> argv(argc + 2); // +2 para el nombre del programa y NULL
    
    argv[0] = strdup(tor_path); // Primer argumento es el nombre del programa
    
    for (int i = 0; i < argc; i++) {
        jstring jarg = (jstring) env->GetObjectArrayElement(args, i);
        const char* arg = env->GetStringUTFChars(jarg, nullptr);
        argv[i + 1] = strdup(arg);
        LOGD("Argumento %d: %s", i, arg);
        env->ReleaseStringUTFChars(jarg, arg);
    }
    argv[argc + 1] = nullptr; // Último elemento debe ser NULL

    // Crear pipe para capturar la salida
    int pipefd[2];
    if (pipe(pipefd) == -1) {
        LOGE("Error creando pipe: %s", strerror(errno));
        env->ReleaseStringUTFChars(torPath, tor_path);
        return -1;
    }

    // Fork para ejecutar Tor
    pid_t pid = fork();
    
    if (pid == -1) {
        LOGE("Error en fork: %s", strerror(errno));
        env->ReleaseStringUTFChars(torPath, tor_path);
        return -1;
    }
    
    if (pid == 0) {
        // Proceso hijo
        
        // Redirigir stdout y stderr al pipe
        close(pipefd[0]); // Cerrar extremo de lectura
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);
        
        // Ejecutar Tor con execve
        LOGD("Ejecutando execve...");
        execve(tor_path, argv.data(), nullptr);
        
        // Si llegamos aquí, execve falló
        LOGE("execve falló: %s", strerror(errno));
        _exit(1);
    }
    
    // Proceso padre
    close(pipefd[1]); // Cerrar extremo de escritura
    tor_pid = pid;
    
    LOGD("Tor iniciado con PID: %d", pid);
    
    env->ReleaseStringUTFChars(torPath, tor_path);
    
    // Liberar memoria de argv
    for (auto ptr : argv) {
        if (ptr) free(ptr);
    }
    
    // Retornar el file descriptor del pipe para leer la salida
    return pipefd[0];
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_helloworld_TorProcessManager_stopTorNative(
        JNIEnv* env,
        jobject /* this */) {
    
    if (tor_pid > 0) {
        LOGD("Deteniendo Tor con PID: %d", tor_pid);
        kill(tor_pid, SIGTERM);
        
        // Esperar a que el proceso termine
        int status;
        waitpid(tor_pid, &status, 0);
        
        LOGD("Tor detenido");
        tor_pid = -1;
    } else {
        LOGD("No hay proceso Tor activo");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_helloworld_TorProcessManager_readOutputNative(
        JNIEnv* env,
        jobject /* this */,
        jint fd) {
    
    char buffer[4096];
    ssize_t bytes_read = read(fd, buffer, sizeof(buffer) - 1);
    
    if (bytes_read > 0) {
        buffer[bytes_read] = '\0';
        return env->NewStringUTF(buffer);
    }
    
    return env->NewStringUTF("");
}
