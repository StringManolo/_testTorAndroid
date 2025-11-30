package com.example.helloworld

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class TorProcessManager(private val context: Context) {
    
    private var torProcess: Process? = null
    
    val torSocksPort = 9050
    val torControlPort = 9051
    
    private fun getExecDir(): File {
        val execDir = File(context.filesDir, "exec")
        if (!execDir.exists()) {
            execDir.mkdirs()
        }
        return execDir
    }

    private fun getTorExecutableFile(): File {
        return File(getExecDir(), "tor") 
    }
    
    private fun getTorDataDir(): File {
        return File(context.filesDir, "tor_data")
    }

    private fun executeShellCommand(command: String, onLog: (String) -> Unit): Boolean {
        try {
            val process = Runtime.getRuntime().exec(command)
            
            // Consumir STDOUT
            process.inputStream.bufferedReader().useLines { lines -> 
                lines.forEach { onLog("SH OUT: $it") } 
            }
            // Consumir STDERR
            process.errorStream.bufferedReader().useLines { lines -> 
                lines.forEach { onLog("SH ERR: $it") } 
            }
            
            val exitCode = process.waitFor()
            onLog("Comando '$command' finalizado con código: $exitCode")
            return exitCode == 0
        } catch (e: Exception) {
            onLog("Excepción al ejecutar shell: ${e.message}")
            return false
        }
    }

    fun ensureBinaryExtracted(onLog: (String) -> Unit) {
        val torExecutable = getTorExecutableFile()
        
        // 1. Verificar si existe y tiene permisos básicos
        if (torExecutable.exists() && torExecutable.canExecute()) return

        try {
            val abi = android.os.Build.SUPPORTED_ABIS[0]
            val assetPath = "tor_bin/$abi/tor" 
            
            onLog("Extrayendo binario Tor para $abi...")
            
            context.assets.open(assetPath).use { inputStream ->
                FileOutputStream(torExecutable).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            // 2. Intentar establecer permisos de ejecución (rwx para el dueño)
            torExecutable.setExecutable(true, false) 
            
            // 3. Ejecutar CHMOD explícitamente como contingencia de SELinux
            val chmodSuccess = executeShellCommand("chmod 700 ${torExecutable.absolutePath}", onLog)
            
            if (!chmodSuccess || !torExecutable.canExecute()) {
                onLog("ADVERTENCIA: Fallo al aplicar permisos de ejecución (chmod). Esto podría causar Error=13.")
            } else {
                onLog("Extracción y permisos OK. Listo para ejecutar.")
            }
            
        } catch (e: IOException) {
            onLog("Fallo crítico al extraer binario de Tor: ${e.message}")
        }
    }
    
    fun startTor(onLog: (String) -> Unit, onReady: () -> Unit) {
        ensureBinaryExtracted(onLog)
        
        val torExecutable = getTorExecutableFile()
        val torDataDir = getTorDataDir()
        
        if (!torExecutable.exists() || !torExecutable.canExecute()) {
            onLog("Error crítico: El binario de Tor no existe o no tiene permisos de ejecución.")
            return
        }
        
        if (!torDataDir.exists()) torDataDir.mkdirs()

        val command = listOf(
            torExecutable.absolutePath,
            "DataDirectory", torDataDir.absolutePath,
            "SocksPort", "$torSocksPort",
            "ControlPort", "$torControlPort",
            "__DisablePredictedCircuits", "1"
        )
        
        try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(true)
            
            torProcess = processBuilder.start()
            onLog("Proceso Tor iniciado. PID: ${torProcess.hashCode()}")
            
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
                    onLog("Error leyendo el stream de Tor: ${e.message}")
                } finally {
                    val exitCode = torProcess?.waitFor()
                    onLog("El proceso de Tor ha terminado con código de salida: $exitCode")
                }
            }.start()
            
        } catch (e: Exception) {
            onLog("Excepción al iniciar el binario: ${e.message}. El kernel denegó la ejecución (SELinux/noexec).")
        }
    }
    
    fun stopTor() {
        torProcess?.destroy()
        torProcess = null
    }
}
