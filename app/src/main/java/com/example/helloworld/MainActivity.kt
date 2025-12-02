package com.example.helloworld

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import android.widget.LinearLayout
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import android.widget.ScrollView
import android.graphics.Typeface

class MainActivity : AppCompatActivity() {

    private lateinit var torManager: TorProcessManager
    private lateinit var webView: WebView
    private lateinit var logTextView: EditText
    private lateinit var logScrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        torManager = TorProcessManager.getInstance(this)

        logTextView = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.2f
            )
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setBackgroundColor(0xFF000000.toInt())
            setTextColor(0xFF00FF00.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isLongClickable = true
            setPadding(8, 8, 8, 8)
            showSoftInputOnFocus = false
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.8f
            )
            settings.javaScriptEnabled = true
        }

        logScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.2f
            )
            addView(logTextView)
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(logScrollView)
            addView(webView)
        }

        setContentView(mainLayout)
    }

    override fun onStart() {
        super.onStart()
        
        Toast.makeText(this, "Checking Tor status...", Toast.LENGTH_SHORT).show()

        Thread {
            torManager.startTor(
                onLog = { line ->
                    runOnUiThread {
                        updateLog(line)
                    }
                },
                onReady = {
                    runOnUiThread {
                        setupWebViewAndLoadUrl()
                        Toast.makeText(this, "Tor Ready. Loading...", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }.start()
    }

    private fun updateLog(line: String) {
        logTextView.append(line + "\n")
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun setupWebViewAndLoadUrl() {
        logScrollView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        webView.webViewClient = TorWebViewClient("127.0.0.1", torManager.torSocksPort)
        webView.loadUrl("https://check.torproject.org/")
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
