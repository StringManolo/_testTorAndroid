package com.example.helloworld

import android.content.Context
import android.os.Build
import java.io.File

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

    private external fun setLogCallback(callback: LogCallback)
    private external fun startTorNative(torPath: String, args: Array<String>): Int
    private external fun stopTorNative()
    private external fun readOutputNative(fd: Int): String
    private external fun isProcessAlive(): Boolean

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
        val binaryName = when {
            abi.startsWith("arm64") -> "tor-arm64-v8a"
            abi.startsWith("armeabi") -> "tor-armeabi-v7a"
            else -> "tor-arm64-v8a"
        }

        onLog("📦 Binario a usar: $binaryName")

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
        onLog("⚡ Usando execve/fexecve desde código nativo")
        onLog("")

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

        val args = arrayOf(
            "--DataDirectory", torDataDir.absolutePath,
            "--SocksPort", "$torSocksPort",
            "--ControlPort", "$torControlPort",
            "--__DisablePredictedCircuits", "1"
        )

        onLog("📍 Ejecutable: ${torExecutable.absolutePath}")
        onLog("📂 Directorio de datos: ${torDataDir.absolutePath}")
        onLog("🔌 Puerto SOCKS: $torSocksPort")
        onLog("🎛️ Puerto de control: $torControlPort")
        onLog("")

        try {
            onLog("🔧 Llamando a código nativo JNI...")

            torOutputFd = startTorNative(torExecutable.absolutePath, args)

            if (torOutputFd < 0) {
                onLog("❌ startTorNative devolvió error")
                return
            }

            onLog("✅ Tor lanzado desde JNI")
            onLog("📄 File descriptor salida: $torOutputFd")
            isRunning = true

            readerThread = Thread {
                var isReady = false
                var emptyCount = 0

                onLog("📖 Thread de lectura iniciado")

                Thread.sleep(1000)
                val alive = isProcessAlive()
                onLog("🔍 Proceso Tor vivo: $alive")

                try {
                    while (isRunning) {
                        val output = readOutputNative(torOutputFd)

                        if (output.isNotEmpty()) {
                            emptyCount = 0

                            val lines = output.split("\n")
                            for (line in lines) {
                                if (line.isNotBlank()) {
                                    onLog(line)

                                    if (!isReady && line.contains("Bootstrapped 100%")) {
                                        isReady = true
                                        onLog("🎉 Tor completamente iniciado")
                                        onReady()
                                    }
                                }
                            }
                        } else {
                            emptyCount++

                            if (emptyCount % 50 == 0) {
                                val stillAlive = isProcessAlive()
                                onLog("🔍 Verificación proceso (${emptyCount / 10}s): $stillAlive")
                            }

                            if (emptyCount == 300) {
                                onLog("⚠️ 30s sin salida de Tor")
                            }
                        }

                        Thread.sleep(100)
                    }
                } catch (e: Exception) {
                    onLog("❌ Error leyendo salida: ${e.message}")
                } finally {
                    onLog("⏹️ Thread de lectura terminado")
                }
            }

            readerThread?.start()

        } catch (e: Exception) {
            onLog("❌ Excepción al iniciar Tor: ${e.message}")
        }
    }

    fun stopTor() {
        isRunning = false

        try {
            stopTorNative()
        } catch (_: Exception) {}

        readerThread?.interrupt()
        readerThread = null
    }
}

