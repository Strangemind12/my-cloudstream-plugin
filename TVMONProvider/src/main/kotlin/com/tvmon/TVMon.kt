package com.tvmon

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * TVMon Provider v1.4
 *
 * [v1.4 수정 사항]
 * - 상세 정보 태그화: 줄거리를 제외한 정보를 태그(Tags) 리스트로 분리하여 KOTBC와 동일한 구조로 변경.
 * - 태그 정제: "제목" 태그 제외, "개봉년도" -> "공개일" 라벨 변경, 불필요한 연도/괄호 텍스트 제거.
 */
class TVMon : MainAPI() {
    override var mainUrl = "https://tvmon.site"
    override var name = "TVMON"
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

    // 태그 내용 정제용 정규식
    private val tagCleanRegex = Regex("""\s*\(?\d{4}\)?.*""")

    override val mainPage = mainPageOf(
        "/kor_movie" to "영화",
        "/drama" to "드라마",
        "/ent" to "예능",
        "/sisa" to "시사/다큐",
        "/movie" to "해외영화",
        "/world" to "해외드라마",
        "/animation" to "애니메이션",
        "/ani_movie" to "극장판 애니",
        "/old_drama" to "추억의 드라마",
        "/old_ent" to "추억의 예능"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        println("[TVMon][v1.4] getMainPage 요청: $url")
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            var elements = doc.select(".mov_list ul li")
            if (elements.isEmpty()) {
                elements = doc.select(".item")
            }
            if (elements.isEmpty()) {
                elements = doc.select("li, div.item")
            }

            val list = elements.mapNotNull { it.toSearchResponse() }
            println("[TVMon] 메인 페이지 아이템 로드 성공: ${list.size}건")
            
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            println("[TVMon] 메인 페이지 로드 에러: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.img") ?: return null
        var link = fixUrl(aTag.attr("href"))
        val title = this.selectFirst("a.title")?.text()?.trim() ?: return null

        val imgTag = aTag.selectFirst("img")
        var rawPoster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        if (rawPoster.startsWith("/")) {
            rawPoster = "$mainUrl$rawPoster"
        }
        val fixedPoster = fixUrl(rawPoster)

        if (fixedPoster.isNotEmpty() && !fixedPoster.contains("no3.png")) {
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
            url.contains("/ent") || url.contains("/old_ent") -> TvType.TvSeries
            else -> TvType.TvSeries
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("[TVMon][v1.4] 검색 실행: $query")
        val searchUrl = "$mainUrl/search?stx=$query"
        val doc = app.get(searchUrl, headers = commonHeaders).document
        
        var items = doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
        if (items.isEmpty()) {
             items = doc.select(".mov_list ul li, .item").mapNotNull { it.toSearchResponse() }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        println("[TVMon][v1.4] 상세 페이지 load 진입: $url")

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
                println("[TVMon] URL에서 터널링된 포스터 복원: $passedPoster")
            }
        } catch (e: Exception) {
            println("[TVMon] 포스터 파라미터 복원 실패: ${e.message}")
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
        title = title!!.replace(
            Regex("\\s*\\d+\\s*[화회부].*"),
            ""
        ).replace(" 다시보기", "").trim()

        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            val hasKorean = pureOriTitle.contains(Regex("[가-힣]"))
            if (!hasKorean && pureOriTitle.isNotEmpty()) {
                title = "$title (원제 : $pureOriTitle)"
            }
        }
        println("[TVMon] 상세 제목 확정: $title")

        var poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        val isInvalidPoster = poster.isEmpty() 
            || poster.contains("no3.png") 
            || poster.contains("no_image")
            || poster == mainUrl 
            || poster == "$mainUrl/"
            || poster.endsWith("/")
        
        if (isInvalidPoster) {
            if (passedPoster != null) {
                poster = passedPoster
                println("[TVMon] 유효하지 않은 포스터 감지 -> 터널링된 포스터 사용")
            }
        }

        // --- 태그 파싱 로직 (KOTBC 스타일로 변경) ---
        val tagsList = mutableListOf<String>()
        
        // 1. 기본 정보 태그화
        doc.select(".bo_v_info dd").forEach { dd ->
            val text = dd.text().trim()
            if (text.isNotEmpty() && !text.startsWith("제목")) {
                // "개봉년도" 라벨을 "공개일"로 변경하고 괄호 내용 정제
                val cleanedText = text.replace("개봉년도:", "공개일:")
                                      .replace(tagCleanRegex, "").trim()
                tagsList.add(cleanedText)
            }
        }

        // 2. 장르 정보 태그화
        val genreList = doc.select(".ctgs dd a").filter {
            val txt = it.text()
            !txt.contains("트레일러") && !it.hasClass("btn_watch")
        }.map { it.text().trim().replace(tagCleanRegex, "") }

        if (genreList.isNotEmpty()) {
            tagsList.add("장르: ${genreList.joinToString(", ")}")
        }
        
        println("[TVMon] 생성된 태그 개수: ${tagsList.size}")

        // --- 줄거리 파싱 ---
        var story = doc.selectFirst(".story")?.text()?.trim()
            ?: doc.selectFirst(".tmdb-overview")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: ""

        if (story.contains("다시보기") && story.contains("무료")) story = "다시보기"
        if (story.isEmpty()) story = "다시보기"

        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))

            val epName = li.selectFirst(".clamp")?.text()?.trim()
                ?: li.selectFirst("a.title")?.text()?.trim()
                ?: "Episode"

            val thumbImg = li.selectFirst(".img-container img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { null }
                ?: thumbImg?.attr("src")?.ifEmpty { null }
                ?: li.selectFirst("img")?.attr("src")

            newEpisode(href) {
                this.name = epName
                this.posterUrl = fixUrl(epThumb ?: "")
            }
        }.reversed()
        println("[TVMon] 에피소드 파싱 완료: ${episodes.size}건")

        val type = determineTypeFromUrl(realUrl)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: realUrl
                newMovieLoadResponse(title, realUrl, type, movieLink) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = story // 줄거리는 설명 내용만
                    this.tags = tagsList // 기타 정보는 태그로
                }
            }

            else -> {
                newTvSeriesLoadResponse(title, realUrl, type, episodes) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = story // 줄거리는 설명 내용만
                    this.tags = tagsList // 기타 정보는 태그로
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
        println("[TVMon][v1.4] loadLinks 진입: $data")
        val doc = app.get(data, headers = commonHeaders).document
        val thumbnailHint = extractThumbnailHint(doc)
        val iframe = doc.selectFirst("iframe#view_iframe")
        val playerUrl = iframe?.attr("data-player1")?.ifEmpty { null }
            ?: iframe?.attr("data-player2")?.ifEmpty { null }
            ?: iframe?.attr("src")

        if (playerUrl != null) {
            val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")
            println("[TVMon] 플레이어 URL 발견: $finalPlayerUrl")
            val extracted = BunnyPoorCdn().extract(
                finalPlayerUrl,
                data,
                subtitleCallback,
                callback,
                thumbnailHint
            )
            if (extracted) return true
        }

        if (thumbnailHint != null) {
            println("[TVMon] 썸네일 힌트 기반 다이렉트 링크 생성 시도: $thumbnailHint")
            try {
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(thumbnailHint)
                if (pathMatch != null) {
                    val m3u8Url =
                        thumbnailHint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    val fixedM3u8Url = m3u8Url.replace(Regex("//v/"), "/v/")
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
                println("[TVMon] 다이렉트 링크 생성 에러: ${e.message}")
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
            if (priorityRegex.containsMatchIn(fixed)) {
                return fixed
            }
        }
        return null
    }
}
