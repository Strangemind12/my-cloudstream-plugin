/**
 * DaddyLiveExtractor v4.7 (Header Snatcher & New CDN Support)
 * - [핵심] 6개 채널 동시 요청으로 인한 서버의 연결 차단(ERR_CONNECTION_CLOSED) 방지를 위해 순차 탐색(Sequential) 적용
 * - [Fix] WebView 키 요청 대기 타임아웃 5초(5000ms) 유지
 * - [Feature] URL 토큰 인증 방식(md5=, expires=)이 적용된 새로운 CDN(sanwalyaarpya.com 등)의 직링(.m3u8) 추출 로직 추가
 */
package com.DaddyLive

import android.content.Context
import android.os.*
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlstreams.top"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v4.7 헤더 스내칭 엔진 가동 (순차 탐색 + New CDN 지원)")

        for ((name, link) in links) {
            println("[DaddyLiveExt] 시도 중인 링크: $name -> $link")
            val resultData = runWebViewHeaderSnatcher(name, link)
            
            if (resultData != null) {
                val (finalUrl, snatchedHeaders) = resultData
                println("[DaddyLiveExt] ★최종 추출된 영상 URL: $finalUrl")
                
                callback(newExtractorLink(name, name, finalUrl, type = ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://lefttoplay.xyz/"
                    this.headers = snatchedHeaders
                })
                
                break
            }
        }
    }

    private suspend fun runWebViewHeaderSnatcher(nameTag: String, targetUrl: String): Pair<String, Map<String, String>>? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                var isFinished = false
                var capturedLoveCdn: Pair<String, Map<String, String>>? = null

                webView.settings.apply { 
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        val lower = reqUrl.lowercase()
                        val reqHeaders = request?.requestHeaders ?: mutableMapOf()

                        // 1. 기존 DaddyLive 헤더 인증 방식 감지 (dvalna.ru, mono.css)
                        val isMono = lower.contains("mono.css")
                        val isKey = lower.contains("/key/") && lower.contains("dvalna.ru")
                        val hasAuth = reqHeaders.containsKey("Authorization") || reqHeaders.containsKey("authorization")

                        // 2. 신규 CDN 및 토큰 인증 방식 감지 (sanwalyaarpya.com 또는 md5 토큰이 포함된 m3u8)
                        val isNewM3u8Cdn = lower.contains(".m3u8") && (lower.contains("sanwalyaarpya.com") || lower.contains("md5="))

                        // --- 분기 1: 신규 CDN 링크 발견 시 ---
                        if (isNewM3u8Cdn && !isFinished) {
                            println("[DaddyLiveExt] [$nameTag] ★신규 CDN/토큰 형태의 m3u8 링크를 낚아챘습니다! URL: $reqUrl")
                            
                            val headersToSteal = mutableMapOf<String, String>()
                            reqHeaders.forEach { (k, v) -> headersToSteal[k] = v }
                            headersToSteal["Referer"] = "https://lefttoplay.xyz/"
                            headersToSteal["Origin"] = "https://lefttoplay.xyz"

                            isFinished = true
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(Pair(reqUrl, headersToSteal))
                            return super.shouldInterceptRequest(view, request)
                        }

                        // --- 분기 2: 기존 헤더 인증 방식 발견 시 ---
                        if ((isMono || isKey) && hasAuth && !isFinished) {
                            println("[DaddyLiveExt] [$nameTag] ★기존 헤더 인증 방식(Authorization)을 낚아챘습니다!")
                            
                            val headersToSteal = mutableMapOf<String, String>()
                            reqHeaders.forEach { (k, v) -> headersToSteal[k] = v }
                            headersToSteal["Referer"] = "https://lefttoplay.xyz/"
                            headersToSteal["Origin"] = "https://lefttoplay.xyz"
                            
                            val finalUrl = if (isMono) reqUrl else {
                                reqUrl.replace("/key/", "/").replace(Regex("/\\d+$"), "/mono.css")
                            }

                            isFinished = true
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(Pair(finalUrl, headersToSteal))
                            return super.shouldInterceptRequest(view, request)
                        }

                        // --- 분기 3: lovecdn.ru 백업용 ---
                        if (lower.contains("lovecdn.ru") && lower.contains(".m3u8") && !isFinished) {
                            val backupHeaders = mutableMapOf<String, String>()
                            reqHeaders.forEach { (k, v) -> backupHeaders[k] = v }
                            capturedLoveCdn = Pair(reqUrl, backupHeaders)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlstreams.top"))

                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        webView.destroy()
                        if (capturedLoveCdn != null) {
                            println("[DaddyLiveExt] [$nameTag] 1, 2순위 추출 실패, 백업 lovecdn으로 후퇴")
                            cont.resume(capturedLoveCdn)
                        } else {
                            println("[DaddyLiveExt] [$nameTag] 추출 실패 (타임아웃 5초)")
                            cont.resume(null)
                        }
                    } 
                }, 5000)
            } catch (e: Exception) { 
                println("[DaddyLiveExt] [$nameTag] 예외 발생: ${e.message}")
                if (cont.isActive) cont.resume(null) 
            }
        }
    }
}
