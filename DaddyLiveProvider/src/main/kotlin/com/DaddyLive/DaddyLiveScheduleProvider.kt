/**
 * DaddyLiveExtractor v1.7
 * - [Fix] 'newExtractorLink' 호출 위치를 WebView 콜백 밖으로 이동하여 빌드 에러 해결
 * - [Fix] 패키지명을 폴더 구조에 맞게 com.DaddyLive로 확정
 * - [Refactor] WebViewInterceptor는 URL 문자열만 추출하도록 경량화
 */
package com.DaddyLive

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URL
import kotlin.coroutines.resume

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url)
        println("[DaddyLiveExt] 분석 시작 (v1.7)")
        
        links?.forEach { (name, link) ->
            extractAndCallback(link, name, callback)
        } ?: extractAndCallback(url, this.name, callback)
    }

    private suspend fun extractAndCallback(
        url: String, 
        sourceName: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. 웹뷰를 통해 주소만 가로챔 (일반 문자열 반환)
        val interceptedUrl = runWebViewInterceptor(url)
        
        if (interceptedUrl != null) {
            println("[DaddyLiveExt] ★실제 주소 발견: $interceptedUrl")
            
            // 2. 코루틴 안전 구역(suspend 함수 내부)에서 ExtractorLink 생성
            val extractorLink = newExtractorLink(
                sourceName,
                sourceName,
                interceptedUrl,
                type = if (interceptedUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
            ) {
                val uri = URL(interceptedUrl)
                this.referer = "${uri.protocol}://${uri.host}/"
                this.headers = mapOf(
                    "User-Agent" to userAgent,
                    "Origin" to "${uri.protocol}://${uri.host}"
                )
            }
            callback(extractorLink)
        }
    }

    private suspend fun runWebViewInterceptor(targetUrl: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                var isFinished = false

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = userAgent

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val requestUrl = request.url.toString()
                        val lowerUrl = requestUrl.lowercase()
                        
                        // 감시 패턴 유지
                        val isMedia = lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") || lowerUrl.contains("playlist")
                        val isStreamServer = lowerUrl.contains("mizhls") || lowerUrl.contains("newkso")
                        val isFake = lowerUrl.contains("topembed.pw")

                        if ((isMedia || isStreamServer) && !isFake && !isFinished) {
                            isFinished = true
                            handler.post { 
                                webView.stopLoading()
                                webView.destroy() 
                            }
                            if (cont.isActive) cont.resume(requestUrl) // URL 문자열만 전달하고 코루틴 재개
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                println("[DaddyLiveExt] 웹뷰 로딩 시작: $targetUrl")
                webView.loadUrl(targetUrl)

                // 20초 타임아웃
                handler.postDelayed({
                    if (!isFinished && cont.isActive) {
                        isFinished = true
                        webView.destroy()
                        cont.resume(null)
                    }
                }, 20000)

            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
