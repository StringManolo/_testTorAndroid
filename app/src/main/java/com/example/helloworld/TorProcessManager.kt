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
        
        // Intentar múltiples ubicaciones para encontrar una que funcione con SELinux
        val possibleLocations = listOf(
            Pair("codeCacheDir", File(context.codeCacheDir, "tor")),
            Pair("cacheDir", File(context.cacheDir, "tor")),
            Pair("filesDir", File(context.filesDir, "tor")),
            Pair("noBackupFilesDir", File(context.noBackupFilesDir, "tor")),
            Pair("dataDir", File(context.applicationInfo.dataDir, "tor"))
        )
        
        onLog("🔍 Probando múltiples ubicaciones para el binario...")
        
        var successfulLocation: File? = null
        
        for ((locationName, torExecutable) in possibleLocations) {
            onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            onLog("🧪 Probando ubicación: $locationName")
            onLog("📍 Ruta: ${torExecutable.absolutePath}")
            
            try {
                // Verificar si el directorio padre existe
                val parentDir = torExecutable.parentFile
                if (parentDir == null || !parentDir.exists()) {
                    onLog("❌ Directorio padre no existe o es nulo")
                    continue
                }
                
                onLog("✅ Directorio padre existe")
                
                // Si el archivo ya existe, eliminarlo para forzar reextracción
                if (torExecutable.exists()) {
                    onLog("🗑️ Archivo existente encontrado, eliminando...")
                    torExecutable.delete()
                }
                
                // Extraer desde assets
                onLog("📥 Copiando binario desde assets...")
                context.assets.open(binaryName).use { input ->
                    torExecutable.outputStream().use { output ->
                        val bytesWritten = input.copyTo(output)
                        onLog("✅ Copiados $bytesWritten bytes")
                    }
                }
                
                onLog("📊 Tamaño del archivo: ${torExecutable.length()} bytes")
                
                if (torExecutable.length() == 0L) {
                    onLog("❌ El archivo está vacío después de copiar")
                    continue
                }
                
                // Intentar múltiples métodos para establecer permisos
                onLog("🔐 Intentando establecer permisos de ejecución...")
                
                // Método 1: setExecutable()
                val setExecResult = torExecutable.setExecutable(true, false)
                onLog("  Método 1 - setExecutable(true, false): $setExecResult")
                
                // Método 2: setReadable, setWritable, setExecutable
                val setReadResult = torExecutable.setReadable(true, false)
                val setWriteResult = torExecutable.setWritable(true, false)
                val setExecResult2 = torExecutable.setExecutable(true, false)
                onLog("  Método 2 - setReadable: $setReadResult, setWritable: $setWriteResult, setExecutable: $setExecResult2")
                
                // Método 3: chmod 777
                try {
                    val chmod777 = Runtime.getRuntime().exec("chmod 777 ${torExecutable.absolutePath}")
                    val chmod777Result = chmod777.waitFor()
                    onLog("  Método 3 - chmod 777: exitCode=$chmod777Result")
                } catch (e: Exception) {
                    onLog("  Método 3 - chmod 777: falló (${e.message})")
                }
                
                // Método 4: chmod 755
                try {
                    val chmod755 = Runtime.getRuntime().exec("chmod 755 ${torExecutable.absolutePath}")
                    val chmod755Result = chmod755.waitFor()
                    onLog("  Método 4 - chmod 755: exitCode=$chmod755Result")
                } catch (e: Exception) {
                    onLog("  Método 4 - chmod 755: falló (${e.message})")
                }
                
                // Método 5: chmod 700
                try {
                    val chmod700 = Runtime.getRuntime().exec("chmod 700 ${torExecutable.absolutePath}")
                    val chmod700Result = chmod700.waitFor()
                    onLog("  Método 5 - chmod 700: exitCode=$chmod700Result")
                } catch (e: Exception) {
                    onLog("  Método 5 - chmod 700: falló (${e.message})")
                }
                
                // Verificar permisos finales
                onLog("📋 Verificación de permisos:")
                onLog("  ¿Es ejecutable?: ${torExecutable.canExecute()}")
                onLog("  ¿Es legible?: ${torExecutable.canRead()}")
                onLog("  ¿Es escribible?: ${torExecutable.canWrite()}")
                
                // Intentar ejecutar un comando de prueba
                onLog("🧪 Probando ejecución del binario...")
                try {
                    val testProcess = ProcessBuilder(torExecutable.absolutePath, "--version")
                        .redirectErrorStream(true)
                        .start()
                    
                    val output = testProcess.inputStream.bufferedReader().readText()
                    val exitCode = testProcess.waitFor()
                    
                    if (exitCode == 0) {
                        onLog("✅ ¡ÉXITO! El binario es ejecutable en esta ubicación")
                        onLog("📄 Salida del binario: ${output.take(200)}")
                        successfulLocation = torExecutable
                        break
                    } else {
                        onLog("❌ El binario se ejecutó pero falló con código: $exitCode")
                        onLog("📄 Salida: ${output.take(200)}")
                    }
                } catch (e: Exception) {
                    onLog("❌ Error al intentar ejecutar: ${e.javaClass.simpleName}: ${e.message}")
                    
                    // Si es un error de permisos específico
                    if (e.message?.contains("Permission denied") == true || 
                        e.message?.contains("EACCES") == true) {
                        onLog("🔒 Error de permisos confirmado en esta ubicación")
                    }
                }
                
            } catch (e: Exception) {
                onLog("❌ Error general en esta ubicación: ${e.message}")
                onLog("📋 ${e.stackTraceToString().take(300)}")
            }
        }
        
        onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        if (successfulLocation != null) {
            onLog("🎉 UBICACIÓN EXITOSA ENCONTRADA:")
            onLog("📍 ${successfulLocation.absolutePath}")
            return successfulLocation
        } else {
            onLog("❌ NINGUNA UBICACIÓN FUNCIONÓ")
            onLog("💡 Puede ser una restricción de SELinux del dispositivo")
            onLog("💡 Considera usar una librería como Tor-Android de Guardian Project")
            // Retornar el primero aunque no funcione para que continúe el flujo
            return possibleLocations[0].second
        }
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
            onLog("❌ FALLO FINAL: El binario no es ejecutable")
            onLog("🔒 Esto probablemente se debe a políticas de SELinux")
            return
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
