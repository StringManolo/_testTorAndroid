package com.example.helloworld

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import android.widget.LinearLayout
import android.view.ViewGroup
import android.widget.Toast

class MainActivity : AppCompatActivity() {
    
    private lateinit var torManager: TorProcessManager
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        torManager = TorProcessManager(this)
        
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true 
        }

        setContentView(webView)

        Thread {
            try {
                torManager.startTor()
                
                runOnUiThread {
                    webView.webViewClient = TorWebViewClient("127.0.0.1", torManager.torSocksPort)
                    webView.loadUrl("https://check.torproject.org/")
                    Toast.makeText(this, "Intentando conectar vía Tor...", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Fallo al iniciar Tor: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        torManager.stopTor()
    }
}
