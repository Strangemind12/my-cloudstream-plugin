package com.tvwiki

import android.content.Context
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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
// v2.6: 코루틴 취소 예외 처리를 위한 import 추가
import kotlinx.coroutines.CancellationException

/**
 * TVWiki Provider v2.6
 * - Fix: StandaloneCoroutine was cancelled 예외가 isDomainChecked 플래그를 오염시켜 스캔을 마비시키는 치명적 버그 해결
 * - Update: 모든 예외 처리 블록에 CancellationException을 상위로 다시 던지는(rethrow) 방어 로직 추가
 * - Retain: v2.4의 Mutex 동기화, SharedPreferences 디스크 캐시, HTTPS 강제 변환 유지
 */
class TVWiki : MainAPI() {
    companion object {
        var currentMainUrl = "https://tvwiki7.net" 
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "TVWiki_Domain_Cache"
        private const val PREF_KEY = "current_domain"
    }

    override var mainUrl = currentMainUrl
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

    private val tagCleanRegex = Regex("""\s*(한국|해외)?영화\s*\(\d{4}\).*""")
    private val requestMutex = Mutex()

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

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return

        domainMutex.withLock {
            if (isDomainChecked) return@withLock

            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawCachedDomain = prefs?.getString(PREF_KEY, null)
            val cachedDomain = rawCachedDomain?.replace("http://", "https://")
            
            if (cachedDomain != null && currentMainUrl == "https://tvwiki7.net") {
                println("[TVWiki v2.6] 디스크 캐시에서 도메인 로드 완료: $cachedDomain")
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
            }

            val testPath = "/popular"
            println("[TVWiki v2.6] 도메인 유효성 검사 시작 (단일 스레드 진입): $currentMainUrl$testPath")
            
            try {
                val res = app.get("$currentMainUrl$testPath", headers = commonHeaders)
                val extractedUrl = Regex("^https?://[^/]+").find(res.url)?.value ?: currentMainUrl
                val finalUrl = extractedUrl.replace("http://", "https://")
                
                if (res.isSuccessful && res.text.contains("list_type")) {
                    if (currentMainUrl != finalUrl) {
                        println("[TVWiki v2.6] 리다이렉트 감지. 도메인 강제 갱신 및 저장: $currentMainUrl -> $finalUrl")
                        currentMainUrl = finalUrl
                        mainUrl = currentMainUrl
                        prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                    } else {
                        println("[TVWiki v2.6] 현재 도메인이 유효합니다: $currentMainUrl")
                    }
                    isDomainChecked = true
                    return@withLock
                } else {
                    println("[TVWiki v2.6] 접속은 성공했으나 데이터가 없습니다. 새 도메인 스캔을 시작합니다.")
                }
            } catch (e: Exception) {
                // v2.6 핵심 픽스: 코루틴 취소 에러 발생 시, 플래그를 오염시키지 않고 즉시 중단 및 락 해제
                if (e is CancellationException) {
                    println("[TVWiki v2.6] 코루틴 취소됨(CancellationException). 스캔을 안전하게 중단합니다.")
                    throw e 
                }
                println("[TVWiki v2.6] 접속 실패, 새 스캔 시작. 에러: ${e.message}")
            }

            val match = Regex("tvwiki(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 7
            
            println("[TVWiki v2.6] 번호 순차 스캔 시작 (tvwiki$startNum 부터)")
            for (i in startNum..startNum + 20) {
                val testDomain = "https://tvwiki$i.net"
                val testUrl = "$testDomain$testPath"
                println("[TVWiki v2.6] 스캔 시도: $testUrl")
                try {
                    val res = app.get(testUrl, headers = commonHeaders, timeout = 3L)
                    val extractedTestUrl = Regex("^https?://[^/]+").find(res.url)?.value ?: testDomain
                    val testFinalUrl = extractedTestUrl.replace("http://", "https://")
                    
                    if (res.isSuccessful && res.text.contains("list_type")) {
                        println("[TVWiki v2.6] 새 도메인 스캔 성공!: $testFinalUrl")
                        currentMainUrl = testFinalUrl
                        mainUrl = currentMainUrl
                        isDomainChecked = true
                        
                        prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                        return@withLock
                    }
                } catch (e: Exception) {
                    // v2.6 핵심 픽스: 반복문 내에서도 취소 발생 시 안전하게 탈출
                    if (e is CancellationException) throw e
                    println("[TVWiki v2.6] 스캔 실패: $testUrl (${e.message})")
                }
            }
            
            println("[TVWiki v2.6] 도메인 탐색을 완료했지만 찾을 수 없습니다.")
            isDomainChecked = true 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val url = "$mainUrl${request.data}?page=$page"
        
        return try {
            val doc = requestMutex.withLock {
                val randomDelay = (200..800).random().toLong()
                println("[TVWiki v2.6] 카테고리 로드 대기: ${request.name} | ${randomDelay}ms 지연 후 요청: $url")
                delay(randomDelay)
                app.get(url, headers = commonHeaders).document
            }
            
            val list = doc.select("#list_type ul li").mapNotNull { it.toSearchResponse() }
            
            if (list.isEmpty()) {
                println("[TVWiki v2.6] 메인 페이지 데이터 로드 결과가 0건입니다.")
            } else {
                println("[TVWiki v2.6] 메인 페이지 로드 성공. 아이템 개수: ${list.size} - 카테고리: ${request.name}")
            }
            
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[TVWiki v2.6] Main page error: ${e.message}")
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
                title, link, type
            ) { this.posterUrl = fixedPoster }
            TvType.Anime -> newAnimeSearchResponse(
                title, link, TvType.Anime
            ) { this.posterUrl = fixedPoster }
            else -> newTvSeriesSearchResponse(
                title, link, TvType.TvSeries
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
        checkAndUpdateDomain() 
        val searchUrl = "$mainUrl/search?stx=$query"
        println("[TVWiki v2.6] search: $searchUrl")
        return try {
            val doc = app.get(searchUrl, headers = commonHeaders).document
            
            var items = doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
            if (items.isEmpty()) {
                 items = doc.select("#list_type ul li").mapNotNull { it.toSearchResponse() }
            }
            if (items.isEmpty()) {
                println("[TVWiki v2.6] 검색 결과가 없거나 데이터 파싱에 실패했습니다. (검색어: $query)")
            }
            items
        } catch (e: Exception) { 
            if (e is CancellationException) throw e
            println("[TVWiki v2.6] Search 에러: ${e.message}")
            emptyList() 
        }
    }

    private fun getEpisodeNumber(name: String): Int {
        return try {
            val numberString = name.replace(Regex("[^0-9]"), "")
            if (numberString.isNotEmpty()) numberString.toInt() else Int.MAX_VALUE
        } catch (e: Exception) { Int.MAX_VALUE }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain() 
        
        var targetUrl = url
        if (!targetUrl.startsWith(mainUrl) && targetUrl.contains("tvwiki")) {
            val path = targetUrl.replace(Regex("https?://[^/]+"), "")
            targetUrl = mainUrl + path
            println("[TVWiki v2.6] 과거 도메인 주소 감지. 최신 도메인으로 치환: $targetUrl")
        }

        println("[TVWiki v2.6] load 시작 - URL: $targetUrl")

        var passedPoster: String? = null
        var realUrl = targetUrl

        try {
            val regex = Regex("[?&]cw_poster=([^&]+)")
            val match = regex.find(targetUrl)
            if (match != null) {
                val encoded = match.groupValues[1]
                passedPoster = URLDecoder.decode(encoded, "UTF-8")
                realUrl = targetUrl.replace(match.value, "")
                if (realUrl.endsWith("?") || realUrl.endsWith("&")) {
                    realUrl = realUrl.dropLast(1)
                }
                println("[TVWiki v2.6] 터널링 포스터 복원 완료: $passedPoster")
            }
        } catch (e: Exception) { e.printStackTrace() }

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
            ?: ""

        val tvwikiMainUrlRegex = Regex("""^https?://tvwiki\d*\.net/?$""")
        val isInvalidPoster = poster.isEmpty() 
            || poster.contains("no3.png") 
            || poster.contains("no_image")
            || tvwikiMainUrlRegex.matches(poster)
            || poster.endsWith("/")

        if (isInvalidPoster && passedPoster != null) {
            println("[TVWiki v2.6] 상세페이지 포스터 무효 확인. 터널링된 포스터 우선 적용 진행.")
            poster = passedPoster
        }

        val tagsList = mutableListOf<String>()
        
        doc.select(".bo_v_info dd").forEach { dd ->
            val text = dd.text().trim()
            if (text.isNotEmpty() && !text.startsWith("제목")) {
                val renamedText = text.replace("개봉년도:", "공개일:")
                val cleanedText = renamedText.replace(tagCleanRegex, "").trim()
                if (cleanedText.isNotEmpty()) tagsList.add(cleanedText)
            }
        }

        val genreList = doc.select(".tags dd a").filter {
            !it.text().contains("트레일러") && !it.hasClass("btn_watch")
        }.map { it.text().trim().replace(tagCleanRegex, "") }

        if (genreList.isNotEmpty()) tagsList.add("장르: ${genreList.joinToString(", ")}")

        val castList = doc.select(".slider_act .item .name").map { it.text().trim().replace(tagCleanRegex, "") }
        if (castList.isNotEmpty() && castList.none { it.contains("운영팀") }) {
            tagsList.add("출연: ${castList.joinToString(", ")}")
        }

        var story = doc.selectFirst("#bo_v_con")?.text()?.trim()
            ?: doc.selectFirst(".story")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: "다시보기"

        if (story.isEmpty() || (story.contains("다시보기") && story.contains("무료"))) {
            story = "다시보기"
        }
        
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
        }.sortedBy { getEpisodeNumber(it.name ?: "") }
        
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
        checkAndUpdateDomain()
        
        var targetData = data
        if (!targetData.startsWith(mainUrl) && targetData.contains("tvwiki")) {
            val path = targetData.replace(Regex("https?://[^/]+"), "")
            targetData = mainUrl + path
            println("[TVWiki v2.6] loadLinks 과거 도메인 치환: $targetData")
        }

        val doc = try {
            app.get(targetData, headers = commonHeaders).document
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return false
        }

        if (extractFromApi(doc, targetData, subtitleCallback, callback)) {
            return true
        }

        val iframe = doc.selectFirst("iframe#view_iframe")
        if (iframe != null) {
            val playerUrl = iframe.attr("src")
            if (playerUrl.contains("player.bunny-frame.online")) {
                 val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), targetData, subtitleCallback, callback, null)
                 if(extracted) return true
            }
        }

        try {
            val webViewInterceptor = WebViewResolver(
                Regex("bunny-frame|googleapis"), 
                timeout = 15000L
            )
            val response = app.get(targetData, headers = commonHeaders, interceptor = webViewInterceptor)
            val webViewDoc = response.document
            
            val wbIframe = webViewDoc.selectFirst("iframe#view_iframe") ?: webViewDoc.selectFirst("iframe[src*='bunny-frame']")
            if (wbIframe != null) {
                val playerUrl = wbIframe.attr("src")
                if (playerUrl.contains("player.bunny-frame.online")) {
                     val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), targetData, subtitleCallback, callback, null)
                     if(extracted) return true
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
            } catch (e: Exception) { 
                if (e is CancellationException) throw e
                e.printStackTrace() 
            }
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
        } catch (e: Exception) { 
            if (e is CancellationException) throw e
            e.printStackTrace() 
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
