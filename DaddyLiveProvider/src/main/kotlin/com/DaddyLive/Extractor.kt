/**
 * DaddyLiveExtractor v3.2
 * - [Fix] 모든 플레이어 요소에 대해 인터셉트 성공 시 소스 명칭 로그 출력
 * - [Fix] mono.css, mizhls, m3u8 모든 스트림 패턴 감지 및 추출
 * - [Fix] 빌드 에러 방지를 위해 webViewParam 변수명 고정 및 suspend 구조 최적화
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
        println("[DaddyLiveExt] v3.2 상위 1개 채널 집중 분석 시작")
        
        coroutineScope {
            // 모든 링크(최대 6개)를 병렬로 즉시 실행
            links.amap { (name, link) ->
                println("[DaddyLiveExt] [분석시작] $name ($link)")
                val result = runWebViewInterceptor(name, link)
                if (result != null) {
                    println("[DaddyLiveExt] ★데이터 확보 성공 [$name]: $result")
                    processLinkWithHeaders(name, result, callback)
                }
            }
        }
    }

    private suspend fun processLinkWithHeaders(sourceName: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val cookies = CookieManager.getInstance().getCookie(result)
        
        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        suspend fun push(finalName: String, finalUrl: String, qual: Int) {
            println("[DaddyLiveExt] [최종추가] $finalName")
            callback(newExtractorLink(finalName, sourceName, finalUrl, type = ExtractorLinkType.M3U8) {
                this.quality = qual
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

        // 아마존 S3 도메인인 경우 화질 교차 재조립 (검증용)
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
                        
                        // [핵심] 모든 스트림 패턴 감지: .m3u8, mono.css, mizhls
                        val isStream = lower.contains(".m3u8") || lower.contains("mono.css") || lower.contains("mizhls")
                        
                        if (isStream && !lower.contains("topembed.pw") && !isFinished) {
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
                            ) { jsResult ->
                                val clean = jsResult?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
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
