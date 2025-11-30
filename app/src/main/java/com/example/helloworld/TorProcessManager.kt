package com.example.helloworld

import android.content.Context
import android.util.Log
import java.io.File

class TorProcessManager(private val context: Context) {
    
    private var torProcess: Process? = null
    
    val torSocksPort = 9050
    val torControlPort = 9051
    
    // --- NUEVO: OBTENER RUTA DEL BINARIO INSTALADO POR EL SISTEMA ---
    private fun getTorExecutableFile(): File {
        // La ruta base donde Android copia los archivos de jniLibs
        val libDir = context.applicationInfo.nativeLibraryDir
        // El binario debe estar aquí bajo el nombre 'tor'
        return File(libDir, "tor") 
    }
    
    private fun getTorDataDir(): File {
        // El DataDir aún puede estar en filesDir
        val dataDir = File(context.filesDir, "tor_data")
        if (!dataDir.exists()) dataDir.mkdirs()
        return dataDir
    }

    // Ya NO necesitamos ensureBinaryExtracted ni chmod
    // La instalación lo maneja automáticamente.

    fun startTor(onLog: (String) -> Unit, onReady: () -> Unit) {
        
        val torExecutable = getTorExecutableFile()
        val torDataDir = getTorDataDir()
        
        if (!torExecutable.exists()) {
            onLog("Error crítico: Binario 'tor' no encontrado en el directorio de librerías nativas: ${torExecutable.absolutePath}")
            return
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
            
            // ... (Resto del hilo de lectura de logs, sin cambios)
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
            onLog("Excepción al iniciar Tor. Fallo grave en el entorno: ${e.message}")
        }
    }
    
    fun stopTor() {
        torProcess?.destroy()
        torProcess = null
    }
}
