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
    
    // --- NUEVO Directorio de Ejecución (Para evitar restricciones SElinux) ---
    private fun getExecDir(): File {
        val execDir = File(context.filesDir, "exec")
        if (!execDir.exists()) {
            execDir.mkdirs()
        }
        return execDir
    }

    fun ensureBinaryExtracted() {
        val torExecutable = getTorExecutableFile()
        if (torExecutable.exists() && torExecutable.canExecute()) return

        try {
            val abi = android.os.Build.SUPPORTED_ABIS[0]
            val assetPath = "tor_bin/$abi/tor" 
            
            context.assets.open(assetPath).use { inputStream ->
                // COPIAR AL NUEVO DIRECTORIO 'exec'
                FileOutputStream(torExecutable).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            // Asegurar que sea ejecutable para el sistema de archivos
            torExecutable.setExecutable(true, false) // <-- Usar 'setExecutable(true, false)'
            
            // Opcional: Intento de cambiar permisos a 700 (lectura/escritura/ejecución para el dueño)
            Runtime.getRuntime().exec("chmod 700 ${torExecutable.absolutePath}").waitFor()
            
            Log.d("TorProcessManager", "Tor binario extraído y permisos aplicados en ${torExecutable.absolutePath}")
            
        } catch (e: IOException) {
            Log.e("TorProcessManager", "Fallo al extraer binario de Tor", e)
        }
    }
    
    fun startTor(onLog: (String) -> Unit, onReady: () -> Unit) {
        ensureBinaryExtracted()
        
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
            
            Thread {
                var isReady = false
                val reader = torProcess?.inputStream?.bufferedReader()
                if (reader == null) {
                    onLog("Error: No se pudo obtener el InputStream del proceso.")
                    return@Thread
                }
                
                try {
                    reader.forEachLine { line ->
                        onLog(line)
                        
                        if (line.contains("Bootstrapped 100%") && !isReady) {
                            isReady = true
                            Log.d("TorProcess", "Tor está listo y Bootstrapped")
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
            onLog("Excepción al iniciar el binario: ${e.message}")
        }
    }
    
    fun stopTor() {
        torProcess?.destroy()
        torProcess = null
        Log.d("TorProcessManager", "Proceso de Tor detenido")
    }
    
    private fun getTorExecutableFile(): File {
        // --- CAMBIO CLAVE: USAR EL DIRECTORIO 'exec' ---
        return File(getExecDir(), "tor") 
    }
    
    private fun getTorDataDir(): File {
        return File(context.filesDir, "tor_data")
    }
}
