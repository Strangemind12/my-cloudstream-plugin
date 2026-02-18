// v3.1 - (재확인용) 정확한 Referer 획득 및 M3U8 반환
package com.DaddyLive

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /**
     * M3U8 URL과 해당 요청에 유효한 Referer를 반환
     * @return Pair(M3U8_URL, REFERER_URL)
     */
    suspend fun fetchM3u8Url(url: String, referer: String?): Pair<String, String>? {
        return runWebViewSniffing(url, referer ?: mainUrl)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Provider에서 fetchM3u8Url을 직접 사용하므로 이 함수는 사용되지 않지만 인터페이스 구현을 위해 남겨둠
        val result = fetchM3u8Url(url, referer)
        if (result != null) {
            val (m3u8Url, finalReferer) = result
            callback(newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                this.referer = finalReferer
                this.headers = mapOf("Referer" to finalReferer)
            })
        }
    }

    private suspend fun runWebViewSniffing(url: String, referer: String): Pair<String, String>? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        handler.post {
            try {
                println("[DaddyLiveExtractor] WebView 초기화: $url")
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    mediaPlaybackRequiresUserGesture = false 
                }

                val timeoutRunnable = Runnable {
                    if (cont.isActive) {
                        println("[DaddyLiveExtractor] 타임아웃")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(timeoutRunnable, 15000)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        if (reqUrl.contains(".m3u8") && !reqUrl.contains("favicon")) {
                            println("[DaddyLiveExtractor] M3U8 발견: $reqUrl")
                            
                            // 현재 페이지 URL(iframe 주소)을 캡처하여 Referer로 사용
                            val currentUrl = view?.url ?: referer
                            println("[DaddyLiveExtractor] 캡처된 Referer: $currentUrl")
                            
                            handler.removeCallbacks(timeoutRunnable)
                            
                            if (cont.isActive) {
                                view?.post { try { webView.destroy() } catch (e: Exception) {} }
                                cont.resume(Pair(reqUrl, currentUrl))
                            }
                            return null
                        }
                        
                        if (reqUrl.matches(Regex(".*\\.(jpg|png|gif|css|woff2?|ico)$"))) {
                            return WebResourceResponse("text/plain", "utf-8", null)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }
                webView.loadUrl(url, mapOf("Referer" to referer))
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
