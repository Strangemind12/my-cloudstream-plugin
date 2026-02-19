/**
 * DaddyLiveExtractor v3.5 (Extreme Debug Version)
 * - [Debug] 필터링 0%: 웹뷰가 요청하는 모든 URL을 로그캣에 출력합니다.
 * - [Debug] 리다이렉트 추적: 모든 네트워크 요청을 [WebViewRequest] 태그로 로깅합니다.
 * - [Fix] mono.css가 발견될 때까지 절대 종료하지 않고 40초간 대기합니다.
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
        println("[DaddyLiveExt] v3.5 디버그 모드 시작 - 모든 URL을 감시합니다.")
        
        coroutineScope {
            links.amap { (name, link) ->
                val result = runWebViewInterceptor(name, link)
                if (result != null) {
                    processLink(name, result, callback)
                }
            }
        }
    }

    private suspend fun processLink(sourceName: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        callback(newExtractorLink(sourceName, sourceName, result, type = ExtractorLinkType.M3U8) {
            this.quality = Qualities.Unknown.value
            this.referer = fixedReferer
            this.headers = mapOf("User-Agent" to userAgent, "Referer" to fixedReferer, "Origin" to "https://dlhd.link")
        })
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
                    databaseEnabled = true
                    userAgentString = userAgent
                    // 리다이렉트 및 보안 통과를 위해 모든 보안 옵션 완화
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                // 쿠키 허용 설정 강화
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }

                    // [핵심] 필터 없이 모든 요청을 로그캣에 찍습니다.
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        // Fiddler처럼 모든 네트워크 요청을 로깅
                        println("[WebViewRequest] [$nameTag] -> $reqUrl")

                        // mono.css를 발견하면 로그에 별표와 함께 기록하고 즉시 반환 준비
                        if (reqUrl.contains("mono.css", ignoreCase = true) && !isFinished) {
                            println("[DaddyLiveExt] ★★★ mono.css 발견 성공: $reqUrl")
                            isFinished = true
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                        }
                        
                        // lovecdn.ru도 발견 시 로깅 강화
                        if (reqUrl.contains("lovecdn.ru") && !isFinished) {
                            println("[DaddyLiveExt] ★★★ lovecdn.ru 발견: $reqUrl")
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        println("[WebViewStatus] [$nameTag] 페이지 로드 완료: $url")
                    }
                }
                
                println("[DaddyLiveExt] [$nameTag] 분석 시작 (타겟: $targetUrl)")
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))

                // 분석 시간을 40초로 대폭 늘려 리다이렉트를 끝까지 기다립니다.
                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        webView.destroy()
                        println("[DaddyLiveExt] [$nameTag] 40초 경과로 종료 (발견 못 함)")
                        cont.resume(null) 
                    } 
                }, 40000)
            } catch (e: Exception) { 
                println("[DaddyLiveExt] 에러 발생: ${e.message}")
                if (cont.isActive) cont.resume(null) 
            }
        }
    }
}
