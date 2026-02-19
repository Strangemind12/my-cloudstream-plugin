/**
 * DaddyLiveExtractor v3.7 (Node Sniper)
 * - [Fix] server_lookup 감지 시 해당 도메인(dvalna.ru) 추적 로깅 강화
 * - [Fix] 종료 조건: mono.css 발견 시 최우선 종료, lovecdn은 백업 보관 후 계속 대기
 * - [Strict] amazonaws.com(S3) 발견 시 가로채지 않고 무시 (403 방지)
 * - [Debug] 인터셉트 성공 및 소스 확정 단계별 식별 로그 출력
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
        println("[DaddyLiveExt] v3.7 정밀 추출 엔진 시작")
        
        coroutineScope {
            links.amap { (name, link) ->
                println("[DaddyLiveExt] [분석시작] $name")
                val result = runWebViewInterceptor(name, link)
                if (result != null) {
                    processFinalLink(name, result, callback)
                }
            }
        }
    }

    private suspend fun processFinalLink(sourceName: String, result: String, callback: (ExtractorLink) -> Unit) {
        val fixedReferer = "https://dlhd.link/"
        val cookies = CookieManager.getInstance().getCookie(result)
        
        // 최종 도메인 검증 (lovecdn.ru 또는 dvalna.ru망)
        if (result.contains("lovecdn.ru") || result.contains("dvalna.ru") || result.contains("mono.css")) {
            println("[DaddyLiveExt] ★최종 소스 확정: $result")
            callback(newExtractorLink(sourceName, sourceName, result, type = ExtractorLinkType.M3U8) {
                this.quality = Qualities.Unknown.value
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
    }

    private suspend fun runWebViewInterceptor(nameTag: String, targetUrl: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                var isFinished = false
                var backupUrl: String? = null // lovecdn.ru 보관용

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
                        
                        // 1. [사용자 분석 포인트] server_lookup 호출 시 별도 로깅
                        if (lower.contains("server_lookup")) {
                            println("[DaddyLiveExt] [Node-Sniper] server_lookup 포착: $reqUrl")
                        }

                        // 2. 미끼 주소(S3)는 철저히 무시
                        if (lower.contains("amazonaws.com") || lower.contains("topembed.pw")) {
                            return super.shouldInterceptRequest(webViewParam, request)
                        }

                        // 3. 최우선 순위: mono.css 발견 시 즉시 종료 및 반환
                        if (lower.contains("mono.css") && !isFinished) {
                            println("[DaddyLiveExt] [$nameTag] ★★★ mono.css 가로채기 성공!")
                            isFinished = true
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(reqUrl)
                            return super.shouldInterceptRequest(webViewParam, request)
                        }

                        // 4. 차선 순위: lovecdn.ru 발견 시 보관만 하고 mono.css를 위해 계속 대기
                        if (lower.contains("lovecdn.ru") && lower.contains(".m3u8") && !isFinished) {
                            backupUrl = reqUrl
                            println("[DaddyLiveExt] [$nameTag] lovecdn 포착 (mono.css 대기 지속...)")
                        }

                        return super.shouldInterceptRequest(webViewParam, request)
                    }

                    override fun onPageFinished(webViewParam: WebView?, url: String?) {
                        if (!isFinished) {
                            webViewParam?.evaluateJavascript(
                                "(function(){var s=document.getElementsByTagName('script');for(var i=0;i<s.length;i++){var m=s[i].innerHTML.match(/file\\s*:\\s*[\"']([^\"']+(?:mono\\.css)[^\"']*)[\"']/);if(m)return m[1];}return null;})();"
                            ) { jsResult ->
                                val clean = jsResult?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                                if (clean != null && !isFinished) {
                                    println("[DaddyLiveExt] [$nameTag] ★★★ JS로 mono.css 탈취 성공!")
                                    isFinished = true
                                    handler.post { webView.destroy() }
                                    if (cont.isActive) cont.resume(clean)
                                }
                            }
                        }
                    }
                }
                
                println("[DaddyLiveExt] [$nameTag] 웹뷰 로딩: $targetUrl")
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))

                // 타임아웃 35초: mono.css를 끝내 못 잡았을 때만 lovecdn 반환
                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        webView.destroy()
                        if (backupUrl != null) {
                            println("[DaddyLiveExt] [$nameTag] mono.css 미발견, lovecdn으로 최종 결정")
                            cont.resume(backupUrl)
                        } else {
                            println("[DaddyLiveExt] [$nameTag] 유효 주소 확보 실패 (타임아웃)")
                            cont.resume(null)
                        }
                    } 
                }, 35000)
            } catch (e: Exception) { 
                if (cont.isActive) cont.resume(null) 
            }
        }
    }
}
