// v3.2 - 빌드 에러 수정 및 newExtractorLink 적용
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

    // 카테고리 매핑
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
        
        // 1. 껍데기 메인 페이지 로드
        val mainDoc = app.get(url).document

        // 2. 숨겨진 Iframe URL 추출
        val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame")
        var iframeSrc = iframeElement?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
        }

        // 상대 경로일 경우 절대 경로로 변환
        val realUrl = fixUrl(url, iframeSrc!!)

        // 3. 실제 채널 리스트 페이지 로드 (Referer 필수)
        val contentDoc = app.get(realUrl, referer = url).document

        // 4. 테이블 행(tr) 파싱
        val rows = contentDoc.select("div.sports_on_list table tbody tr")

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
        // 방송전인 경우 제외
        if (this.selectFirst("span.off") != null) {
            return null
        }

        val channelDiv = this.selectFirst("div.channel_on") ?: return null
        val title = channelDiv.attr("data-title")
        val streamId = channelDiv.attr("data-stream")
        
        if (title.isBlank() || streamId.isBlank()) return null

        val thumbUrl = this.selectFirst("td.thumb img")?.attr("src")?.let { 
            fixUrl(refererUrl, it) 
        }

        // 카테고리/리그 정보 등은 필요 시 활용
        // val categoryClass = this.classNames().firstOrNull { it.startsWith("cate_") } ?: ""

        val fakeUrl = "$mainUrl/live_stream/$streamId?ref=$refererUrl"

        // LiveSearchResponse 생성
        return newLiveSearchResponse(title, fakeUrl, TvType.Live) {
            this.posterUrl = thumbUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val streamId = url.substringAfter("/live_stream/").substringBefore("?")
        
        // [수정 1] 3번째 인자에 emptyList() 대신 url(String) 전달 (String 타입을 요구함)
        return newLiveStreamLoadResponse("LIVE $streamId", url, url) {
            this.plot = "실시간 중계 ID: $streamId"
            // this.posterUrl = ... (필요시 추가)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // url에서 파라미터 파싱
        val streamId = data.substringAfter("/live_stream/").substringBefore("?")
        val refererUrl = data.substringAfter("ref=", "")
        val finalReferer = if (refererUrl.isNotBlank()) refererUrl else "https://kktv.speed10-1.com/"

        val m3u8Url = "http://play.ogtv3.com/live/$streamId/playlist.m3u8"

        // [수정 2] 구형 ExtractorLink 생성자 대신 newExtractorLink 사용 (Tennistream 스타일)
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8 // 최신 Enum 타입 사용
            ) {
                this.referer = finalReferer
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        
        return try {
            val baseUri = java.net.URI(baseUrl)
            val resolved = baseUri.resolve(relativeUrl)
            resolved.toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }
}
