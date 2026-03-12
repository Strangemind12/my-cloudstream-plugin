package com.kotbc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Kotbc Provider v4.0
 * - Version: v4.0
 * - Update: 도메인 자동 갱신 로직 추가 (리다이렉트 및 m숫자 순차 스캔)
 * - Update: 과거 북마크 링크 진입 시 최신 도메인으로 URL 자동 치환 기능 추가
 * - Fix: 영화(Movie)도 에피소드 구조(링크 클릭 필요)를 가질 수 있음을 반영.
 * - Logic: 영화/드라마 구분 없이 에피소드 리스트를 먼저 파싱한 후, 영화는 첫 번째 링크를 dataUrl로 사용.
 */
class Kotbc : MainAPI() {
    companion object {
        var currentMainUrl = "https://m142.kotbc2.com" // v4.0: 초기 도메인
        var isDomainChecked = false // 세션 당 1회만 체크하기 위한 플래그
    }

    override var mainUrl = currentMainUrl
    override var name = "KOTBC"
    override val hasMainPage = true
    override var lang = "ko"
    
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "movie" to "영화",
        "drama" to "드라마",
        "enter" to "예능/시사",
        "mid" to "해외드라마"
    )

    // v4.0: 동적 도메인 탐색 및 갱신 로직
    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) {
            println("[Kotbc v4.0] 도메인 체크 완료 상태. 현재 도메인 유지: $currentMainUrl")
            return
        }
        
        println("[Kotbc v4.0] 도메인 유효성 검사 시작: $currentMainUrl")
        
        try {
            val res = app.get(currentMainUrl)
            if (res.isSuccessful) {
                println("[Kotbc v4.0] 현재 도메인이 유효합니다: $currentMainUrl")
                isDomainChecked = true
                return
            }
        } catch (e: Exception) {
            println("[Kotbc v4.0] 현재 도메인 접속 실패, 새 도메인 탐색을 시작합니다. 에러: ${e.message}")
        }

        // 탐색 1단계: 베이스 도메인 리다이렉트 검사
        try {
            println("[Kotbc v4.0] 베이스 도메인(kotbc2.com) 리다이렉트 추적 시도...")
            val redirectRes = app.get("https://kotbc2.com")
            if (redirectRes.isSuccessful && redirectRes.url.contains("kotbc2.com")) {
                currentMainUrl = redirectRes.url.trimEnd('/')
                mainUrl = currentMainUrl
                isDomainChecked = true
                println("[Kotbc v4.0] 베이스 도메인 리다이렉트를 통해 새 도메인 확보: $currentMainUrl")
                return
            }
        } catch (e: Exception) {
            println("[Kotbc v4.0] 베이스 도메인 리다이렉트 추적 실패: ${e.message}")
        }

        // 탐색 2단계: 숫자 증가 브루트포스 스캔
        val match = Regex("m(\\d+)").find(currentMainUrl)
        val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 136
        
        println("[Kotbc v4.0] 도메인 번호 순차 스캔 시작 (m$startNum 부터)")
        for (i in startNum..startNum + 20) {
            val testUrl = "https://m$i.kotbc2.com"
            println("[Kotbc v4.0] 도메인 스캔 시도: $testUrl")
            try {
                val res = app.get(testUrl, timeout = 3L)
                if (res.isSuccessful) {
                    println("[Kotbc v4.0] 새 도메인 스캔 성공!: $testUrl")
                    currentMainUrl = testUrl
                    mainUrl = currentMainUrl
                    isDomainChecked = true
                    return
                }
            } catch (e: Exception) {
                println("[Kotbc v4.0] 스캔 실패: $testUrl (${e.message})")
            }
        }
        
        println("[Kotbc v4.0] 도메인 탐색을 완료했지만 찾을 수 없습니다. 기존 도메인을 유지합니다.")
        isDomainChecked = true // 무한 재귀 방지
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain() // v4.0: 도메인 갱신 확인
        val url = "$mainUrl/bbs/board.php?bo_table=${request.data}&page=$page"
        println("[Kotbc v4.0] getMainPage: $url")
        return try {
            val doc = app.get(url).document
            val elements = doc.select(".list-body .list-row .list-box")
            val list = elements.mapNotNull { element -> element.toSearchResponse() }
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            println("[Kotbc v4.0] Main page error: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain() // v4.0: 도메인 갱신 확인
        val url = "$mainUrl/bbs/search.php?sfl=wr_subject&stx=$query"
        println("[Kotbc v4.0] search: $url")
        return try {
            val doc = app.get(url).document
            val elements = doc.select(".list-body .list-row .list-box")
            elements.mapNotNull { element -> element.toSearchResponse() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        try {
            val linkEl = this.selectFirst(".list-front a") ?: this.selectFirst("a")
            val href = linkEl?.attr("href") ?: return null
            val fullUrl = fixUrl(href)
            val titleEl = this.selectFirst(".post-title")
            var title = titleEl?.text()?.trim() ?: linkEl.text().trim()
            title = title.replace(Regex("\\s*\\(\\d{4}\\)$"), "").trim()
            val imgTag = this.selectFirst(".img-item img") ?: this.selectFirst("img")
            val posterUrl = imgTag?.attr("src")?.let { resolvePosterUrl(it) }
            val type = if (href.contains("bo_table=movie")) TvType.Movie else TvType.TvSeries

            return if (type == TvType.Movie) {
                newMovieSearchResponse(title, fullUrl, TvType.Movie) { this.posterUrl = posterUrl }
            } else {
                newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) { this.posterUrl = posterUrl }
            }
        } catch (e: Exception) { return null }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain() // v4.0: 도메인 갱신 확인
        
        // v4.0: 북마크에 저장된 과거 도메인일 경우 최신 도메인으로 자동 치환
        var targetUrl = url
        if (!targetUrl.startsWith(mainUrl)) {
            val path = targetUrl.replace(Regex("https?://[^/]+"), "")
            targetUrl = mainUrl + path
            println("[Kotbc v4.0] 과거 도메인 주소 감지. 최신 도메인으로 치환: $targetUrl")
        }
        
        println("[Kotbc v4.0] load: $targetUrl")
        val doc = app.get(targetUrl).document
        val title = doc.selectFirst(".view-title h1")?.text()?.trim()?.replace(Regex("\\s*\\(\\d{4}\\)$"), "") ?: "Unknown"
        val poster = doc.selectFirst(".view-info .image img")?.attr("src")?.let { resolvePosterUrl(it) }
        val description = doc.selectFirst(".view-cont")?.text()?.trim()
        
        val tags = doc.select(".view-info p").mapNotNull { p ->
            val labelEl = p.selectFirst("span.block:first-child")
            val valueEl = p.selectFirst("span.block:last-child")
            
            if (labelEl != null && valueEl != null) {
                var label = labelEl.text().trim()
                var value = valueEl.text().trim()

                if (label == "제목") return@mapNotNull null

                if (label == "개요") {
                    label = "장르"
                    value = value.replace(Regex("\\s*(한국|해외)?영화\\s*\\(\\d{4}\\).*"), "").trim()
                }

                if (value.isNotEmpty()) "$label: $value" else null
            } else {
                null
            }
        }

        val episodes = mutableListOf<Episode>()
        val episodeItems = doc.select(".serial-list .list-body .list-item")
        
        if (episodeItems.isNotEmpty()) {
            println("[Kotbc v4.0] 에피소드 리스트 발견: ${episodeItems.size}개")
            episodeItems.forEach { item ->
                val linkEl = item.selectFirst("a.item-subject")
                val epHref = linkEl?.attr("href")
                val epName = linkEl?.text()?.trim() ?: "Episode"
                val epNum = Regex("(\\d+)[화회부]").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                
                if (!epHref.isNullOrEmpty()) {
                    episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
            }
        } else {
            println("[Kotbc v4.0] 에피소드 리스트 없음. 현재 페이지를 단일 에피소드로 처리.")
            episodes.add(newEpisode(targetUrl) { 
                this.name = title
                this.posterUrl = poster 
            })
        }

        val type = if (targetUrl.contains("bo_table=movie")) TvType.Movie else TvType.TvSeries
        
        if (type == TvType.Movie) {
            val movieDataUrl = episodes.firstOrNull()?.data ?: targetUrl
            println("[Kotbc v4.0] Movie 타입 로드. Data URL: $movieDataUrl")
            
            return newMovieLoadResponse(title, targetUrl, TvType.Movie, movieDataUrl) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            println("[Kotbc v4.0] TVSeries 타입 로드.")
            return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes.sortedBy { it.episode ?: 0 }) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[Kotbc v4.0] loadLinks: $data")
        try {
            val doc = app.get(data).document
            val form = doc.selectFirst("form.tt2") ?: doc.selectFirst("form.tt")
            if (form != null) {
                val action = form.attr("action")
                val vParam = form.selectFirst("input[name=v]")?.attr("value")
                if (action.isNotEmpty() && !vParam.isNullOrEmpty()) {
                    val targetUrl = "$action?v=$vParam"
                    println("[Kotbc v4.0] Extractor 호출: $targetUrl")
                    val extractor = KotbcExtractor()
                    extractor.getUrl(targetUrl, mainUrl, subtitleCallback, callback)
                    return true
                }
            }
            
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("nnmo0oi1") || src.contains("glamov")) {
                     val extractor = KotbcExtractor()
                     extractor.getUrl(fixUrl(src), mainUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val baseUrl = "$mainUrl/bbs/"
        if (url.startsWith("./")) return baseUrl + url.substring(2)
        if (url.startsWith("/")) return mainUrl + url
        return baseUrl + url
    }

    private fun resolvePosterUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("../")) return mainUrl + "/" + url.substring(3)
        if (url.startsWith("/")) return mainUrl + url
        return "$mainUrl/bbs/$url"
    }
}
