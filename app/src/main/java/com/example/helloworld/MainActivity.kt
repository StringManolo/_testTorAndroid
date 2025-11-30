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
        
        // Muestra un mensaje inicial
        Toast.makeText(this, "Iniciando Tor...", Toast.LENGTH_LONG).show()

        // El trabajo de Tor se hace en un hilo separado
        Thread {
            try {
                // Iniciar Tor y pasar el callback de éxito
                torManager.startTor {
                    // Este bloque se ejecuta solo cuando Tor está Bootstrapped 100%
                    runOnUiThread {
                        setupWebViewAndLoadUrl()
                        Toast.makeText(this, "Tor Listo. Conectando a la web de prueba.", Toast.LENGTH_LONG).show()
                    }
                }
                
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Fallo al iniciar Tor: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
    
    private fun setupWebViewAndLoadUrl() {
        // 1. Configurar el WebViewClient para usar el proxy SOCKS
        webView.webViewClient = TorWebViewClient("127.0.0.1", torManager.torSocksPort)
        
        // 2. Cargar la URL de prueba
        webView.loadUrl("https://check.torproject.org/")
    }

    override fun onDestroy() {
        super.onDestroy()
        torManager.stopTor()
    }
}
