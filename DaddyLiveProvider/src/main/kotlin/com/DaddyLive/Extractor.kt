/**
 * DaddyLiveExtractor v2.4
 * - [Fix] 화질 재조립: 1080p 발견 시 720p 링크 추가 생성 (반대의 경우도 포함)
 * - [Fix] 추출 링크마다 정확한 화질(quality) 정보 및 명칭 부여
 * - [Optimize] 모든 요청에 대해 순차적 추출을 보장하기 위해 return 제거
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
        println("[DaddyLiveExt] v2.4 추출 시작 (화질 재조립 모드)")
        
        links?.forEach { (name, link) ->
            val result = runWebViewInterceptor(link)
            if (result != null) {
                val fixedReferer = "https://dlhd.link/"
                val cookies = CookieManager.getInstance().getCookie(result)
                
                val is1080 = result.contains("/1080p/")
                val is720 = result.contains("/720p/")

                // 1. 발견된 원본 링크 추가
                val originalName = when {
                    is1080 -> "$name (1080p)"
                    is720 -> "$name (720p)"
                    else -> name
                }
                val originalQuality = when {
                    is1080 -> Qualities.P1080.value
                    is720 -> Qualities.P720.value
                    else -> Qualities.Unknown.value
                }

                callback(newExtractorLink(originalName, name, result, type = ExtractorLinkType.M3U8) {
                    this.quality = originalQuality
                    this.referer = fixedReferer
                    this.headers = mutableMapOf(
                        "User-Agent" to userAgent,
                        "Referer" to fixedReferer,
                        "Accept" to "*/*"
                    ).apply {
                        if (!cookies.isNullOrEmpty()) put("Cookie", cookies)
                    }
                })

                // 2. 화질 재조립 링크 추가 (1080p <-> 720p)
                if (is1080 || is720) {
                    val altUrl = if (is1080) result.replace("/1080p/", "/720p/") else result.replace("/720p/", "/1080p/")
                    val altQuality = if (is1080) Qualities.P720.value else Qualities.P1080.value
                    val altName = if (is1080) "$name (720p)" else "$name (1080p)"
                    
                    println("[DaddyLiveExt] 화질 재조립 링크 추가: $altName")
                    callback(newExtractorLink(altName, name, altUrl, type = ExtractorLinkType.M3U8) {
                        this.quality = altQuality
                        this.referer = fixedReferer
                        this.headers = mutableMapOf(
                            "User-Agent" to userAgent,
                            "Referer" to fixedReferer,
                            "Accept" to "*/*"
                        ).apply {
                            if (!cookies.isNullOrEmpty()) put("Cookie", cookies)
                        }
                    })
                }
                // 모든 채널/플레이어를 확인하기 위해 return을 제거했습니다.
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
                            println("[DaddyLiveExt] ★인터셉트 성공: $reqUrl")
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
                                    println("[DaddyLiveExt] ★JS 추출 성공")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(cleanUrl)
                                }
                            }
                        }
                    }
                }

                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))

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
