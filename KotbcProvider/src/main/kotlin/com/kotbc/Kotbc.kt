package com.kotbc

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Kotbc : MainAPI() {
    companion object {
        var currentMainUrl = "https://m142.kotbc2.com" 
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "Kotbc_Domain_Cache"
        private const val PREF_KEY = "current_domain"
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

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return

        domainMutex.withLock {
            if (isDomainChecked) return@withLock
            
            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedDomain = prefs?.getString(PREF_KEY, null)

            if (cachedDomain != null && currentMainUrl == "https://m142.kotbc2.com") {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
            }
            
            try {
                val res = app.get(currentMainUrl, timeout = 3L)
                val finalUrl = res.url.trimEnd('/')
                
                if (res.isSuccessful && res.text.contains("list-box")) {
                    if (currentMainUrl != finalUrl) {
                        currentMainUrl = finalUrl
                        mainUrl = currentMainUrl
                        prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                    }
                    isDomainChecked = true
                    return@withLock
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }

            try {
                val redirectRes = app.get("https://kotbc2.com", timeout = 3L)
                val baseFinalUrl = redirectRes.url.trimEnd('/')
                if (redirectRes.isSuccessful && baseFinalUrl.contains("kotbc2.com") && redirectRes.text.contains("list-box")) {
                    currentMainUrl = baseFinalUrl
                    mainUrl = currentMainUrl
                    isDomainChecked = true
                    prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                    return@withLock
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }

            val match = Regex("m(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 142
            
            // [Fix] 도메인 50개 순차 스캔 -> 5개 단위 청크 병렬 스캔으로 속도 10배 향상
            coroutineScope {
                val candidates = (startNum..startNum + 50).map { "https://m$it.kotbc2.com" }
                val chunks = candidates.chunked(5)
                
                for (chunk in chunks) {
                    val deferreds = chunk.map { testUrl ->
                        async {
                            try {
                                val res = app.get(testUrl, timeout = 3L)
                                val testFinalUrl = res.url.trimEnd('/')
                                if (res.isSuccessful && res.text.contains("list-box")) {
                                    return@async testFinalUrl
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                            }
                            null
                        }
                    }
                    val found = deferreds.awaitAll().firstOrNull { it != null }
                    if (found != null) {
                        currentMainUrl = found
                        mainUrl = currentMainUrl
                        isDomainChecked = true
                        prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                        return@coroutineScope
                    }
                }
            }
            
            isDomainChecked = true 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val url = "$mainUrl/bbs/board.php?bo_table=${request.data}&page=$page"
        return try {
            val doc = app.get(url).document
            val elements = doc.select(".list-body .list-row .list-box")
            val list = elements.mapNotNull { element -> element.toSearchResponse() }
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain()
        val url = "$mainUrl/bbs/search.php?sfl=wr_subject&stx=$query"
        return try {
            val doc = app.get(url).document
            val elements = doc.select(".list-body .list-row .list-box")
            elements.mapNotNull { element -> element.toSearchResponse() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
        }
        
        try {
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
                } else null
            }

            val episodes = mutableListOf<Episode>()
            val episodeItems = doc.select(".serial-list .list-body .list-item")
            
            if (episodeItems.isNotEmpty()) {
                episodeItems.forEach { item ->
                    val linkEl = item.selectFirst("a.item-subject")
                    val epHref = linkEl?.attr("href")
                    val epName = linkEl?.text()?.trim() ?: "Episode"
                    val epNum = Regex("(\\d+)[화회부]").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    
                    if (!epHref.isNullOrEmpty()) {
                        episodes.add(newEpisode(fixUrl(epHref)) {
                            this.name = epName; this.episode = epNum; this.posterUrl = poster
                        })
                    }
                }
            } else {
                episodes.add(newEpisode(targetUrl) { this.name = title; this.posterUrl = poster })
            }

            val type = if (targetUrl.contains("bo_table=movie")) TvType.Movie else TvType.TvSeries
            
            if (type == TvType.Movie) {
                val movieDataUrl = episodes.firstOrNull()?.data ?: targetUrl
                return newMovieLoadResponse(title, targetUrl, TvType.Movie, movieDataUrl) {
                    this.posterUrl = poster; this.plot = description; this.tags = tags
                }
            } else {
                return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes.sortedBy { it.episode ?: 0 }) {
                    this.posterUrl = poster; this.plot = description; this.tags = tags
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data).document
            val form = doc.selectFirst("form.tt2") ?: doc.selectFirst("form.tt")
            if (form != null) {
                val action = form.attr("action")
                val vParam = form.selectFirst("input[name=v]")?.attr("value")
                if (action.isNotEmpty() && !vParam.isNullOrEmpty()) {
                    val targetUrl = "$action?v=$vParam"
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
        } catch (e: Exception) { 
            if (e is CancellationException) throw e
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
