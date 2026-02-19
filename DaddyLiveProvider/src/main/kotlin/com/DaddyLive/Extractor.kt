/**
 * DaddyLiveExtractor v2.7
 * - [Reassemble] 원본 코드의 고속 구조 + 현재 발견된 S3 추출 로직 결합
 * - [Optimize] pmap(병렬 맵)을 사용하여 30개 링크를 수 초 내에 추출 (웹뷰 최소화)
 * - [Fix] 1080p <-> 720p 화질 상호 재조립 및 추가
 */
package com.DaddyLive

import android.content.Context
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

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        
        // [핵심] pmap을 사용하여 30개의 HTTP 요청을 동시에 날림 (압도적 속도)
        links.pmap { (name, link) ->
            try {
                val result = extractDirectly(link) ?: runWebViewInterceptor(link)
                if (result != null) {
                    processAndAddLink(name, result, callback)
                }
            } catch (e: Exception) {
                println("[DaddyLiveExt] $name 추출 실패: ${e.message}")
            }
        }
    }

    // [v2.7 추가] 웹뷰 없이 소스 코드에서 주소를 바로 뽑아내는 고속 함수
    private suspend fun extractDirectly(targetUrl: String): String? {
        return try {
            val response = app.get(targetUrl, headers = mapOf("Referer" to "$mainUrl/")).text
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response)?.groupValues?.get(1) ?: return null
            
            // iframe 페이지 소스 가져오기
            val iframeContent = app.get(iframeSrc, headers = mapOf("Referer" to targetUrl)).text
            // 소스 내 m3u8 주소 정규식 추출
            val m3u8Match = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(iframeContent)
            m3u8Match?.groupValues?.get(1)
        } catch (e: Exception) { null }
    }

    private suspend fun processAndAddLink(name: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val cookies = CookieManager.getInstance().getCookie(result)
        
        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        fun add(n: String, u: String, q: Int) {
            callback(newExtractorLink(n, name, u, type = ExtractorLinkType.M3U8) {
                this.quality = q
                this.referer = fixedReferer
                this.headers = mutableMapOf("User-Agent" to userAgent, "Referer" to fixedReferer, "Accept" to "*/*").apply {
                    if (!cookies.isNullOrEmpty()) put("Cookie", cookies)
                }
            })
        }

        // 원본 및 재조립 링크 추가
        val baseQual = if(is1080) Qualities.P1080.value else if(is720) Qualities.P720.value else Qualities.Unknown.value
        add("$name ${if(is1080) "(1080p)" else if(is720) "(720p)" else ""}", result, baseQual)

        if (is1080) add("$name (720p - 재조립)", result.replace("/1080p/", "/720p/"), Qualities.P720.value)
        else if (is720) add("$name (1080p - 재조립)", result.replace("/720p/", "/1080p/"), Qualities.P1080.value)
    }

    // 최후의 수단: 직접 추출 실패 시에만 실행 (기존 v2.6 로직 유지)
    private suspend fun runWebViewInterceptor(targetUrl: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                var isFinished = false
                webView.settings.apply { javaScriptEnabled = true; userAgentString = userAgent; blockNetworkImage = true }
                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        if (reqUrl.contains(".m3u8") && !reqUrl.contains("topembed.pw") && !isFinished) {
                            isFinished = true
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))
                handler.postDelayed({ if (!isFinished && cont.isActive) { isFinished = true; webView.destroy(); cont.resume(null) } }, 15000)
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
}
