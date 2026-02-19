/**
 * DaddyLiveExtractor v2.10
 * - [Fix] 병렬 웹뷰 실행으로 9개 경로 고속 스캔
 * - [Fix] 1080p/720p 화질 자동 교차 생성 로직 적용
 * - [Optimize] 403 에러 방지를 위해 Referer 고정 및 쿠키 주입
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
import kotlinx.coroutines.suspendCancellableCoroutine

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        
        println("[DaddyLiveExt] v2.10 병렬 추출 모드 가동")
        
        coroutineScope {
            // 한 번에 3개씩 병렬 실행하여 시스템 부하 조절
            links.chunked(3).forEach { chunk ->
                chunk.map { (name, link) ->
                    async {
                        val result = runWebViewInterceptor(link)
                        if (result != null) {
                            processLink(name, result, callback)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun processLink(name: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val cookies = CookieManager.getInstance().getCookie(result)
        
        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        fun push(n: String, u: String, q: Int) {
            callback(newExtractorLink(n, name, u, type = ExtractorLinkType.M3U8) {
                this.quality = q
                this.referer = fixedReferer
                this.headers = mutableMapOf("User-Agent" to userAgent, "Referer" to fixedReferer).apply {
                    if (!cookies.isNullOrEmpty()) put("Cookie", cookies)
                }
            })
        }

        // 원본 추가
        val baseQual = if(is1080) Qualities.P1080.value else if(is720) Qualities.P720.value else Qualities.Unknown.value
        push("$name ${if(is1080) "(1080p)" else if(is720) "(720p)" else ""}", result, baseQual)

        // 화질 재조립 (교차 추가)
        if (is1080) {
            push("$name (720p - 재조립)", result.replace("/1080p/", "/720p/"), Qualities.P720.value)
        } else if (is720) {
            push("$name (1080p - 재조립)", result.replace("/720p/", "/1080p/"), Qualities.P1080.value)
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
                    userAgentString = userAgent
                    blockNetworkImage = true 
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        if (reqUrl.contains(".m3u8") && !reqUrl.contains("topembed.pw") && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] ★인터셉트 성공")
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
