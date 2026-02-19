/**
 * DaddyLiveExtractor v1.8
 * - [Fix] SSL 인증서 에러 무시 로직(onReceivedSslError) 추가하여 페이지 로딩 중단 방지
 * - [Fix] 패키지명 com.DaddyLive 유지
 * - [Debug] SSL 에러 발생 시 로그 출력 및 강제 진행 상태 기록
 */
package com.DaddyLive

import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.SslErrorHandler
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
        println("[DaddyLiveExt] v1.8 분석 시작 (SSL Bypass 적용)")
        
        links?.forEach { (name, link) ->
            extractAndCallback(link, name, callback)
        } ?: extractAndCallback(url, this.name, callback)
    }

    private suspend fun extractAndCallback(
        url: String, 
        sourceName: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        val interceptedUrl = runWebViewInterceptor(url)
        
        if (interceptedUrl != null) {
            println("[DaddyLiveExt] ★최종 주소 확보: $interceptedUrl")
            
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

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    // 혼합 콘텐츠 허용
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webViewClient = object : WebViewClient() {
                    // [핵심] SSL 인증서 에러 발생 시 강제로 진행
                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        println("[DaddyLiveExt] SSL 에러 무시 및 진행: ${error?.url}")
                        handler?.proceed() 
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val requestUrl = request.url.toString()
                        val lowerUrl = requestUrl.lowercase()
                        
                        // 감시 패턴
                        val isMedia = lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") || lowerUrl.contains("playlist")
                        val isStreamServer = lowerUrl.contains("mizhls") || lowerUrl.contains("newkso")
                        val isFake = lowerUrl.contains("topembed.pw")

                        if ((isMedia || isStreamServer) && !isFake && !isFinished) {
                            isFinished = true
                            println("[DaddyLiveExt] ★인터셉트 성공: $requestUrl")
                            
                            handler.post { 
                                webView.stopLoading()
                                webView.destroy() 
                            }
                            if (cont.isActive) cont.resume(requestUrl)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        println("[DaddyLiveExt] 페이지 로딩 완료: $url")
                        super.onPageFinished(view, url)
                    }
                }

                println("[DaddyLiveExt] 웹뷰 로딩 시도 (SSL Bypass): $targetUrl")
                webView.loadUrl(targetUrl)

                // 타임아웃 30초로 연장 (SSL 핸드셰이크 지연 고려)
                handler.postDelayed({
                    if (!isFinished && cont.isActive) {
                        isFinished = true
                        webView.destroy()
                        println("[DaddyLiveExt] 웹뷰 분석 타임아웃 (30s)")
                        cont.resume(null)
                    }
                }, 30000)

            } catch (e: Exception) {
                println("[DaddyLiveExt] 오류 발생: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
