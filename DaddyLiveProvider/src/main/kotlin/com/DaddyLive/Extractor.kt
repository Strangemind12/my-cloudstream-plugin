/**
 * DaddyLiveExtractor v2.8
 * - [Fix] 'pmap'을 표준 'amap'으로 교체하여 빌드 에러 해결
 * - [Fix] 'view' 참조 에러 수정 및 코루틴 호출 구조 정상화
 * - [Fix] 화질 재조립(1080p/720p) 로직 포함
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
        
        println("[DaddyLiveExt] v2.8 고속 병렬 추출 시작")
        
        // [Fix] pmap 대신 amap 사용 (Cloudstream 표준 병렬 처리)
        links.amap { (name, link) ->
            try {
                // 1. HTTP 직접 추출 시도
                var result = extractDirectly(link)
                
                // 2. 실패 시 웹뷰 인터셉터 가동
                if (result == null) {
                    result = runWebViewInterceptor(link)
                }

                if (result != null) {
                    processAndAddLink(name, result, callback)
                }
            } catch (e: Exception) {
                println("[DaddyLiveExt] $name 추출 오류: ${e.message}")
            }
        }
    }

    private suspend fun extractDirectly(targetUrl: String): String? {
        return try {
            val response = app.get(targetUrl, headers = mapOf("Referer" to "$mainUrl/")).text
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response)?.groupValues?.get(1) ?: return null
            
            val iframeContent = app.get(iframeSrc, headers = mapOf("Referer" to targetUrl)).text
            val m3u8Match = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(iframeContent)
            m3u8Match?.groupValues?.get(1)
        } catch (e: Exception) { null }
    }

    private suspend fun processAndAddLink(name: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val cookies = CookieManager.getInstance().getCookie(result)
        
        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        fun addSource(n: String, u: String, q: Int) {
            callback(newExtractorLink(n, name, u, type = ExtractorLinkType.M3U8) {
                this.quality = q
                this.referer = fixedReferer
                this.headers = mutableMapOf("User-Agent" to userAgent, "Referer" to fixedReferer, "Accept" to "*/*").apply {
                    if (!cookies.isNullOrEmpty()) put("Cookie", cookies)
                }
            })
        }

        val baseQual = if(is1080) Qualities.P1080.value else if(is720) Qualities.P720.value else Qualities.Unknown.value
        addSource("$name ${if(is1080) "(1080p)" else if(is720) "(720p)" else ""}", result, baseQual)

        if (is1080) addSource("$name (720p - 재조립)", result.replace("/1080p/", "/720p/"), Qualities.P720.value)
        else if (is720) addSource("$name (1080p - 재조립)", result.replace("/720p/", "/1080p/"), Qualities.P1080.value)
    }

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
                        return super.shouldInterceptRequest(v, request)
                    }

                    // [Fix] view 참조 오류 해결 (매개변수 이름 일치)
                    override fun onPageFinished(webViewInstance: WebView?, url: String?) {
                        if (!isFinished) {
                            webViewInstance?.evaluateJavascript("(function(){var s=document.getElementsByTagName('script');for(var i=0;i<s.length;i++){var m=s[i].innerHTML.match(/file\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']/);if(m)return m[1];}return null;})();") { r ->
                                val clean = r?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                                if (clean != null && !isFinished) {
                                    isFinished = true
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(clean)
                                }
                            }
                        }
                    }
                }
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))
                handler.postDelayed({ if (!isFinished && cont.isActive) { isFinished = true; webView.destroy(); cont.resume(null) } }, 15000)
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
}
