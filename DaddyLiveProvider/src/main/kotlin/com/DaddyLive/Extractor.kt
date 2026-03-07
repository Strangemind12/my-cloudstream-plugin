/**
 * DaddyLiveExtractor v4.9 (Direct Player Bypass & Kodi Header Sync)
 * - [핵심] dlstreams.top 광고 페이지를 건너뛰고 채널 ID를 파싱하여 ksohls.ru 플레이어로 직접 진입 (iframe 병목 해결)
 * - [Fix] Kodi 애드온 참고: Referer 및 Origin을 ksohls.ru 정책에 맞게 동기화
 * - [Fix] Kodi 애드온 참고: User-Agent를 Chrome/131.0.0.0 으로 업데이트하여 봇 필터링 회피
 * - [유지] WebView 키 요청 대기 타임아웃 5초(5000ms) 및 자동 재생 해제 로직 보존
 * - [유지] 기존 isMono, isKey 검출 로직 제거 없이 호환 유지
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
    
    // Kodi 애드온에 명시된 _AUTH_UA 적용
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val playerReferer = "https://www.ksohls.ru/"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v4.9 헤더 스내칭 우회 엔진 가동 (ksohls.ru 직접 호출)")

        for ((name, link) in links) {
            println("[DaddyLiveExt] 시도 중인 원본 링크: $name -> $link")
            
            // 1. URL에서 채널 ID 파싱 후 플레이어 직링크 생성 (광고 페이지 우회)
            val idMatch = Regex("""stream-(\d+)""").find(link)
            val finalTargetUrl = if (idMatch != null) {
                "https://www.ksohls.ru/premiumtv/daddyhd.php?id=${idMatch.groupValues[1]}"
            } else {
                link
            }
            println("[DaddyLiveExt] 플레이어 직링크 우회 접속: $finalTargetUrl")

            val resultData = runWebViewHeaderSnatcher(name, finalTargetUrl)
            
            if (resultData != null) {
                val (finalUrl, snatchedHeaders) = resultData
                println("[DaddyLiveExt] ★최종 추출된 영상 URL: $finalUrl")
                
                callback(newExtractorLink(name, name, finalUrl, type = ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.referer = playerReferer
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
                var capturedBackup: Pair<String, Map<String, String>>? = null

                webView.settings.apply { 
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false 
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }
                    
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        val lower = reqUrl.lowercase()
                        val reqHeaders = request?.requestHeaders ?: mutableMapOf()

                        // 기존 및 신규 DaddyLive 헤더 인증 방식 감지
                        val isMono = lower.contains("mono.css")
                        val isKey = lower.contains("/key/") && lower.contains("dvalna.ru")
                        val isM3u8 = lower.contains(".m3u8")
                        val hasAuth = reqHeaders.containsKey("Authorization") || reqHeaders.containsKey("authorization")

                        // --- 분기 1: 완벽한 m3u8 스트림 + 인증 헤더 발견 (Kodi 정책과 일치) ---
                        if (isM3u8 && hasAuth && !isFinished) {
                            println("[DaddyLiveExt] [$nameTag] ★직통 m3u8 및 인증(Authorization) 헤더 낚아채기 성공! URL: $reqUrl")
                            
                            val headersToSteal = mutableMapOf<String, String>()
                            reqHeaders.forEach { (k, v) -> headersToSteal[k] = v }
                            headersToSteal["Referer"] = playerReferer
                            headersToSteal["Origin"] = "https://www.ksohls.ru"

                            isFinished = true
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(Pair(reqUrl, headersToSteal))
                            return super.shouldInterceptRequest(view, request)
                        }

                        // --- 분기 2: 기존 레거시 헤더 인증 방식 발견 시 (혹시 모를 서버 롤백 대비) ---
                        if ((isMono || isKey) && hasAuth && !isFinished) {
                            println("[DaddyLiveExt] [$nameTag] ★기존 레거시 헤더(Authorization)를 낚아챘습니다!")
                            
                            val headersToSteal = mutableMapOf<String, String>()
                            reqHeaders.forEach { (k, v) -> headersToSteal[k] = v }
                            headersToSteal["Referer"] = playerReferer
                            headersToSteal["Origin"] = "https://www.ksohls.ru"
                            
                            val finalUrl = if (isMono) reqUrl else {
                                reqUrl.replace("/key/", "/").replace(Regex("/\\d+$"), "/mono.css")
                            }

                            isFinished = true
                            handler.post { webView.destroy() }
                            if (cont.isActive) cont.resume(Pair(finalUrl, headersToSteal))
                            return super.shouldInterceptRequest(view, request)
                        }

                        // --- 분기 3: 백업 캡처 (Authorization이 없더라도 m3u8이면 기록) ---
                        if (isM3u8 && capturedBackup == null && !isFinished) {
                            val backupHeaders = mutableMapOf<String, String>()
                            reqHeaders.forEach { (k, v) -> backupHeaders[k] = v }
                            backupHeaders["Referer"] = playerReferer
                            backupHeaders["Origin"] = "https://www.ksohls.ru"
                            capturedBackup = Pair(reqUrl, backupHeaders)
                            println("[DaddyLiveExt] [$nameTag] 백업 m3u8 캡처 완료 대기 중...")
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                // 플레이어 서버가 요구하는 Referer를 초기 로드부터 정확히 세팅
                webView.loadUrl(targetUrl, mapOf("Referer" to playerReferer))

                handler.postDelayed({ 
                    if (!isFinished && cont.isActive) { 
                        isFinished = true
                        webView.destroy()
                        if (capturedBackup != null) {
                            println("[DaddyLiveExt] [$nameTag] 1, 2순위 추출 실패, 백업 m3u8 스트림으로 후퇴합니다.")
                            cont.resume(capturedBackup)
                        } else {
                            println("[DaddyLiveExt] [$nameTag] 추출 실패 (타임아웃 5초 - 타겟 데이터 없음)")
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
