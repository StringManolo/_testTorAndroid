package com.example.helloworld

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers.Companion.toHeaders
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

class TorWebViewClient(proxyHost: String, proxyPort: Int) : WebViewClient() {
    
    private val client: OkHttpClient
    
    init {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort))
        client = OkHttpClient.Builder()
            .proxy(proxy)
            .build()
    }

    override fun shouldInterceptRequest(view: android.webkit.WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) {
            return super.shouldInterceptRequest(view, request)
        }
        
        try {
            val okHttpRequest = Request.Builder()
                .url(request.url.toString())
                .headers(request.requestHeaders.toHeaders())
                .build()

            val response = client.newCall(okHttpRequest).execute()

            val body = response.body
            if (response.isSuccessful && body != null) {
                val contentType = body.contentType()
                val mimeType = contentType?.type + "/" + contentType?.subtype
                val encoding = response.header("Content-Encoding") ?: "utf-8"

                val headersMap = response.headers.toMap()

                return WebResourceResponse(
                    mimeType,
                    encoding,
                    response.code,
                    response.message,
                    headersMap,
                    body.byteStream()
                )
            }
        } catch (e: IOException) {
            return null
        }
        
        return super.shouldInterceptRequest(view, request)
    }
}
