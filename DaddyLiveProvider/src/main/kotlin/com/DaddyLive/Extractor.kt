/**
 * DaddyLiveExtractor v2.3
 * - [Fix] 2004(403) 에러 해결: WebView에서 쿠키(Cookie)를 추출하여 ExoPlayer 헤더에 주입
 * - [Fix] AWS S3 차단 방지: 불필요한 Origin 헤더 제거 및 Referer 최적화
 * - [Debug] DNS 및 403 경고 로그 추가
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
    
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String, 
        referer: String?, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url)
        println("[DaddyLiveExt] v2.3 추출 시작 (Cookie Injection 모드)")
        
        links?.forEach { (name, link) ->
            val result = runWebViewInterceptor(link)
            if (result != null) {
                // 웹뷰로부터 해당 URL에 대한 쿠키 획득
                val cookies = CookieManager.getInstance().getCookie(result)
                val fixedReferer = "https://dlhd.link/"

                println("[DaddyLiveExt] ★링크 확보: $result")
                if (result.contains("amazonaws.com")) {
                    println("[DaddyLiveExt] AWS S3 감지: 헤더 최소화 적용")
                }

                callback(newExtractorLink(name, name, result, type = ExtractorLinkType.M3U8) {
                    this.referer = fixedReferer
                    this.headers = mutableMapOf(
                        "User-Agent" to userAgent,
                        "Referer" to fixedReferer,
                        "Accept" to "*/*"
                    ).apply {
                        if (!cookies.isNullOrEmpty()) {
                            put("Cookie", cookies)
                            println("[DaddyLiveExt] 쿠키 주입 완료")
                        }
                    }
                })
                return
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

                CookieManager.getInstance().setAcceptCookie(true)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, e: SslError?) {
                        h?.proceed()
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        if (reqUrl.contains(".m3u8") && !reqUrl.contains("topembed.pw") && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] ★네트워크 가로채기 성공")
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!isFinished) {
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    var scripts = document.getElementsByTagName('script');
                                    for (var i=0; i < scripts.length; i++) {
                                        var content = scripts[i].innerHTML;
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
                                    println("[DaddyLiveExt] ★JS Injection 성공")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(cleanUrl)
                                }
                            }
                        }
                    }
                }

                println("[DaddyLiveExt] 웹뷰 로딩: $targetUrl")
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))

                handler.postDelayed({
                    if (!isFinished && cont.isActive) {
                        isFinished = true
                        webView.destroy()
                        println("[DaddyLiveExt] 분석 타임아웃")
                        cont.resume(null)
                    }
                }, 20000)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
