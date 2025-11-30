package com.example.helloworld

import android.content.Context
import android.os.Build
import java.io.File

class TorProcessManager(private val context: Context) {

    private var torProcess: Process? = null

    val torSocksPort = 9050
    val torControlPort = 9051

    private fun getTorExecutableFile(onLog: (String) -> Unit): File {
        // Detectar la arquitectura correcta
        val abi = Build.SUPPORTED_ABIS[0]
        onLog("📱 ABI del dispositivo: $abi")
        
        val binaryName = when (abi) {
            "arm64-v8a" -> "tor-arm64-v8a"
            "armeabi-v7a" -> "tor-armeabi-v7a"
            else -> {
                onLog("⚠️ ABI no soportada: $abi, intentando con arm64-v8a")
                "tor-arm64-v8a"
            }
        }
        
        onLog("📦 Nombre del binario en assets: $binaryName")
        
        // Archivo destino en el directorio de archivos de la app
        val torExecutable = File(context.filesDir, "tor")
        
        onLog("🎯 Ruta de destino: ${torExecutable.absolutePath}")
        
        // Verificar si ya existe y es válido
        if (torExecutable.exists()) {
            onLog("📄 Binario ya existe, tamaño: ${torExecutable.length()} bytes")
            if (torExecutable.length() > 0) {
                onLog("✅ Usando binario existente")
                return torExecutable
            } else {
                onLog("⚠️ Binario existente está vacío, reextrayendo...")
                torExecutable.delete()
            }
        }
        
        // Extraer desde assets
        try {
            onLog("📂 Listando archivos en assets/:")
            val assetsList = context.assets.list("") ?: emptyArray()
            assetsList.forEach { asset ->
                onLog("  📄 $asset")
            }
            
            onLog("🔄 Copiando $binaryName desde assets...")
            
            context.assets.open(binaryName).use { input ->
                torExecutable.outputStream().use { output ->
                    val bytesWritten = input.copyTo(output)
                    onLog("✅ Copiados $bytesWritten bytes")
                }
            }
            
            onLog("📊 Tamaño del archivo copiado: ${torExecutable.length()} bytes")
            
            // Establecer permisos de ejecución
            val success = torExecutable.setExecutable(true, false)
            onLog("🔐 Permisos de ejecución establecidos: $success")
            onLog("✅ ¿Es ejecutable ahora?: ${torExecutable.canExecute()}")
            
        } catch (e: Exception) {
            onLog("❌ Error al extraer binario desde assets: ${e.message}")
            onLog("📋 Stack trace: ${e.stackTraceToString()}")
        }
        
        return torExecutable
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

        onLog("🚀 Iniciando proceso de configuración de Tor...")
        
        val torExecutable = getTorExecutableFile(onLog)
        val torDataDir = getTorDataDir(onLog)

        // Verificación de existencia
        if (!torExecutable.exists()) {
            onLog("❌ Error crítico: Binario Tor no encontrado en: ${torExecutable.absolutePath}")
            onLog("💡 Verifica que el archivo esté en app/src/main/assets/")
            return
        }

        // Verificar tamaño del archivo
        if (torExecutable.length() == 0L) {
            onLog("❌ Error crítico: El binario Tor está vacío (0 bytes)")
            return
        }

        // Verificación de permisos de ejecución
        if (!torExecutable.canExecute()) {
            onLog("⚠️ ADVERTENCIA: El binario no tiene permisos de ejecución")
            
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

        onLog("==================================================")
        onLog("📍 Ejecutable: ${torExecutable.absolutePath}")
        onLog("📂 Directorio de datos: ${torDataDir.absolutePath}")
        onLog("🔌 Puerto SOCKS: $torSocksPort")
        onLog("🎛️ Puerto de control: $torControlPort")
        onLog("⚙️ Comando: ${command.joinToString(" ")}")
        onLog("==================================================")

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
