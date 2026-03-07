// 버전 정보: v1.3
package com.streamed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        println("[Streamed v1.3] 디버깅 - getMainPage 호출됨: url=${request.data}, page=$page")
        
        val document = app.get(request.data).document
        val homeList = arrayListOf<SearchResponse>()
        
        println("[Streamed v1.3] 디버깅 - 메인 페이지 HTML 파싱 시작")
        
        // /watch/ 로 시작하는 a 태그 추출
        document.select("a[href^=/watch/]").forEach { element ->
            val href = element.attr("href")
            val parts = href.trimEnd('/').split("/")
            
            // URL의 마지막 부분이 숫자가 아닌 경우(메인 경기 링크)만 추출 (/watch/match-name)
            if (parts.isNotEmpty() && parts.last().toIntOrNull() == null) {
                val title = element.text().trim().ifEmpty { parts.last() }
                val url = fixUrl(href)
                
                println("[Streamed v1.3] 디버깅 - 발견된 경기 항목: title=$title, url=$url")
                
                // v1.3 변경점: DaddyLiveScheduleProvider 규격 참고, 빌더 패턴 적용
                homeList.add(newLiveSearchResponse(title, url) {
                    this.posterUrl = null // 썸네일 이미지가 확인되면 여기에 할당
                })
            }
        }
        
        println("[Streamed v1.3] 디버깅 - getMainPage 파싱 완료, 총 ${homeList.size}개 항목 반환")
        return newHomePageResponse(request.name, homeList.distinctBy { it.url })
    }

    override suspend fun load(url: String): LoadResponse {
        println("[Streamed v1.3] 디버깅 - load 호출됨: url=$url")
        
        val document = app.get(url).document
        val title = document.select("title").text().replace("Stream Links - Streamed", "").trim()
        
        println("[Streamed v1.3] 디버깅 - 추출된 타이틀: $title")
        
        val episodes = arrayListOf<Episode>()
        
        // 경기 링크 페이지에서 실제 영상 재생 서버(Admin 1, Admin 2 등) 링크 추출
        document.select("a[href^=/watch/]").forEachIndexed { index, element ->
            val href = element.attr("href")
            val parts = href.trimEnd('/').split("/")
            
            // URL의 마지막 부분이 숫자인 경우(예: /watch/match-name/1) 소스 링크로 판단
            if (parts.isNotEmpty() && parts.last().toIntOrNull() != null) {
                val epName = element.text().trim().ifEmpty { "Admin ${parts.last()}" }
                
                println("[Streamed v1.3] 디버깅 - 발견된 재생 링크: name=$epName, url=${fixUrl(href)}")
                
                // v1.3 변경점: TVMon.kt 규격 참고, data 단일 파라미터 및 빌더 패턴 사용
                episodes.add(newEpisode(fixUrl(href)) {
                    this.name = epName
                })
            }
        }
        
        println("[Streamed v1.3] 디버깅 - load 파싱 완료, 총 ${episodes.size}개 링크 반환")
        
        // v1.3 변경점: 다중 채널을 에피소드 형태로 반환 (TVMon.kt 참고)
        return newTvSeriesLoadResponse(title, url, TvType.Live, episodes) {
            this.posterUrl = null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        println("[Streamed v1.3] 디버깅 - loadLinks 호출됨: data=$data")
        
        val response = app.get(data)
        val document = response.document
        val html = document.html()
        
        println("[Streamed v1.3] 디버깅 - 재생 페이지 HTML 가져오기 성공, iframe 또는 m3u8 검색 시작")
        
        var isSuccess = false
        
        // 1. iframe 소스 추출 시도
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            println("[Streamed v1.3] 디버깅 - iframe 발견: src=$src")
            if (src.isNotBlank()) {
                val fixedSrc = fixUrl(src)
                println("[Streamed v1.3] 디버깅 - iframe 경로 처리 중: $fixedSrc")
                loadExtractor(fixedSrc, data, subtitleCallback, callback)
                isSuccess = true
            }
        }
        
        // 2. 평문 m3u8 URL이 HTML 내부에 있는지 정규식으로 직접 탐색
        val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
        val m3u8Match = m3u8Regex.find(html)
        
        if (m3u8Match != null) {
            val m3u8Url = m3u8Match.groupValues[1]
            println("[Streamed v1.3] 디버깅 - m3u8 정규식 매칭 성공: url=$m3u8Url")
            
            // v1.3 변경점: TVMon.kt의 Extractor 규격 완전 적용 (ExtractorLinkType 열거형 및 빌더 패턴 사용)
            callback(
                newExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                }
            )
            isSuccess = true
        } else {
            println("[Streamed v1.3] 디버깅 - m3u8 정규식 매칭 실패. 크롬 개발자 도구 '네트워크(Network)' 탭 분석이 필요할 수 있습니다.")
        }
        
        // 3. 난독화 객체 탐지 로그
        if (html.contains("window['")) {
            println("[Streamed v1.3] 디버깅 - 난독화된 자바스크립트 블록이 탐지되었습니다. 정적 파싱이 실패할 경우 WebView 렌더러 연동 등 추가 작업이 필요합니다.")
        }
        
        return isSuccess
    }
}
