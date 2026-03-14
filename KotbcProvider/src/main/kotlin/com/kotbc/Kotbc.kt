package com.kotbc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// v4.4: Mutex 도입을 위한 코루틴 동기화 라이브러리 추가
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Kotbc Provider v4.4
 * - Rollback: v4.2 베이스로 롤백 (SharedPreferences 디스크 캐싱 로직 완전 제거)
 * - Fix: domainMutex를 추가하여 병렬 로드 시 다중 스레드가 중복으로 도메인 스캔을 도는 현상 원천 차단
 * - Fix: 메인 페이지 파싱 결과가 0건(또는 에러)이더라도 isDomainChecked = false로 초기화하는 위험 로직 완전 삭제
 */
class Kotbc : MainAPI() {
    companion object {
        var currentMainUrl = "https://m143.kotbc2.com" 
        var isDomainChecked = false 
        private val domainMutex = Mutex() // v4.4: 동시성 스캔 방지용 뮤텍스 자물쇠
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

    // v4.4: 동적 도메인 탐색 및 리다이렉트 갱신 로직 (Mutex 락 적용)
    private suspend fun checkAndUpdateDomain() {
        // 이미 누군가 체크를 끝냈다면 빠르게 리턴
        if (isDomainChecked) {
            println("[Kotbc v4.4] 도메인 체크 완료 상태. 현재 도메인 유지: $currentMainUrl")
            return
        }
        
        domainMutex.withLock {
            // 락을 획득하고 들어왔는데 그 사이 다른 스레드가 끝내놨다면 리턴 (Double-checked locking)
            if (isDomainChecked) {
                println("[Kotbc v4.4] 도메인 체크 이미 완료됨. 대기하던 스레드 우회 처리. 도메인 유지: $currentMainUrl")
                return@withLock
            }

            println("[Kotbc v4.4] 도메인 유효성 검사 시작 (단일 스레드 진입): $currentMainUrl")
            
            try {
                val res = app.get(currentMainUrl)
                val finalUrl = res.url.trimEnd('/')
                
                // 실제 데이터 구조가 있는지 팩트 체크
                if (res.isSuccessful && res.text.contains("list-box")) {
                    if (currentMainUrl != finalUrl) {
                        println("[Kotbc v4.4] HTTP 리다이렉트 감지. 도메인 강제 갱신: $currentMainUrl -> $finalUrl")
                        currentMainUrl = finalUrl
                        mainUrl = currentMainUrl
                    } else {
                        println("[Kotbc v4.4] 현재 도메인이 유효하고 실제 데이터가 존재합니다: $currentMainUrl")
                    }
                    isDomainChecked = true
                    return@withLock
                } else {
                    println("[Kotbc v4.4] 접속은 성공했으나 데이터가 없습니다(JS 리다이렉트/차단 페이지 의심). 새 도메인 탐색을 시작합니다.")
                }
            } catch (e: Exception) {
                println("[Kotbc v4.4] 현재 도메인 접속 실패, 새 도메인 탐색을 시작합니다. 에러: ${e.message}")
            }

            // 탐색 1단계: 베이스 도메인 리다이렉트 검사
            try {
                println("[Kotbc v4.4] 베이스 도메인(kotbc2.com) 리다이렉트 추적 시도...")
                val redirectRes = app.get("https://kotbc2.com")
                val baseFinalUrl = redirectRes.url.trimEnd('/')
                if (redirectRes.isSuccessful && baseFinalUrl.contains("kotbc2.com") && redirectRes.text.contains("list-box")) {
                    currentMainUrl = baseFinalUrl
                    mainUrl = currentMainUrl
                    isDomainChecked = true
                    println("[Kotbc v4.4] 베이스 도메인 리다이렉트를 통해 새 도메인 및 데이터 확보: $currentMainUrl")
                    return@withLock
                }
            } catch (e: Exception) {
                println("[Kotbc v4.4] 베이스 도메인 리다이렉트 추적 실패: ${e.message}")
            }

            // 탐색 2단계: 숫자 증가 브루트포스 스캔
            val match = Regex("m(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 142
            
            println("[Kotbc v4.4] 도메인 번호 순차 스캔 시작 (m$startNum 부터)")
            for (i in startNum..startNum + 20) {
                val testUrl = "https://m$i.kotbc2.com"
                println("[Kotbc v4.4] 도메인 스캔 시도: $testUrl")
                try {
                    val res = app.get(testUrl, timeout = 3L)
                    val testFinalUrl = res.url.trimEnd('/')
                    if (res.isSuccessful && res.text.contains("list-box")) {
                        println("[Kotbc v4.4] 새 도메인 스캔 성공 및 데이터 확인!: $testFinalUrl")
                        currentMainUrl = testFinalUrl
                        mainUrl = currentMainUrl
                        isDomainChecked = true
                        return@withLock
                    }
                } catch (e: Exception) {
                    println("[Kotbc v4.4] 스캔 실패: $testUrl (${e.message})")
                }
            }
            
            println("[Kotbc v4.4] 도메인 탐색을 완료했지만 찾을 수 없습니다. 기존 도메인을 유지합니다.")
            isDomainChecked = true // 무한 재귀 방지
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val url = "$mainUrl/bbs/board.php?bo_table=${request.data}&page=$page"
        println("[Kotbc v4.4] getMainPage 요청: $url")
        return try {
            val doc = app.get(url).document
            val elements = doc.select(".list-body .list-row .list-box")
            val list = elements.mapNotNull { element -> element.toSearchResponse() }
            
            // v4.4 수정: 플래그 강제 초기화(isDomainChecked = false) 삭제하여 허공 스캔 루프 차단
            if (list.isEmpty()) {
                println("[Kotbc v4.4] 메인 페이지 데이터 로드 결과가 0건입니다. (빈 카테고리 또는 파싱 오류 발생 가능성)")
            } else {
                println("[Kotbc v4.4] 메인 페이지 로드 성공. 아이템 개수: ${list.size}")
            }
            
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            println("[Kotbc v4.4] Main page error 발생 사유: ${e.message}")
            // v4.4 수정: 에러 발생 시에도 플래그 강제 초기화 구문 삭제
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain()
        val url = "$mainUrl/bbs/search.php?sfl=wr_subject&stx=$query"
        println("[Kotbc v4.4] search 요청: $url")
        return try {
            val doc = app.get(url).document
            val elements = doc.select(".list-body .list-row .list-box")
            val list = elements.mapNotNull { element -> element.toSearchResponse() }
            
            if (list.isEmpty()) {
                println("[Kotbc v4.4] 검색 결과가 없거나 데이터 파싱에 실패했습니다. (검색어: $query)")
            }
            list
        } catch (e: Exception) {
            println("[Kotbc v4.4] Search 에러: ${e.message}")
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
        checkAndUpdateDomain()
        
        var targetUrl = url
        if (!targetUrl.startsWith(mainUrl)) {
            val path = targetUrl.replace(Regex("https?://[^/]+"), "")
            targetUrl = mainUrl + path
            println("[Kotbc v4.4] 과거 도메인 주소 감지. 최신 도메인으로 치환: $targetUrl")
        }
        
        println("[Kotbc v4.4] load 상세 페이지 요청: $targetUrl")
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
            println("[Kotbc v4.4] 에피소드 리스트 발견: ${episodeItems.size}개")
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
            println("[Kotbc v4.4] 에피소드 리스트 없음. 현재 페이지를 단일 에피소드로 처리.")
            episodes.add(newEpisode(targetUrl) { 
                this.name = title
                this.posterUrl = poster 
            })
        }

        val type = if (targetUrl.contains("bo_table=movie")) TvType.Movie else TvType.TvSeries
        
        if (type == TvType.Movie) {
            val movieDataUrl = episodes.firstOrNull()?.data ?: targetUrl
            println("[Kotbc v4.4] Movie 타입 로드 완료. Data URL: $movieDataUrl")
            
            return newMovieLoadResponse(title, targetUrl, TvType.Movie, movieDataUrl) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            println("[Kotbc v4.4] TVSeries 타입 로드 완료.")
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
        println("[Kotbc v4.4] loadLinks 영상 링크 요청: $data")
        try {
            val doc = app.get(data).document
            val form = doc.selectFirst("form.tt2") ?: doc.selectFirst("form.tt")
            if (form != null) {
                val action = form.attr("action")
                val vParam = form.selectFirst("input[name=v]")?.attr("value")
                if (action.isNotEmpty() && !vParam.isNullOrEmpty()) {
                    val targetUrl = "$action?v=$vParam"
                    println("[Kotbc v4.4] Extractor 호출 시작: $targetUrl")
                    val extractor = KotbcExtractor()
                    extractor.getUrl(targetUrl, mainUrl, subtitleCallback, callback)
                    return true
                }
            }
            
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("nnmo0oi1") || src.contains("glamov")) {
                     println("[Kotbc v4.4] Iframe 감지, Extractor 직접 호출: $src")
                     val extractor = KotbcExtractor()
                     extractor.getUrl(fixUrl(src), mainUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) { 
            println("[Kotbc v4.4] loadLinks 에러: ${e.message}")
            e.printStackTrace() 
        }
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
