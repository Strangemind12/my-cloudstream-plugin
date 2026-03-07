// 버전 정보: v1.8
package com.streamed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
        println("[Streamed v1.8] 디버깅 - getMainPage 호출됨: url=${request.data}, page=$page")
        
        val document = app.get(request.data).document
        val homeList = arrayListOf<SearchResponse>()
        
        println("[Streamed v1.8] 디버깅 - 메인 페이지 HTML 파싱 시작")
        
        // /watch/ 로 시작하는 a 태그 추출
        document.select("a[href^=/watch/]").forEach { element ->
            val href = element.attr("href")
            val parts = href.trimEnd('/').split("/")
            
            // URL의 마지막 부분이 숫자가 아닌 경우(메인 경기 링크)만 추출
            if (parts.isNotEmpty() && parts.last().toIntOrNull() == null) {
                val title = element.text().trim().ifEmpty { parts.last() }
                val url = fixUrl(href)
                
                homeList.add(newLiveSearchResponse(title, url) {
                    this.posterUrl = null
                })
            }
        }
        
        println("[Streamed v1.8] 디버깅 - getMainPage 파싱 완료, 총 ${homeList.size}개 항목 반환")
        return newHomePageResponse(request.name, homeList.distinctBy { it.url })
    }

    override suspend fun load(url: String): LoadResponse {
        println("[Streamed v1.8] 디버깅 - load 호출됨: url=$url")
        
        val document = app.get(url).document
        val title = document.select("title").text().replace("Stream Links - Streamed", "").trim()
        
        println("[Streamed v1.8] 디버깅 - 추출된 타이틀: $title")
        
        val sourceLinks = arrayListOf<String>()
        val baseUrl = url.trimEnd('/')
        
        // 1차 시도: 정적 DOM 파싱을 통해 Admin/Delta/Echo 링크 파생 유추
        document.select("a").forEach { element ->
            val href = element.attr("href")
            if (href.isNotBlank()) {
                val absUrl = fixUrl(href)
                // 현재 URL을 기준으로 하위 숫자 디렉토리(admin, delta, echo) 검증
                if (absUrl.startsWith(baseUrl) && absUrl != baseUrl) {
                    if (absUrl.matches(Regex(""".*/(?:admin|delta|echo)/\d+/?$"""))) {
                        if (!sourceLinks.contains(absUrl)) {
                            sourceLinks.add(absUrl)
                            println("[Streamed v1.8] 디버깅 - a 태그에서 재생 링크 추출됨: $absUrl")
                        }
                    }
                }
            }
        }
        
        // CSR 특성 상 링크가 DOM에 없을 확률이 높으므로 기본 구조 강제 주입
        if (sourceLinks.isEmpty()) {
            println("[Streamed v1.8] 디버깅 - 정적 a 태그에서 파생 링크를 찾지 못해 지정된 기본 예측 URL(admin/1, admin/2, delta/1, echo/1)을 강제 생성합니다.")
            sourceLinks.add("$baseUrl/admin/1")
            sourceLinks.add("$baseUrl/admin/2")
            sourceLinks.add("$baseUrl/delta/1")
            sourceLinks.add("$baseUrl/echo/1")
        }
        
        println("[Streamed v1.8] 디버깅 - load 파싱 완료. 앱 메인에 재생 버튼을 활성화시키기 위해 LiveStreamLoadResponse를 반환합니다.")
        
        return newLiveStreamLoadResponse(title, url, dataUrl = sourceLinks.toJson()) {
            this.posterUrl = null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        println("[Streamed v1.8] 디버깅 - loadLinks 호출됨 (재생 버튼 클릭됨): data=$data")
        
        val links = AppUtils.tryParseJson<List<String>>(data) ?: listOf(data)
        var isSuccess = false
        
        for ((index, sourceUrl) in links.withIndex()) {
            println("[Streamed v1.8] 디버깅 - 채널 우회 탐색 시작: $sourceUrl")
            
            // 네이티브 WebView를 띄워 백그라운드 네트워크 감청 (m3u8 낚아채기)
            val m3u8Url = captureM3u8WithWebView(sourceUrl, mainUrl)
            
            if (!m3u8Url.isNullOrEmpty()) {
                val sourceName = if (links.size > 1) "Server ${index + 1}" else this.name
                println("[Streamed v1.8] 디버깅 - 최종 m3u8 매칭 및 캡처 성공: name=$sourceName, url=$m3u8Url")
                
                callback(
                    newExtractorLink(
                        name = sourceName,
                        source = this.name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                    }
                )
                isSuccess = true
            } else {
                println("[Streamed v1.8] 디버깅 - 채널 $sourceUrl 에서 m3u8 캡처 실패 또는 404 발생.")
            }
        }
        
        if (!isSuccess) {
            println("[Streamed v1.8] 디버깅 - 모든 서버 탐색 완료. 추출된 링크가 없습니다.")
        }
        
        return isSuccess
    }

    // 안드로이드 네이티브 WebView를 활용한 네트워크 탭 수준의 강력한 패킷 감청 로직
    private suspend fun captureM3u8WithWebView(url: String, referer: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false // 비디오 자동 재생 허용 (m3u8 빠른 로드용)
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                    
                    // v1.8 핵심 수정: 렌더링 가속 및 통신 에러 방지
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    blockNetworkImage = true // 이미지/광고 로딩 차단하여 5초 내 m3u8 탐색 가속
                }

                // 지침 엄수: 타임아웃 5초 설정 유지
                val timeoutRunnable = Runnable {
                    if (cont.isActive) {
                        println("[Streamed v1.8] 디버깅 - WebView 타임아웃 10초 도달 (강제 종료): $url")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(timeoutRunnable, 10000L)

                webView.webViewClient = object : WebViewClient() {
                    // v1.8 핵심 수정: 로그캣의 net_error -100(인증서 오류)로 인한 스트리밍 플레이어 로드 중단 방지
                    override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                        println("[Streamed v1.8] 디버깅 - SSL 에러 강제 무시 및 우회 진행: ${error?.primaryError}")
                        handler?.proceed()
                    }

                    // 크롬 개발자 도구의 '네트워크' 탭처럼 모든 리소스 요청을 가로챔
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        if (reqUrl.contains(".m3u8")) {
                            println("[Streamed v1.8] 디버깅 - 백그라운드 m3u8 가로채기 완벽 성공: $reqUrl")
                            handler.removeCallbacks(timeoutRunnable)
                            handler.post {
                                if (cont.isActive) {
                                    try { webView.destroy() } catch (e: Exception) {}
                                    cont.resume(reqUrl) // 낚아챈 m3u8 주소 즉시 반환
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    // 존재하지 않는 채널(404)에 대해 빠르게 포기하기 위함
                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame == true && errorResponse?.statusCode == 404) {
                            println("[Streamed v1.8] 디버깅 - 프레임 404 에러 감지. 타임아웃 대기 없이 조기 종료: ${request.url}")
                            handler.removeCallbacks(timeoutRunnable)
                            handler.post {
                                if (cont.isActive) {
                                    try { webView.destroy() } catch (e: Exception) {}
                                    cont.resume(null)
                                }
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // v1.8 수정: 비동기로 로드되는 플레이어 요소에 대비하여 0.5초 간격으로 재생 버튼 반복 클릭 유도
                        val clickScript = """
                            setInterval(function() {
                                var btns = document.querySelectorAll('.play-btn, .plyr__control--overlaid, [role="button"], video');
                                btns.forEach(function(btn) { try { btn.click(); } catch(e){} });
                            }, 500);
                        """.trimIndent()
                        view?.evaluateJavascript(clickScript, null)
                    }
                }
                
                println("[Streamed v1.8] 디버깅 - WebView 보이지 않는 백그라운드 렌더링 시작: $url")
                webView.loadUrl(url, mapOf("Referer" to referer))
            } catch (e: Exception) {
                println("[Streamed v1.8] 디버깅 - WebView 초기화 예외: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
