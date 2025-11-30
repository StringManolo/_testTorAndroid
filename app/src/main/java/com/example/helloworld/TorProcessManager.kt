package com.example.helloworld

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileDescriptor

class TorProcessManager(private val context: Context) {

    private var torOutputFd: Int = -1
    private var readerThread: Thread? = null
    private var isRunning = false

    val torSocksPort = 9050
    val torControlPort = 9051

    companion object {
        init {
            System.loadLibrary("torwrapper")
        }
    }

    // Métodos nativos
    private external fun setLogCallback(callback: LogCallback)
    private external fun startTorNative(torPath: String, args: Array<String>): Int
    private external fun stopTorNative()
    private external fun readOutputNative(fd: Int): String
    
    // Interface para el callback
    interface LogCallback {
        fun onLog(message: String)
    }

    private fun getTorExecutableFile(onLog: (String) -> Unit): File {
        onLog("📱 Información del dispositivo:")
        onLog("  Modelo: ${Build.MODEL}")
        onLog("  Fabricante: ${Build.MANUFACTURER}")
        onLog("  Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        onLog("  ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        onLog("")
        
        val abi = Build.SUPPORTED_ABIS[0]
        val binaryName = when (abi) {
            "arm64-v8a" -> "tor-arm64-v8a"
            "armeabi-v7a" -> "tor-armeabi-v7a"
            else -> "tor-arm64-v8a"
        }
        
        onLog("📦 Binario a usar: $binaryName")
        
        // Extraer a filesDir (aunque no se ejecutará desde Java)
        val torExecutable = File(context.filesDir, "tor")
        
        if (!torExecutable.exists() || torExecutable.length() == 0L) {
            onLog("📥 Extrayendo binario desde assets...")
            
            try {
                context.assets.open(binaryName).use { input ->
                    torExecutable.outputStream().use { output ->
                        val bytesWritten = input.copyTo(output)
                        onLog("✅ Copiados $bytesWritten bytes")
                    }
                }
                
                // Establecer permisos
                torExecutable.setExecutable(true, false)
                torExecutable.setReadable(true, false)
                
                onLog("✅ Binario extraído: ${torExecutable.absolutePath}")
                onLog("📊 Tamaño: ${torExecutable.length()} bytes")
                
            } catch (e: Exception) {
                onLog("❌ Error extrayendo binario: ${e.message}")
            }
        } else {
            onLog("✅ Binario ya existe: ${torExecutable.absolutePath}")
            onLog("📊 Tamaño: ${torExecutable.length()} bytes")
        }
        
        return torExecutable
    }

    private fun getTorDataDir(onLog: (String) -> Unit): File {
        val dataDir = File(context.filesDir, "tor_data")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
            onLog("📁 Directorio de datos Tor creado: ${dataDir.absolutePath}")
        }
        return dataDir
    }

    fun startTor(onLog: (String) -> Unit, onReady: () -> Unit) {
        onLog("🚀 INICIANDO TOR CON JNI")
        onLog("==================================================")
        onLog("⚡ Usando execve desde código nativo (bypass SELinux)")
        onLog("")
        
        // Configurar callback para logs desde C++
        val logCallback = object : LogCallback {
            override fun onLog(message: String) {
                onLog(message)
            }
        }
        setLogCallback(logCallback)
        
        val torExecutable = getTorExecutableFile(onLog)
        val torDataDir = getTorDataDir(onLog)

        if (!torExecutable.exists()) {
            onLog("❌ Error: Binario no encontrado")
            return
        }

        // Argumentos para Tor (sin incluir el nombre del programa)
        val args = arrayOf(
            "DataDirectory", torDataDir.absolutePath,
            "SocksPort", "$torSocksPort",
            "ControlPort", "$torControlPort",
            "__DisablePredictedCircuits", "1"
        )

        onLog("📍 Ejecutable: ${torExecutable.absolutePath}")
        onLog("📂 Directorio de datos: ${torDataDir.absolutePath}")
        onLog("🔌 Puerto SOCKS: $torSocksPort")
        onLog("🎛️ Puerto de control: $torControlPort")
        onLog("")

        try {
            onLog("🔧 Llamando a código nativo JNI...")
            
            // Llamar al método nativo
            torOutputFd = startTorNative(torExecutable.absolutePath, args)
            
            if (torOutputFd < 0) {
                onLog("❌ Error: El método nativo retornó código de error: $torOutputFd")
                return
            }
            
            onLog("✅ Tor iniciado exitosamente desde JNI")
            onLog("📄 File descriptor para salida: $torOutputFd")
            onLog("")
            
            isRunning = true
            
            // Thread para leer la salida de Tor
            readerThread = Thread {
                var isReady = false
                var consecutiveEmpty = 0
                
                onLog("📖 Thread de lectura iniciado")
                
                try {
                    while (isRunning) {
                        val output = readOutputNative(torOutputFd)
                        
                        if (output.isNotEmpty()) {
                            consecutiveEmpty = 0
                            val lines = output.split("\n")
                            for (line in lines) {
                                if (line.isNotBlank()) {
                                    onLog(line)
                                    
                                    // Detectar cuando Tor está listo
                                    if (line.contains("Bootstrapped 100%") && !isReady) {
                                        isReady = true
                                        onLog("🎉 Tor completamente iniciado!")
                                        onReady()
                                    }
                                }
                            }
                        } else {
                            consecutiveEmpty++
                            
                            // Si no hay salida por 30 segundos, avisar
                            if (consecutiveEmpty == 300) {
                                onLog("⚠️ No se ha recibido salida de Tor en 30 segundos")
                                onLog("💡 El proceso puede estar bloqueado o sin salida")
                            }
                        }
                        
                        Thread.sleep(100) // Leer cada 100ms
                    }
                } catch (e: Exception) {
                    onLog("❌ Error leyendo salida de Tor: ${e.message}")
                    onLog("📋 ${e.stackTraceToString()}")
                } finally {
                    onLog("⏹️ Thread de lectura terminado")
                }
            }
            
            readerThread?.start()

        } catch (e: Exception) {
            onLog("❌ Excepción al iniciar Tor: ${e.message}")
            onLog("📋 ${e.stackTraceToString()}")
        }
    }

    fun stopTor() {
        isRunning = false
        
        try {
            stopTorNative()
        } catch (e: Exception) {
            // Ignorar errores al detener
        }
        
        readerThread?.interrupt()
        readerThread = null
    }
}
