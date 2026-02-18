// v2.1 - WebView Sniffing 적용 (동적 페이지 대응)
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
    
    // PC User-Agent 사용 (모바일 차단 우회)
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[DaddyLiveExtractor] getUrl 호출됨. 타겟: $url")
        
        // 1. WebView를 통해 m3u8 스니핑 시도
        val m3u8Url = runWebViewSniffing(url, referer ?: mainUrl)
        
        if (m3u8Url != null) {
            println("[DaddyLiveExtractor] M3U8 추출 성공: $m3u8Url")
            
            // DaddyLive는 Referer와 Origin 헤더 체크가 엄격함
            val finalReferer = "https://dlhd.link/" 
            
            callback(newExtractorLink(
                name,
                name, // 이 이름은 DaddyLiveScheduleProvider에서 덮어씌워집니다.
                m3u8Url,
                ExtractorLinkType.M3U8,
                Qualities.Unknown.value
            ) {
                this.headers = mapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to finalReferer,
                    "Origin" to "https://dlhd.link"
                )
            })
        } else {
            println("[DaddyLiveExtractor] M3U8 추출 실패 (타임아웃 또는 발견 못함): $url")
        }
    }

    /**
     * WebView를 생성하여 페이지를 로드하고, 네트워크 요청 중 .m3u8이 포함된 URL을 가로챕니다.
     */
    private suspend fun runWebViewSniffing(url: String, referer: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        handler.post {
            try {
                println("[DaddyLiveExtractor] WebView 인스턴스 생성 및 설정...")
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    mediaPlaybackRequiresUserGesture = false // 자동 재생 허용 (중요)
                }

                // 15초 타임아웃 설정
                val timeoutRunnable = Runnable {
                    if (cont.isActive) {
                        println("[DaddyLiveExtractor] WebView 타임아웃 (15초). 강제 종료.")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(timeoutRunnable, 15000)

                webView.webViewClient = object : WebViewClient() {
                    // 리소스 로드 가로채기
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        // [핵심 로직] .m3u8 요청 감지
                        if (reqUrl.contains(".m3u8") && !reqUrl.contains("favicon")) {
                            println("[DaddyLiveExtractor] >>> M3U8 발견됨! <<< : $reqUrl")
                            
                            // 타임아웃 해제
                            handler.removeCallbacks(timeoutRunnable)
                            
                            if (cont.isActive) {
                                view?.post { try { webView.destroy() } catch (e: Exception) {} }
                                cont.resume(reqUrl)
                            }
                            return null
                        }
                        
                        // 성능 향상을 위해 이미지/폰트/광고 등 불필요한 리소스 차단
                        if (reqUrl.matches(Regex(".*\\.(jpg|png|gif|css|woff2?)$")) || 
                            reqUrl.contains("google") || 
                            reqUrl.contains("facebook") ||
                            reqUrl.contains("analytics")) {
                            return WebResourceResponse("text/plain", "utf-8", null)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        println("[DaddyLiveExtractor] 페이지 로딩 완료: $url")
                        // 페이지가 로드된 후 JS가 실행되면서 iframe 내의 플레이어가 로드됩니다.
                        // m3u8 요청은 비동기로 발생하므로 여기서 바로 종료하지 않고 기다립니다.
                    }
                }

                println("[DaddyLiveExtractor] URL 로드 시작 (Referer: $referer): $url")
                webView.loadUrl(url, mapOf("Referer" to referer))

            } catch (e: Exception) {
                println("[DaddyLiveExtractor] WebView 초기화 중 에러: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
