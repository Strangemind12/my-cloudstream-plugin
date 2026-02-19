/**
 * DaddyLiveExtractor v2.13
 * - [Fix] 'Unresolved reference view' 빌드 에러 해결 (매개변수명 통일)
 * - [Fix] AWS S3 403 에러 해결: 세션 쿠키 및 Origin 헤더 주입 강화
 * - [Optimize] 병렬 처리(amap) 및 1080p/720p 상호 재조립 로직 유지
 */
package com.DaddyLive

import android.content.Context
import android.os.*
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import java.net.URL
import kotlin.coroutines.resume

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        
        coroutineScope {
            // 3개씩 병렬 처리하여 타임아웃 방지
            links.chunked(3).forEach { chunk ->
                chunk.amap { (name, link) ->
                    val result = runWebViewInterceptor(link)
                    if (result != null) {
                        processFinalLink(name, result, callback)
                    }
                }
            }
        }
    }

    private suspend fun processFinalLink(name: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val fixedOrigin = "https://dlhd.link"
        
        // S3 보안 통과를 위해 웹뷰에 저장된 모든 쿠키(세션)를 긁어옴
        val cookieManager = CookieManager.getInstance()
        val siteCookies = cookieManager.getCookie("https://dlhd.link")
        val streamCookies = cookieManager.getCookie(result)
        val combinedCookies = listOfNotNull(siteCookies, streamCookies).joinToString("; ").takeIf { it.isNotBlank() }

        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        suspend fun add(n: String, u: String, q: Int) {
            callback(newExtractorLink(n, name, u, type = ExtractorLinkType.M3U8) {
                this.quality = q
                this.referer = fixedReferer
                this.headers = mutableMapOf(
                    "User-Agent" to userAgent,
                    "Referer" to fixedReferer,
                    "Origin" to fixedOrigin,
                    "Accept" to "*/*"
                ).apply {
                    if (combinedCookies != null) {
                        put("Cookie", combinedCookies)
                    }
                }
            })
        }

        // 원본 추가
        val baseQual = if(is1080) Qualities.P1080.value else if(is720) Qualities.P720.value else Qualities.Unknown.value
        add("$name ${if(is1080) "(1080p)" else if(is720) "(720p)" else ""}", result, baseQual)

        // 화질 재조립 추가
        if (is1080) add("$name (720p - 재조립)", result.replace("/1080p/", "/720p/"), Qualities.P720.value)
        else if (is720) add("$name (1080p - 재조립)", result.replace("/720p/", "/1080p/"), Qualities.P1080.value)
    }

    private suspend fun runWebViewInterceptor(targetUrl: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                var isFinished = false

                webView.settings.apply { 
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    blockNetworkImage = true 
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    
                    // [Fix] 매개변수 이름을 webViewParam으로 명확히 하여 unresolved reference 해결
                    override fun shouldInterceptRequest(webViewParam: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains(".m3u8") && !reqUrl.contains("topembed.pw") && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] ★인터셉트 성공: $reqUrl")
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(webViewParam, request)
                    }

                    override fun onPageFinished(webViewParam: WebView?, url: String?) {
                        if (!isFinished) {
                            webViewParam?.evaluateJavascript(
                                "(function(){var s=document.getElementsByTagName('script');for(var i=0;i<s.length;i++){var m=s[i].innerHTML.match(/file\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']/);if(m)return m[1];}return null;})();"
                            ) { r ->
                                val clean = r?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                                if (clean != null && !isFinished) {
                                    isFinished = true
                                    println("[DaddyLiveExt] ★JS 추출 성공: $clean")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(clean)
                                }
                            }
                        }
                    }
                }
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))
                handler.postDelayed({ if (!isFinished && cont.isActive) { isFinished = true; webView.destroy(); cont.resume(null) } }, 20000)
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
}
