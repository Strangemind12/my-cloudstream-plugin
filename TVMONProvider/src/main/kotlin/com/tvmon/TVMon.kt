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
 * TVMon Provider v1.2
 *
 * [v1.2 수정 사항]
 * 1. CSS Selector 확장: '.mov_list ul li' 외에 '.item' (사용자 제보 소스 대응) 추가
 * 2. 포스터 URL 절대 경로 강제 변환 로직 추가 (/data/... -> https://tvmon.site/data/...)
 * 3. 디버깅 로그 강화 (포스터 감지 및 인코딩 여부 확인용)
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
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            
            // [v1.2 수정] .mov_list ul li 뿐만 아니라 .item (div)도 선택하도록 범위 확장
            // 사용자 소스 코드: <div class="item">...</div> 대응
            var elements = doc.select(".mov_list ul li")
            if (elements.isEmpty()) {
                elements = doc.select(".item")
            }
            // 그래도 없으면 섞어서 찾기
            if (elements.isEmpty()) {
                elements = doc.select("li, div.item")
            }

            val list = elements.mapNotNull { it.toSearchResponse() }
            
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        // [v1.2 디버깅] 현재 처리 중인 요소 확인 (너무 많으면 주석 처리)
        // println("[TVMon] Processing element: ${this.html().take(50)}...")

        val aTag = this.selectFirst("a.img") ?: return null
        var link = fixUrl(aTag.attr("href"))
        val title = this.selectFirst("a.title")?.text()?.trim() ?: return null

        val imgTag = aTag.selectFirst("img")
        var rawPoster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        // [v1.2 수정] 포스터 URL이 상대경로(/data/...)인 경우 강제로 절대경로로 변환
        // fixUrl에만 의존하지 않고 직접 문자열 처리
        if (rawPoster.startsWith("/")) {
            rawPoster = "$mainUrl$rawPoster"
        }
        val fixedPoster = fixUrl(rawPoster)

        // [v1.2 핵심] URL에 포스터 주소 인코딩해서 붙이기
        if (fixedPoster.isNotEmpty() && !fixedPoster.contains("no3.png")) { // no3.png(이미지 없음)는 제외
            try {
                val encodedPoster = URLEncoder.encode(fixedPoster, "UTF-8")
                val separator = if (link.contains("?")) "&" else "?"
                link = "$link${separator}cw_poster=$encodedPoster"
                
                // [v1.2 디버깅] 성공적으로 포스터를 URL에 심었는지 확인
                println("[TVMon] Poster Attached to Link: $title -> $encodedPoster")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
             println("[TVMon] No valid poster found for: $title (src: $rawPoster)")
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
        val searchUrl = "$mainUrl/search?stx=$query"
        val doc = app.get(searchUrl, headers = commonHeaders).document
        
        // 검색 결과에서도 .item을 찾을 수 있도록 선택자 확장
        var items = doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
        if (items.isEmpty()) {
             items = doc.select(".mov_list ul li, .item").mapNotNull { it.toSearchResponse() }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        println("[TVMon] load 진입: $url")

        // 1. URL 파라미터(cw_poster) 복원
        var passedPoster: String? = null
        var realUrl = url

        try {
            // cw_poster 파라미터 추출 정규식
            val regex = Regex("[?&]cw_poster=([^&]+)")
            val match = regex.find(url)
            if (match != null) {
                val encoded = match.groupValues[1]
                passedPoster = URLDecoder.decode(encoded, "UTF-8")
                realUrl = url.replace(match.value, "")
                
                // URL 정제 (끝에 남은 ? 또는 & 제거)
                if (realUrl.endsWith("?") || realUrl.endsWith("&")) {
                    realUrl = realUrl.dropLast(1)
                }
                println("[TVMon] Link에서 포스터 복원 성공: $passedPoster")
            } else {
                println("[TVMon] Link에 포스터 정보 없음")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("[TVMon] 포스터 디코딩 에러: ${e.message}")
        }

        // 2. 상세 페이지 파싱
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
            Regex("\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\s*\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\d+\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\s*[화회부].*"),
            ""
        ).replace(" 다시보기", "").trim()

        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            val hasKorean = pureOriTitle.contains(Regex("[가-힣]"))
            if (!hasKorean && pureOriTitle.isNotEmpty()) {
                title = "$title (원제 : $pureOriTitle)"
            }
        }

        // 3. 상세 페이지 내 포스터 찾기
        var poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        // [v1.2 핵심] 상세페이지 포스터가 없거나, 'no image' 아이콘인 경우 => 전달받은 포스터(passedPoster) 사용
        val isInvalidPoster = poster.isEmpty() || poster.contains("no3.png") || poster.contains("no_image")
        
        if (isInvalidPoster) {
            if (passedPoster != null) {
                println("[TVMon] 상세페이지 포스터가 유효하지 않음($poster) -> 전달받은 포스터 적용: $passedPoster")
                poster = passedPoster
            } else {
                println("[TVMon] 상세페이지 포스터 없음 & 전달받은 포스터도 없음")
            }
        } else {
             println("[TVMon] 상세페이지 포스터 사용: $poster")
        }

        val infoList = doc.select(".bo_v_info dd").map { it.text().trim().replace("개봉년도:", "공개일:") }
        val genreList = doc.select(".ctgs dd a").filter {
            val txt = it.text()
            !txt.contains("트레일러") && !it.hasClass("btn_watch")
        }.map { it.text().trim() }

        val genreFormatted = if (genreList.isNotEmpty()) {
            "장르: ${genreList.joinToString(", ")}"
        } else {
            ""
        }

        val metaParts = mutableListOf<String>()
        if (infoList.isNotEmpty()) {
            metaParts.add(infoList.joinToString(" / "))
        }
        if (genreFormatted.isNotEmpty()) {
            metaParts.add(genreFormatted)
        }
        val metaString = metaParts.joinToString(" / ")

        var story = doc.selectFirst(".story")?.text()?.trim()
            ?: doc.selectFirst(".tmdb-overview")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: ""

        if (story.contains("다시보기") && story.contains("무료")) story = "다시보기"
        if (story.isEmpty()) story = "다시보기"

        val finalPlot = if (story == "다시보기") {
            "다시보기"
        } else {
            "$metaString / 줄거리: $story".trim()
        }

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
        // 기존 코드와 동일 (변경 없음)
        val doc = app.get(data, headers = commonHeaders).document
        val thumbnailHint = extractThumbnailHint(doc)
        val iframe = doc.selectFirst("iframe#view_iframe")
        val playerUrl = iframe?.attr("data-player1")?.ifEmpty { null }
            ?: iframe?.attr("data-player2")?.ifEmpty { null }
            ?: iframe?.attr("src")

        if (playerUrl != null) {
            val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")
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
            if (priorityRegex.containsMatchIn(fixed)) {
                return fixed
            }
        }
        return null
    }
}
