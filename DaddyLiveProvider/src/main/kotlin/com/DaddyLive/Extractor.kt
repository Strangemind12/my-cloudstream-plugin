/**
 * DaddyLiveExtractor v2.12
 * - [Fix] AWS S3 403(2004) 에러 해결: Cookie 및 Origin 헤더 주입 강화
 * - [Fix] ExoPlayer용 헤더 세트 정밀 조정 (Referer/Origin/Cookie 일치)
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
            links.chunked(3).forEach { chunk ->
                chunk.map { (name, link) ->
                    async {
                        val result = runWebViewInterceptor(link)
                        if (result != null) {
                            processLinkWithAdvancedHeaders(name, result, callback)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun processLinkWithAdvancedHeaders(name: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val fixedOrigin = "https://dlhd.link"
        // 웹뷰에서 생성된 쿠키를 해당 M3U8 주소 기준으로 획득
        val cookies = CookieManager.getInstance().getCookie(result)
        
        val isS3 = result.contains("amazonaws.com")
        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        suspend fun push(n: String, u: String, q: Int) {
            callback(newExtractorLink(n, name, u, type = ExtractorLinkType.M3U8) {
                this.quality = q
                this.referer = fixedReferer
                this.headers = mutableMapOf(
                    "User-Agent" to userAgent,
                    "Referer" to fixedReferer,
                    "Origin" to fixedOrigin,
                    "Accept" to "*/*"
                ).apply {
                    // S3 스토리지는 쿠키가 없으면 403을 뱉는 경우가 많음
                    if (!cookies.isNullOrEmpty()) put("Cookie", cookies)
                }
            })
        }

        val baseQual = if(is1080) Qualities.P1080.value else if(is720) Qualities.P720.value else Qualities.Unknown.value
        push("$name ${if(is1080) "(1080p)" else if(is720) "(720p)" else ""}", result, baseQual)

        if (is1080) push("$name (720p - 재조립)", result.replace("/1080p/", "/720p/"), Qualities.P720.value)
        else if (is720) push("$name (1080p - 재조립)", result.replace("/720p/", "/1080p/"), Qualities.P1080.value)
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
                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        if (reqUrl.contains(".m3u8") && !reqUrl.contains("topembed.pw") && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] ★인터셉트 성공: $reqUrl")
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))
                handler.postDelayed({ if (!isFinished && cont.isActive) { isFinished = true; webView.destroy(); cont.resume(null) } }, 20000)
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
}
