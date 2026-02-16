package com.tvwiki

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonProperty

// v1.2 - 다음화 재생 버그 수정 및 동적 세션 로직 추가
class TVWiki : MainAPI() {
    override var mainUrl = "https://tvwiki5.net"
    override var name = "TVWiki"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama,
        TvType.Anime,
        TvType.AnimeMovie
    )

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    // 세션 응답용 데이터 클래스
    data class SessionResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("player_url") val playerUrl: String?,
        @JsonProperty("t") val t: String?,
        @JsonProperty("sig") val sig: String?
    )

    override val mainPage = mainPageOf(
        "/popular" to "인기순위",
        "/kor_movie" to "영화",
        "/drama" to "드라마",
        "/ent" to "예능",
        "/sisa" to "시사/다큐",
        "/movie" to "해외영화",
        "/world" to "해외드라마",
        "/ott_ent" to "해외예능/다큐",
        "/animation" to "일반 애니메이션",
        "/ani_movie" to "극장판 애니",
        "/old_ent" to "추억의 예능",
        "/old_drama" to "추억의 드라마"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select("#list_type ul li").mapNotNull { it.toSearchResponse() }
            
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.img") ?: return null
        var link = fixUrl(aTag.attr("href"))
        
        val title = this.selectFirst("a.title")?.text()?.trim() 
            ?: this.selectFirst("a.title2")?.text()?.trim() 
            ?: return null

        val imgTag = aTag.selectFirst("img")
        val poster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        val fixedPoster = fixUrl(poster)

        if (fixedPoster.isNotEmpty()) {
            try {
                val encodedPoster = URLEncoder.encode(fixedPoster, "UTF-8")
                val separator = if (link.contains("?")) "&" else "?"
                link = "$link${separator}cw_poster=$encodedPoster"
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val type = determineTypeFromUrl(link)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(
                title,
                link,
                type
            ) { this.posterUrl = fixedPoster }

            TvType.Anime -> newAnimeSearchResponse(
                title,
                link,
                TvType.Anime
            ) { this.posterUrl = fixedPoster }

            else -> newTvSeriesSearchResponse(
                title,
                link,
                TvType.TvSeries
            ) { this.posterUrl = fixedPoster }
        }
    }

    private fun determineTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/movie") || url.contains("/kor_movie") -> TvType.Movie
            url.contains("/ani_movie") -> TvType.AnimeMovie
            url.contains("/animation") -> TvType.Anime
            url.contains("/ent") || url.contains("/old_ent") || url.contains("/ott_ent") -> TvType.TvSeries
            else -> TvType.TvSeries
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?stx=$query"
        val doc = app.get(searchUrl, headers = commonHeaders).document
        
        var items = doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
        if (items.isEmpty()) {
             items = doc.select("#list_type ul li").mapNotNull { it.toSearchResponse() }
        }
        return items
    }

    private fun getEpisodeNumber(name: String): Int {
        return try {
            val numberString = name.replace(Regex("[^0-9]"), "")
            if (numberString.isNotEmpty()) numberString.toInt() else Int.MAX_VALUE
        } catch (e: Exception) {
            Int.MAX_VALUE
        }
    }

    override suspend fun load(url: String): LoadResponse {
        println("[TVWiki] load 시작 - URL: $url")

        var passedPoster: String? = null
        var realUrl = url

        try {
            val regex = Regex("[?&]cw_poster=([^&]+)")
            val match = regex.find(url)
            if (match != null) {
                val encoded = match.groupValues[1]
                passedPoster = URLDecoder.decode(encoded, "UTF-8")
                realUrl = url.replace(match.value, "")
                if (realUrl.endsWith("?") || realUrl.endsWith("&")) {
                    realUrl = realUrl.dropLast(1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val doc = app.get(realUrl, headers = commonHeaders).document

        val h3Element = doc.selectFirst("#bo_v_movinfo h3")
        var title = h3Element?.ownText()?.trim()
        val oriTitleFull = h3Element?.selectFirst(".ori_title")?.text()?.trim()

        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("#bo_v_movinfo h3")?.text()?.trim()
                ?: doc.selectFirst("input[name='con_title']")?.attr("value")?.trim()
                ?: "Unknown"
        }
        
        title = title!!.replace(Regex("\\s*\\d+[화회부].*"), "").replace(" 다시보기", "").trim()

        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            val hasKorean = pureOriTitle.contains(Regex("[가-힣]"))
            if (!hasKorean && pureOriTitle.isNotEmpty()) {
                title = "$title (원제 : $pureOriTitle)"
            }
        }

        var poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        
        if (poster.isNullOrEmpty() && passedPoster != null) {
            poster = passedPoster
        }
        
        poster = poster ?: ""

        val infoList = doc.select(".bo_v_info dd").map { it.text().trim().replace("개봉년도:", "공개일:") }
        
        val genreList = doc.select(".tags dd a").filter {
            val txt = it.text()
            !txt.contains("트레일러") && !it.hasClass("btn_watch")
        }.map { it.text().trim() }

        val genreFormatted = if (genreList.isNotEmpty()) "장르: ${genreList.joinToString(", ")}" else ""

        val castList = doc.select(".slider_act .item .name").map { it.text().trim() }
        
        val castFormatted = if (castList.isNotEmpty() && castList.none { it.contains("운영팀") }) {
            "출연: ${castList.joinToString(", ")}"
        } else {
            ""
        }

        val metaParts = mutableListOf<String>()
        if (infoList.isNotEmpty()) metaParts.add(infoList.joinToString(" / "))
        if (genreFormatted.isNotEmpty()) metaParts.add(genreFormatted)
        if (castFormatted.isNotEmpty()) metaParts.add(castFormatted)
        val metaString = metaParts.joinToString(" / ")

        var story = doc.selectFirst("#bo_v_con")?.text()?.trim()
            ?: doc.selectFirst(".story")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: ""

        if (story.contains("다시보기") && story.contains("무료")) story = "다시보기"
        if (story.isEmpty()) story = "다시보기"

        val finalPlot = if (story == "다시보기") {
                "다시보기"
        } else {
                if (metaString.isNullOrBlank()) "줄거리: $story".trim()
                else "$metaString / 줄거리: $story".trim()
        }
        
        println("[TVWiki] 에피소드 파싱 시작")
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val epName = li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            val thumbImg = li.selectFirst("a.img img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { null }
                ?: thumbImg?.attr("data-original")?.ifEmpty { null }
                ?: thumbImg?.attr("src")?.ifEmpty { null }
                ?: li.selectFirst("img")?.attr("src")

            println("[TVWiki] 파싱됨 - 이름: $epName, 링크: $href")

            newEpisode(href) {
                this.name = epName
                this.posterUrl = fixUrl(epThumb ?: "")
            }
        }.sortedBy { 
            getEpisodeNumber(it.name ?: "") 
        }

        println("[TVWiki] 최종 정렬된 에피소드 개수: ${episodes.size}")
        if (episodes.isNotEmpty()) {
            println("[TVWiki] 첫번째 에피소드: ${episodes.first().name} -> ${episodes.first().data}")
            println("[TVWiki] 마지막 에피소드: ${episodes.last().name} -> ${episodes.last().data}")
        }

        val type = determineTypeFromUrl(realUrl)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: realUrl
                newMovieLoadResponse(title, realUrl, type, movieLink) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = finalPlot
                }
            }

            else -> {
                newTvSeriesLoadResponse(title, realUrl, type, episodes) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = finalPlot
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[TVWiki] loadLinks 시작 - data: $data")
        val doc = app.get(data, headers = commonHeaders).document
        
        var foundUrl = false
        val iframe = doc.selectFirst("iframe#view_iframe")
        
        // 1. iframe src 확인
        if (iframe != null) {
            val playerUrl = iframe.attr("src")
            println("[TVWiki] iframe src 발견: $playerUrl")
            
            if (playerUrl.isNotEmpty() && playerUrl.contains("player.bunny-frame.online")) {
                 val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), data, subtitleCallback, callback, null)
                 if(extracted) foundUrl = true
            }
            
            // src가 비어있거나 실패했고, data-session1이 있다면 API로 URL 생성 시도
            if (!foundUrl) {
                val sessionData = iframe.attr("data-session1")
                if (sessionData.isNotEmpty()) {
                    println("[TVWiki] data-session1 발견, 세션 API 요청 시도")
                    try {
                        val sessionUrl = "$mainUrl/api/create_session.php"
                        val response = app.post(
                            sessionUrl,
                            headers = commonHeaders + mapOf("Content-Type" to "application/json"),
                            data = mapOf("raw" to sessionData) // body: JSON string directly
                        ).parsedSafe<SessionResponse>()

                        if (response != null && response.success && !response.playerUrl.isNullOrEmpty()) {
                            val generatedUrl = "${response.playerUrl}?t=${response.t}&sig=${response.sig}"
                            println("[TVWiki] 세션 API로 URL 생성 성공: $generatedUrl")
                            val extracted = BunnyPoorCdn().extract(fixUrl(generatedUrl), data, subtitleCallback, callback, null)
                            if (extracted) foundUrl = true
                        } else {
                            println("[TVWiki] 세션 API 요청 실패 또는 응답 오류")
                        }
                    } catch (e: Exception) {
                        println("[TVWiki] 세션 API 요청 중 예외 발생: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }

        if (foundUrl) return true

        // 2. Script 태그 폴백 (주의: nextEp 관련 URL 제외)
        println("[TVWiki] iframe 실패, Script 태그 탐색 시작")
        val scriptTags = doc.select("script")
        for (script in scriptTags) {
            val scriptContent = script.html()
            
            // 다음화 정보(episodeData)에 있는 URL은 절대 사용하면 안됨 (현재 문제의 원인)
            if (scriptContent.contains("nextEpPlayer1Url") || scriptContent.contains("nextEpUrl")) {
                println("[TVWiki] Script에서 URL 발견했으나 다음화(NextEpisode) 정보이므로 스킵함")
                continue
            }

            if (scriptContent.contains("player.bunny-frame.online")) {
                val urlRegex = Regex("""https://player\.bunny-frame\.online/[^"'\s]+""")
                val match = urlRegex.find(scriptContent)
                
                if (match != null) {
                    val url = match.value.replace("&amp;", "&")
                    println("[TVWiki] Script에서 URL 발견: $url")
                    if(BunnyPoorCdn().extract(url, data, subtitleCallback, callback, null)) return true
                }
            }
        }

        // 3. 썸네일 힌트 (최후의 수단)
        val thumbnailHint = extractThumbnailHint(doc)
        if (thumbnailHint != null) {
            println("[TVWiki] 썸네일 힌트로 m3u8 추측 시도: $thumbnailHint")
            try {
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(thumbnailHint)
                if (pathMatch != null) {
                    val m3u8Url = thumbnailHint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    val fixedM3u8Url = m3u8Url.replace(Regex("//v/"), "/v/")
                    println("[TVWiki] 생성된 m3u8 URL: $fixedM3u8Url")
                    callback(
                        newExtractorLink(name, name, fixedM3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = commonHeaders
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }

    private fun extractThumbnailHint(doc: Document): String? {
        val videoThumbElements = doc.select("img[src*='/v/'], img[data-src*='/v/']")
        val priorityRegex = Regex("""/v/[a-z]/""")
        for (el in videoThumbElements) {
            val raw = el.attr("src").ifEmpty { el.attr("data-src") }
            val fixed = fixUrl(raw)
            if (priorityRegex.containsMatchIn(fixed)) return fixed
        }
        return null
    }
}
