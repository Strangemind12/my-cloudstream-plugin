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

    companion object {
        @Volatile private var cachedHeaders: Map<String, String>? = null // [고유 개선] Fast-fail용 헤더 캐시
    }

    data class InterceptResult(val url: String, val headers: Map<String, String>)

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val safeReferer = if (referer.isNullOrEmpty() || referer.contains("m136")) Kotbc.currentMainUrl + "/" else referer

        // [고유 개선] Fast-Fail 로직: 매번 웹뷰를 띄우지 않고, 이전 시청 시 획득한 쿠키 헤더로 찔러보기
        if (cachedHeaders != null) {
            try {
                val testRes = app.get(url, headers = cachedHeaders!!)
                val testContent = testRes.text.trim()
                if (!testContent.contains("security error") && (testContent.startsWith("#EXTM3U") || testContent.contains(".m3u8"))) {
                    var finalFastUrl = url
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(testContent)?.let { finalFastUrl = it.groupValues[1] }
                    callback(newExtractorLink(name, name, finalFastUrl, ExtractorLinkType.M3U8) { this.headers = cachedHeaders!! })
                    return
                }
            } catch (e: Exception) {}
        }

        // Fast-Fail 실패 시에만 WebView 후킹 실행
        val hookResult = runWebViewHook(url, safeReferer)
        if (hookResult != null) {
            var finalUrl = hookResult.url
            val headers = hookResult.headers.toMutableMap()
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) headers["User-Agent"] = DESKTOP_UA
            
            val cookie = CookieManager.getInstance().getCookie(finalUrl)
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie
            
            cachedHeaders = headers // 새 캐시 저장

            try {
                val response = app.get(finalUrl, headers = headers)
                val content = try { response.text.trim() } catch (e: Exception) { response.document.text().trim() }
                if (!content.startsWith("#EXTM3U") && !content.contains("security error")) {
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let { finalUrl = it.groupValues[1] }
                }
            } catch (e: Exception) {}

            callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) { this.headers = headers })
        }
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<InterceptResult?> { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        
        // [공통 개선] invokeOnCancellation을 통한 WebView 메모리 누수 방지
        cont.invokeOnCancellation {
            handler.post { try { webView?.destroy(); webView = null } catch (e: Exception) {} }
        }
        
        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                webView = WebView(context)
                webView?.settings?.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = DESKTOP_UA }

                val discoveryTimeout = Runnable {
                    if (cont.isActive) { try { webView?.destroy(); webView = null } catch (e: Exception) {}; cont.resume(null) }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (Regex("""_[0-9]+\.(html|ts)(\?.*)?$""").containsMatchIn(reqUrl)) return super.shouldInterceptRequest(view, request)

                        if ((reqUrl.contains(".m3u8") || reqUrl.contains(".html") || reqUrl.contains("master") || reqUrl.contains(".txt")) 
                            && (Regex("p[1-9][0-9]?player2\\.xyz").containsMatchIn(reqUrl) || reqUrl.contains("bunny-frame") || reqUrl.contains("glamov") || reqUrl.contains("nnmo0oi1.com"))) {
                            
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
