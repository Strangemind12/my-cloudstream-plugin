/**
 * DaddyLiveExtractor v4.5 (Header Snatcher)
 * - [핵심] Header Snatching: WebView가 생성한 Authorization 및 X-Key 헤더를 실시간 복제
 * - [핵심] 재생 보장: 웹뷰가 통과한 보안 검증 결과(헤더)를 플레이어에 그대로 덮어씌움
 * - [Fix] mono.css 발견 시에만 종료하여 가짜 주소에 속지 않도록 설계
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
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v4.5 헤더 스내칭 엔진 가동")

        coroutineScope {
            links.amap { (name, link) ->
                val resultData = runWebViewHeaderSnatcher(name, link)
                if (resultData != null) {
                    val (finalUrl, snatchedHeaders) = resultData
                    println("[DaddyLiveExt] ★인증 헤더 확보 성공: $finalUrl")
                    
                    callback(newExtractorLink(name, name, finalUrl, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "https://lefttoplay.xyz/"
                        this.headers = snatchedHeaders // 웹뷰에서 훔친 헤더들을 플레이어에 주입
                    })
                }
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

                        // 1. [사용자 요청 핵심] dvalna.ru의 mono.css 혹은 키 주소 감지
                        val isMono = lower.contains("mono.css")
                        val isKey = lower.contains("/key/") && lower.contains("dvalna.ru")
                        
                        // 2. 인증 헤더(Authorization)가 포함되어 있는지 확인
                        val hasAuth = reqHeaders.containsKey("Authorization") || reqHeaders.containsKey("authorization")

                        if ((isMono || isKey) && hasAuth && !isFinished) {
                            println("[DaddyLiveExt] [$nameTag] ★진짜 인증 헤더를 낚아챘습니다!")
                            
                            // Fiddler에서 보신 그 모든 헤더들을 그대로 복제합니다.
                            val headersToSteal = mutableMapOf<String, String>()
                            reqHeaders.forEach { (k, v) -> headersToSteal[k] = v }
                            
                            // Referer와 Origin은 lefttoplay.xyz로 확실히 고정 (서버 검증 통과용)
                            headersToSteal["Referer"] = "https://lefttoplay.xyz/"
                            headersToSteal["Origin"] = "https://lefttoplay.xyz"
                            
                            // 만약 키 주소에서 헤더를 땄다면, 영상 주소는 mono.css로 추정하여 반환
                            val finalUrl = if (isMono) reqUrl else {
                                // 키 주소를 통해 유추하거나 이전에 조립한 mono.css 주소 사용
                                reqUrl.replace("/key/", "/").replace(Regex("/\\d+$"), "/mono.css")
                            }

                            isFinished = true
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(Pair(finalUrl, headersToSteal))
                            return super.shouldInterceptRequest(view, request)
                        }

                        // 3. lovecdn.ru 백업용 (mono.css가 안 나올 경우 대비)
                        if (lower.contains("lovecdn.ru") && lower.contains(".m3u8") && !isFinished) {
                            val backupHeaders = mutableMapOf<String, String>()
                            reqHeaders.forEach { (k, v) -> backupHeaders[k] = v }
                            capturedLoveCdn = Pair(reqUrl, backupHeaders)
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                webView.loadUrl(targetUrl, mapOf("Referer" to "https://dlhd.link/"))

                // 35초간 끈질기게 인증 정보를 기다립니다.
                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        webView.destroy()
                        if (capturedLoveCdn != null) {
                            println("[DaddyLiveExt] [$nameTag] mono.css 인증 실패, lovecdn으로 후퇴")
                            cont.resume(capturedLoveCdn)
                        } else {
                            cont.resume(null)
                        }
                    } 
                }, 35000)
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
}
