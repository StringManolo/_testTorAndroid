package com.example.helloworld

import android.content.Context
import android.util.Log
import java.io.File

class TorProcessManager(private val context: Context) {
    
    private var torProcess: Process? = null
    
    val torSocksPort = 9050
    val torControlPort = 9051
    
    private fun getTorExecutableFile(): File {
        val libDir = context.applicationInfo.nativeLibraryDir
        // *** CAMBIO CLAVE: Busca 'libtor.so' ***
        return File(libDir, "libtor.so") 
    }
    
    private fun getTorDataDir(): File {
        val dataDir = File(context.filesDir, "tor_data")
        if (!dataDir.exists()) dataDir.mkdirs()
        return dataDir
    }

    fun startTor(onLog: (String) -> Unit, onReady: () -> Unit) {
        
        val torExecutable = getTorExecutableFile()
        val torDataDir = getTorDataDir()
        
        if (!torExecutable.exists()) {
            onLog("Error crítico: Binario 'libtor.so' no encontrado en el directorio de librerías nativas: ${torExecutable.absolutePath}")
            return
        }
        
        // Comprobar que sea ejecutable (aunque el sistema lo garantiza en esta ruta)
        if (!torExecutable.canExecute()) {
             onLog("ADVERTENCIA: 'libtor.so' no tiene permisos de ejecución, intentando chmod.")
             // Intento de chmod si es necesario, aunque debe ser automático aquí
             try {
                Runtime.getRuntime().exec("chmod 700 ${torExecutable.absolutePath}").waitFor()
             } catch (e: Exception) {
                onLog("Fallo en chmod de contingencia: ${e.message}")
             }
        }


        val command = listOf(
            torExecutable.absolutePath,
            "DataDirectory", torDataDir.absolutePath,
            "SocksPort", "$torSocksPort",
            "ControlPort", "$torControlPort",
            "__DisablePredictedCircuits", "1"
        )
        
        onLog("Ruta de Ejecución: ${torExecutable.absolutePath}")
        
        try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(true)
            
            torProcess = processBuilder.start()
            onLog("Proceso Tor iniciado.")
            
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
            onLog("Excepción al iniciar Tor. Error: ${e.message}")
        }
    }
    
    fun stopTor() {
        torProcess?.destroy()
        torProcess = null
    }
}
