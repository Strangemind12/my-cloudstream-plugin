// v3.0 - 구조적 해결: URL 문자열 반환 함수 추가
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
     * [구조 개선] 직접 M3U8 문자열을 반환하는 Suspend 함수
     * Provider에서 이 함수를 직접 호출하면 콜백 지옥이나 Suspend 에러 없이 깔끔하게 처리 가능
     */
    suspend fun fetchM3u8Url(url: String, referer: String?): String? {
        return runWebViewSniffing(url, referer ?: mainUrl)
    }

    // 기존 Interface 구현 (Provider에서는 사용하지 않음)
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val m3u8Url = fetchM3u8Url(url, referer)
        if (m3u8Url != null) {
            val finalReferer = "https://dlhd.link/"
            // getUrl은 suspend 함수이므로 여기서 newExtractorLink 호출 가능
            val link = newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                this.referer = finalReferer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to finalReferer,
                    "Origin" to "https://dlhd.link"
                )
            }
            callback(link)
        }
    }

    private suspend fun runWebViewSniffing(url: String, referer: String): String? = suspendCancellableCoroutine { cont ->
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
                        println("[DaddyLiveExtractor] 타임아웃 종료")
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
                            handler.removeCallbacks(timeoutRunnable)
                            if (cont.isActive) {
                                view?.post { try { webView.destroy() } catch (e: Exception) {} }
                                cont.resume(reqUrl)
                            }
                            return null
                        }
                        
                        if (reqUrl.matches(Regex(".*\\.(jpg|png|gif|css|woff2?)$"))) {
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
