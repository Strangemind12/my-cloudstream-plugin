/**
 * DaddyLiveExtractor v2.15
 * - [Fix] 위장된 스트림 패턴(.css) 감시 추가: dvalna.ru 등의 신규 서버 대응
 * - [Fix] S3 및 위장 서버의 403 에러 방지를 위한 헤더/쿠키 로직 최적화
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
            links.amap { (name, link) ->
                val result = runWebViewInterceptor(link)
                if (result != null) {
                    processSource(name, result, callback)
                }
            }
        }
    }

    private suspend fun processSource(name: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val cookies = CookieManager.getInstance().getCookie(result)
        
        // [Fix] .css 위장 주소일 경우 강제로 M3U8 타입으로 지정하여 재생 유도
        val isM3U8 = result.contains(".m3u8") || result.contains("mono.css")
        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        suspend fun push(n: String, u: String, q: Int) {
            callback(newExtractorLink(n, name, u, type = ExtractorLinkType.M3U8) {
                this.quality = q
                this.referer = fixedReferer
                this.headers = mutableMapOf("User-Agent" to userAgent, "Referer" to fixedReferer, "Origin" to "https://dlhd.link").apply {
                    if (!cookies.isNullOrEmpty()) put("Cookie", cookies)
                }
            })
        }

        val baseQual = if(is1080) Qualities.P1080.value else if(is720) Qualities.P720.value else Qualities.Unknown.value
        push("$name ${if(is1080) "(1080p)" else if(is720) "(720p)" else ""}", result, baseQual)

        // 화질 재조립 (CSS 위장 주소는 경로 규칙이 다를 수 있어 S3인 경우에만 우선 적용)
        if (result.contains("amazonaws.com")) {
            if (is1080) push("$name (720p - 재조립)", result.replace("/1080p/", "/720p/"), Qualities.P720.value)
            else if (is720) push("$name (1080p - 재조립)", result.replace("/720p/", "/1080p/"), Qualities.P1080.value)
        }
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
                    
                    override fun shouldInterceptRequest(webViewParam: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        val lower = reqUrl.lowercase()
                        
                        // [핵심] 감시 패턴에 .css (위장 스트림) 추가
                        val isMatch = lower.contains(".m3u8") || lower.contains("mono.css") || lower.contains("mizhls")
                        
                        if (isMatch && !lower.contains("topembed.pw") && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] ★인터셉트 성공: $reqUrl")
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(webViewParam, request)
                    }
                }
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))
                handler.postDelayed({ if (!isFinished && cont.isActive) { isFinished = true; webView.destroy(); cont.resume(null) } }, 20000)
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
}
