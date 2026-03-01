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
 * KotbcExtractor v3.4 (Based on TVWiki/TVMON Provider)
 * - [v3.4] master.txt 등 캡처된 파일의 내부 텍스트(본문)를 콘솔에 출력하는 디버깅 로직 추가
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
        println("[Kotbc v3.4] getUrl 실행: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // WebView를 통해 1차 URL 획득
        val capturedUrl = runWebViewHook(url, referer ?: "https://m136.kotbc2.com/")
        
        if (capturedUrl != null) {
            println("[Kotbc v3.4] WebView 캡처 성공: $capturedUrl")
            
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://nnmo0oi1.com/", // 스트리밍 시 필요한 리퍼러
                "Origin" to "https://nnmo0oi1.com"
            )

            val cookie = CookieManager.getInstance().getCookie(capturedUrl)
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
                println("[Kotbc v3.4] 쿠키 셋팅 완료")
            }

            var finalUrl = capturedUrl
            
            try {
                println("[Kotbc v3.4] 캡처된 URL 본문 다운로드 및 파싱 시도: $finalUrl")
                val response = app.get(finalUrl, headers = headers)
                
                val content = try {
                    response.text.trim()
                } catch (e: Exception) {
                    println("[Kotbc v3.4] text 파싱 에러 (textLarge 시도): ${e.message}")
                    response.document.text().trim() 
                }

                // ==========================================
                // [디버깅용 추가] 파일의 실제 내용을 콘솔에 출력 (최대 2000자 제한하여 로그 잘림 방지)
                println("[Kotbc v3.4] ================= 본문 내용 시작 =================")
                println(content.take(2000))
                if (content.length > 2000) println("... (이하 생략됨, 총 길이: ${content.length}) ...")
                println("[Kotbc v3.4] ================= 본문 내용 끝 ===================")
                // ==========================================

                if (!content.startsWith("#EXTM3U")) {
                    println("[Kotbc v3.4] 본문이 M3U8 포맷이 아님. 내부 M3U8 링크 추출 탐색")
                    val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                    m3u8Regex.find(content)?.let {
                        finalUrl = it.groupValues[1]
                        println("[Kotbc v3.4] 실제 M3U8 주소 추출 성공: $finalUrl")
                    } ?: run {
                        println("[Kotbc v3.4] 본문에서 M3U8 절대경로 링크를 찾지 못함. 원본 URL 유지.")
                    }
                } else {
                    println("[Kotbc v3.4] 캡처된 URL 본문이 정상적인 #EXTM3U 포맷입니다.")
                }
            } catch (e: Exception) {
                println("[Kotbc v3.4] 파싱 중 치명적 예외 발생: ${e.message}")
            }

            // 최종 확정된 URL을 플레이어(ExoPlayer)에 전달
            callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                this.headers = headers
            })
            println("[Kotbc v3.4] 최종 ExtractorLink 전달 완료: $finalUrl")
            return true
        } else {
            println("[Kotbc v3.4] WebView 캡처 실패")
            return false
        }
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<String?> { cont ->
        println("[Kotbc v3.4] WebView 훅 실행: $url")
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

                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        println("[Kotbc v3.4] WebView Timeout")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        // 영상 조각(Segment) 파일은 가로채지 않고 무시
                        val isSegment = Regex("""_[0-9]+\.(html|ts)(\?.*)?$""").containsMatchIn(reqUrl)
                        if (isSegment) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        // 진짜 플레이리스트나 중간 연결 파일만 가로챔 (master.txt 포함)
                        if ((reqUrl.contains(".m3u8") || reqUrl.contains(".html") || reqUrl.contains("master")) 
                            && (Regex("p[1-9][0-9]?player2\\.xyz").containsMatchIn(reqUrl)  || reqUrl.contains("bunny-frame") || reqUrl.contains("glamov") || reqUrl.contains("nnmo0oi1.com"))) {
                            
                            println("[Kotbc v3.4] Target URL Intercepted: $reqUrl")
                            
                            handler.removeCallbacks(discoveryTimeout)
                            
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
                println("[Kotbc v3.4] WebView Init Error: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
