/**
 * DaddyLiveExtractor v2.11
 * - [Version] v2.11
 * - [Fix] 'processLink' 및 내부 함수에 'suspend' 추가하여 빌드 에러(Suspend function call) 해결
 * - [Fix] 1080p/720p 화질 자동 재조립 및 쿠키 주입 로직 유지
 * - [Optimize] 병렬 웹뷰 실행(chunked) 및 디버깅 로그 강화
 */
package com.DaddyLive

import android.content.Context
import android.net.http.SslError
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
        println("[DaddyLiveExt] v2.11 추출 프로세스 가동 (병렬 3개씩)")
        
        coroutineScope {
            links.chunked(3).forEach { chunk ->
                chunk.map { (name, link) ->
                    async {
                        println("[DaddyLiveExt] 분석 대기 중: $link")
                        val result = runWebViewInterceptor(link)
                        if (result != null) {
                            println("[DaddyLiveExt] ★데이터 확보: $link -> $result")
                            processLink(name, result, callback)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    // [Fix] newExtractorLink 호출을 위해 suspend 키워드 추가
    private suspend fun processLink(name: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val cookies = CookieManager.getInstance().getCookie(result)
        
        val is1080 = result.contains("/1080p/")
        val is720 = result.contains("/720p/")

        // [Fix] 내부 함수도 suspend로 선언하여 문법 오류 해결
        suspend fun pushLink(n: String, u: String, q: Int) {
            println("[DaddyLiveExt] 소스 추가 시도: $n")
            callback(newExtractorLink(n, name, u, type = ExtractorLinkType.M3U8) {
                this.quality = q
                this.referer = fixedReferer
                this.headers = mutableMapOf("User-Agent" to userAgent, "Referer" to fixedReferer).apply {
                    if (!cookies.isNullOrEmpty()) {
                        put("Cookie", cookies)
                    }
                }
            })
        }

        // 1. 발견된 원본 추가
        val originalQual = if (is1080) Qualities.P1080.value else if (is720) Qualities.P720.value else Qualities.Unknown.value
        val originalName = "$name ${if(is1080) "(1080p)" else if(is720) "(720p)" else ""}"
        pushLink(originalName, result, originalQual)

        // 2. 화질 재조립 추가 (1080p <-> 720p)
        if (is1080) {
            pushLink("$name (720p - 재조립)", result.replace("/1080p/", "/720p/"), Qualities.P720.value)
        } else if (is720) {
            pushLink("$name (1080p - 재조립)", result.replace("/720p/", "/1080p/"), Qualities.P1080.value)
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
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { 
                        h?.proceed() 
                    }
                    
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        if (reqUrl.contains(".m3u8") && !reqUrl.contains("topembed.pw") && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] ★인터셉트 성공!")
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))
                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        webView.destroy()
                        println("[DaddyLiveExt] 분석 타임아웃: $targetUrl")
                        cont.resume(null) 
                    } 
                }, 20000)
            } catch (e: Exception) { 
                println("[DaddyLiveExt] 웹뷰 생성 오류: ${e.message}")
                if (cont.isActive) cont.resume(null) 
            }
        }
    }
}
