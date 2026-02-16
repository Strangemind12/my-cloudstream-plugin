package com.movieking

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * MovieKing Provider v1.1
 * [변경 이력]
 * - v1.0: 초기 버전
 * - v1.1: 상세 페이지 정보를 태그(Tags)로 분리하고 줄거리(Plot)를 소개 내용으로 한정함.
 */
class MovieKing : MainAPI() {
    override var mainUrl = "https://mvking6.org"
    override var name = "MovieKing"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/"
    )

    private val titleRegex = Regex("""\s*\(\d{4}\)$""")
    // 태그 내용 정제용 정규식 (국가/연도 중복 표기 제거)
    private val tagCleanRegex = Regex("""\s*(한국|해외)?영화\s*\(?\d{4}\)?.*""")

    override val mainPage = mainPageOf(
        "/video?type=movie" to "영화",
        "/video?type=drama" to "드라마",
        "/video?type=enter" to "예능",
        "/video?type=ani" to "애니",
        "/video?type=docu" to "시사/다큐"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}&page=$page"
        println("[MovieKing][v1.1] getMainPage 요청: $url")
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse() }
            println("[MovieKing] 메인 아이템 검색 성공: ${list.size}건")
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            println("[MovieKing] 메인 페이지 로드 에러: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst(".video-card-image a") ?: return null
        val titleTag = this.selectFirst(".video-title a") ?: return null
        
        val href = fixUrl(linkTag.attr("href"))
        val rawTitle = titleTag.text().trim()
        val title = rawTitle.replace(titleRegex, "").trim()

        val imgTag = this.selectFirst("img")
        val rawPoster = imgTag?.attr("src") ?: imgTag?.attr("data-src")
        val fixedPoster = fixUrl(rawPoster ?: "")

        var finalHref = href
        if (fixedPoster.isNotEmpty()) {
            try {
                val encodedPoster = URLEncoder.encode(fixedPoster, "UTF-8")
                finalHref = "$href&poster_url=$encodedPoster"
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val type = if (href.contains("movie")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, finalHref, TvType.Movie) {
                this.posterUrl = fixedPoster
            }
        } else {
            newTvSeriesSearchResponse(title, finalHref, TvType.TvSeries) {
                this.posterUrl = fixedPoster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("[MovieKing][v1.1] 검색 요청: $query")
        val searchUrl = "$mainUrl/video/search?keyword=$query"
        val doc = app.get(searchUrl, headers = commonHeaders).document
        return doc.select(".video-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        println("[MovieKing][v1.1] 상세 페이지 로드 시작: $url")
        var passedPoster: String? = null
        var realUrl = url

        try {
            val match = Regex("""[&?]poster_url=([^&]+)""").find(url)
            if (match != null) {
                val encodedPoster = match.groupValues[1]
                passedPoster = URLDecoder.decode(encodedPoster, "UTF-8")
                realUrl = url.replace(match.value, "")
                println("[MovieKing] 전달된 포스터 URL 복원 성공")
            }
        } catch (e: Exception) {
            println("[MovieKing] 포스터 파라미터 처리 에러: ${e.message}")
        }

        val doc = app.get(realUrl, headers = commonHeaders).document
        val infoContent = doc.selectFirst(".single-video-info-content")
        
        val rawTitle = infoContent?.selectFirst("h3")?.text()?.trim() ?: "Unknown"
        val title = rawTitle.replace(titleRegex, "").trim()
        println("[MovieKing] 상세 제목: $title")
        
        var poster = passedPoster
        if (poster.isNullOrEmpty()) {
            poster = doc.selectFirst(".single-video-left img")?.attr("src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        }

        fun getInfoText(keyword: String): String? {
            return infoContent?.select("p:contains($keyword)")?.text()
                ?.replace(keyword, "")?.replace(":", "")?.trim()
        }

        // 데이터 추출
        val quality = getInfoText("화질")
        val genre = getInfoText("장르")?.replace(tagCleanRegex, "")?.trim()
        val country = getInfoText("나라")
        val releaseDate = getInfoText("개봉")
        val director = getInfoText("감독")
        val cast = getInfoText("출연")
        val intro = infoContent?.selectFirst("h6:contains(소개)")?.nextElementSibling()?.text()?.trim()

        println("[MovieKing] 데이터 파싱 완료 - 태그 생성 시작")

        // 태그 리스트 생성 (태그명: 태그내용 형식)
        val tagsList = mutableListOf<String>()
        if (!quality.isNullOrBlank()) tagsList.add("화질: $quality")
        if (!genre.isNullOrBlank()) tagsList.add("장르: $genre")
        if (!country.isNullOrBlank()) tagsList.add("국가: $country")
        if (!releaseDate.isNullOrBlank()) tagsList.add("공개일: $releaseDate")
        if (!director.isNullOrBlank()) tagsList.add("감독(방송사): $director")
        if (!cast.isNullOrBlank()) tagsList.add("출연: $cast")

        val year = releaseDate?.replace(Regex("[^0-9-]"), "")?.take(4)?.toIntOrNull()

        // 에피소드 파싱
        val episodeList = doc.select(".video-slider-right-list .eps_a").map { element ->
            val epHref = fixUrl(element.attr("href"))
            val epName = element.text().trim()
            newEpisode(epHref) {
                this.name = epName
            }
        }.reversed()

        println("[MovieKing] 에피소드 개수: ${episodeList.size}")

        val isMovie = episodeList.isEmpty() || (episodeList.size == 1 && realUrl.contains("type=movie"))

        return if (isMovie) {
            println("[MovieKing] Movie 타입으로 결과 반환")
            newMovieLoadResponse(title, realUrl, TvType.Movie, realUrl) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = intro // 줄거리는 소개 내용만
                this.tags = tagsList // 나머지 정보는 태그로
                this.year = year
            }
        } else {
            println("[MovieKing] TvSeries 타입으로 결과 반환")
            newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, episodeList) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = intro // 줄거리는 소개 내용만
                this.tags = tagsList // 나머지 정보는 태그로
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[MovieKing][v1.1] loadLinks 실행: $data")
        val doc = app.get(data, headers = commonHeaders).document
        val iframe = doc.selectFirst("iframe#view_iframe")
        val src = iframe?.attr("src")

        return if (src != null) {
            val fixedSrc = fixUrl(src)
            println("[MovieKing] iframe 발견: $fixedSrc")
            loadExtractor(fixedSrc, data, subtitleCallback, callback)
            true
        } else {
            println("[MovieKing] iframe을 찾을 수 없음")
            false
        }
    }
}
