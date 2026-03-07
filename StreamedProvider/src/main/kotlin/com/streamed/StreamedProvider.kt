// 버전 정보: v1.9
package com.streamed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.AcraApplication
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.su"
    override var name = "Streamed"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Live Sports"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val homeList = arrayListOf<SearchResponse>()
        
        document.select("a[href^=/watch/]").forEach { element ->
            val href = element.attr("href")
            val parts = href.trimEnd('/').split("/")
            if (parts.isNotEmpty() && parts.last().toIntOrNull() == null) {
                val title = element.text().trim().ifEmpty { parts.last() }
                homeList.add(newLiveSearchResponse(title, fixUrl(href)) { this.posterUrl = null })
            }
        }
        return newHomePageResponse(request.name, homeList.distinctBy { it.url })
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("title").text().replace("Stream Links - Streamed", "").trim()
        val sourceLinks = arrayListOf<String>()
        val baseUrl = url.trimEnd('/')
        
        // v1.9 수정: 사용자가 지정한 하위 경로 순서 적용
        sourceLinks.add("$baseUrl/admin/1")
        sourceLinks.add("$baseUrl/admin/2")
        sourceLinks.add("$baseUrl/delta/1")
        sourceLinks.add("$baseUrl/echo/1")
        
        return newLiveStreamLoadResponse(title, url, dataUrl = sourceLinks.toJson())
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        println("[Streamed v1.9] 디버깅 - loadLinks 시작: $data")
        val links = AppUtils.tryParseJson<List<String>>(data) ?: listOf(data)
        var isSuccess = false
        
        for (sourceUrl in links) {
            println("[Streamed v1.9] 디버깅 - 탐색 시도: $sourceUrl")
            val m3u8Url = captureM3u8WithWebView(sourceUrl)
            
            if (!m3u8Url.isNullOrEmpty()) {
                println("[Streamed v1.9] 디버깅 - m3u8 가로채기 성공: $m3u8Url")
                callback(newExtractorLink(this.name, this.name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "https://embedme.top/" // 주요 플레이어 Referer 고정
                })
                isSuccess = true; break // 하나라도 찾으면 종료
            }
        }
        return isSuccess
    }

    private suspend fun captureM3u8WithWebView(url: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                val chromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = chromeUA
                }

                // v1.9 수정: 타임아웃 8초로 약간 여유 부여 (Cloudflare 로딩 대기)
                val timeoutRunnable = Runnable {
                    if (cont.isActive) {
                        println("[Streamed v1.9] 디버깅 - 웹뷰 타임아웃 종료: $url")
                        webView.stopLoading(); webView.destroy(); cont.resume(null)
                    }
                }
                handler.postDelayed(timeoutRunnable, 8000L)

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        // 1. m3u8 발견 시 즉시 반환
                        if (reqUrl.contains(".m3u8")) {
                            println("[Streamed v1.9] 디버깅 - 패킷 캡처 성공!")
                            handler.removeCallbacks(timeoutRunnable)
                            handler.post { if (cont.isActive) { webView.destroy(); cont.resume(reqUrl) } }
                        }
                        
                        // 2. v1.9 핵심: Iframe 소스 발견 시 웹뷰를 해당 페이지로 직접 이동 (CORS 우회)
                        if (reqUrl.contains("embedme.top") || reqUrl.contains("/player/")) {
                            if (!view?.url?.contains(reqUrl.substringBefore("?"))!!) {
                                println("[Streamed v1.9] 디버깅 - 플레이어 소스 감지, 리다이렉트 수행: $reqUrl")
                                handler.post { view.loadUrl(reqUrl) }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            println("[Streamed v1.9] 디버깅 - 웹뷰 로드 에러: ${error?.description} (${error?.errorCode})")
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // v1.9 핵심: 플레이어 내부 버튼을 0.3초 간격으로 매우 공격적으로 클릭 시도
                        val clickScript = """
                            var clickInterval = setInterval(function() {
                                var targets = document.querySelectorAll('video, .play-btn, .plyr__control--overlaid, button, [role="button"]');
                                targets.forEach(function(t) { 
                                    try { 
                                        t.click(); 
                                        if(t.play) t.play();
                                    } catch(e) {} 
                                });
                            }, 300);
                            setTimeout(function() { clearInterval(clickInterval); }, 5000);
                        """.trimIndent()
                        view?.evaluateJavascript(clickScript, null)
                    }
                }

                println("[Streamed v1.9] 디버깅 - 웹뷰 백그라운드 로드 시작: $url")
                // Cloudflare 통과를 위한 추가 헤더 주입
                val extraHeaders = mapOf(
                    "Referer" to "https://streamed.su/",
                    "Sec-Ch-Ua" to "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"",
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to "\"Windows\""
                )
                webView.loadUrl(url, extraHeaders)
            } catch (e: Exception) {
                println("[Streamed v1.9] 디버깅 - 웹뷰 초기화 치명적 오류: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
