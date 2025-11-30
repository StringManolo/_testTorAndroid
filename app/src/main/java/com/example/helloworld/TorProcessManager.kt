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

    fun ensureBinaryExtracted() {
        val torExecutable = getTorExecutableFile()
        if (torExecutable.exists() && torExecutable.canExecute()) return

        try {
            val abi = android.os.Build.SUPPORTED_ABIS[0]
            val assetPath = "tor_bin/$abi/tor" 
            
            context.assets.open(assetPath).use { inputStream ->
                FileOutputStream(torExecutable).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            torExecutable.setExecutable(true)
            Log.d("TorProcessManager", "Tor binario extraído a ${torExecutable.absolutePath}")
            
        } catch (e: IOException) {
            Log.e("TorProcessManager", "Fallo al extraer binario de Tor", e)
        }
    }
    
    fun startTor(onReady: () -> Unit) {
        ensureBinaryExtracted()
        
        val torExecutable = getTorExecutableFile()
        val torDataDir = getTorDataDir()
        
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
                torProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.i("TorProcess", line)
                    if (line.contains("Bootstrapped 100%") && !isReady) {
                        isReady = true
                        Log.d("TorProcess", "Tor está listo y Bootstrapped")
                        onReady()
                    }
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e("TorProcessManager", "Fallo al iniciar el proceso de Tor", e)
        }
    }
    
    fun stopTor() {
        torProcess?.destroy()
        torProcess = null
        Log.d("TorProcessManager", "Proceso de Tor detenido")
    }
    
    private fun getTorExecutableFile(): File {
        return File(context.filesDir, "tor")
    }
    
    private fun getTorDataDir(): File {
        return File(context.filesDir, "tor_data")
    }
}
