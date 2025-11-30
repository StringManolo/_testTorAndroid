package com.example.helloworld

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import android.widget.LinearLayout
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import android.graphics.Typeface
import android.util.Log

class MainActivity : AppCompatActivity() {
    
    private lateinit var torManager: TorProcessManager
    private lateinit var webView: WebView
    private lateinit var logTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        torManager = TorProcessManager(this)
        
        // 1. Crear el TextView para logs
        logTextView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                0, // 0 height initially
                0.2f // Peso para ocupar el 20% de la pantalla
            )
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setBackgroundColor(0xFF000000.toInt()) // Fondo negro
            setTextColor(0xFF00FF00.toInt()) // Texto verde
        }
        
        // 2. Crear el WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                0, 
                0.8f // Peso para ocupar el 80% de la pantalla
            )
            settings.javaScriptEnabled = true 
        }

        // 3. Usar ScrollView para que el logTextView pueda desplazarse
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                0, 
                0.2f
            )
            addView(logTextView)
        }

        // 4. Crear el Layout principal
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(scrollView) // Primero el log
            addView(webView)    // Luego el WebView
        }

        setContentView(mainLayout)
        
        Toast.makeText(this, "Iniciando Tor...", Toast.LENGTH_LONG).show()

        Thread {
            try {
                // Iniciar Tor y pasar el callback de éxito
                torManager.startTor(
                    onLog = { line ->
                        // Actualizar la UI con cada línea de log
                        runOnUiThread {
                            updateLog(line)
                        }
                    },
                    onReady = {
                        runOnUiThread {
                            setupWebViewAndLoadUrl()
                            Toast.makeText(this, "Tor Listo. Conectando a la web de prueba.", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Fallo al iniciar Tor: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
    
    private fun updateLog(line: String) {
        logTextView.append(line + "\n")
        // Scroll automático hacia abajo
        (logTextView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
    }
    
    private fun setupWebViewAndLoadUrl() {
        // Ocultar el log o reducir su tamaño una vez que Tor esté listo (opcional)
        logTextView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT // Cambiar a WRAP_CONTENT para minimizarlo
        )
        
        webView.webViewClient = TorWebViewClient("127.0.0.1", torManager.torSocksPort)
        webView.loadUrl("https://check.torproject.org/")
    }

    override fun onDestroy() {
        super.onDestroy()
        torManager.stopTor()
    }
}
