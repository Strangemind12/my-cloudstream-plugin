// v5.1 - 빌드 에러 수정 (headers.putAll -> headers = ...)
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class KingkongTv : MainAPI() {
    override var mainUrl = "https://holyindia.org"
    override var name = "KingkongTv"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Live)

    // 카테고리 매핑
    private val categoryMap = mapOf(
        "cate_1" to "축구", "cate_2" to "농구", "cate_3" to "배구",
        "cate_4" to "야구", "cate_5" to "하키", "cate_6" to "럭비",
        "cate_7" to "LoL", "cate_8" to "e스포츠", "cate_9" to "일반/HD",
        "cate_10" to "UFC", "cate_11" to "테니스"
    )

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        println("DEBUG [KingkongTv] v5.1: 메인 페이지 요청 - URL: $url")
        
        try {
            val mainDoc = app.get(url).document
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame")
            var iframeSrc = iframeElement?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                println("DEBUG [KingkongTv] v5.1: iframe#broadcastFrame 없음. 기본값 사용.")
                iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
            }

            val realUrl = fixUrl(url, iframeSrc!!)
            println("DEBUG [KingkongTv] v5.1: Iframe 내부 진입 - URL: $realUrl")

            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr")
            println("DEBUG [KingkongTv] v5.1: 파싱된 행 개수: ${rows.size}")

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
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v5.1: 메인 페이지 로드 실패 - ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        if (this.selectFirst("span.off") != null) return null

        val channelDiv = this.selectFirst("div.channel_on") ?: return null
        val title = channelDiv.attr("data-title")
        val streamId = channelDiv.attr("data-stream")
        
        if (title.isBlank() || streamId.isBlank()) return null

        val thumbUrl = this.selectFirst("td.thumb img")?.attr("src")?.let { 
            fixUrl(refererUrl, it) 
        } ?: ""

        // URL 인코딩 (데이터 전달용)
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedPoster = URLEncoder.encode(thumbUrl, "UTF-8")
        val encodedRef = URLEncoder.encode(refererUrl, "UTF-8")

        val fakeUrl = "$mainUrl/live_stream/$streamId?title=$encodedTitle&poster=$encodedPoster&ref=$encodedRef"

        return newLiveSearchResponse(title, fakeUrl, TvType.Live) {
            this.posterUrl = thumbUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("DEBUG [KingkongTv] v5.1: 상세 페이지(load) 진입 - URL: $url")
        val uri = Uri.parse(url)
        val streamId = url.substringAfter("/live_stream/").substringBefore("?")
        
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live $streamId"
        
        val rawPoster = uri.getQueryParameter("poster")
        val poster = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else null

        // 상세페이지 제목 동기화 및 플롯 고정
        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = poster
            this.plot = "실시간 중계" 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("DEBUG [KingkongTv] v5.1: loadLinks 시작")
        println("DEBUG [KingkongTv] v5.1: 요청 데이터: $data")

        val uri = Uri.parse(data)
        val streamId = data.substringAfter("/live_stream/").substringBefore("?")
        val rawRef = uri.getQueryParameter("ref")
        val refererUrl = if (!rawRef.isNullOrBlank()) URLDecoder.decode(rawRef, "UTF-8") else "https://kktv.speed10-1.com/"

        println("DEBUG [KingkongTv] v5.1: Target Stream ID: $streamId")
        println("DEBUG [KingkongTv] v5.1: Target Referer: $refererUrl")

        // 테스트할 URL 패턴 목록
        val candidates = listOf(
            "HLS_V1" to "http://play.ogtv3.com/live/$streamId/playlist.m3u8?site=kktv",
            "HLS_V2" to "http://play.ogtv3.com/live/$streamId.m3u8?site=kktv",
            "HLS_V3" to "http://play.ogtv3.com/live/$streamId/chunklist.m3u8?site=kktv",
            "FLV_V1" to "http://play.ogtv3.com/live/$streamId.flv?site=kktv"
        )

        val headers = mapOf(
            "Referer" to refererUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Origin" to "https://kktv.speed10-1.com"
        )

        var foundValidLink = false

        // 각 후보 URL에 대해 실제 연결 테스트(Probe) 수행
        for ((label, testUrl) in candidates) {
            println("DEBUG [KingkongTv] v5.1: 네트워크 테스트 시도 [$label] -> $testUrl")
            try {
                // 타임아웃 5초 설정하여 빠르게 체크
                val response = app.get(testUrl, headers = headers, timeout = 5000L)
                val code = response.code
                println("DEBUG [KingkongTv] v5.1: 응답 코드 [$code] - $label")

                if (code == 200) {
                    println("DEBUG [KingkongTv] v5.1: >> 유효한 링크 발견! 플레이어에 전달합니다.")
                    foundValidLink = true
                    
                    val type = if (label.contains("FLV")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    
                    callback.invoke(
                        newExtractorLink(
                            name = "KingkongTv ($label)",
                            source = name,
                            url = testUrl,
                            type = type
                        ) {
                            this.referer = refererUrl
                            // [수정됨] putAll 대신 대입 연산자 사용
                            this.headers = headers
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    println("DEBUG [KingkongTv] v5.1: 유효하지 않은 응답 ($code). 패스.")
                }
            } catch (e: Exception) {
                println("DEBUG [KingkongTv] v5.1: 연결 실패 [$label] - ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (!foundValidLink) {
            println("DEBUG [KingkongTv] v5.1: 모든 URL 패턴 테스트 실패. 서버가 WebRTC 전용이거나 HTTP 접근을 차단했을 가능성이 높습니다.")
        }

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
