/**
 * DaddyLiveExtractor v2.14
 * - [Fix] 상위 3개 채널의 "player" 소스만 병렬 추출하여 속도 및 재생 성공률 극대화
 * - [Fix] 'webViewParam' 변수명 통일로 빌드 에러 원천 차단
 * - [Fix] S3 및 CDN 보안 통과를 위한 이중 쿠키 주입 로직 강화
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
        println("[DaddyLiveExt] v2.14 병렬 추출 프로세스 시작 (3개 동시)")
        
        coroutineScope {
            // Player 소스 3개를 동시에 웹뷰로 처리
            links.amap { (name, link) ->
                val result = runWebViewInterceptor(link)
                if (result != null) {
                    println("[DaddyLiveExt] ★데이터 획득 성공: $result")
                    processAndAddSource(name, result, callback)
                }
            }
        }
    }

    private suspend fun processAndAddSource(name: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val fixedOrigin = "https://dlhd.link"
        
        // 보안 통과를 위해 사이트 및 영상 도메인 쿠키 획득
        val cookieManager = CookieManager.getInstance()
        val siteCookies = cookieManager.getCookie("https://dlhd.link")
        val streamCookies = cookieManager.getCookie(result)
        val combinedCookies = listOfNotNull(siteCookies, streamCookies).joinToString("; ").takeIf { it.isNotBlank() }

        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        // [Fix] newExtractorLink 호출을 위해 suspend 내부 함수 사용
        suspend fun push(finalName: String, finalUrl: String, qual: Int) {
            println("[DaddyLiveExt] 리스트 추가: $finalName")
            callback(newExtractorLink(finalName, name, finalUrl, type = ExtractorLinkType.M3U8) {
                this.quality = qual
                this.referer = fixedReferer
                this.headers = mutableMapOf(
                    "User-Agent" to userAgent,
                    "Referer" to fixedReferer,
                    "Origin" to fixedOrigin,
                    "Accept" to "*/*"
                ).apply {
                    if (combinedCookies != null) put("Cookie", combinedCookies)
                }
            })
        }

        // 1. 원본 소스 추가
        val originalQual = if (is1080) Qualities.P1080.value else if (is720) Qualities.P720.value else Qualities.Unknown.value
        push("$name ${if(is1080) "(1080p)" else if(is720) "(720p)" else ""}", result, originalQual)

        // 2. 화질 교차 재조립 (1080p <-> 720p)
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
                    domStorageEnabled = true
                    userAgentString = userAgent
                    blockNetworkImage = true // 속도 향상을 위해 이미지 차단
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    
                    // [Fix] webViewParam 매개변수 사용으로 unresolved reference 에러 방지
                    override fun shouldInterceptRequest(webViewParam: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains(".m3u8") && !reqUrl.contains("topembed.pw") && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] ★네트워크 가로채기 성공")
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(webViewParam, request)
                    }

                    override fun onPageFinished(webViewParam: WebView?, url: String?) {
                        if (!isFinished) {
                            webViewParam?.evaluateJavascript(
                                "(function(){var s=document.getElementsByTagName('script');for(var i=0;i<s.length;i++){var m=s[i].innerHTML.match(/file\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']/);if(m)return m[1];}return null;})();"
                            ) { jsResult ->
                                val clean = jsResult?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                                if (clean != null && !isFinished) {
                                    isFinished = true
                                    println("[DaddyLiveExt] ★JS Injection 성공")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(clean)
                                }
                            }
                        }
                    }
                }
                println("[DaddyLiveExt] 웹뷰 분석 중: $targetUrl")
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))

                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        webView.destroy()
                        println("[DaddyLiveExt] 분석 타임아웃")
                        cont.resume(null) 
                    } 
                }, 20000)
            } catch (e: Exception) { 
                if (cont.isActive) cont.resume(null) 
            }
        }
    }
}
