/**
 * DaddyLiveExtractor v1.6
 * - [Fix] 존재하지 않는 'webViewLinkInspector' 제거 및 직접 WebView 인터셉터 구현
 * - [Fix] 패키지명을 폴더 구조에 맞춰 com.DaddyLive로 수정
 * - [Debug] 가로채기 성공 시 탐지된 실제 m3u8 주소를 로그에 출력
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
        println("[DaddyLiveExt] 분석 시작 (v1.6)")
        
        links?.forEach { (name, link) ->
            val intercepted = runWebViewInterceptor(link, name)
            intercepted?.let { callback(it) }
        } ?: runWebViewInterceptor(url, this.name)?.let { callback(it) }
    }

    private suspend fun runWebViewInterceptor(
        url: String, 
        sourceName: String
    ): ExtractorLink? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                // Cloudstream 앱 컨텍스트 획득
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                var isFinished = false

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val requestUrl = request.url.toString().lowercase()
                        
                        // 감시 패턴: .m3u8, .mpd, wss 등 (v1.5의 로직 유지)
                        val isMedia = requestUrl.contains(".m3u8") || requestUrl.contains(".mpd") || requestUrl.contains("playlist")
                        val isStreamServer = requestUrl.contains("mizhls") || requestUrl.contains("newkso")
                        val isFake = requestUrl.contains("topembed.pw")

                        if ((isMedia || isStreamServer) && !isFake && !isFinished) {
                            val finalUrl = request.url.toString()
                            println("[DaddyLiveExt] ★실제 주소 발견: $finalUrl")
                            
                            isFinished = true
                            handler.post { 
                                webView.stopLoading()
                                webView.destroy() 
                            }
                            
                            if (cont.isActive) {
                                val extractorLink = newExtractorLink(
                                    sourceName, sourceName, finalUrl,
                                    type = if (finalUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                                ) {
                                    val uri = URL(finalUrl)
                                    this.referer = "${uri.protocol}://${uri.host}/"
                                    this.headers = mapOf("User-Agent" to userAgent, "Origin" to "${uri.protocol}://${uri.host}")
                                }
                                cont.resume(extractorLink)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                println("[DaddyLiveExt] 웹뷰 로드 시작: $url")
                webView.loadUrl(url)

                // 20초 후 타임아웃 처리
                handler.postDelayed({
                    if (!isFinished && cont.isActive) {
                        isFinished = true
                        webView.destroy()
                        println("[DaddyLiveExt] 웹뷰 타임아웃")
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
