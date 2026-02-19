/**
 * DaddyLiveExtractor v2.22
 * - [Strict] lovecdn.ru(m3u8) 및 mono.css 주소만 유효 소스로 인정
 * - [Strict] 아마존 S3(amazonaws.com) 및 기타 가짜 도메인 완전 배제
 * - [Fix] 웹뷰 종료 조건: mono.css 발견 시 즉시 종료, lovecdn 발견 시 저장 후 대기
 * - [Debug] 각 탐지 단계별 상세 식별 로그 출력
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
        println("[DaddyLiveExt] v2.22 정밀 필터링 모드 시작")
        
        coroutineScope {
            // 모든 요소를 병렬로 실행하되, 내부에서 엄격한 필터링 수행
            links.amap { (name, link) ->
                val result = runWebViewInterceptor(name, link)
                if (result != null) {
                    processStrictLink(name, result, callback)
                }
            }
        }
    }

    private suspend fun processStrictLink(sourceName: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val cookies = CookieManager.getInstance().getCookie(result)
        
        // 다시 한번 도메인 검증 (안전장치)
        val isLoveCdn = result.contains("lovecdn.ru")
        val isMonoCss = result.contains("mono.css")

        if (isLoveCdn || isMonoCss) {
            println("[DaddyLiveExt] [검증통과] $sourceName -> $result")
            callback(newExtractorLink(sourceName, sourceName, result, type = ExtractorLinkType.M3U8) {
                this.quality = Qualities.Unknown.value
                this.referer = fixedReferer
                this.headers = mutableMapOf("User-Agent" to userAgent, "Referer" to fixedReferer, "Origin" to "https://dlhd.link").apply {
                    if (!cookies.isNullOrEmpty()) put("Cookie", cookies)
                }
            })
        } else {
            println("[DaddyLiveExt] [필터링차단] 부적합 도메인 제외: $result")
        }
    }

    private suspend fun runWebViewInterceptor(nameTag: String, targetUrl: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                var isFinished = false
                var capturedLoveCdn: String? = null

                webView.settings.apply { 
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    blockNetworkImage = true // 가속을 위해 이미지 차단
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    
                    override fun shouldInterceptRequest(webViewParam: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        val lower = reqUrl.lowercase()
                        
                        // 1. 아마존 및 가짜 주소는 즉시 무시
                        if (lower.contains("amazonaws.com") || lower.contains("topembed.pw")) {
                            return super.shouldInterceptRequest(webViewParam, request)
                        }

                        // 2. mono.css 발견 시: 즉시 종료 및 반환 (최우선순위)
                        if (lower.contains("mono.css") && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] [$nameTag] ★mono.css 감지 성공: $reqUrl")
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                            return super.shouldInterceptRequest(webViewParam, request)
                        }

                        // 3. lovecdn.ru 발견 시: 일단 저장하고 계속 감시 (mono.css가 뒤에 나올 수 있음)
                        if (lower.contains("lovecdn.ru") && lower.contains(".m3u8") && !isFinished) {
                            capturedLoveCdn = reqUrl
                            println("[DaddyLiveExt] [$nameTag] lovecdn 포착 (mono.css 대기 중...): $reqUrl")
                        }

                        return super.shouldInterceptRequest(webViewParam, request)
                    }

                    override fun onPageFinished(webViewParam: WebView?, url: String?) {
                        if (!isFinished) {
                            // JS Injection으로 mono.css 강제 탐색
                            webViewParam?.evaluateJavascript(
                                "(function(){var s=document.getElementsByTagName('script');for(var i=0;i<s.length;i++){var m=s[i].innerHTML.match(/file\\s*:\\s*[\"']([^\"']+(?:mono\\.css)[^\"']*)[\"']/);if(m)return m[1];}return null;})();"
                            ) { jsResult ->
                                val clean = jsResult?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                                if (clean != null && !isFinished) {
                                    isFinished = true
                                    println("[DaddyLiveExt] [$nameTag] ★JS로 mono.css 탈취 성공: $clean")
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(clean)
                                }
                            }
                        }
                    }
                }
                
                println("[DaddyLiveExt] [$nameTag] 분석 시작")
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))

                // 타임아웃 30초: 끝까지 mono.css를 못 찾았을 경우, 차선책으로 lovecdn 반환
                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        val finalResult = capturedLoveCdn
                        webView.destroy()
                        if (finalResult != null) {
                            println("[DaddyLiveExt] [$nameTag] mono.css 미발견, 보관된 lovecdn 반환")
                            cont.resume(finalResult)
                        } else {
                            println("[DaddyLiveExt] [$nameTag] 유효 주소 없음 (타임아웃)")
                            cont.resume(null)
                        }
                    } 
                }, 30000)
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
}
