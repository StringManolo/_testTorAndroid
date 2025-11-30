#include <jni.h>
#include <string>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <vector>
#include <errno.h>

#define LOG_TAG "TorWrapper"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Variables globales
static pid_t tor_pid = -1;
static JavaVM* g_jvm = nullptr;
static jobject g_callback_obj = nullptr;
static jmethodID g_callback_method = nullptr;

// Función helper para enviar logs a Java
void logToJava(const char* message) {
    if (g_jvm == nullptr || g_callback_obj == nullptr || g_callback_method == nullptr) {
        return;
    }
    
    JNIEnv* env;
    bool detach = false;
    
    int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        detach = true;
    }
    
    if (env != nullptr) {
        jstring jmsg = env->NewStringUTF(message);
        env->CallVoidMethod(g_callback_obj, g_callback_method, jmsg);
        env->DeleteLocalRef(jmsg);
        
        if (detach) {
            g_jvm->DetachCurrentThread();
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_helloworld_TorProcessManager_isProcessAlive(
        JNIEnv* env,
        jobject /* this */) {
    
    if (tor_pid <= 0) {
        return JNI_FALSE;
    }
    
    // Enviar señal 0 para verificar si el proceso existe
    int result = kill(tor_pid, 0);
    
    if (result == 0) {
        return JNI_TRUE; // Proceso existe
    } else {
        char log_msg[256];
        snprintf(log_msg, sizeof(log_msg), "[C++] ⚠️ Proceso no responde: %s", strerror(errno));
        logToJava(log_msg);
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_helloworld_TorProcessManager_setLogCallback(
        JNIEnv* env,
        jobject thiz,
        jobject callback) {
    
    // Guardar la JavaVM para poder hacer llamadas desde otros threads
    env->GetJavaVM(&g_jvm);
    
    // Guardar el objeto callback como referencia global
    if (g_callback_obj != nullptr) {
        env->DeleteGlobalRef(g_callback_obj);
    }
    g_callback_obj = env->NewGlobalRef(callback);
    
    // Obtener el método onLog
    jclass callbackClass = env->GetObjectClass(callback);
    g_callback_method = env->GetMethodID(callbackClass, "onLog", "(Ljava/lang/String;)V");
    
    LOGD("Callback de logs configurado");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_helloworld_TorProcessManager_startTorNative(
        JNIEnv* env,
        jobject /* this */,
        jstring torPath,
        jobjectArray args) {

    const char* tor_path = env->GetStringUTFChars(torPath, nullptr);
    
    logToJava("[C++] 🔧 Iniciando Tor desde JNI...");
    
    char log_msg[512];
    snprintf(log_msg, sizeof(log_msg), "[C++] 📍 Ruta del binario: %s", tor_path);
    logToJava(log_msg);
    
    LOGD("Iniciando Tor con execve desde JNI...");
    LOGD("Ruta del binario: %s", tor_path);

    // Convertir argumentos Java a C
    int argc = env->GetArrayLength(args);
    std::vector<char*> argv(argc + 2); // +2 para el nombre del programa y NULL
    
    argv[0] = strdup(tor_path); // Primer argumento es el nombre del programa
    
    logToJava("[C++] ⚙️ Argumentos de Tor:");
    snprintf(log_msg, sizeof(log_msg), "[C++]   argv[0] = %s", tor_path);
    logToJava(log_msg);
    
    for (int i = 0; i < argc; i++) {
        jstring jarg = (jstring) env->GetObjectArrayElement(args, i);
        const char* arg = env->GetStringUTFChars(jarg, nullptr);
        argv[i + 1] = strdup(arg);
        
        snprintf(log_msg, sizeof(log_msg), "[C++]   argv[%d] = %s", i + 1, arg);
        logToJava(log_msg);
        
        LOGD("Argumento %d: %s", i, arg);
        env->ReleaseStringUTFChars(jarg, arg);
    }
    argv[argc + 1] = nullptr; // Último elemento debe ser NULL

    // Crear pipe para capturar la salida
    int pipefd[2];
    if (pipe(pipefd) == -1) {
        snprintf(log_msg, sizeof(log_msg), "[C++] ❌ Error creando pipe: %s", strerror(errno));
        logToJava(log_msg);
        LOGE("Error creando pipe: %s", strerror(errno));
        env->ReleaseStringUTFChars(torPath, tor_path);
        return -1;
    }
    
    // Hacer el extremo de lectura no bloqueante desde el principio
    int flags = fcntl(pipefd[0], F_GETFL, 0);
    fcntl(pipefd[0], F_SETFL, flags | O_NONBLOCK);
    
    logToJava("[C++] ✅ Pipe creado correctamente");

    // Fork para ejecutar Tor
    logToJava("[C++] 🔀 Llamando a fork()...");
    pid_t pid = fork();
    
    if (pid == -1) {
        snprintf(log_msg, sizeof(log_msg), "[C++] ❌ Error en fork: %s", strerror(errno));
        logToJava(log_msg);
        LOGE("Error en fork: %s", strerror(errno));
        env->ReleaseStringUTFChars(torPath, tor_path);
        return -1;
    }
    
    if (pid == 0) {
        // Proceso hijo
        
        logToJava("[C++] 👶 Proceso hijo iniciado");
        
        // Redirigir stdout y stderr al pipe
        close(pipefd[0]); // Cerrar extremo de lectura
        
        if (dup2(pipefd[1], STDOUT_FILENO) == -1) {
            snprintf(log_msg, sizeof(log_msg), "[C++] ❌ Error en dup2 STDOUT: %s", strerror(errno));
            logToJava(log_msg);
            LOGE("Error en dup2 STDOUT: %s", strerror(errno));
            _exit(1);
        }
        
        if (dup2(pipefd[1], STDERR_FILENO) == -1) {
            snprintf(log_msg, sizeof(log_msg), "[C++] ❌ Error en dup2 STDERR: %s", strerror(errno));
            logToJava(log_msg);
            LOGE("Error en dup2 STDERR: %s", strerror(errno));
            _exit(1);
        }
        
        close(pipefd[1]);
        
        logToJava("[C++] ✅ Redireccionamiento de salida completado");
        logToJava("[C++] ⚡ Llamando a execve...");
        
        LOGD("Redireccionamiento de salida completado");
        LOGD("Llamando a execve...");
        execve(tor_path, argv.data(), nullptr);
        
        // Si llegamos aquí, execve falló
        snprintf(log_msg, sizeof(log_msg), "[C++] ❌ execve falló: %s", strerror(errno));
        logToJava(log_msg);
        LOGE("execve falló: %s", strerror(errno));
        _exit(1);
    }
    
    // Proceso padre
    close(pipefd[1]); // Cerrar extremo de escritura
    tor_pid = pid;
    
    snprintf(log_msg, sizeof(log_msg), "[C++] ✅ Tor iniciado con PID: %d", pid);
    logToJava(log_msg);
    LOGD("Tor iniciado con PID: %d", pid);
    
    // Hacer el extremo de lectura no bloqueante
    flags = fcntl(pipefd[0], F_GETFL, 0);
    fcntl(pipefd[0], F_SETFL, flags | O_NONBLOCK);
    
    // Leer salida inmediatamente durante 2 segundos para capturar errores iniciales
    logToJava("[C++] 📖 Leyendo salida inicial de Tor...");
    char early_output[4096];
    int total_read = 0;
    
    for (int i = 0; i < 20; i++) { // 20 iteraciones x 100ms = 2 segundos
        usleep(100000); // 100ms
        
        ssize_t bytes = read(pipefd[0], early_output + total_read, sizeof(early_output) - total_read - 1);
        if (bytes > 0) {
            total_read += bytes;
        }
        
        // Verificar si el proceso terminó
        int status;
        pid_t result = waitpid(pid, &status, WNOHANG);
        
        if (result == pid) {
            // Proceso terminó
            if (WIFEXITED(status)) {
                snprintf(log_msg, sizeof(log_msg), "[C++] ❌ Proceso terminó con código: %d", WEXITSTATUS(status));
                logToJava(log_msg);
            } else if (WIFSIGNALED(status)) {
                snprintf(log_msg, sizeof(log_msg), "[C++] ❌ Proceso terminado por señal: %d", WTERMSIG(status));
                logToJava(log_msg);
            }
            
            // Intentar leer cualquier salida restante
            while ((bytes = read(pipefd[0], early_output + total_read, sizeof(early_output) - total_read - 1)) > 0) {
                total_read += bytes;
            }
            
            break;
        }
    }
    
    // Mostrar la salida capturada
    if (total_read > 0) {
        early_output[total_read] = '\0';
        logToJava("[C++] 📄 Salida de Tor:");
        
        // Dividir en líneas y enviar
        char* line = strtok(early_output, "\n");
        while (line != NULL) {
            snprintf(log_msg, sizeof(log_msg), "[Tor] %s", line);
            logToJava(log_msg);
            line = strtok(NULL, "\n");
        }
    } else {
        logToJava("[C++] ⚠️ No se capturó ninguna salida de Tor");
    }
    
    // Verificar estado final
    int status;
    pid_t result = waitpid(pid, &status, WNOHANG);
    
    if (result == 0) {
        logToJava("[C++] ✅ Proceso sigue activo");
    } else if (result == pid) {
        logToJava("[C++] ❌ Proceso ya terminó, no continuará");
        tor_pid = -1;
    }
    
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
    
    // Hacer el read no bloqueante
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    
    char buffer[8192];
    ssize_t bytes_read = read(fd, buffer, sizeof(buffer) - 1);
    
    if (bytes_read > 0) {
        buffer[bytes_read] = '\0';
        return env->NewStringUTF(buffer);
    } else if (bytes_read == -1 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        // No hay datos disponibles, no es un error
        return env->NewStringUTF("");
    }
    
    return env->NewStringUTF("");
}
