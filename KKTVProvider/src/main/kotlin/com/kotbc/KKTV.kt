package com.KingkongTv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// v1.0
// 이 내용은 제 가설입니다:
// 1. 킹콩티비의 메인 도메인은 제공된 파일 내 메타태그에 근거하여 'https://holyindia.org'로 가정합니다.
// 2. 스트리밍 서버는 'webrtc://play.ogtv3.com/...' 패턴을 보이나, CloudStream 호환성을 위해
//    표준 HLS 주소인 'http://play.ogtv3.com/live/{id}/playlist.m3u8' 형태로 변환하여 시도합니다.
class KingkongTv : MainAPI() {
    // 기본 설정
    override var mainUrl = "https://holyindia.org"
    override var name = "KingkongTv"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Live)

    // 메인 페이지 카테고리 (제공된 파일의 네비게이션 링크 참고)
    override val mainPage = mainPageOf(
        "/live/sportstv" to "스포츠중계 A",
        "/live/sportstv2" to "스포츠중계 B"
    )

    // 메인 페이지 파싱
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = "$mainUrl${request.data}"
        println("DEBUG [KingkongTv] v1.0: 메인 페이지 요청 시작 - URL: $targetUrl")

        // Jsoup을 사용하여 페이지 로드
        val document = app.get(targetUrl).document

        // 사용자가 명시한 DOM 구조: <div class="channel_on ch1_select" data-stream="..." data-title="...">
        // 'channel_on' 클래스를 가진 div 요소를 모두 찾습니다.
        val elements = document.select("div.channel_on")
        println("DEBUG [KingkongTv] v1.0: 'div.channel_on' 요소 ${elements.size}개 발견")

        val home = elements.mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true // 가로형 이미지로 가정
            ),
            hasNext = false // 단일 페이지 구조로 추정됨
        )
    }

    // 개별 채널 정보를 SearchResponse로 변환
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("data-title")
        val streamId = this.attr("data-stream")

        // 디버깅: 파싱된 데이터 확인
        // println("DEBUG [KingkongTv] 요소 파싱 중... title='$title', streamId='$streamId'")

        if (title.isNullOrBlank() || streamId.isNullOrBlank()) {
            println("DEBUG [KingkongTv] v1.0: 유효하지 않은 데이터로 인해 항목 스킵")
            return null
        }

        // 스트림 ID를 URL 경로에 포함시켜 loadLinks 함수에서 파싱할 수 있게 함
        // 실제 유효한 URL일 필요는 없으며, 식별자 역할만 하면 됨
        val fakeUrl = "$mainUrl/live_stream/$streamId"

        // LiveSearchResponse 생성
        return newLiveSearchResponse(title, fakeUrl, TvType.Live) {
            // 포스터 이미지가 div 내부에 img 태그로 있다면 아래와 같이 추가 가능 (현재는 정보 없음)
            // this.posterUrl = element.selectFirst("img")?.attr("src")
        }
    }

    // 상세 정보 로드 (라이브 스트림은 보통 비디오 로드로 직행하므로 간단히 처리)
    override suspend fun load(url: String): LoadResponse? {
        println("DEBUG [KingkongTv] v1.0: 상세 페이지(load) 요청 - URL: $url")

        // URL 뒤의 streamId 추출
        val streamId = url.substringAfterLast("/")
        val title = "Live Channel $streamId"

        return newLiveStreamLoadResponse(title, url, []) {
            this.plot = "킹콩티비 실시간 중계 (ID: $streamId)"
        }
    }

    // 실제 비디오 스트림 링크 추출
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("DEBUG [KingkongTv] v1.0: 링크 추출(loadLinks) 요청 - Data: $data")

        val streamId = data.substringAfterLast("/")
        println("DEBUG [KingkongTv] v1.0: 추출된 Stream ID: $streamId")

        // 분석 결과: 재생페이지 로그에 'webrtc://play.ogtv3.com/live/live503?site=kktv' 확인됨.
        // WebRTC는 CloudStream 기본 플레이어에서 직접 재생이 어려울 수 있으므로,
        // 통상적인 HLS(m3u8) 경로로 변환하여 시도.
        val m3u8Url = "http://play.ogtv3.com/live/$streamId/playlist.m3u8"

        println("DEBUG [KingkongTv] v1.0: 생성된 스트림 URL: $m3u8Url")

        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                referer = mainUrl, // 차단을 피하기 위해 레퍼러 헤더 추가
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )
        return true
    }
}
