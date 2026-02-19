/**
 * DaddyLiveExtractor v2.20
 * - [Fix] 인터셉트 성공 로그에 소스 명칭(요소 타입 포함) 출력하도록 개선
 * - [Fix] mono.css 및 mizhls 등 변칙 패턴 감지 유지
 * - [Optimize] 3개씩 병렬 처리하여 타임아웃 및 자원 부족 방지
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
        println("[DaddyLiveExt] v2.20 전수 추출 가동")
        
        coroutineScope {
            links.chunked(3).forEach { chunk ->
                chunk.amap { (name, link) ->
                    // [핵심] 요소 이름(name)을 인터셉터에 전달하여 로그 식별
                    val result = runWebViewInterceptor(name, link)
                    if (result != null) {
                        processFinalResult(name, result, callback)
                    }
                }
            }
        }
    }

    private suspend fun processFinalResult(name: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val cookies = CookieManager.getInstance().getCookie(result)
        
        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        suspend fun push(n: String, u: String, q: Int) {
            println("[DaddyLiveExt] [${name}] 최종 리스트 추가 완료")
            callback(newExtractorLink(n, name, u, type = ExtractorLinkType.M3U8) {
                this.quality = q
                this.referer = fixedReferer
                this.headers = mutableMapOf(
                    "User-Agent" to userAgent,
                    "Referer" to fixedReferer,
                    "Origin" to "https://dlhd.link",
                    "Accept" to "*/*"
                ).apply {
                    if (!cookies.isNullOrEmpty()) put("Cookie", cookies)
                }
            })
        }

        val baseQual = if (is1080) Qualities.P1080.value else if (is720) Qualities.P720.value else Qualities.Unknown.value
        push(name, result, baseQual)

        // S3 도메인이 포함된 경우에만 화질 재조립 시도
        if (result.contains("amazonaws.com")) {
            if (is1080) push("$name (720p - 재조립)", result.replace("/1080p/", "/720p/"), Qualities.P720.value)
            else if (is720) push("$name (1080p - 재조립)", result.replace("/720p/", "/1080p/"), Qualities.P1080.value)
        }
    }

    private suspend fun runWebViewInterceptor(sourceName: String, targetUrl: String): String? = suspendCancellableCoroutine { cont ->
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
                        
                        // .m3u8, mono.css, mizhls 등 모든 스트림 패턴 감지
                        val isMatch = lower.contains(".m3u8") || lower.contains("mono.css") || lower.contains("mizhls")
                        
                        if (isMatch && !lower.contains("topembed.pw") && !isFinished) {
                            isFinished = true
                            // [Fix] 로그에 어떤 요소(sourceName)에서 가로챘는지 출력
                            println("[DaddyLiveExt] [${sourceName}] ★인터셉트 성공: $reqUrl")
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(webViewParam, request)
                    }

                    override fun onPageFinished(webViewParam: WebView?, url: String?) {
                        if (!isFinished) {
                            webViewParam?.evaluateJavascript(
                                "(function(){var s=document.getElementsByTagName('script');for(var i=0;i<s.length;i++){var m=s[i].innerHTML.match(/file\\s*:\\s*[\"']([^\"']+(?:\\.m3u8|mono\\.css|mizhls)[^\"']*)[\"']/);if(m)return m[1];}return null;})();"
                            ) { r ->
                                val clean = r?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                                if (clean != null && !isFinished) {
                                    isFinished = true
                                    println("[DaddyLiveExt] [${sourceName}] ★JS 추출 성공: $clean")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(clean)
                                }
                            }
                        }
                    }
                }
                println("[DaddyLiveExt] [${sourceName}] 웹뷰 로딩 시작")
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))

                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        webView.destroy()
                        cont.resume(null) 
                    } 
                }, 25000)
            } catch (e: Exception) { 
                if (cont.isActive) cont.resume(null) 
            }
        }
    }
}
