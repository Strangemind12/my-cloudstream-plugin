package com.kotbc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.Collections
import kotlin.coroutines.resume

/**
 * KotbcExtractor v3.0 (Based on TVWiki/TVMON Provider)
 * - Uses WebView to intercept M3U8 requests from nnmo0oi1.com / mov.glamov.com
 */
class KotbcExtractor : ExtractorApi() {
    override val name = "KOTBC"
    override val mainUrl = "https://mov.glamov.com"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[Kotbc] getUrl: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // WebView를 통해 최종 M3U8 URL을 획득
        val capturedUrl = runWebViewHook(url, referer ?: "https://m136.kotbc2.com/")
        
        if (capturedUrl != null) {
            println("[Kotbc] WebView 캡처 성공: $capturedUrl")
            
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://nnmo0oi1.com/", // 스트리밍 시 필요한 리퍼러
                "Origin" to "https://nnmo0oi1.com"
            )

            // 쿠키가 있다면 추가
            val cookie = CookieManager.getInstance().getCookie(capturedUrl)
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }

            callback(newExtractorLink(name, name, capturedUrl, ExtractorLinkType.M3U8) {
                this.headers = headers
            })
            return true
        } else {
            println("[Kotbc] WebView 캡처 실패")
            return false
        }
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<String?> { cont ->
        println("[Kotbc] WebView 실행: $url")
        val handler = Handler(Looper.getMainLooper())
        
        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                }

                // 타임아웃 설정 (15초 내에 못 찾으면 종료)
                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        println("[Kotbc] WebView Timeout")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        // [핵심] M3U8 또는 Master.txt 요청 감지
                        // nnmo0oi1.com 도메인의 .m3u8 또는 .txt 요청을 찾음                  
                        if ((reqUrl.contains(".m3u8") || reqUrl.contains(".html") || reqUrl.contains("master")) 
                            && (Regex("p[1-9][0-9]?player2\\.xyz").containsMatchIn(reqUrl)  || reqUrl.contains("bunny-frame") || reqUrl.contains("glamov"))) {
                            println("[Kotbc] Target URL Intercepted: $reqUrl")
                            
                            handler.removeCallbacks(discoveryTimeout)
                            
                            // 코루틴 재개 (결과 반환)
                            if (cont.isActive) {
                                view?.post { try { webView.destroy() } catch (e: Exception) {} }
                                cont.resume(reqUrl)
                            }
                            return null
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webView.loadUrl(url, mapOf("Referer" to referer))

            } catch (e: Exception) {
                println("[Kotbc] WebView Init Error: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
