// 버전 정보: v2.1
package com.streamed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.AcraApplication
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class StreamedProvider : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Live Sports"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("[Streamed v2.1] 디버깅 - getMainPage 호출: ${request.data}")
        // v2.1 변경: CloudflareKiller 적용
        val document = app.get(request.data, interceptor = CloudflareKiller()).document
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
        println("[Streamed v2.1] 디버깅 - load 호출: $url")
        // v2.1 변경: CloudflareKiller 적용
        val document = app.get(url, interceptor = CloudflareKiller()).document
        val title = document.select("title").text().replace("Stream Links - Streamed", "").trim()
        val sourceLinks = arrayListOf<String>()
        val baseUrl = url.trimEnd('/')
        
        sourceLinks.add("$baseUrl/admin/1")
        sourceLinks.add("$baseUrl/admin/2")
        sourceLinks.add("$baseUrl/delta/1")
        sourceLinks.add("$baseUrl/echo/1")
        
        return newLiveStreamLoadResponse(title, url, dataUrl = sourceLinks.toJson())
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        println("[Streamed v2.1] 디버깅 - loadLinks 시작")
        val links = AppUtils.tryParseJson<List<String>>(data) ?: listOf(data)
        var isSuccess = false
        
        for (sourceUrl in links) {
            println("[Streamed v2.1] 디버깅 - 채널 탐색 시도: $sourceUrl")
            
            // 1단계: CloudflareKiller를 통해 CF 통행증 획득 및 iframe 추출
            val response = try {
                app.get(sourceUrl, interceptor = CloudflareKiller())
            } catch (e: Exception) {
                println("[Streamed v2.1] 디버깅 - 메인 페이지 우회 에러: ${e.message}")
                continue
            }
            
            val doc = response.document
            var targetUrl = sourceUrl
            
            val iframe = doc.select("iframe").firstOrNull()
            if (iframe != null) {
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isNotBlank()) {
                    targetUrl = fixUrl(src)
                    println("[Streamed v2.1] 디버깅 - Iframe 소스 발견: $targetUrl")
                    
                    // 외부 플레이어 도메인일 수 있으므로 해당 주소로도 CF Killer 선행 적용
                    try {
                        app.get(targetUrl, interceptor = CloudflareKiller())
                    } catch (e: Exception) {
                        println("[Streamed v2.1] 디버깅 - Iframe CF 우회 에러 무시 및 계속 진행: ${e.message}")
                    }
                }
            }

            // 2단계: CF 쿠키가 확보된 상태에서 플레이어 주소로 네이티브 웹뷰 구동
            val m3u8Url = captureM3u8WithWebView(targetUrl)
            
            if (!m3u8Url.isNullOrEmpty()) {
                println("[Streamed v2.1] 디버깅 - 최종 영상 링크 가로채기 성공: $m3u8Url")
                callback(newExtractorLink(this.name, this.name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "https://embedme.top/"
                })
                isSuccess = true
                break
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
                
                // CookieManager 동기화 (CloudflareKiller가 저장한 쿠키를 웹뷰에 적용)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                CookieManager.getInstance().flush()
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = chromeUA
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                // 타임아웃 5초 유지
                val timeoutRunnable = Runnable {
                    if (cont.isActive) {
                        println("[Streamed v2.1] 디버깅 - 웹뷰 타임아웃(5초): $url")
                        webView.stopLoading(); webView.destroy(); cont.resume(null)
                    }
                }
                handler.postDelayed(timeoutRunnable, 5000L)

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) { h?.proceed() }

                    // 크롬 개발자 도구의 '네트워크(Network)' 탭 기능 수행
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        if (reqUrl.contains(".m3u8") || reqUrl.contains("modifiles.fans")) {
                            println("[Streamed v2.1] 디버깅 - m3u8 주소 추출 성공: $reqUrl")
                            handler.removeCallbacks(timeoutRunnable)
                            handler.post { if (cont.isActive) { webView.destroy(); cont.resume(reqUrl) } }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val clickScript = """
                            var itv = setInterval(function() {
                                var btns = document.querySelectorAll('video, .play-btn, .plyr__control--overlaid, button');
                                btns.forEach(function(b) { 
                                    try { b.click(); if(b.play) b.play(); } catch(e){} 
                                });
                            }, 200);
                            setTimeout(function() { clearInterval(itv); }, 4500);
                        """.trimIndent()
                        view?.evaluateJavascript(clickScript, null)
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            println("[Streamed v2.1] 디버깅 - 웹뷰 로드 오류: ${error?.errorCode} ${error?.description}")
                        }
                    }
                }

                println("[Streamed v2.1] 디버깅 - 웹뷰 플레이어 로드 시작: $url")
                val extraHeaders = mapOf(
                    "Referer" to "https://streamed.pk/",
                    "Sec-Ch-Ua" to "\"Chromium\";v=\"122\", \"Google Chrome\";v=\"122\"",
                    "Sec-Ch-Ua-Platform" to "\"Windows\""
                )
                webView.loadUrl(url, extraHeaders)
            } catch (e: Exception) {
                println("[Streamed v2.1] 디버깅 - 웹뷰 초기화 실패: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
