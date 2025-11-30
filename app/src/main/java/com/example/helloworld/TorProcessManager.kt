package com.example.helloworld

import android.content.Context
import android.os.Build
import java.io.File

class TorProcessManager(private val context: Context) {

    private var torProcess: Process? = null

    val torSocksPort = 9050
    val torControlPort = 9051

    private fun checkBinaryInfo(file: File, onLog: (String) -> Unit) {
        onLog("🔍 ANÁLISIS DETALLADO DEL BINARIO:")
        onLog("  📄 Ruta: ${file.absolutePath}")
        onLog("  📊 Tamaño: ${file.length()} bytes")
        onLog("  ✅ Existe: ${file.exists()}")
        onLog("  📖 Legible: ${file.canRead()}")
        onLog("  ✏️ Escribible: ${file.canWrite()}")
        onLog("  ▶️ Ejecutable: ${file.canExecute()}")
        
        // Leer los primeros bytes para verificar que es un ELF válido
        try {
            val bytes = file.inputStream().use { input ->
                ByteArray(4).also { input.read(it) }
            }
            val magic = bytes.joinToString("") { "%02X".format(it) }
            onLog("  🔮 Magic number: $magic")
            
            if (magic == "7F454C46") {
                onLog("  ✅ Es un archivo ELF válido")
            } else {
                onLog("  ❌ NO es un archivo ELF válido (debería empezar con 7F454C46)")
            }
        } catch (e: Exception) {
            onLog("  ❌ Error leyendo magic number: ${e.message}")
        }
        
        // Intentar obtener información con 'file' command
        try {
            val fileCmd = Runtime.getRuntime().exec(arrayOf("file", file.absolutePath))
            val output = fileCmd.inputStream.bufferedReader().readText().trim()
            val exitCode = fileCmd.waitFor()
            if (exitCode == 0) {
                onLog("  📋 Tipo de archivo: $output")
            }
        } catch (e: Exception) {
            onLog("  ℹ️ Comando 'file' no disponible")
        }
        
        // Intentar obtener contexto de SELinux
        try {
            val getenforceCmd = Runtime.getRuntime().exec("getenforce")
            val selinuxMode = getenforceCmd.inputStream.bufferedReader().readText().trim()
            val exitCode = getenforceCmd.waitFor()
            if (exitCode == 0) {
                onLog("  🔒 Modo SELinux: $selinuxMode")
            }
        } catch (e: Exception) {
            onLog("  ℹ️ No se pudo obtener el estado de SELinux")
        }
        
        // Obtener contexto SELinux del archivo
        try {
            val lsCmd = Runtime.getRuntime().exec(arrayOf("ls", "-Z", file.absolutePath))
            val context = lsCmd.inputStream.bufferedReader().readText().trim()
            val exitCode = lsCmd.waitFor()
            if (exitCode == 0) {
                onLog("  🔒 Contexto SELinux: $context")
            }
        } catch (e: Exception) {
            onLog("  ℹ️ No se pudo obtener el contexto SELinux del archivo")
        }
    }

    private fun tryNativeLibraryExecution(onLog: (String) -> Unit): File? {
        onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        onLog("🧪 MÉTODO ALTERNATIVO: Usar directorio de librerías nativas")
        
        // Intentar copiar a una ubicación con nombre de librería
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir).parentFile
        if (nativeLibDir != null && nativeLibDir.exists()) {
            onLog("📁 Directorio padre de libs nativas: ${nativeLibDir.absolutePath}")
            
            // Listar contenido
            nativeLibDir.listFiles()?.forEach { file ->
                onLog("  📄 ${file.name} (${if (file.isDirectory) "dir" else "file"})")
            }
        }
        
        return null
    }

    private fun tryDataLocalTmp(onLog: (String) -> Unit): File? {
        onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        onLog("🧪 MÉTODO EXPERIMENTAL: /data/local/tmp")
        
        try {
            val tmpFile = File("/data/local/tmp/tor_test")
            onLog("📍 Intentando: ${tmpFile.absolutePath}")
            
            val abi = Build.SUPPORTED_ABIS[0]
            val binaryName = when (abi) {
                "arm64-v8a" -> "tor-arm64-v8a"
                "armeabi-v7a" -> "tor-armeabi-v7a"
                else -> "tor-arm64-v8a"
            }
            
            // Intentar copiar
            context.assets.open(binaryName).use { input ->
                tmpFile.outputStream().use { output ->
                    val bytesWritten = input.copyTo(output)
                    onLog("✅ Copiados $bytesWritten bytes a /data/local/tmp")
                }
            }
            
            Runtime.getRuntime().exec("chmod 777 ${tmpFile.absolutePath}").waitFor()
            
            checkBinaryInfo(tmpFile, onLog)
            
            // Probar ejecución
            val testProcess = ProcessBuilder(tmpFile.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            
            val output = testProcess.inputStream.bufferedReader().readText()
            val exitCode = testProcess.waitFor()
            
            if (exitCode == 0) {
                onLog("✅ ¡FUNCIONÓ en /data/local/tmp!")
                return tmpFile
            } else {
                onLog("❌ Falló con código $exitCode: ${output.take(200)}")
            }
            
        } catch (e: Exception) {
            onLog("❌ Error: ${e.javaClass.simpleName}: ${e.message}")
        }
        
        return null
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
        
        // Verificar que el binario existe en assets
        try {
            onLog("📂 Verificando assets...")
            val assetsList = context.assets.list("") ?: emptyArray()
            onLog("  Archivos en assets: ${assetsList.joinToString(", ")}")
            
            if (!assetsList.contains(binaryName)) {
                onLog("❌ ¡El binario $binaryName NO está en assets!")
                onLog("💡 Asegúrate de que el archivo está en app/src/main/assets/$binaryName")
            } else {
                onLog("✅ Binario encontrado en assets")
                
                // Verificar tamaño en assets
                val assetSize = context.assets.open(binaryName).use { it.available() }
                onLog("  Tamaño en assets: $assetSize bytes")
            }
        } catch (e: Exception) {
            onLog("❌ Error verificando assets: ${e.message}")
        }
        
        onLog("")
        
        // Ubicaciones a probar
        val possibleLocations = listOf(
            Pair("codeCacheDir", File(context.codeCacheDir, "tor")),
            Pair("cacheDir", File(context.cacheDir, "tor")),
            Pair("filesDir", File(context.filesDir, "tor")),
            Pair("noBackupFilesDir", File(context.noBackupFilesDir, "tor")),
            Pair("dataDir/cache", File(context.applicationInfo.dataDir, "cache/tor"))
        )
        
        var successfulLocation: File? = null
        
        for ((locationName, torExecutable) in possibleLocations) {
            onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            onLog("🧪 Probando: $locationName")
            
            try {
                val parentDir = torExecutable.parentFile
                if (parentDir == null || !parentDir.exists()) {
                    parentDir?.mkdirs()
                }
                
                if (torExecutable.exists()) {
                    torExecutable.delete()
                }
                
                // Copiar binario
                context.assets.open(binaryName).use { input ->
                    torExecutable.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Análisis del binario
                checkBinaryInfo(torExecutable, onLog)
                
                // Intentar múltiples métodos de chmod
                onLog("🔐 Aplicando permisos...")
                
                torExecutable.setReadable(true, false)
                torExecutable.setWritable(true, false)
                torExecutable.setExecutable(true, false)
                
                val chmodCommands = listOf("777", "755", "700", "711", "a+x")
                for (perm in chmodCommands) {
                    try {
                        val cmd = Runtime.getRuntime().exec("chmod $perm ${torExecutable.absolutePath}")
                        cmd.waitFor()
                    } catch (e: Exception) {
                        // Ignorar errores
                    }
                }
                
                onLog("  Final - Ejecutable: ${torExecutable.canExecute()}")
                
                // Probar ejecución
                onLog("🧪 Probando ejecución...")
                try {
                    val testProcess = ProcessBuilder(torExecutable.absolutePath, "--version")
                        .redirectErrorStream(true)
                        .start()
                    
                    val output = testProcess.inputStream.bufferedReader().readText()
                    val exitCode = testProcess.waitFor()
                    
                    onLog("  Código de salida: $exitCode")
                    onLog("  Salida: ${output.take(300)}")
                    
                    if (exitCode == 0 || output.contains("Tor")) {
                        onLog("✅ ¡ÉXITO EN ESTA UBICACIÓN!")
                        successfulLocation = torExecutable
                        break
                    }
                } catch (e: Exception) {
                    onLog("  ❌ ${e.javaClass.simpleName}: ${e.message}")
                    
                    // Logging detallado del stack trace
                    if (e.message?.contains("Permission denied") == true) {
                        onLog("  🔒 ERROR DE PERMISOS CONFIRMADO")
                    } else if (e.message?.contains("No such file") == true) {
                        onLog("  📁 ARCHIVO NO ENCONTRADO")
                    } else if (e.message?.contains("Exec format error") == true) {
                        onLog("  ⚠️ FORMATO EJECUTABLE INVÁLIDO")
                        onLog("  💡 El binario podría no ser compatible con esta arquitectura")
                    }
                }
                
            } catch (e: Exception) {
                onLog("❌ Error: ${e.message}")
            }
        }
        
        // Intentar métodos alternativos
        if (successfulLocation == null) {
            onLog("")
            successfulLocation = tryDataLocalTmp(onLog)
        }
        
        if (successfulLocation == null) {
            successfulLocation = tryNativeLibraryExecution(onLog)
        }
        
        onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        
        if (successfulLocation != null) {
            onLog("🎉 UBICACIÓN EXITOSA: ${successfulLocation.absolutePath}")
            return successfulLocation
        } else {
            onLog("❌ TODAS LAS UBICACIONES FALLARON")
            onLog("")
            onLog("💡 DIAGNÓSTICO:")
            onLog("  1. Verifica que el binario sea ELF válido (magic: 7F454C46)")
            onLog("  2. Verifica que la arquitectura sea correcta (${Build.SUPPORTED_ABIS[0]})")
            onLog("  3. SELinux puede estar bloqueando la ejecución (modo Enforcing)")
            onLog("  4. El binario podría estar compilado para una versión incorrecta de Android")
            onLog("")
            onLog("🔧 SOLUCIONES ALTERNATIVAS:")
            onLog("  • Usa una librería como Tor-Android de Guardian Project")
            onLog("  • Compila Tor específicamente para tu arquitectura y versión de Android")
            onLog("  • Considera usar Orbot y conectarte a través de su proxy")
            
            return possibleLocations[0].second
        }
    }

    private fun getTorDataDir(onLog: (String) -> Unit): File {
        val dataDir = File(context.filesDir, "tor_data")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        return dataDir
    }

    fun startTor(onLog: (String) -> Unit, onReady: () -> Unit) {
        onLog("🚀 DIAGNÓSTICO COMPLETO DE TOR")
        onLog("==================================================")
        onLog("")
        
        val torExecutable = getTorExecutableFile(onLog)
        val torDataDir = getTorDataDir(onLog)

        if (!torExecutable.exists()) {
            onLog("❌ FALLO: Binario no encontrado")
            return
        }

        if (!torExecutable.canExecute()) {
            onLog("❌ FALLO: Binario no es ejecutable")
            return
        }

        val command = listOf(
            torExecutable.absolutePath,
            "DataDirectory", torDataDir.absolutePath,
            "SocksPort", "$torSocksPort",
            "ControlPort", "$torControlPort",
            "__DisablePredictedCircuits", "1"
        )

        onLog("")
        onLog("==================================================")
        onLog("🚀 INICIANDO TOR")
        onLog("==================================================")
        onLog("📍 Ejecutable: ${torExecutable.absolutePath}")
        onLog("📂 Data dir: ${torDataDir.absolutePath}")
        onLog("⚙️ Comando: ${command.joinToString(" ")}")
        onLog("")

        try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(true)

            torProcess = processBuilder.start()
            onLog("✅ Proceso iniciado")

            Thread {
                var isReady = false
                val reader = torProcess?.inputStream?.bufferedReader()

                try {
                    reader?.forEachLine { line ->
                        onLog(line)
                        if (line.contains("Bootstrapped 100%") && !isReady) {
                            isReady = true
                            onReady()
                        }
                    }
                } catch (e: Exception) {
                    onLog("❌ Error: ${e.message}")
                } finally {
                    val exitCode = torProcess?.waitFor()
                    onLog("⏹️ Proceso terminado: $exitCode")
                }
            }.start()

        } catch (e: Exception) {
            onLog("❌ Excepción: ${e.message}")
            onLog("📋 ${e.stackTraceToString()}")
        }
    }

    fun stopTor() {
        torProcess?.destroy()
        torProcess = null
    }
}
