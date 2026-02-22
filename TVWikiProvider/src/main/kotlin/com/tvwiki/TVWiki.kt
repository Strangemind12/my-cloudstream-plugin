package com.tvwiki

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * TVWiki Provider v1.6
 * [v1.6 수정 사항]
 * - 상세 페이지 포스터 Fallback 정규식 강화 (tvwiki숫자.net 형태의 메인 URL 무효화 처리)
 * - 플롯(줄거리) 추출 시 빈 값(Empty)일 경우 "다시보기" 텍스트로 치환되도록 방어 로직 추가
 * - 주요 실행 흐름 추적을 위한 디버깅 로그 추가
 * * [v1.5 수정 사항]
 * - 상세 페이지 포스터 Fallback 로직 강화 (애니/예능 포스터 누락 수정)
 * - 사이트가 og:image에 메인 URL이나 'no_image'를 넣을 경우, 이를 무효 처리하고 터널링된 포스터 사용.
 * - toSearchResponse에서 포스터 절대 경로 변환 로직 추가.
 */
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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    // 태그 내용 정제용 정규식
    private val tagCleanRegex = Regex("""\s*(한국|해외)?영화\s*\(\d{4}\).*""")

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
        var rawPoster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        // [v1.5 수정] 포스터가 상대 경로일 경우 절대 경로로 변환하여 전달 (중요)
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
        println("[TVWiki v1.6] load 시작 - URL: $url")

        // 1. URL 파라미터(cw_poster) 복원
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
                println("[TVWiki v1.6] 터널링 포스터 복원 완료: $passedPoster")
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

        // 2. 상세 페이지 포스터 추출
        var poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        // [v1.6 핵심 수정] 포스터 유효성 검사 (정규식 기반 와일드카드 도메인 차단)
        val tvwikiMainUrlRegex = Regex("""^https?://tvwiki\d*\.net/?$""")
        val isInvalidPoster = poster.isEmpty() 
            || poster.contains("no3.png") 
            || poster.contains("no_image")
            || tvwikiMainUrlRegex.matches(poster)
            || poster.endsWith("/") // 디렉토리 경로만 있는 경우

        println("[TVWiki v1.6] 포스터 유효성 검사 - 추출된포스터: $poster / 무효판정여부: $isInvalidPoster")

        if (isInvalidPoster) {
            if (passedPoster != null) {
                println("[TVWiki v1.6] 상세페이지 포스터 무효 확인. 터널링된 포스터 우선 적용 진행.")
                poster = passedPoster
            }
        }

        // 태그 처리
        val tagsList = mutableListOf<String>()
        
        doc.select(".bo_v_info dd").forEach { dd ->
            var text = dd.text().trim()
            if (text.isNotEmpty() && !text.startsWith("제목")) {
                val renamedText = text.replace("개봉년도:", "공개일:")
                val cleanedText = renamedText.replace(tagCleanRegex, "").trim()
                
                if (cleanedText.isNotEmpty()) {
                    tagsList.add(cleanedText)
                }
            }
        }

        val genreList = doc.select(".tags dd a").filter {
            val txt = it.text()
            !txt.contains("트레일러") && !it.hasClass("btn_watch")
        }.map { it.text().trim().replace(tagCleanRegex, "") }

        if (genreList.isNotEmpty()) {
            val genreTag = "장르: ${genreList.joinToString(", ")}"
            tagsList.add(genreTag)
        }

        val castList = doc.select(".slider_act .item .name").map { it.text().trim().replace(tagCleanRegex, "") }
        if (castList.isNotEmpty() && castList.none { it.contains("운영팀") }) {
            val castTag = "출연: ${castList.joinToString(", ")}"
            tagsList.add(castTag)
        }

        var story = doc.selectFirst("#bo_v_con")?.text()?.trim()
            ?: doc.selectFirst(".story")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: "다시보기"

        // [v1.6 핵심 수정] 줄거리가 빈 문자열인 경우 또는 쓸모없는 텍스트일 경우 확실히 처리
        if (story.isEmpty() || (story.contains("다시보기") && story.contains("무료"))) {
            story = "다시보기"
        }
        
        println("[TVWiki v1.6] 최종 적용된 줄거리(Plot) 텍스트 길이: ${story.length}")
        
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val epName = li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            val thumbImg = li.selectFirst("a.img img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { null }
                ?: thumbImg?.attr("data-original")?.ifEmpty { null }
                ?: thumbImg?.attr("src")?.ifEmpty { null }
                ?: li.selectFirst("img")?.attr("src")

            newEpisode(href) {
                this.name = epName
                this.posterUrl = fixUrl(epThumb ?: "")
            }
        }.sortedBy { 
            getEpisodeNumber(it.name ?: "") 
        }
        
        val type = determineTypeFromUrl(realUrl)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: realUrl
                newMovieLoadResponse(title, realUrl, type, movieLink) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = story
                    this.tags = tagsList
                }
            }

            else -> {
                newTvSeriesLoadResponse(title, realUrl, type, episodes) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = story
                    this.tags = tagsList
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
        // 기존 코드 유지
        val doc = app.get(data, headers = commonHeaders).document

        if (extractFromApi(doc, data, subtitleCallback, callback)) {
            return true
        }

        val iframe = doc.selectFirst("iframe#view_iframe")
        if (iframe != null) {
            val playerUrl = iframe.attr("src")
            if (playerUrl.contains("player.bunny-frame.online")) {
                 val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), data, subtitleCallback, callback, null)
                 if(extracted) return true
            }
        }

        try {
            val webViewInterceptor = WebViewResolver(
                Regex("bunny-frame|googleapis"), 
                timeout = 15000L
            )
            val response = app.get(data, headers = commonHeaders, interceptor = webViewInterceptor)
            val webViewDoc = response.document
            
            val wbIframe = webViewDoc.selectFirst("iframe#view_iframe") ?: webViewDoc.selectFirst("iframe[src*='bunny-frame']")
            if (wbIframe != null) {
                val playerUrl = wbIframe.attr("src")
                if (playerUrl.contains("player.bunny-frame.online")) {
                     val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), data, subtitleCallback, callback, null)
                     if(extracted) return true
                }
            }
        } catch (e: Exception) {
            // e.printStackTrace()
        }

        val thumbnailHint = extractThumbnailHint(doc)
        if (thumbnailHint != null) {
            try {
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(thumbnailHint)
                if (pathMatch != null) {
                    val m3u8Url = thumbnailHint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    callback(
                        newExtractorLink(name, name, m3u8Url.replace(Regex("//v/"), "/v/"), ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            this.headers = commonHeaders
                        }
                    )
                    return true
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        return false
    }

    private suspend fun extractFromApi(
        doc: Document,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val iframe = doc.selectFirst("iframe#view_iframe") ?: return false
            val sessionData = iframe.attr("data-session1").ifEmpty { iframe.attr("data-session2") }

            if (sessionData.isNullOrEmpty()) return false

            val apiUrl = "$mainUrl/api/create_session.php"
            val headers = commonHeaders.toMutableMap()
            headers["Content-Type"] = "application/json"
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["Referer"] = refererUrl 

            val requestBody = sessionData.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post(apiUrl, headers = headers, requestBody = requestBody)
            val json = response.parsedSafe<SessionResponse>()

            if (json != null && json.success && !json.playerUrl.isNullOrEmpty()) {
                val fullUrl = "${json.playerUrl}?t=${json.t}&sig=${json.sig}"
                if (fullUrl.contains("player.bunny-frame.online")) {
                    return BunnyPoorCdn().extract(fullUrl, refererUrl, subtitleCallback, callback, null)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
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
