/**
 * DaddyLiveExtractor v1.9
 * - [Fix] Mobile User-Agent 및 CookieManager 적용으로 서버 차단 회피
 * - [Fix] SSL 인증서 오류 강제 통과 및 혼합 콘텐츠 허용
 * - [Debug] 탐지 성공 시 '★탐지 성공' 로그 출력
 */
package com.DaddyLive

import android.content.Context
import android.net.http.SslError
import android.os.*
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URL
import kotlin.coroutines.resume

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = false
    
    // 서버 차단을 피하기 위한 최신 모바일 브라우저 User-Agent
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String, 
        referer: String?, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url)
        println("[DaddyLiveExt] v1.9 추출 프로세스 시작")
        
        links?.forEach { (name, link) ->
            val result = runWebViewInterceptor(link)
            if (result != null) {
                println("[DaddyLiveExt] ★탐지 성공: $result")
                val extractorLink = newExtractorLink(name, name, result, type = ExtractorLinkType.M3U8) {
                    val uri = URL(result)
                    this.referer = "${uri.protocol}://${uri.host}/"
                    this.headers = mapOf("User-Agent" to userAgent)
                }
                callback(extractorLink)
                return // 유효한 링크 하나를 찾으면 즉시 종료
            }
        }
    }

    private suspend fun runWebViewInterceptor(targetUrl: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                // 앱 컨텍스트를 사용하여 웹뷰 생성
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                var isFinished = false

                // 쿠키 설정 (봇 탐지 회피 핵심)
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = userAgent
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webViewClient = object : WebViewClient() {
                    // SSL 인증서 에러 무시
                    override fun onReceivedSslError(view: WebView?, sslHandler: SslErrorHandler?, error: SslError?) {
                        println("[DaddyLiveExt] SSL 에러 무시 진행: ${error?.url}")
                        sslHandler?.proceed()
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        val lower = reqUrl.lowercase()
                        
                        // 실제 영상 스트림 서버 키워드 감시
                        val isMedia = lower.contains(".m3u8") || lower.contains("mizhls") || lower.contains("newkso")
                        val isFake = lower.contains("topembed.pw")

                        if (isMedia && !isFake && !isFinished) {
                            isFinished = true
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        println("[DaddyLiveExt] 로드 완료: $url")
                    }
                }

                println("[DaddyLiveExt] 웹뷰 분석 시도: $targetUrl")
                webView.loadUrl(targetUrl)

                // 개별 링크 타임아웃 15초
                handler.postDelayed({
                    if (!isFinished && cont.isActive) {
                        isFinished = true
                        webView.destroy()
                        println("[DaddyLiveExt] 분석 타임아웃 (15s)")
                        cont.resume(null)
                    }
                }, 15000)

            } catch (e: Exception) {
                println("[DaddyLiveExt] 웹뷰 실행 오류: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
