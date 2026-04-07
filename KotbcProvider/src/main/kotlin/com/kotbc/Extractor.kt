package com.kotbc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class KotbcExtractor : ExtractorApi() {
    override val name = "KOTBC"
    override val mainUrl = "https://mov.glamov.com"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    data class InterceptResult(val url: String, val headers: Map<String, String>)

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val safeReferer = if (referer.isNullOrEmpty() || referer.contains("m136")) {
            Kotbc.currentMainUrl + "/"
        } else referer
        
        val hookResult = runWebViewHook(url, safeReferer)
        
        if (hookResult != null) {
            var finalUrl = hookResult.url
            val headers = hookResult.headers.toMutableMap()
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = DESKTOP_UA
            }

            val cookie = CookieManager.getInstance().getCookie(finalUrl)
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            try {
                val response = app.get(finalUrl, headers = headers)
                val content = try { response.text.trim() } catch (e: Exception) { response.document.text().trim() }

                if (!content.startsWith("#EXTM3U") && !content.contains("security error")) {
                    val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                    m3u8Regex.find(content)?.let { finalUrl = it.groupValues[1] }
                }
            } catch (e: Exception) {}

            callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                this.headers = headers
            })
            return true
        }
        return false
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<InterceptResult?> { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        
        // [Fix] 메모리 누수 방지 로직 추가
        cont.invokeOnCancellation {
            handler.post { try { webView?.destroy(); webView = null } catch (e: Exception) {} }
        }
        
        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                webView = WebView(context)
                
                webView?.settings?.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                }

                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        try { webView?.destroy(); webView = null } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        val isSegment = Regex("""_[0-9]+\.(html|ts)(\?.*)?$""").containsMatchIn(reqUrl)
                        if (isSegment) return super.shouldInterceptRequest(view, request)

                        if ((reqUrl.contains(".m3u8") || reqUrl.contains(".html") || reqUrl.contains("master") || reqUrl.contains(".txt")) 
                            && (Regex("p[1-9][0-9]?player2\\.xyz").containsMatchIn(reqUrl)  || reqUrl.contains("bunny-frame") || reqUrl.contains("glamov") || reqUrl.contains("nnmo0oi1.com"))) {
                            
                            handler.removeCallbacks(discoveryTimeout)
                            
                            if (cont.isActive) {
                                val requestHeaders = request?.requestHeaders ?: emptyMap()
                                view?.post { try { webView?.destroy(); webView = null } catch (e: Exception) {} }
                                cont.resume(InterceptResult(reqUrl, requestHeaders))
                            }
                            return null
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                webView?.loadUrl(url, mapOf("Referer" to referer))

            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
