/**
 * DaddyLiveExtractor v2.1
 * - [Fix] 브라우저 정밀 모사 (Referer, Accept, Accept-Language 헤더 주입)
 * - [Fix] JS Injection 추가: 네트워크 가로채기 실패 시 소스 코드에서 m3u8 강제 탈취
 * - [Fix] SSL 인증서 오류 및 Cookie 세션 관리 강화
 * - [Debug] 추출 성공 시 방식(Network 또는 JS)에 따른 상세 로그 출력
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
    
    // 브라우저로 인식되기 위한 최신 데스크톱 User-Agent
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String, 
        referer: String?, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url)
        println("[DaddyLiveExt] v2.1 정밀 추출 프로세스 시작")
        
        links?.forEach { (name, link) ->
            val result = runWebViewInterceptor(link)
            if (result != null) {
                println("[DaddyLiveExt] ★최종 링크 확보: $result")
                val extractorLink = newExtractorLink(name, name, result, type = ExtractorLinkType.M3U8) {
                    val uri = URL(result)
                    this.referer = "${uri.protocol}://${uri.host}/"
                    this.headers = mapOf("User-Agent" to userAgent)
                }
                callback(extractorLink)
                return // 하나라도 확보되면 나머지 시도 중단
            }
        }
    }

    private suspend fun runWebViewInterceptor(targetUrl: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                var isFinished = false

                // 쿠키 설정: 세션을 유지해야 봇 탐지를 피할 수 있음
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
                    // 1. SSL 에러 무시 (차단 우회)
                    override fun onReceivedSslError(view: WebView?, sslHandler: SslErrorHandler?, error: SslError?) {
                        println("[DaddyLiveExt] SSL 에러 무시 진행: ${error?.url}")
                        sslHandler?.proceed()
                    }

                    // 2. 네트워크 요청 가로채기 (실시간 감시)
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        val lower = reqUrl.lowercase()
                        
                        // m3u8 또는 스트림 서버 키워드 필터링 (가짜 도메인 제외)
                        if ((lower.contains(".m3u8") || lower.contains("mizhls") || lower.contains("newkso")) 
                            && !lower.contains("topembed.pw") && !isFinished) {
                            
                            isFinished = true
                            println("[DaddyLiveExt] ★네트워크 가로채기 성공: $reqUrl")
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    // 3. JS Injection: 네트워크 차단 시 대비하여 DOM 내부의 소스 직접 탈취
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!isFinished) {
                            println("[DaddyLiveExt] 페이지 로드 완료, JS Injection 시도")
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    var scripts = document.getElementsByTagName('script');
                                    for (var i=0; i < scripts.length; i++) {
                                        var content = scripts[i].innerHTML;
                                        // file: '...' 또는 source: '...' 형태의 m3u8 주소 매칭
                                        var match = content.match(/file\s*:\s*["']([^"']+\.m3u8[^"']*)["']|source\s*:\s*["']([^"']+\.m3u8[^"']*)["']/);
                                        if (match) return match[1] || match[2];
                                    }
                                    return null;
                                })();
                                """.trimIndent()
                            ) { jsResult ->
                                val cleanUrl = jsResult?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                                if (cleanUrl != null && !isFinished) {
                                    isFinished = true
                                    println("[DaddyLiveExt] ★JS Injection으로 주소 탈취 성공: $cleanUrl")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(cleanUrl)
                                }
                            }
                        }
                    }
                }

                // 브라우저와 동일한 수준의 헤더 세트 주입 (중요)
                val browserHeaders = mapOf(
                    "Referer" to "https://dlhd.link/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Cache-Control" to "max-age=0"
                )
                
                println("[DaddyLiveExt] 웹뷰 분석 시작: $targetUrl")
                webView.loadUrl(targetUrl, browserHeaders)

                // 타임아웃 25초
                handler.postDelayed({
                    if (!isFinished && cont.isActive) {
                        isFinished = true
                        webView.destroy()
                        println("[DaddyLiveExt] 분석 타임아웃 (25s)")
                        cont.resume(null)
                    }
                }, 25000)

            } catch (e: Exception) {
                println("[DaddyLiveExt] 치명적 오류: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
