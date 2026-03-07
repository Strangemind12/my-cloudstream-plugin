// 버전 정보: v1.4
package com.streamed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson

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
        println("[Streamed v1.4] 디버깅 - getMainPage 호출됨: url=${request.data}, page=$page")
        
        val document = app.get(request.data).document
        val homeList = arrayListOf<SearchResponse>()
        
        println("[Streamed v1.4] 디버깅 - 메인 페이지 HTML 파싱 시작")
        
        // /watch/ 로 시작하는 a 태그 추출
        document.select("a[href^=/watch/]").forEach { element ->
            val href = element.attr("href")
            val parts = href.trimEnd('/').split("/")
            
            // URL의 마지막 부분이 숫자가 아닌 경우(메인 경기 링크)만 추출 (/watch/match-name)
            if (parts.isNotEmpty() && parts.last().toIntOrNull() == null) {
                val title = element.text().trim().ifEmpty { parts.last() }
                val url = fixUrl(href)
                
                println("[Streamed v1.4] 디버깅 - 발견된 경기 항목: title=$title, url=$url")
                
                homeList.add(newLiveSearchResponse(title, url) {
                    this.posterUrl = null
                })
            }
        }
        
        println("[Streamed v1.4] 디버깅 - getMainPage 파싱 완료, 총 ${homeList.size}개 항목 반환")
        return newHomePageResponse(request.name, homeList.distinctBy { it.url })
    }

    override suspend fun load(url: String): LoadResponse {
        println("[Streamed v1.4] 디버깅 - load 호출됨: url=$url")
        
        val document = app.get(url).document
        val title = document.select("title").text().replace("Stream Links - Streamed", "").trim()
        
        println("[Streamed v1.4] 디버깅 - 추출된 타이틀: $title")
        
        val sourceLinks = arrayListOf<String>()
        
        // 경기 링크 페이지에서 실제 영상 재생 서버(Admin 1, Admin 2 등) 링크 일괄 추출
        document.select("a[href^=/watch/]").forEach { element ->
            val href = element.attr("href")
            val parts = href.trimEnd('/').split("/")
            
            // URL의 마지막 부분이 숫자인 경우 소스 링크로 판단 (예: /watch/match-name/1)
            if (parts.isNotEmpty() && parts.last().toIntOrNull() != null) {
                val fixedUrl = fixUrl(href)
                sourceLinks.add(fixedUrl)
                println("[Streamed v1.4] 디버깅 - 발견된 재생 링크 추가됨: $fixedUrl")
            }
        }
        
        // SvelteKit 동적 렌더링 등의 이유로 정적 a 태그를 찾지 못했을 경우의 안전 장치
        if (sourceLinks.isEmpty()) {
            println("[Streamed v1.4] 디버깅 - 상세 페이지에서 분기 링크를 찾지 못해 기본 url을 바로 넘깁니다.")
            sourceLinks.add(url)
        }
        
        println("[Streamed v1.4] 디버깅 - load 파싱 완료. 앱 메인에 재생 버튼을 활성화시키기 위해 LiveStreamLoadResponse를 반환합니다.")
        
        // v1.4 핵심 변경점: 다중 소스 링크 리스트를 Json 문자열로 변환해 dataUrl에 통째로 심어 loadLinks로 전달 (DaddyLive 방식)
        return newLiveStreamLoadResponse(title, url, dataUrl = sourceLinks.toJson()) {
            this.posterUrl = null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        println("[Streamed v1.4] 디버깅 - loadLinks 호출됨 (재생 버튼 클릭됨): data=$data")
        
        // Json 배열로 넘어온 dataUrl을 다시 리스트로 복원
        val links = AppUtils.tryParseJson<List<String>>(data) ?: listOf(data)
        var isSuccess = false
        
        links.forEachIndexed { index, sourceUrl ->
            println("[Streamed v1.4] 디버깅 - Admin ${index + 1} 서버 탐색 중: $sourceUrl")
            val response = app.get(sourceUrl)
            val html = response.document.html()
            
            // 1. iframe 소스 추출 시도
            response.document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    val fixedSrc = fixUrl(src)
                    println("[Streamed v1.4] 디버깅 - iframe 발견: src=$fixedSrc")
                    loadExtractor(fixedSrc, sourceUrl, subtitleCallback, callback)
                    isSuccess = true
                }
            }
            
            // 2. 평문 m3u8 URL이 HTML 내부에 있는지 정규식으로 직접 탐색
            val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
            val m3u8Match = m3u8Regex.find(html)
            
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                val sourceName = if (links.size > 1) "Admin ${index + 1}" else this.name
                println("[Streamed v1.4] 디버깅 - m3u8 매칭 성공: name=$sourceName, url=$m3u8Url")
                
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
            }
        }
        
        if (!isSuccess) {
            println("[Streamed v1.4] 디버깅 - 모든 Admin 서버 탐색 완료. 추출된 링크가 없습니다. 동적 렌더링 우회용 WebView가 필요할 수 있습니다.")
        }
        
        return isSuccess
    }
}
