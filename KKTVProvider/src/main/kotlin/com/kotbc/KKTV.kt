// v3.0 - 숨겨진 Iframe(index.html) 정밀 분석 반영
package com.KingkongTv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KingkongTv : MainAPI() {
    override var mainUrl = "https://holyindia.org"
    override var name = "KingkongTv"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Live)

    // 카테고리 매핑 (index.html 분석 결과)
    // 1:축구, 2:농구, 3:배구, 4:야구, 5:하키, 6:럭비, 7:LoL, 8:e스포츠, 9:HD, 10:UFC, 11:테니스
    private val categoryMap = mapOf(
        "cate_1" to "축구",
        "cate_2" to "농구",
        "cate_3" to "배구",
        "cate_4" to "야구",
        "cate_5" to "하키",
        "cate_6" to "럭비",
        "cate_7" to "LoL",
        "cate_8" to "e스포츠",
        "cate_9" to "일반/HD",
        "cate_10" to "UFC",
        "cate_11" to "테니스"
    )

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        println("DEBUG [KingkongTv] v3.0: 메인 페이지 요청 - $url")

        // 1. 껍데기 메인 페이지 로드
        val mainDoc = app.get(url).document

        // 2. 숨겨진 Iframe URL 추출
        // id="broadcastFrame" 인 iframe의 src를 가져옵니다.
        val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame")
        var iframeSrc = iframeElement?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            println("DEBUG [KingkongTv] v3.0: iframe#broadcastFrame 요소를 찾지 못했습니다.")
            // 혹시 모르니 제공된 파일의 도메인으로 하드코딩된 백업 URL 시도
            iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
        }

        // 상대 경로일 경우 절대 경로로 변환
        val realUrl = fixUrl(url, iframeSrc!!)
        println("DEBUG [KingkongTv] v3.0: 실제 채널 리스트 페이지(Iframe) 요청 - $realUrl")

        // 3. 실제 채널 리스트 페이지 로드 (Referer 필수)
        val contentDoc = app.get(realUrl, referer = url).document

        // 4. 테이블 행(tr) 파싱 (index.html 구조 반영)
        // div.sports_on_list > table > tbody > tr
        val rows = contentDoc.select("div.sports_on_list table tbody tr")
        println("DEBUG [KingkongTv] v3.0: 파싱된 방송 행 개수 - ${rows.size}")

        val home = rows.mapNotNull { row ->
            row.toSearchResult(realUrl)
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = false
        )
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        // 방송 상태 확인 (index.html의 span.on "방송시청" or "시청중" 확인)
        // 방송전인 경우 span.off "방송전" 클래스가 있음 -> 제외 처리
        if (this.selectFirst("span.off") != null) {
            return null
        }

        // 데이터 추출
        val channelDiv = this.selectFirst("div.channel_on") ?: return null
        val title = channelDiv.attr("data-title")
        val streamId = channelDiv.attr("data-stream")
        
        if (title.isBlank() || streamId.isBlank()) return null

        // 썸네일 추출 (tr > td.thumb > img)
        val thumbUrl = this.selectFirst("td.thumb img")?.attr("src")?.let { 
            fixUrl(refererUrl, it) 
        }

        // 카테고리/리그 정보 추출 (상세 정보용)
        val categoryClass = this.classNames().firstOrNull { it.startsWith("cate_") } ?: ""
        val categoryName = categoryMap[categoryClass] ?: "스포츠"
        val league = this.selectFirst("td.league")?.text()?.trim() ?: ""

        // URL 생성: streamId와 Referer(Iframe 주소)를 함께 넘기기 위해 인코딩하여 저장
        // 실제 재생 링크가 아니라 load()로 넘길 데이터입니다.
        val fakeUrl = "$mainUrl/live_stream/$streamId?ref=$refererUrl"

        return newLiveSearchResponse(title, fakeUrl, TvType.Live) {
            this.posterUrl = thumbUrl
            this.plot = "[$categoryName] $league - $title" // 상세 설명에 리그 정보 표시
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // url에서 streamId 추출
        val streamId = url.substringAfter("/live_stream/").substringBefore("?")
        
        return newLiveStreamLoadResponse("LIVE $streamId", url, []) {
            this.plot = "실시간 중계 ID: $streamId"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // fakeUrl 파싱: "$mainUrl/live_stream/$streamId?ref=$refererUrl"
        val streamId = data.substringAfter("/live_stream/").substringBefore("?")
        val refererUrl = data.substringAfter("ref=", "")

        println("DEBUG [KingkongTv] v3.0: 링크 추출 요청 - ID: $streamId")

        // 스트림 URL 생성
        // index.html 분석 결과 플레이어는 pc_view.php를 사용하지만,
        // 내부적으로 play.ogtv3.com 서버를 사용하는 것이 확인됨 (이전 파일 분석)
        val m3u8Url = "http://play.ogtv3.com/live/$streamId/playlist.m3u8"
        
        // Referer 설정: 중요!
        // 메인 페이지가 아닌, 실제 리스트가 있던 Iframe 도메인(kktv.speed10-1.com)을 Referer로 사용해야 함.
        val finalReferer = if (refererUrl.isNotBlank()) refererUrl else "https://kktv.speed10-1.com/"

        println("DEBUG [KingkongTv] v3.0: 최종 M3U8 URL - $m3u8Url")
        println("DEBUG [KingkongTv] v3.0: 적용된 Referer - $finalReferer")

        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                referer = finalReferer,
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )
        return true
    }

    // URL 보정 유틸리티
    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        
        val baseUri = java.net.URI(baseUrl)
        val resolved = baseUri.resolve(relativeUrl)
        return resolved.toString()
    }
}
