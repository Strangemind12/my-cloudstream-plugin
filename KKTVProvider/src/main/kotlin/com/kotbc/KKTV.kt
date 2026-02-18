// v7.0 - DaddyLive 스타일 파싱 적용 (페이지 소스 분석 -> 링크 추출)
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class KingkongTv : MainAPI() {
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Live)

    // DaddyLive에서 사용된 User-Agent 참고
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        
        try {
            val mainDoc = app.get(url).document
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame")
            var iframeSrc = iframeElement?.attr("src")

            // Iframe이 없거나 비어있으면 기본값 사용 (제공된 파일 기반)
            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
            }

            val realUrl = fixUrl(url, iframeSrc!!)
            val contentDoc = app.get(realUrl, referer = url).document
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
        } catch (e: Exception) {
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

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedPoster = URLEncoder.encode(thumbUrl, "UTF-8")
        
        // Iframe URL을 Referer로 넘겨야 함
        val encodedRef = URLEncoder.encode(refererUrl, "UTF-8")

        // URL에 streamId 뿐만 아니라 referer 정보도 포함
        val fakeUrl = "$mainUrl/live_stream/$streamId?title=$encodedTitle&poster=$encodedPoster&ref=$encodedRef"

        return newLiveSearchResponse(title, fakeUrl, TvType.Live) {
            this.posterUrl = thumbUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val streamId = url.substringAfter("/live_stream/").substringBefore("?")
        
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawPoster = uri.getQueryParameter("poster")
        val poster = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else null

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
        // 1. 파라미터 파싱
        val uri = Uri.parse(data)
        val streamId = data.substringAfter("/live_stream/").substringBefore("?")
        val rawRef = uri.getQueryParameter("ref")
        // Referer는 메인 페이지가 아닌 Iframe 주소여야 함 (DaddyLive 방식)
        val refererUrl = if (!rawRef.isNullOrBlank()) URLDecoder.decode(rawRef, "UTF-8") else "https://kktv.speed10-1.com/kktv/index.php"
        
        println("DEBUG [KingkongTv] v7.0: ID=$streamId, Referer=$refererUrl")

        // 2. 플레이어 페이지 소스 가져오기 (DaddyLive의 extractFromNewzar 방식 모방)
        // 실제 플레이어가 로드되는 URL (pc_view.php 추정)
        val playerPageUrl = "https://kktv.speed10-1.com/kktv/pc_view.php?stream=$streamId"
        
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to refererUrl,
            "Origin" to "https://kktv.speed10-1.com"
        )

        try {
            println("DEBUG [KingkongTv] v7.0: 플레이어 페이지 요청 - $playerPageUrl")
            val response = app.get(playerPageUrl, headers = headers).text
            
            // 3. 페이지 소스에서 URL 추출 (Regex)
            // 패턴 1: 직접적인 .m3u8 링크 찾기
            var m3u8Url = Regex("""['"](http[^'"]+\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            
            // 패턴 2: WebRTC 링크 찾기 (webrtc://...) -> HTTPS HLS로 변환
            if (m3u8Url == null) {
                val webrtcMatch = Regex("""['"](webrtc://[^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (webrtcMatch != null) {
                    println("DEBUG [KingkongTv] v7.0: WebRTC 링크 발견 - $webrtcMatch")
                    // webrtc://play.ogtv3.com/live/live503?site=kktv
                    // -> https://play.ogtv3.com/live/live503.m3u8?site=kktv (프로토콜 및 확장자 변경)
                    m3u8Url = webrtcMatch
                        .replace("webrtc://", "https://")
                        .replace(streamId, "$streamId.m3u8") // live503 -> live503.m3u8 변환 시도
                }
            }

            // 4. 추출 실패 시, 수동 구성 (Backup Plan)
            if (m3u8Url == null) {
                println("DEBUG [KingkongTv] v7.0: 링크 추출 실패. 수동 구성 시도.")
                // HTTPS 프로토콜 강제 사용 (DaddyLive는 HTTPS를 씀)
                m3u8Url = "https://play.ogtv3.com/live/$streamId.m3u8?site=kktv"
            }

            println("DEBUG [KingkongTv] v7.0: 최종 M3U8 URL - $m3u8Url")

            // 5. ExtractorLink 반환 (DaddyLive 스타일)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url!!,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://kktv.speed10-1.com/" // Origin과 동일하게 맞춤
                    this.quality = Qualities.Unknown.value
                    // 헤더 강제 설정 (중요: putAll 대신 대입)
                    this.headers = mapOf(
                        "Origin" to "https://kktv.speed10-1.com",
                        "Referer" to "https://kktv.speed10-1.com/",
                        "User-Agent" to userAgent
                    )
                }
            )

        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.0: 에러 발생 - ${e.message}")
            e.printStackTrace()
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
