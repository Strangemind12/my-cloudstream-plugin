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
 * KotbcExtractor v3.2 (Based on TVWiki/TVMON Provider)
 * - Uses WebView to intercept requests from nnmo0oi1.com / mov.glamov.com
 * - [v3.2] .html 캡처 후 내부 텍스트를 파싱하여 실제 .m3u8 주소를 추출하도록 로직 보완
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
        println("[Kotbc v3.2] getUrl 실행: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // WebView를 통해 1차 URL(주로 .html 또는 .m3u8) 획득
        val capturedUrl = runWebViewHook(url, referer ?: "https://m136.kotbc2.com/")
        
        if (capturedUrl != null) {
            println("[Kotbc v3.2] WebView 캡처 성공: $capturedUrl")
            
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://nnmo0oi1.com/", // 스트리밍 시 필요한 리퍼러
                "Origin" to "https://nnmo0oi1.com"
            )

            // 쿠키가 있다면 헤더에 추가
            val cookie = CookieManager.getInstance().getCookie(capturedUrl)
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
                println("[Kotbc v3.2] 쿠키 셋팅 완료")
            }

            var finalUrl = capturedUrl
            
            // [v3.2 핵심] 캡처된 URL이 .html 등 M3U8 형식이 아닐 경우 실제 M3U8 추출
            try {
                println("[Kotbc v3.2] 캡처된 URL 본문 파싱 시도: $finalUrl")
                val response = app.get(finalUrl, headers = headers)
                val content = response.text.trim()

                if (!content.startsWith("#EXTM3U")) {
                    println("[Kotbc v3.2] 본문이 M3U8 포맷이 아님. 내부 M3U8 링크 추출 탐색")
                    val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                    m3u8Regex.find(content)?.let {
                        finalUrl = it.groupValues[1]
                        println("[Kotbc v3.2] 실제 M3U8 주소 추출 성공: $finalUrl")
                    } ?: run {
                        println("[Kotbc v3.2] 본문에서 M3U8 링크를 찾지 못함. 원본 URL 유지.")
                    }
                } else {
                    println("[Kotbc v3.2] 캡처된 URL이 이미 정상적인 M3U8입니다.")
                }
            } catch (e: Exception) {
                println("[Kotbc v3.2] 파싱 중 예외 발생: ${e.message}")
            }

            // 최종 확정된 URL을 플레이어(ExoPlayer)에 전달
            callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                this.headers = headers
            })
            println("[Kotbc v3.2] 최종 ExtractorLink 전달 완료")
            return true
        } else {
            println("[Kotbc v3.2] WebView 캡처 실패")
            return false
        }
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<String?> { cont ->
        println("[Kotbc v3.2] WebView 훅 실행: $url")
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
                        println("[Kotbc v3.2] WebView Timeout")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        // [복구됨] 원본 조건문과 동일하게 .html 유지
                        if ((reqUrl.contains(".m3u8"))
                            && (Regex("p[1-9][0-9]?player2\\.xyz").containsMatchIn(reqUrl)  || reqUrl.contains("bunny-frame") || reqUrl.contains("glamov"))) {
                            println("[Kotbc v3.2] Target URL Intercepted: $reqUrl")
                            
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
                println("[Kotbc v3.2] WebView Init Error: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
