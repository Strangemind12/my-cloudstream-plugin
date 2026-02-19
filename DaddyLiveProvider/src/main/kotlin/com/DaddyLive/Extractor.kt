/**
 * DaddyLiveExtractor v2.2
 * - [Fix] 2004 에러(403 Forbidden) 해결: Referer를 원본 사이트로 고정 및 Origin 헤더 추가
 * - [Fix] JS Injection 로직 정교화 (file/source 패턴 매칭 강화)
 * - [Debug] 추출된 m3u8 주소와 함께 적용된 헤더 정보를 로그에 출력
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
        println("[DaddyLiveExt] v2.2 추출 프로세스 시작")
        
        links?.forEach { (name, link) ->
            val result = runWebViewInterceptor(link)
            if (result != null) {
                // [핵심] 403 에러 방지를 위한 헤더 재설정
                // Referer는 영상 서버(S3) 주소가 아닌, 원본 사이트(dlhd.link)여야 함
                val fixedReferer = "https://dlhd.link/"
                val fixedOrigin = "https://dlhd.link"

                println("[DaddyLiveExt] ★확보된 링크: $result")
                println("[DaddyLiveExt] 적용 헤더: Referer=$fixedReferer, Origin=$fixedOrigin")

                callback(newExtractorLink(name, name, result, type = ExtractorLinkType.M3U8) {
                    this.referer = fixedReferer
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to fixedOrigin,
                        "Referer" to fixedReferer
                    )
                })
                return // 성공 시 즉시 종료
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
                        val lower = reqUrl.lowercase()
                        
                        // m3u8 감시 (topembed.pw와 같은 낚시 주소 제외)
                        if (lower.contains(".m3u8") && !lower.contains("topembed.pw") && !isFinished) {
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

                val browserHeaders = mapOf("Referer" to "https://dlhd.link/")
                println("[DaddyLiveExt] 웹뷰 로딩: $targetUrl")
                webView.loadUrl(targetUrl, browserHeaders)

                handler.postDelayed({
                    if (!isFinished && cont.isActive) {
                        isFinished = true
                        webView.destroy()
                        cont.resume(null)
                    }
                }, 20000)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
