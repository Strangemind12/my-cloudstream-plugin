// v1.107 (Implemented LRU Cache & Removed Bloated Torrent Trackers)
package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.imdbUrlToIdNullable
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.hsp1020.SubsExtractors.invokeOpenSubs
import com.hsp1020.SubsExtractors.invokeWatchsomuch
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

private fun String.cleanBaseUrl(): String {
    return this.substringBefore("?").replace("/manifest.json", "").trimEnd('/')
}

// [해결 2] OOM 방지를 위한 LRU(최근 최소 사용) Set 구현체 추가
private inline fun <T> lruSet(maxSize: Int): MutableSet<T> {
    return Collections.newSetFromMap(object : java.util.LinkedHashMap<T, Boolean>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<T, Boolean>): Boolean {
            return size > maxSize
        }
    })
}

class StremioC(override var mainUrl: String, override var name: String) : MainAPI() {
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
     
    private var cachedManifest: Manifest? = null
    private var lastManifestUrl: String = ""
    private var lastCacheTime: Long = 0
    
    // [해결 2] 무한 스크롤 메모리 방어: 내부 Set을 LRU 기반으로 래핑하여 생성
    private val catalogSentIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val pageContentCache = ConcurrentHashMap<String, List<SearchResponse>>()
    private val catalogSkipState = ConcurrentHashMap<String, Int>()
    
    private val pageMutex = Mutex()
    private val activePageRequests = mutableMapOf<Int, Deferred<HomePageResponse>>()
    
    val customSession by lazy {
        println("[StremioC v1.107-TRACKING] 커스텀 OkHttp 세션 초기화")
        val newClient = app.baseClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .dispatcher(Dispatcher().apply {
                maxRequests = 100
                maxRequestsPerHost = 100
            })
            .connectionPool(ConnectionPool(100, 5, TimeUnit.MINUTES))
            .build()
            
        Requests(newClient).apply {
            this.responseParser = app.responseParser
            this.defaultHeaders = app.defaultHeaders
        }
    }

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "cc9982c4801545a1481d167137ea7b53"
        private const val TRANSPARENT_PIXEL = "https://upload.wikimedia.org/wikipedia/commons/c/ca/1x1.png"
        private const val TRAKT_CLIENT_ID = "6d8668915ed1953f5023ea090e206facc6261813243f567dea15a9a678783b6d" 
        private const val SIMKL_CLIENT_ID = "f392628a1235f474859905f5453239c57715d9a197a89bd71cac975ddd9c4d39" 
        
        // [해결 3] trackers_best.txt 다운로드 및 캐시 변수 완전 삭제 (파싱 에러 방지)
    }

    private fun buildStremioId(type: String?, id: String?, season: Int?, episode: Int?): String? {
        if (type == "series" && season != null && episode != null) {
            return when {
                id?.startsWith("kitsu:") == true -> {
                    val parts = id.split(":")
                    val baseId = if (parts.size >= 2) "kitsu:${parts[1]}" else id
                    "$baseId:$season:$episode"
                }
                id?.endsWith(":$season:$episode") == true -> id
                else -> "$id:$season:$episode"
            }
        }
        return id
    }

    private fun mergeOverview(original: String?, newOverview: String?): String? {
        if (newOverview.isNullOrBlank()) return original
        if (original != null && original.contains("✨")) {
            val splitIndex = original.indexOf("|")
            return if (splitIndex != -1) {
                original.substring(0, splitIndex + 1).trim() + " " + newOverview
            } else {
                newOverview
            }
        }
        return newOverview
    }

    private fun baseUrl(): String {
        return mainUrl.fixSourceUrl().cleanBaseUrl()
    }

    private fun querySuffix(): String {
        return mainUrl.substringAfter("?", "")
            .takeIf { it.isNotEmpty() }
            ?.let { "?$it" }
            ?: ""
    }

    private fun buildUrl(path: String): String {
        return "${baseUrl()}$path${querySuffix()}"
    }

    private fun getActorUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w300$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original$link" else link
    }

    private suspend fun getManifest(): Manifest? {
        val originalBase = mainUrl.substringBefore("?").trimEnd('/')
        val currentUrl = if (originalBase.endsWith("manifest.json")) originalBase else "$originalBase/manifest.json"
        
        val now = System.currentTimeMillis()
        val isExpired = (now - lastCacheTime) > 24 * 60 * 60 * 1000

        if (cachedManifest != null && lastManifestUrl == currentUrl && !isExpired && !cachedManifest?.catalogs.isNullOrEmpty()) {
            return cachedManifest
        }

        val res = customSession.get(currentUrl, timeout = 120L).parsedSafe<Manifest>()

        if (res != null && res.catalogs.isNotEmpty()) {
            cachedManifest = res
            lastManifestUrl = currentUrl
            lastCacheTime = now
            pageContentCache.clear()
            catalogSentIds.clear()
            catalogSkipState.clear()
        }      
        return res ?: cachedManifest
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        val deferred = pageMutex.withLock {
            if (page <= 1) {
                catalogSentIds.clear()
                activePageRequests.clear()
                pageContentCache.clear()
                catalogSkipState.clear()
            }
            activePageRequests.getOrPut(page) {
                async { fetchMainPageData(page, request) }
            }
        }
        deferred.await()
    }

    private suspend fun fetchMainPageData(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = mainUrl.fixSourceUrl()
        val manifest = getManifest()
        val targetCatalogs = manifest?.catalogs?.filter { !it.isSearchRequired() } ?: emptyList()
        val lists = mutableListOf<HomePageList>()
        
        val chunkResults = coroutineScope {
            targetCatalogs.map { catalog ->
                async(Dispatchers.IO) {
                    try {
                        val catalogKey = "${catalog.id}-${catalog.type}"
                        val currentSkip = catalogSkipState[catalogKey] ?: 0
                        val cacheKey = "${catalogKey}_$currentSkip"
                        
                        val cachedItems = pageContentCache[cacheKey]
                        
                        val row = if (cachedItems != null) {
                            val displayType = catalog.type?.replaceFirstChar { it.uppercase() } ?: ""
                            HomePageList("${catalog.name ?: catalog.id} - $displayType", cachedItems)
                        } else {
                            val resultPair = catalog.toHomePageList(provider = this@StremioC, skip = currentSkip)
                            val freshRow = resultPair.first
                            
                            if (freshRow.list.isNotEmpty()) {
                                pageContentCache[cacheKey] = freshRow.list
                                catalogSkipState[catalogKey] = currentSkip + resultPair.second
                            }
                            freshRow
                        }
                        
                        // [해결 2] LRU Set 적용: 한 카탈로그당 최대 500개 아이템만 기록하여 무한 스크롤 OOM 완벽 방지
                        val seenForThisCatalog = catalogSentIds.getOrPut(catalogKey) { 
                            Collections.synchronizedSet(lruSet<String>(500))
                        }
                        
                        val filteredItems = row.list.filter { item -> seenForThisCatalog.add(item.url) }
                        row.copy(list = filteredItems)
                    } catch (e: Exception) { null }
                }
            }.awaitAll()
        }.filterNotNull().filter { it.list.isNotEmpty() }
        
        lists.addAll(chunkResults)
        return newHomePageResponse(lists, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = mainUrl.fixSourceUrl()
        val manifest = getManifest()
        val supportedCatalogs = manifest?.catalogs?.filter { it.supportsSearch() } ?: emptyList()
        
        val searchResults = coroutineScope {
            supportedCatalogs.map { catalog ->
                async(Dispatchers.IO) {
                    try { catalog.search(query, this@StremioC) } catch (e: Exception) { emptyList() }
                }
            }.awaitAll().flatten()
        }
        return searchResults.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val res: CatalogEntry = if (url.startsWith("{")) {
            parseJson(url)
        } else {
            val responseText = customSession.get(url).text
            parseJson<CatalogResponse>(responseText).meta ?: parseJson(JSONObject(responseText).getJSONObject("meta").toString())
        }
        
        var finalProcessedId = res.id
        if (res.id.startsWith("tmdb:")) {
            val tmdbIdOnly = res.id.removePrefix("tmdb:")
            try {
                val mediaType = if (res.type == "movie") "movie" else "tv"
                val externalUrl = "$tmdbAPI/$mediaType/$tmdbIdOnly/external_ids?api_key=$apiKey"
                val externalRes = customSession.get(externalUrl).parsedSafe<ExternalIds>()
                val imdbId = externalRes?.imdb_id
                if (!imdbId.isNullOrBlank() && imdbId.startsWith("tt")) {
                    finalProcessedId = imdbId
                }
            } catch (e: Exception) {}
        }

        val normalizedId = try { normalizeId(finalProcessedId) } catch (e: Exception) { finalProcessedId }
        val encodedId = try { URLEncoder.encode(normalizedId, "UTF-8") } catch (e: Exception) { normalizedId }
        
        val addonDeferred = async(Dispatchers.IO) {
            try {
                val response = customSession.get(buildUrl("/meta/${res.type}/$encodedId.json")).parsedSafe<CatalogResponse>()
                response?.meta ?: response?.metas?.firstOrNull { it.id == finalProcessedId || it.id == res.id } ?: response?.metas?.firstOrNull()
            } catch (e: Exception) { null }
        }

        return@coroutineScope res.toLoadResponse(this@StremioC, finalProcessedId, addonDeferred)
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = try { parseJson<LoadData>(data) } catch (e: Exception) { null } ?: return false
        val normalizedId = try { normalizeId(loadData.id) } catch (e: Exception) { loadData.id ?: "" }
        val encodedId = try { URLEncoder.encode(normalizedId, "UTF-8") } catch (e: Exception) { normalizedId }
        
        val targetId = if (loadData.id != null && !(loadData.id.startsWith("tt") || loadData.id.startsWith("tmdb:") || loadData.id.startsWith("kitsu:"))) {
            if (!loadData.imdbId.isNullOrBlank()) loadData.imdbId else loadData.id
        } else {
            loadData.id
        }

        val targetType = if ((loadData.season ?: 0) == 0 && (loadData.episode ?: 0) == 0) "movie"
        else if ((loadData.season ?: 0) > 0 || (loadData.episode ?: 0) > 0) "series" 
        else loadData.type
        
        runAllAsync(
            {
                try {
                    val url = buildUrl("/stream/${loadData.type}/$encodedId.json")
                    val res = customSession.get(url, timeout = 120L).parsedSafe<StreamsResponse>()
                    if (!res?.streams.isNullOrEmpty()) {
                        res?.streams?.forEach { stream -> stream.runCallback(this@StremioC, subtitleCallback, callback) }
                    } else {
                        invokeStremioX(targetType, targetId, loadData.season, loadData.episode, subtitleCallback, callback)
                    }
                } catch (e: Exception) {}
            },
            { invokeWatchsomuch(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback) },
            { invokeOpenSubs(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback) },
            { invokeStremioSubtitles(targetType, if (!loadData.imdbId.isNullOrBlank()) loadData.imdbId else targetId, loadData.season, loadData.episode, subtitleCallback) }
        )
        return true
    }

    private suspend fun invokeStremioX(type: String?, id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val sites = AcraApplication.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList() ?: mutableListOf()
        val filteredSites = sites.filter { it.parentJavaClass == "StremioX" || it.parentJavaClass == "StremioC" }
        
        val stremioId = buildStremioId(type, id, season, episode) ?: return

        coroutineScope {
            filteredSites.map { site ->
                async(Dispatchers.IO) {
                    try {
                        val api = site.url.cleanBaseUrl()
                        val url = "$api/stream/$type/$stremioId.json"
                        val res = customSession.get(url, timeout = 120L).parsedSafe<StreamsResponse>()
                        res?.streams?.forEach { stream -> stream.runCallback(this@StremioC, subtitleCallback, callback) }
                    } catch (e: Exception) {}
                }
            }.awaitAll()
        }
    }

    private data class StremioSubtitleResponse(@JsonProperty("subtitles") val subtitles: List<Subtitle>?)
    
    private suspend fun invokeStremioSubtitles(type: String?, id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit) {
        val sites = AcraApplication.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList() ?: mutableListOf()
        val addonUrls = mutableSetOf<String>()
        addonUrls.add(baseUrl())
        
        sites.filter { it.parentJavaClass == "StremioX" || it.parentJavaClass == "StremioC" }.forEach { site ->
            addonUrls.add(site.url.cleanBaseUrl())
        }

        val stremioId = buildStremioId(type, id, season, episode) ?: return
        val gson = Gson()

        coroutineScope {
            addonUrls.toList().map { api ->
                async(Dispatchers.IO) {
                    try {
                        val url = "$api/subtitles/$type/$stremioId.json"
                        val json = customSession.get(url, timeout = 30L).text
                        val subtitleResponse = gson.fromJson(json, StremioSubtitleResponse::class.java)

                        subtitleResponse?.subtitles?.forEach { sub ->
                            val lang = sub.lang ?: sub.lang_code ?: "Unknown"
                            val fileUrl = sub.url
                            if (!fileUrl.isNullOrBlank()) {
                                subtitleCallback.invoke(newSubtitleFile(SubtitleHelper.fromTagToEnglishLanguageName(lang) ?: lang, fileUrl))
                            }
                        }
                    } catch (e: Exception) {}
                }
            }.awaitAll()
        }
    }

    data class LoadData(val type: String? = null, val id: String? = null, val season: Int? = null, val episode: Int? = null, val imdbId: String? = null, val year: Int? = null)
    data class CustomSite(@JsonProperty("parentJavaClass") val parentJavaClass: String, @JsonProperty("name") val name: String, @JsonProperty("url") val url: String, @JsonProperty("lang") val lang: String)

    private fun isImdborTmdb(url: String?): Boolean {
        return imdbUrlToIdNullable(url) != null || url?.startsWith("tmdb:") == true
    }

    private data class Manifest(val catalogs: List<Catalog>)
    private data class Extra(@JsonProperty("name") val name: String?, @JsonProperty("isRequired") val isRequired: Boolean? = false)

    private data class Catalog(var name: String?, val id: String, val type: String?, val types: MutableList<String> = mutableListOf(), @JsonProperty("extra") val extra: List<Extra>? = null, @JsonProperty("extraSupported") val extraSupported: List<String>? = null) {
        init { if (type != null) types.add(type) }

        fun isSearchRequired(): Boolean = extra?.any { it.name == "search" && it.isRequired == true } == true
        fun supportsSearch(): Boolean = extra?.any { it.name == "search" } == true || extraSupported?.contains("search") == true

        suspend fun search(query: String, provider: StremioC): List<SearchResponse> {
            val allMetas = coroutineScope {
                types.map { type ->
                    async(Dispatchers.IO) {
                        try {
                            val searchUrl = provider.buildUrl("/catalog/${type}/${id}/search=${URLEncoder.encode(query, "UTF-8")}.json")
                            val req = provider.customSession.get(searchUrl, timeout = 120L)
                            if (req.isSuccessful && req.text.isNotBlank()) {
                                parseJson<CatalogResponse>(req.text).metas ?: emptyList()
                            } else emptyList()
                        } catch (e: Exception) { emptyList() }
                    }
                }.awaitAll().flatten()
            }
            return allMetas.distinctBy { it.id }.map { it.toSearchResponse(provider) }
        }

        suspend fun toHomePageList(provider: StremioC, skip: Int): Pair<HomePageList, Int> {
            val allMetas = coroutineScope {
                types.map { type ->
                    async(Dispatchers.IO) {
                        try {
                            val path = if (skip > 0) "/catalog/$type/$id/skip=$skip.json" else "/catalog/$type/$id.json"
                            val req = provider.customSession.get(provider.buildUrl(path), timeout = 120L)
                            if (req.isSuccessful && req.text.isNotBlank()) {
                                parseJson<CatalogResponse>(req.text).metas ?: emptyList()
                            } else emptyList()
                        } catch (e: Exception) { emptyList() }
                    }
                }.awaitAll().flatten()
            }
            val distinctEntries = allMetas.distinctBy { it.id }.map { it.toSearchResponse(provider) }
            val displayType = type?.replaceFirstChar { it.uppercase() } ?: ""
            return Pair(HomePageList("${name ?: id} - $displayType", distinctEntries), allMetas.size)
        }
    }

    private data class CatalogResponse(val metas: List<CatalogEntry>?, val meta: CatalogEntry?)

    private data class Trailer(val source: String?, val type: String?)
    private data class TrailerStream(@JsonProperty("ytId") val ytId: String?, @JsonProperty("title") val title: String? = null)
    private data class Link(@JsonProperty("name") val name: String? = null, @JsonProperty("category") val category: String? = null, @JsonProperty("url") val url: String? = null)

    private data class FetchedTmdbData(
        val fetchedTitle: String?, val fetchedOverview: String?, val fetchedLogo: String?,
        val fetchedTrailers: List<String>, val fetchedRecommendations: List<SearchResponse>?,
        val fetchedRuntime: Int?, val fetchedAgeRating: String?, val fetchedActors: List<ActorData>?,
        val fetchedYear: Int?, val fetchedStatus: ShowStatus?, val episodeTmdbMeta: Map<String, TmdbEpisode>
    )

    private data class CatalogEntry(
        @JsonProperty("name") val name: String, @JsonProperty("id") val id: String, @JsonProperty("moviedb_id") val moviedb_id: Int? = null,
        @JsonProperty("poster") val poster: String?, @JsonProperty("background") val background: String?, @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("description") val description: String?, @JsonProperty("imdbRating") val imdbRating: String?, @JsonProperty("type") val type: String?,
        @JsonProperty("videos") val videos: List<Video>?, @JsonProperty("genre") val genre: List<String>?, @JsonProperty("genres") val genres: List<String> = emptyList(),
        @JsonProperty("cast") val cast: List<String> = emptyList(), @JsonProperty("trailers") val trailersSources: List<Trailer> = emptyList(),
        @JsonProperty("trailerStreams") val trailerStreams: List<TrailerStream> = emptyList(), @JsonProperty("links") val links: List<Link> = emptyList()
    ) {
        fun toSearchResponse(provider: StremioC): SearchResponse {
            return provider.newMovieSearchResponse(name, this.toJson(), TvType.Others) {
                posterUrl = poster
            }
        }

        private suspend fun fetchTmdbDetails(
            provider: StremioC, tmdbMediaType: String, tmdbIdStr: String, isMovie: Boolean, finalImdbId: String?,
            stremioType: String?, targetVideos: List<Video>?
        ): FetchedTmdbData {
            var fetchedRecommendations: List<SearchResponse>? = null
            
            val detailAppend = if (isMovie) "release_dates,credits,images,videos" else "content_ratings,credits,images,videos"
            val detailUrl = "$tmdbAPI/$tmdbMediaType/$tmdbIdStr?api_key=$apiKey&language=ko-KR&append_to_response=$detailAppend&include_image_language=ko"
            
            val detailRes = provider.customSession.get(detailUrl).parsedSafe<TmdbDetailResponse>()
            if (detailRes == null) {
                return FetchedTmdbData(null, null, null, emptyList(), null, null, null, null, null, null, emptyMap())
            }

            val targetCountry = detailRes.origin_country?.firstOrNull()
            val isSameOrigin: (TmdbMedia?) -> Boolean = { media -> 
                media != null && (targetCountry != null && media.origin_country?.contains(targetCountry) == true) 
            }

            coroutineScope {
                val traktDeferred = async(Dispatchers.IO) {
                    if (finalImdbId == null || TRAKT_CLIENT_ID.isEmpty()) return@async emptyList<Int>()
                    val traktMediaType = if (tmdbMediaType == "movie") "movies" else "shows"
                    try {
                        val req = provider.customSession.get("https://api.trakt.tv/$traktMediaType/$finalImdbId/related?limit=30", headers = mapOf("Content-Type" to "application/json", "trakt-api-version" to "2", "trakt-api-key" to TRAKT_CLIENT_ID), timeout = 15)
                        if (req.isSuccessful && req.text.isNotBlank()) {
                            val jsonArray = JSONArray(req.text)
                            val ids = mutableListOf<Int>()
                            for (i in 0 until jsonArray.length()) {
                                val tmdbId = jsonArray.optJSONObject(i)?.optJSONObject("ids")?.optInt("tmdb", 0) ?: 0
                                if (tmdbId > 0) ids.add(tmdbId)
                            }
                            ids
                        } else emptyList()
                    } catch(e: Exception) { emptyList() }
                }

                val simklDeferred = async(Dispatchers.IO) {
                    if (finalImdbId == null || SIMKL_CLIENT_ID.isEmpty()) return@async emptyList<Int>()
                    val simklMediaType = if (tmdbMediaType == "movie") "movies" else "tv"
                    try {
                        val req = provider.customSession.get("https://api.simkl.com/$simklMediaType/$finalImdbId?extended=full&client_id=$SIMKL_CLIENT_ID", timeout = 15)
                        if (req.isSuccessful && req.text.isNotBlank()) {
                            val rootObj = JSONObject(req.text)
                            val recsArray = rootObj.optJSONArray("users_recommendations") ?: JSONArray()
                            val rawSimklRecs = mutableListOf<Pair<Int, Int>>()
                            
                            for (i in 0 until min(recsArray.length(), 30)) {
                                val idsObj = recsArray.optJSONObject(i)?.optJSONObject("ids")
                                val tmdb = idsObj?.optInt("tmdb", 0) ?: 0
                                val simkl = idsObj?.optInt("simkl", 0) ?: 0
                                if (tmdb > 0 || simkl > 0) rawSimklRecs.add(Pair(tmdb, simkl))
                            }
                            
                            coroutineScope {
                                rawSimklRecs.map { (tmdbId, simklId) ->
                                    async(Dispatchers.IO) {
                                        if (tmdbId > 0) tmdbId
                                        else if (simklId > 0) {
                                            try {
                                                val dReq = provider.customSession.get("https://api.simkl.com/$simklMediaType/$simklId?client_id=$SIMKL_CLIENT_ID", timeout = 15)
                                                if (dReq.isSuccessful && dReq.text.isNotBlank()) {
                                                    JSONObject(dReq.text).optJSONObject("ids")?.optInt("tmdb", 0)?.takeIf { it > 0 }
                                                } else null
                                            } catch(e: Exception) { null }
                                        } else null
                                    }
                                }.awaitAll().filterNotNull()
                            }
                        } else emptyList()
                    } catch(e: Exception) { emptyList() }
                }

                val tmdbRecsDeferred = (1..2).map { page ->
                    async(Dispatchers.IO) {
                        try {
                            val recUrl = "$tmdbAPI/$tmdbMediaType/$tmdbIdStr/recommendations?api_key=$apiKey&language=ko-KR&page=$page"
                            provider.customSession.get(recUrl, timeout = 15).parsedSafe<TmdbRecommendations>()?.results ?: emptyList()
                        } catch (e: Exception) { emptyList<TmdbMedia>() }
                    }
                }

                val collectionParts = if (isMovie && detailRes.belongs_to_collection?.id != null) {
                    try {
                        val colUrl = "$tmdbAPI/collection/${detailRes.belongs_to_collection.id}?api_key=$apiKey&language=ko-KR"
                        provider.customSession.get(colUrl).parsedSafe<TmdbCollectionDetail>()?.parts ?: emptyList()
                    } catch (e: Exception) { emptyList() }
                } else emptyList()

                val traktIds = traktDeferred.await().toSet()
                val simklIds = simklDeferred.await().toSet()
                val tmdbRecs = tmdbRecsDeferred.awaitAll().flatten().distinctBy { it.id }
                val tmdbRecsIds = tmdbRecs.mapNotNull { it.id }.toSet()

                val existingTmdbIds = collectionParts.mapNotNull { it.id }.toSet() + tmdbRecsIds
                
                val missingIds = (traktIds + simklIds).distinct()
                    .filter { !existingTmdbIds.contains(it) && it != tmdbIdStr.toIntOrNull() }

                val missingMediaDeferred = missingIds.map { missingId ->
                    async(Dispatchers.IO) {
                        try {
                            var currentType = tmdbMediaType
                            var res = provider.customSession.get("$tmdbAPI/$currentType/$missingId?api_key=$apiKey&language=ko-KR", timeout = 10).parsedSafe<TmdbDetailResponse>()
                            
                            if (res?.id == null) {
                                currentType = if (tmdbMediaType == "movie") "tv" else "movie"
                                res = provider.customSession.get("$tmdbAPI/$currentType/$missingId?api_key=$apiKey&language=ko-KR", timeout = 10).parsedSafe<TmdbDetailResponse>()
                            }
                            res?.toTmdbMedia(currentType)
                        } catch(e:Exception) { null }
                    }
                }
                val fetchedMissingMedia = missingMediaDeferred.awaitAll().filterNotNull()

                val allCandidates = (collectionParts + tmdbRecs + fetchedMissingMedia).distinctBy { it.id }
                    .filter { it.id != null && it.id != tmdbIdStr.toIntOrNull() }
                
                val finalCombinedMedia = allCandidates.map { media ->
                    var score = 0.0 
                    if (collectionParts.any { it.id == media.id }) score += 1000.0 
                    if (isSameOrigin(media)) score += 10.0 
                    if (media.id in traktIds) score += 4.0 
                    if (media.id in simklIds) score += 2.0 
                    if (media.id in tmdbRecsIds) score += 1.0 
                    Pair(media, score)
                }.sortedByDescending { it.second }.map { it.first }.take(50) 

                fetchedRecommendations = finalCombinedMedia.mapNotNull { media ->
                    val recTitle = media.title ?: media.name ?: media.originalTitle ?: return@mapNotNull null
                    val posterUrl = provider.getOriImageUrl(media.posterPath)
                    val outType = if ((media.mediaType ?: tmdbMediaType) == "tv") "series" else "movie"
                    
                    val recommendationEntry = CatalogEntry(
                        name = recTitle, id = "tmdb:${media.id}", type = outType, 
                        poster = posterUrl, background = null, description = media.overview, 
                        imdbRating = null, videos = null, genre = null
                    )
                    
                    provider.newMovieSearchResponse(recTitle, recommendationEntry.toJson(), if (outType == "movie") TvType.Movie else TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                }
            }

            val fetchedAgeRating = if (isMovie) {
                detailRes.release_dates?.results?.firstOrNull { it.iso_3166_1 == "KR" }?.release_dates?.firstOrNull { !it.certification.isNullOrEmpty() }?.certification
            } else {
                detailRes.content_ratings?.results?.firstOrNull { it.iso_3166_1 == "KR" }?.rating
            }

            val crewList = detailRes.credits?.crew?.filter { it.job == "Director" || it.job == "Writer" }
                ?.groupBy { it.name ?: it.originalName }?.mapNotNull { (name, roles) ->
                    if (name == null) return@mapNotNull null
                    val sortedJobs = roles.mapNotNull { it.job }.distinct().sortedBy { if (it == "Director") 1 else 2 }.joinToString(", ")
                    val img = roles.firstNotNullOfOrNull { it.profilePath }?.let { provider.getActorUrl(it) } ?: TRANSPARENT_PIXEL
                    ActorData(Actor(name, img), roleString = sortedJobs)
                }?.sortedBy { 
                    if (it.roleString?.contains("Director, Writer") == true) 1 
                    else if (it.roleString?.contains("Director") == true) 2 else 3 
                } ?: emptyList()

            val castList = detailRes.credits?.cast?.mapNotNull { cast ->
                val actorName = cast.name ?: cast.originalName ?: return@mapNotNull null
                val profileImg = cast.profilePath?.let { provider.getActorUrl(it) } ?: TRANSPARENT_PIXEL
                ActorData(Actor(actorName, profileImg), roleString = cast.character)
            } ?: emptyList()

            val fetchedTrailers = (detailRes.videos?.results ?: emptyList())
                .filter { it.type == "Trailer" || it.type == "Teaser" }
                .sortedWith(compareBy({ if (it.type == "Trailer") 0 else 1 }, { it.publishedAt ?: "9999-12-31" }))
                .mapNotNull { it.key }.map { "https://m.youtube.com/watch?v=$it" }

            val episodeTmdbMeta = mutableMapOf<String, TmdbEpisode>()
            
            // [검증 1] "other" 타입 예외 처리가 작동하는 부분 (정상 코드)
            if (stremioType == "other" && !isMovie && !targetVideos.isNullOrEmpty()) {
                var requiredSeasons = targetVideos.mapNotNull { it.seasonNumber }.filter { it > 0 }.distinct()
                if (requiredSeasons.isEmpty()) requiredSeasons = listOf(1)
                
                if (requiredSeasons.isNotEmpty()) {
                    coroutineScope {
                        requiredSeasons.map { seasonNum ->
                            async(Dispatchers.IO) {
                                try {
                                    val seasonUrl = "$tmdbAPI/tv/$tmdbIdStr/season/$seasonNum?api_key=$apiKey&language=ko-KR"
                                    val seasonRes = provider.customSession.get(seasonUrl).parsedSafe<TmdbSeasonDetail>()
                                    seasonRes?.episodes?.forEach { ep ->
                                        if (ep.episodeNumber != null) {
                                            episodeTmdbMeta["${seasonNum}_${ep.episodeNumber}"] = ep
                                        }
                                    }
                                } catch (e: Exception) {}
                            }
                        }.awaitAll()
                    }
                }
            }

            return FetchedTmdbData(
                if (isMovie) detailRes.title ?: detailRes.original_title else detailRes.name ?: detailRes.original_name,
                detailRes.overview,
                detailRes.images?.logos?.firstOrNull()?.file_path?.let { "https://image.tmdb.org/t/p/w500$it" },
                fetchedTrailers,
                fetchedRecommendations,
                if (isMovie) detailRes.runtime else null,
                fetchedAgeRating,
                crewList + castList,
                (detailRes.releaseDate ?: detailRes.firstAirDate)?.split("-")?.first()?.toIntOrNull(),
                if (detailRes.status == "Returning Series") ShowStatus.Ongoing else ShowStatus.Completed,
                episodeTmdbMeta
            )
        }

        private fun processEpisodes(provider: StremioC, finalImdbId: String?, episodeTmdbMeta: Map<String, TmdbEpisode>, targetVideos: List<Video>, type: String?): List<Episode> {
            val sortedVideos = targetVideos.groupBy { Pair(it.seasonNumber ?: 0, it.episode ?: it.number ?: 0) }
                .flatMap { (_, group) -> group.sortedBy { it.title ?: it.name ?: "" } }
            
            val maxRegularSeason = sortedVideos.mapNotNull { it.seasonNumber }.filter { it > 0 }.maxOrNull() ?: 0
            val targetSeasonForZero = maxRegularSeason + 1

            val maxEpisodePerSeason = mutableMapOf<Int, Int>()
            sortedVideos.forEach { video ->
                val originalSeason = video.seasonNumber ?: 0
                val displaySeason = if (originalSeason == 0) targetSeasonForZero else originalSeason
                val originalEpisode = video.episode ?: video.number ?: 0
                if (originalEpisode > 0) {
                    val currentMax = maxEpisodePerSeason.getOrDefault(displaySeason, 0)
                    if (originalEpisode > currentMax) maxEpisodePerSeason[displaySeason] = originalEpisode
                }
            }

            val zeroEpCounterPerSeason = mutableMapOf<Int, Int>()
            val uniqueEpisodeChecker = mutableSetOf<String>()

            return sortedVideos.mapIndexed { _, video ->
                val originalSeason = video.seasonNumber ?: 0
                val originalEpisode = video.episode ?: video.number ?: 0
                val displaySeason = if (originalSeason == 0) targetSeasonForZero else originalSeason
                var displayEpisode: Int
                
                if (originalEpisode == 0) {
                    val currentZeroCount = zeroEpCounterPerSeason.getOrDefault(displaySeason, 0) + 1
                    zeroEpCounterPerSeason[displaySeason] = currentZeroCount
                    displayEpisode = maxEpisodePerSeason.getOrDefault(displaySeason, 0) + currentZeroCount
                } else {
                    displayEpisode = originalEpisode
                }
                
                var key = "${displaySeason}_${displayEpisode}"
                if (uniqueEpisodeChecker.contains(key)) {
                    displayEpisode = (uniqueEpisodeChecker.filter { it.startsWith("${displaySeason}_") }.maxOfOrNull { it.split("_")[1].toInt() } ?: 0) + 1
                    key = "${displaySeason}_${displayEpisode}"
                }
                uniqueEpisodeChecker.add(key)
                
                video.toEpisode(provider, type, finalImdbId, episodeTmdbMeta, displaySeason, displayEpisode)
            }
        }

        suspend fun toLoadResponse(provider: StremioC, imdbId: String?, addonDeferred: Deferred<CatalogEntry?>? = null): LoadResponse = coroutineScope {
            var finalImdbId = if (this@CatalogEntry.id.startsWith("tt")) this@CatalogEntry.id else imdbId?.takeIf { it.startsWith("tt") }
            
            if (finalImdbId == null) {
                val regex = "tt[0-9]+".toRegex()
                finalImdbId = logo?.let { regex.find(it)?.value } ?: poster?.let { regex.find(it)?.value } ?: background?.let { regex.find(it)?.value }
            }

            if (this@CatalogEntry.id.startsWith("kitsu:") && finalImdbId.isNullOrBlank()) {
                try {
                    val kitsuJson = provider.customSession.get("https://anime-kitsu.strem.fun/meta/${this@CatalogEntry.type}/${this@CatalogEntry.id}.json", timeout = 30L).text
                    val metaObj = JSONObject(kitsuJson).optJSONObject("meta")
                    if (metaObj != null) {
                        val fetchedKitsuImdbId = metaObj.optString("imdb_id", "")
                        if (fetchedKitsuImdbId.isNotBlank() && fetchedKitsuImdbId.startsWith("tt")) finalImdbId = fetchedKitsuImdbId
                    }
                } catch (e: Exception) {}
            }

            var tmdbIdStr: String? = if (this@CatalogEntry.id.startsWith("tmdb:")) this@CatalogEntry.id.removePrefix("tmdb:") else this@CatalogEntry.moviedb_id?.toString()
            val findApiDeferred = if (tmdbIdStr == null && finalImdbId?.startsWith("tt") == true) {
                async(Dispatchers.IO) {
                    try {
                        val findRes = provider.customSession.get("$tmdbAPI/find/$finalImdbId?api_key=$apiKey&external_source=imdb_id&language=ko-KR").parsedSafe<TmdbFindResponse>()
                        findRes?.movie_results?.firstOrNull()?.id?.toString() ?: findRes?.tv_results?.firstOrNull()?.id?.toString()
                    } catch (e: Exception) { null }
                }
            } else null

            val detailedEntry = addonDeferred?.await() ?: this@CatalogEntry

            val finalVideos = detailedEntry.videos ?: this@CatalogEntry.videos
            val finalTrailersSources = detailedEntry.trailersSources ?: this@CatalogEntry.trailersSources
            val finalTrailerStreams = detailedEntry.trailerStreams ?: this@CatalogEntry.trailerStreams
            val finalLinks = detailedEntry.links ?: this@CatalogEntry.links
            val originalName = detailedEntry.name ?: this@CatalogEntry.name
            var finalDescription = detailedEntry.description ?: this@CatalogEntry.description
            val finalGenre = detailedEntry.genre ?: detailedEntry.genres ?: this@CatalogEntry.genre ?: this@CatalogEntry.genres
            val finalCast = detailedEntry.cast.ifEmpty { this@CatalogEntry.cast }
            val finalPoster = detailedEntry.poster ?: this@CatalogEntry.poster
            val finalBackground = detailedEntry.background ?: this@CatalogEntry.background
            val finalImdbRating = detailedEntry.imdbRating ?: this@CatalogEntry.imdbRating
            val finalType = detailedEntry.type ?: this@CatalogEntry.type

            if (finalImdbId == null && finalLinks != null) {
                finalImdbId = finalLinks.firstOrNull { it.category == "imdb" }?.url?.substringAfterLast("/")?.takeIf { it.startsWith("tt") }
            }

            if (findApiDeferred != null) {
                tmdbIdStr = findApiDeferred.await()
            }

            var tmdbData: FetchedTmdbData? = null
            if (tmdbIdStr != null) {
                try {
                    val isSingleMovieVideo = (finalType == "movie" && finalVideos?.size == 1 && finalVideos[0].seasonNumber == 1 && (finalVideos[0].episode == 1 || finalVideos[0].number == 1)) ||
                            (finalVideos?.size == 1 && (finalVideos[0].seasonNumber ?: 0) == 0 && (finalVideos[0].episode ?: finalVideos[0].number ?: 0) == 0)
                    
                    val refinedMediaType = if (isSingleMovieVideo || finalType == "movie" || finalVideos.isNullOrEmpty()) "movie" else "tv"
                    tmdbData = fetchTmdbDetails(provider, refinedMediaType, tmdbIdStr, refinedMediaType == "movie", finalImdbId, finalType, finalVideos)
                } catch (e: Exception) {}
            }

            var finalName = originalName ?: this@CatalogEntry.name

            if (tmdbData != null) {
                if (!tmdbData.fetchedTitle.isNullOrBlank()) {
                    finalName = tmdbData.fetchedTitle
                }
                finalDescription = provider.mergeOverview(finalDescription, tmdbData.fetchedOverview)
                
                if (finalType == "other") {
                    finalDescription = if (finalDescription.isNullOrBlank()) originalName else "$finalDescription | $originalName" 
                }
            }

            val fallbackTrailers = finalTrailerStreams?.mapNotNull { it.ytId }
                ?.ifEmpty { finalTrailersSources?.mapNotNull { it.source } }
                ?.distinct()
                ?.map { if (it.startsWith("http")) it else "https://m.youtube.com/watch?v=$it" } ?: emptyList()

            val finalTrailers = tmdbData?.fetchedTrailers?.ifEmpty { fallbackTrailers } ?: fallbackTrailers

            val movieId = if (!finalVideos.isNullOrEmpty() && !finalVideos[0].id.isNullOrBlank()) finalVideos[0].id else this@CatalogEntry.id

            val isSingleMovieVideoFinal = (finalType == "movie" && finalVideos?.size == 1 && finalVideos[0].seasonNumber == 1 && (finalVideos[0].episode == 1 || finalVideos[0].number == 1)) ||
                    (finalVideos?.size == 1 && (finalVideos[0].seasonNumber ?: 0) == 0 && (finalVideos[0].episode ?: finalVideos[0].number ?: 0) == 0)

            if (finalVideos.isNullOrEmpty() || isSingleMovieVideoFinal) {
                return@coroutineScope provider.newMovieLoadResponse(
                    finalName,
                    "${provider.mainUrl}/meta/${finalType}/${this@CatalogEntry.id}.json",
                    TvType.Movie,
                    LoadData(finalType, movieId, imdbId = finalImdbId, year = tmdbData?.fetchedYear)
                ) {
                    posterUrl = finalPoster
                    backgroundPosterUrl = finalBackground
                    score = Score.from10(finalImdbRating)
                    plot = finalDescription 
                    year = tmdbData?.fetchedYear
                    tags = finalGenre
                    
                    if (tmdbData?.fetchedActors?.isNotEmpty() == true) {
                        this.actors = tmdbData.fetchedActors
                    } else {
                        addActors(finalCast)
                    }
                    
                    addTrailer(finalTrailers)                   
                    this.recommendations = tmdbData?.fetchedRecommendations
                    this.duration = tmdbData?.fetchedRuntime
                    this.contentRating = tmdbData?.fetchedAgeRating
                    tmdbData?.fetchedLogo?.let { this.logoUrl = it }
                    tmdbIdStr?.let { addTMDbId(it) }
                    finalImdbId?.let { if (it.startsWith("tt")) addImdbId(it) }
                }
            } else {
                val episodesList = processEpisodes(provider, finalImdbId, tmdbData?.episodeTmdbMeta ?: emptyMap(), finalVideos, finalType)

                return@coroutineScope provider.newTvSeriesLoadResponse(
                    finalName,
                    "${provider.mainUrl}/meta/${finalType}/${this@CatalogEntry.id}.json",
                    TvType.TvSeries,
                    episodesList
                ) {
                    posterUrl = finalPoster
                    backgroundPosterUrl = finalBackground
                    score = Score.from10(finalImdbRating)
                    plot = finalDescription
                    year = tmdbData?.fetchedYear
                    this.showStatus = tmdbData?.fetchedStatus
                    tags = finalGenre
                    
                    if (tmdbData?.fetchedActors?.isNotEmpty() == true) {
                        this.actors = tmdbData.fetchedActors
                    } else {
                        addActors(finalCast)
                    }
                    
                    addTrailer(finalTrailers.firstOrNull())
                    this.recommendations = tmdbData?.fetchedRecommendations
                    this.contentRating = tmdbData?.fetchedAgeRating
                    tmdbData?.fetchedLogo?.let { this.logoUrl = it }
                    tmdbIdStr?.let { addTMDbId(it) }
                    finalImdbId?.let { if (it.startsWith("tt")) addImdbId(it) }
                }
            }
        }
    }

    private data class Video(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season") val seasonNumber: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("firstAired") val firstAired: String? = null,
        @JsonProperty("released") val released: String? = null,
        @JsonProperty("tvdb_id") val tvdb_id: Int? = null
    ) {
        fun toEpisode(provider: StremioC, type: String?, imdbId: String?, tmdbMetaMap: Map<String, TmdbEpisode>, displaySeason: Int, displayEpisode: Int): Episode {
            return provider.newEpisode(LoadData(type, id, seasonNumber, episode ?: number, imdbId)) {
                
                val rawTitle = this@Video.name ?: this@Video.title ?: "Episode $displayEpisode"
                val cleanOriginalTitle = if (rawTitle.contains("📄")) {
                    rawTitle.substringAfter("📄").replace("\n", "").trim()
                } else {
                    rawTitle.replace("\n", "").trim()
                }
                
                val tmdbEp = tmdbMetaMap["${displaySeason}_${displayEpisode}"] 
                             ?: tmdbMetaMap["${seasonNumber ?: 0}_${episode ?: number ?: 0}"]
                
                this.name = if (tmdbEp?.name != null) tmdbEp.name else cleanOriginalTitle
                this.posterUrl = thumbnail ?: TRANSPARENT_PIXEL
                
                var finalEpDesc = provider.mergeOverview(this@Video.overview ?: this@Video.description, tmdbEp?.overview)
                
                if (type == "other") {
                    finalEpDesc = if (finalEpDesc.isNullOrBlank()) {
                        cleanOriginalTitle
                    } else {
                        "$finalEpDesc | $cleanOriginalTitle"
                    }
                }
                
                this.description = finalEpDesc
                this.season = displaySeason
                this.episode = displayEpisode

                val finalAirDate = tmdbEp?.airDate?.takeIf { it.isNotBlank() } ?: this@Video.firstAired ?: this@Video.released
                finalAirDate?.takeIf { it.isNotBlank() }?.let { this.addDate(it) }
            }
        }
    }

    private data class StreamsResponse(@JsonProperty("streams") val streams: List<Stream>)
    private data class Subtitle(@JsonProperty("url") val url: String?, @JsonProperty("lang") val lang: String?, @JsonProperty("lang_code") val lang_code: String?, @JsonProperty("id") val id: String?)
    private data class ProxyHeaders(@JsonProperty("request") val request: Map<String, String>?)
    private data class BehaviorHints(@JsonProperty("proxyHeaders") val proxyHeaders: ProxyHeaders?, @JsonProperty("headers") val headers: Map<String, String>?)

    private data class Stream(
        @JsonProperty("name") val name: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("ytId") val ytId: String?,
        @JsonProperty("externalUrl") val externalUrl: String?,
        @JsonProperty("behaviorHints") val behaviorHints: BehaviorHints?,
        @JsonProperty("infoHash") val infoHash: String?,
        @JsonProperty("sources") val sources: List<String> = emptyList(),
        @JsonProperty("subtitles") val subtitles: List<Subtitle> = emptyList()
    ) {
        suspend fun runCallback(provider: StremioC, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            if (url != null) {
                callback.invoke(newExtractorLink(name ?: "", fixSourceName(name, title), url, INFER_TYPE) {
                    this.quality = getQuality(listOf(description, title, name))
                    this.headers = behaviorHints?.proxyHeaders?.request ?: behaviorHints?.headers ?: mapOf()
                })
                subtitles.map { sub ->
                    val lang = sub.lang ?: sub.lang_code ?: "Unknown"
                    subtitleCallback.invoke(newSubtitleFile(SubtitleHelper.fromTagToEnglishLanguageName(lang) ?: lang, sub.url ?: return@map))
                }
            }
            if (ytId != null) {
                loadExtractor("https://m.youtube.com/watch?v=$ytId", subtitleCallback, callback)
            }
            if (externalUrl != null) {
                loadExtractor(externalUrl, subtitleCallback, callback)
            }
            if (infoHash != null) {
                // [해결 3] 마그넷 주소 폭파(에러)를 막기 위해 거추장스러운 외부 트래커 캐싱/결합 로직 삭제
                val sourceTrackers = sources.filter { it.startsWith("tracker:") }
                    .joinToString("") { "&tr=${it.removePrefix("tracker:")}" }

                val magnet = "magnet:?xt=urn:btih:${infoHash}${sourceTrackers}"
                callback.invoke(newExtractorLink(name ?: "", title ?: name ?: "", magnet) {
                    this.quality = Qualities.Unknown.value
                })
            }
        }
    }
}

private data class TmdbFindResponse(
    @JsonProperty("movie_results") val movie_results: List<TmdbFindResult>? = null,
    @JsonProperty("tv_results") val tv_results: List<TmdbFindResult>? = null
)

private data class TmdbFindResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val media_type: String? = null
)

private data class TmdbCollection(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val poster_path: String? = null,
    @JsonProperty("backdrop_path") val backdrop_path: String? = null
)

private data class TmdbCollectionDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("parts") val parts: List<TmdbMedia>? = null
)

private data class TmdbDetailResponse(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null, 
    @JsonProperty("name") val name: String? = null, 
    @JsonProperty("original_title") val original_title: String? = null, 
    @JsonProperty("original_name") val original_name: String? = null, 
    @JsonProperty("overview") val overview: String? = null, 
    @JsonProperty("release_date") val releaseDate: String? = null, 
    @JsonProperty("first_air_date") val firstAirDate: String? = null, 
    @JsonProperty("status") val status: String? = null, 
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("origin_country") val origin_country: List<String>? = null,
    @JsonProperty("original_language") val original_language: String? = null,
    @JsonProperty("belongs_to_collection") val belongs_to_collection: TmdbCollection? = null,
    @JsonProperty("recommendations") val recommendations: TmdbRecommendations? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("release_dates") val release_dates: TmdbReleaseDates? = null,
    @JsonProperty("content_ratings") val content_ratings: TmdbContentRatings? = null,
    @JsonProperty("credits") val credits: TmdbCredits? = null,
    @JsonProperty("images") val images: TmdbImages? = null,
    @JsonProperty("videos") val videos: ResultsTrailer? = null
) {
    fun toTmdbMedia(mediaType: String): TmdbMedia {
        return TmdbMedia(
            id = this.id,
            name = this.name ?: this.title,
            title = this.title ?: this.name,
            originalTitle = this.original_title ?: this.original_name,
            mediaType = mediaType,
            posterPath = this.posterPath,
            overview = this.overview,
            origin_country = this.origin_country
        )
    }
}

private data class TmdbImages(@JsonProperty("logos") val logos: List<TmdbLogo>? = null)
private data class TmdbLogo(@JsonProperty("file_path") val file_path: String? = null)
private data class TmdbRecommendations(@JsonProperty("results") val results: List<TmdbMedia>? = null)

private data class TmdbMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("origin_country") val origin_country: List<String>? = null
)

private data class TmdbReleaseDates(@JsonProperty("results") val results: List<TmdbReleaseDateResult>?)
private data class TmdbReleaseDateResult(@JsonProperty("iso_3166_1") val iso_3166_1: String?, @JsonProperty("release_dates") val release_dates: List<TmdbReleaseDate>?)
private data class TmdbReleaseDate(@JsonProperty("certification") val certification: String?)
private data class TmdbContentRatings(@JsonProperty("results") val results: List<TmdbContentRatingResult>?)
private data class TmdbContentRatingResult(@JsonProperty("iso_3166_1") val iso_3166_1: String?, @JsonProperty("rating") val rating: String?)

private data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCast>? = null, @JsonProperty("crew") val crew: List<TmdbCrew>? = null)
private data class TmdbCast(@JsonProperty("name") val name: String?, @JsonProperty("original_name") val originalName: String?, @JsonProperty("character") val character: String?, @JsonProperty("profile_path") val profilePath: String?)
private data class TmdbCrew(@JsonProperty("name") val name: String?, @JsonProperty("original_name") val originalName: String?, @JsonProperty("job") val job: String?, @JsonProperty("profile_path") val profilePath: String?)

private data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)
private data class TmdbEpisode(@JsonProperty("name") val name: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("episode_number") val episodeNumber: Int?, @JsonProperty("air_date") val airDate: String?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("runtime") val runtime: Int?)

data class Trailers(@JsonProperty("key") val key: String? = null, @JsonProperty("type") val type: String? = null, @JsonProperty("published_at") val publishedAt: String? = null)
data class ResultsTrailer(@JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf())
data class ExternalIds(@JsonProperty("imdb_id") val imdb_id: String? = null, @JsonProperty("tvdb_id") val tvdb_id: String? = null)
