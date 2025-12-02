package com.example.helloworld

import android.content.Context
import android.os.Build
import java.io.File

class TorProcessManager private constructor(private val context: Context) {

    private var torOutputFd: Int = -1
    private var readerThread: Thread? = null

    @Volatile
    var isRunning = false
        private set

    val torSocksPort = 9050
    val torControlPort = 9051

    companion object {
        init {
            System.loadLibrary("torwrapper")
        }

        @Volatile
        private var INSTANCE: TorProcessManager? = null

        fun getInstance(context: Context): TorProcessManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TorProcessManager(context.applicationContext).also { INSTANCE = it }
            }
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
        onLog("📱 Device Info:")
        onLog("  Model: ${Build.MODEL}")
        onLog("  ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")

        val abi = Build.SUPPORTED_ABIS[0]
        val binaryName = when {
            abi.startsWith("arm64") -> "tor-arm64-v8a"
            abi.startsWith("armeabi") -> "tor-armeabi-v7a"
            else -> "tor-arm64-v8a"
        }

        val torExecutable = File(context.filesDir, "tor")

        if (!torExecutable.exists() || torExecutable.length() == 0L) {
            onLog("📥 Extracting binary...")
            try {
                context.assets.open(binaryName).use { input ->
                    torExecutable.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                torExecutable.setExecutable(true, false)
                torExecutable.setReadable(true, false)
                onLog("✅ Binary extracted")
            } catch (e: Exception) {
                onLog("❌ Error extracting: ${e.message}")
            }
        }
        return torExecutable
    }

    private fun getTorDataDir(onLog: (String) -> Unit): File {
        val dataDir = File(context.filesDir, "tor_data")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        return dataDir
    }

    fun startTor(onLog: (String) -> Unit, onReady: () -> Unit) {
        if (isRunning) {
            onLog("⚠️ Tor is already running (Singleton).")
            if (isProcessAlive()) {
                onLog("✅ Process is alive. Triggering onReady.")
                onReady()
            }
            return
        }

        onLog("🚀 STARTING TOR")

        val logCallback = object : LogCallback {
            override fun onLog(message: String) {
                onLog(message)
            }
        }
        setLogCallback(logCallback)

        val torExecutable = getTorExecutableFile(onLog)
        val torDataDir = getTorDataDir(onLog)

        if (!torExecutable.exists()) {
            onLog("❌ Binary not found")
            return
        }

        val args = arrayOf(
            "--DataDirectory", torDataDir.absolutePath,
            "--SocksPort", "$torSocksPort",
            "--ControlPort", "$torControlPort",
            "--__DisablePredictedCircuits", "1"
        )

        try {
            torOutputFd = startTorNative(torExecutable.absolutePath, args)

            if (torOutputFd < 0) {
                onLog("❌ startTorNative returned error")
                return
            }

            isRunning = true
            
            readerThread?.interrupt()
            readerThread = Thread {
                var isReady = false
                var emptyCount = 0

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
                                        onReady()
                                    }
                                }
                            }
                        } else {
                            emptyCount++
                            if (emptyCount == 300) {
                                onLog("⚠️ No output for 30s")
                            }
                        }
                        Thread.sleep(100)
                    }
                } catch (e: Exception) {
                    onLog("❌ Read thread error: ${e.message}")
                }
            }
            readerThread?.start()

        } catch (e: Exception) {
            isRunning = false
            onLog("❌ Exception starting Tor: ${e.message}")
        }
    }

    fun stopTor() {
        if (!isRunning) return
        try {
            stopTorNative()
        } catch (_: Exception) {}
        isRunning = false
        readerThread?.interrupt()
        readerThread = null
    }
}
