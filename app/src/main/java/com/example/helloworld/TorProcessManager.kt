package com.example.helloworld

import android.content.Context
import android.os.Build
import java.io.File

class TorProcessManager(private val context: Context) {

    private var torProcess: Process? = null

    val torSocksPort = 9050
    val torControlPort = 9051

    private fun getTorExecutableFile(onLog: (String) -> Unit): File {
        val libDir = context.applicationInfo.nativeLibraryDir
        
        // Logs detallados para debugging
        onLog("📁 Directorio de librerías nativas: $libDir")
        onLog("📱 ABI principal del dispositivo: ${Build.SUPPORTED_ABIS[0]}")
        
        // Lista TODOS los archivos en el directorio nativo
        val filesInDir = File(libDir).listFiles()
        if (filesInDir != null && filesInDir.isNotEmpty()) {
            onLog("📂 Archivos encontrados en el directorio nativo:")
            filesInDir.forEach { file ->
                onLog("  📄 ${file.name} - Ejecutable: ${file.canExecute()}, Tamaño: ${file.length()} bytes")
            }
        } else {
            onLog("⚠️ El directorio nativo está vacío o no es accesible")
        }
        
        // Buscar libtor.so
        val torBinary = File(libDir, "libtor.so")
        onLog("🎯 Buscando binario en: ${torBinary.absolutePath}")
        onLog("✅ ¿Existe el archivo?: ${torBinary.exists()}")
        
        if (torBinary.exists()) {
            onLog("📊 Tamaño del archivo: ${torBinary.length()} bytes")
            onLog("🔐 ¿Es ejecutable?: ${torBinary.canExecute()}")
            onLog("📖 ¿Es legible?: ${torBinary.canRead()}")
        }
        
        return torBinary
    }

    private fun getTorDataDir(onLog: (String) -> Unit): File {
        val dataDir = File(context.filesDir, "tor_data")
        if (!dataDir.exists()) {
            val created = dataDir.mkdirs()
            onLog("📁 Directorio de datos Tor creado: $created en ${dataDir.absolutePath}")
        }
        return dataDir
    }

    fun startTor(onLog: (String) -> Unit, onReady: () -> Unit) {

        val torExecutable = getTorExecutableFile(onLog)
        val torDataDir = getTorDataDir(onLog)

        // Verificación de existencia
        if (!torExecutable.exists()) {
            onLog("❌ Error crítico: Binario 'libtor.so' no encontrado en: ${torExecutable.absolutePath}")
            onLog("💡 Verifica que el archivo esté en jniLibs/arm64-v8a/ y jniLibs/armeabi-v7a/")
            return
        }

        // Verificación de permisos de ejecución
        if (!torExecutable.canExecute()) {
            onLog("⚠️ ADVERTENCIA: 'libtor.so' no tiene permisos de ejecución")
            
            try {
                val success = torExecutable.setExecutable(true, false)
                if (success) {
                    onLog("✅ Permisos de ejecución establecidos correctamente")
                } else {
                    onLog("❌ No se pudieron establecer permisos de ejecución con setExecutable()")
                }
                
                // Fallback: intentar con chmod
                if (!torExecutable.canExecute()) {
                    onLog("🔧 Intentando chmod como fallback...")
                    val chmodProcess = Runtime.getRuntime().exec("chmod 700 ${torExecutable.absolutePath}")
                    val chmodResult = chmodProcess.waitFor()
                    onLog("chmod resultado: $chmodResult")
                }
            } catch (e: Exception) {
                onLog("❌ Error al establecer permisos: ${e.message}")
            }
            
            // Verificación final
            if (!torExecutable.canExecute()) {
                onLog("❌ El binario no es ejecutable después de intentar establecer permisos")
                return
            }
        }

        // Construcción del comando
        val command = listOf(
            torExecutable.absolutePath,
            "DataDirectory", torDataDir.absolutePath,
            "SocksPort", "$torSocksPort",
            "ControlPort", "$torControlPort",
            "__DisablePredictedCircuits", "1"
        )

        onLog("🚀 Iniciando Tor...")
        onLog("📍 Ejecutable: ${torExecutable.absolutePath}")
        onLog("📂 Directorio de datos: ${torDataDir.absolutePath}")
        onLog("🔌 Puerto SOCKS: $torSocksPort")
        onLog("🎛️ Puerto de control: $torControlPort")
        onLog("⚙️ Comando completo: ${command.joinToString(" ")}")

        try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(true)

            torProcess = processBuilder.start()
            onLog("✅ Proceso Tor iniciado")

            // Thread para leer la salida de Tor
            Thread {
                var isReady = false
                val reader = torProcess?.inputStream?.bufferedReader()

                try {
                    reader?.forEachLine { line ->
                        onLog(line)

                        // Detectar cuando Tor está listo
                        if (line.contains("Bootstrapped 100%") && !isReady) {
                            isReady = true
                            onLog("🎉 Tor completamente iniciado (Bootstrapped 100%)")
                            onReady()
                        }
                    }
                } catch (e: Exception) {
                    onLog("❌ Error leyendo el stream de Tor: ${e.message}")
                } finally {
                    val exitCode = torProcess?.waitFor()
                    onLog("⏹️ El proceso de Tor ha terminado con código de salida: $exitCode")
                }
            }.start()

        } catch (e: Exception) {
            onLog("❌ Excepción al iniciar Tor: ${e.message}")
            onLog("📋 Stack trace: ${e.stackTraceToString()}")
        }
    }

    fun stopTor() {
        torProcess?.destroy()
        torProcess = null
    }
}
