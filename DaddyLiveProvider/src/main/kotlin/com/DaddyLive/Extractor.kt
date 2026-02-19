/**
 * DaddyLiveExtractor v2.21
 * - [Fix] 로그 출력 시 어떤 요소(watch/plus/player)에서 성공했는지 명시
 * - [Fix] .m3u8, mono.css, mizhls 등 위장 스트림 패턴 전체 감지
 * - [Optimize] 병렬 처리(amap) 유지 및 webViewParam 빌드 에러 방지
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
        println("[DaddyLiveExt] v2.21 정밀 분석 프로세스 시작")
        
        coroutineScope {
            // 3개 채널씩 묶어서 병렬 실행 (자원 효율성)
            links.chunked(3).forEach { chunk ->
                chunk.amap { (name, link) ->
                    println("[DaddyLiveExt] [분석시작] $name")
                    val result = runWebViewInterceptor(name, link)
                    if (result != null) {
                        processLinkAndCallback(name, result, callback)
                    }
                }
            }
        }
    }

    private suspend fun processLinkAndCallback(sourceName: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val cookies = CookieManager.getInstance().getCookie(result)
        
        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        suspend fun push(n: String, u: String, q: Int) {
            println("[DaddyLiveExt] [성공확정] $n 추가")
            callback(newExtractorLink(n, sourceName, u, type = ExtractorLinkType.M3U8) {
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
        push(sourceName, result, baseQual)

        // 아마존 S3 도메인인 경우 화질 교차 재조립 시도
        if (result.contains("amazonaws.com")) {
            if (is1080) push("$sourceName (720p - 재조립)", result.replace("/1080p/", "/720p/"), Qualities.P720.value)
            else if (is720) push("$sourceName (1080p - 재조립)", result.replace("/720p/", "/1080p/"), Qualities.P1080.value)
        }
    }

    private suspend fun runWebViewInterceptor(nameTag: String, targetUrl: String): String? = suspendCancellableCoroutine { cont ->
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
                        
                        // 모든 스트림 패턴 감지 (.m3u8, mono.css, mizhls)
                        val isMatch = lower.contains(".m3u8") || lower.contains("mono.css") || lower.contains("mizhls")
                        
                        if (isMatch && !lower.contains("topembed.pw") && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] [인터셉트 성공] [$nameTag] -> $reqUrl")
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
                                    println("[DaddyLiveExt] [JS 추출 성공] [$nameTag] -> $clean")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(clean)
                                }
                            }
                        }
                    }
                }
                
                println("[DaddyLiveExt] [분석중] $nameTag 로딩")
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))

                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        webView.destroy()
                        println("[DaddyLiveExt] [타임아웃] $nameTag")
                        cont.resume(null) 
                    } 
                }, 25000)
            } catch (e: Exception) { 
                if (cont.isActive) cont.resume(null) 
            }
        }
    }
}
