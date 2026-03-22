// v1.64
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
import com.hsp1020.StremioC.Companion.TRACKER_LIST_URLS
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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class StremioC(override var mainUrl: String, override var name: String) : MainAPI() {
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
     
    private var cachedManifest: Manifest? = null
    private var lastManifestUrl: String = ""
    private var lastCacheTime: Long = 0
    
    private val catalogSentIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val pageContentCache = ConcurrentHashMap<String, List<SearchResponse>>()
    
    private val catalogSkipState = ConcurrentHashMap<String, Int>()
    
    private val pageMutex = Mutex()
    private val activePageRequests = mutableMapOf<Int, Deferred<HomePageResponse>>()
    
    private val customSession by lazy {
        println("[StremioC v1.64-TRACKING] 커스텀 OkHttp 세션 초기화 (HTTP/1.1 강제, Dispatcher 100, ConnectionPool 100)")
        Requests(
            app.baseClient.newBuilder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .dispatcher(Dispatcher().apply {
                    maxRequests = 100
                    maxRequestsPerHost = 100
                })
                .connectionPool(ConnectionPool(100, 5, TimeUnit.MINUTES))
                .build()
        )
    }

    companion object {
        private const val cinemeta = "https://aiometadata.elfhosted.com/stremio/b7cb164b-074b-41d5-b458-b3a834e197bb"
        private const val cinemetav3 = "https://v3-cinemeta.strem.io"

        val TRACKER_LIST_URLS = listOf(
            "https://raw.githubusercontent.com/ngosang/trackerslist/refs/heads/master/trackers_best.txt",
            "https://raw.githubusercontent.com/ngosang/trackerslist/refs/heads/master/trackers_best_ip.txt",
        )
        private const val TRACKER_LIST_URL = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "cc9982c4801545a1481d167137ea7b53"
        private const val TRANSPARENT_PIXEL = "https://upload.wikimedia.org/wikipedia/commons/c/ca/1x1.png"
    }

    private fun baseUrl(): String {
        return mainUrl.substringBefore("?")
            .replace("/manifest.json", "")
            .trimEnd('/')
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
        val cacheAge = now - lastCacheTime
        val isExpired = cacheAge > 24 * 60 * 60 * 1000

        if (cachedManifest != null && 
            lastManifestUrl == currentUrl && 
            !isExpired && 
            !cachedManifest?.catalogs.isNullOrEmpty()) {
            return cachedManifest
        }

        val res = app.get(currentUrl, timeout = 120L).parsedSafe<Manifest>()

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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse = coroutineScope {
        println("[StremioC v1.64-TRACKING] 📌 getMainPage 요청 접수: page=$page (Thread: ${Thread.currentThread().name})")
        
        val deferred = pageMutex.withLock {
            if (page <= 1) {
                println("[StremioC v1.64-TRACKING] 🧹 page=1 요청으로 인한 상태(캐시/Skip/필터) 초기화 실행")
                catalogSentIds.clear()
                activePageRequests.clear()
                pageContentCache.clear()
                catalogSkipState.clear()
            }
            activePageRequests.getOrPut(page) {
                println("[StremioC v1.64-TRACKING] 🟢 page=$page 최초 요청 확인. 실제 데이터 로드 시작.")
                async { fetchMainPageData(page, request) }
            }
        }
        
        if (deferred.isCompleted) {
            println("[StremioC v1.64-TRACKING] 🟡 page=$page 이미 완료된 캐시 데이터 즉시 반환.")
        } else {
            println("[StremioC v1.64-TRACKING] ⏳ page=$page 데이터를 기다리는 중 (중복 요청 시 대기)...")
        }
        
        deferred.await()
    }

    private suspend fun fetchMainPageData(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val startTime = System.currentTimeMillis()
        
        if (mainUrl.isEmpty()) throw IllegalArgumentException("Configure in Extension Settings\n")
        mainUrl = mainUrl.fixSourceUrl()
        
        val manifestStartTime = System.currentTimeMillis()
        val manifest = getManifest()
        println("[StremioC v1.64-TRACKING] Manifest 가져오기 소요 시간: ${System.currentTimeMillis() - manifestStartTime}ms")
        
        val targetCatalogs = manifest?.catalogs?.filter { !it.isSearchRequired() } ?: emptyList()
        println("[StremioC v1.64-TRACKING] 처리 대상 카탈로그 개수: ${targetCatalogs.size}. 병렬 로드 시작.")
        
        val catalogsStartTime = System.currentTimeMillis()
        val lists = mutableListOf<HomePageList>()
        
        val chunkResults = coroutineScope {
            targetCatalogs.map { catalog ->
                async(Dispatchers.IO) {
                    try {
                        val catalogStartTime = System.currentTimeMillis()
                        val catalogKey = "${catalog.id}-${catalog.type}"
                        
                        val currentSkip = catalogSkipState[catalogKey] ?: 0
                        val cacheKey = "${catalogKey}_$currentSkip"
                        
                        println("[StremioC v1.64-TRACKING] 🚀 [시작] 카탈로그 [${catalog.id}], 현재 저장된 Skip: $currentSkip, 요청 page: $page")

                        val cachedItems = pageContentCache[cacheKey]
                        
                        val row = if (cachedItems != null) {
                            println("[StremioC v1.64-TRACKING] 🔍 카탈로그 [${catalog.id}] cacheKey($cacheKey)에 해당하는 캐시 히트. 바로 반환.")
                            val displayType = catalog.type?.replaceFirstChar { it.uppercase() } ?: ""
                            val catalogName = "${catalog.name ?: catalog.id} - $displayType"
                            HomePageList(catalogName, cachedItems)
                        } else {
                            println("[StremioC v1.64-TRACKING] 🔍 카탈로그 [${catalog.id}] 캐시 미스. toHomePageList 호출 (skip=$currentSkip)")
                            val resultPair = catalog.toHomePageList(provider = this@StremioC, skip = currentSkip)
                            val freshRow = resultPair.first
                            val rawCount = resultPair.second
                            
                            println("[StremioC v1.64-TRACKING] 📥 [수신] 카탈로그 [${catalog.id}] toHomePageList 반환 -> rawCount(원본갯수): $rawCount, freshRow.size(distinct후): ${freshRow.list.size}")

                            if (freshRow.list.isNotEmpty()) {
                                pageContentCache[cacheKey] = freshRow.list
                                val newSkip = currentSkip + rawCount
                                catalogSkipState[catalogKey] = newSkip
                                println("[StremioC v1.64-TRACKING] 💾 [Skip 갱신] 카탈로그 [${catalog.id}] Skip 상태 업데이트: $currentSkip -> $newSkip")
                            } else {
                                println("[StremioC v1.64-TRACKING] ⚠️ [Skip 유지] 카탈로그 [${catalog.id}] freshRow가 비어있어 Skip값을 갱신하지 않음.")
                            }
                            freshRow
                        }
                        
                        val seenForThisCatalog = catalogSentIds.getOrPut(catalogKey) { 
                            Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()) 
                        }
                        
                        val beforeFilterSize = row.list.size
                        val filteredItems = row.list.filter { item ->
                            val isNew = seenForThisCatalog.add(item.url)
                            if (!isNew) {
                                println("[StremioC v1.64-TRACKING] ✂️ [중복 필터링됨] 카탈로그 [${catalog.id}] URL: ${item.url}")
                            }
                            isNew
                        }
                        val afterFilterSize = filteredItems.size
                        
                        println("[StremioC v1.64-TRACKING] 📊 [최종 결과] 카탈로그 [${catalog.id}] 필터 전: $beforeFilterSize -> 필터 후(실제 반환): $afterFilterSize (필터로 유실된 갯수: ${beforeFilterSize - afterFilterSize})")
                        println("[StremioC v1.64-TRACKING] 🏁 [종료] 카탈로그 [${catalog.id}] 로드 완료 소요 시간: ${System.currentTimeMillis() - catalogStartTime}ms")
                        
                        row.copy(list = filteredItems)
                    } catch (e: Exception) {
                        println("[StremioC v1.64-TRACKING] ❌ [에러] 카탈로그 로드 중 에러 발생: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull().filter { it.list.isNotEmpty() }
        
        lists.addAll(chunkResults)

        println("[StremioC v1.64-TRACKING] 모든 카탈로그 데이터 수집 완료. 총 소요 시간: ${System.currentTimeMillis() - catalogsStartTime}ms")
        println("[StremioC v1.64-TRACKING] fetchMainPageData 전체 로직 소요 시간: ${System.currentTimeMillis() - startTime}ms")

        return newHomePageResponse(
            lists,
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val startTime = System.currentTimeMillis()
        
        mainUrl = mainUrl.fixSourceUrl()
        val manifest = getManifest()
        val supportedCatalogs = manifest?.catalogs?.filter { it.supportsSearch() } ?: emptyList()
        
        val addonResults = mutableListOf<SearchResponse>()
        println("[StremioC v1.64-TRACKING] 애드온 검색 시작 (동시 처리 개수: ${supportedCatalogs.size})")
        
        val searchResults = coroutineScope {
            supportedCatalogs.map { catalog ->
                async(Dispatchers.IO) {
                    try {
                        catalog.search(query, this@StremioC) 
                    } catch (e: Exception) {
                        println("[StremioC v1.64-TRACKING] ❌ 검색 중 에러 발생: ${e.message}")
                        emptyList<SearchResponse>()
                    }
                }
            }.awaitAll().flatten()
        }
        addonResults.addAll(searchResults)
        
        val distinctAddonResults = addonResults.distinctBy { it.url }
        println("[StremioC v1.64-TRACKING] search 애드온 탐색 완료 소요 시간: ${System.currentTimeMillis() - startTime}ms")
        
        println("[StremioC v1.64-TRACKING] 🔍 애드온 최종 검색 결과 개수: ${distinctAddonResults.size}. 결과를 즉시 반환합니다.")
        return distinctAddonResults
    }

    override suspend fun load(url: String): LoadResponse {
        val res: CatalogEntry = if (url.startsWith("{")) {
            parseJson(url)
        } else {
            val json = app.get(url).text
            val metaJson = JSONObject(json).getJSONObject("meta").toString()
            parseJson(metaJson)
        }
        
        var finalProcessedId = res.id
        if (res.id.startsWith("tmdb:")) {
            val tmdbIdOnly = res.id.removePrefix("tmdb:")
            try {
                val mediaType = if (res.type == "movie") "movie" else "tv"
                val externalUrl = "$tmdbAPI/$mediaType/$tmdbIdOnly/external_ids?api_key=$apiKey"
                val externalRes = app.get(externalUrl).parsedSafe<ExternalIds>()
                val imdbId = externalRes?.imdb_id
                if (!imdbId.isNullOrBlank() && imdbId.startsWith("tt")) {
                    finalProcessedId = imdbId
                }
            } catch (e: Exception) {
                println("[StremioC v1.64-TRACKING] ❌ TMDB 외부 ID 로드 중 에러: ${e.message}")
            }
        }

        val normalizedId = try { normalizeId(finalProcessedId) } catch (e: Exception) { finalProcessedId }
        val encodedId = try { URLEncoder.encode(normalizedId, "UTF-8") } catch (e: Exception) { normalizedId }
        
        val response = app.get(buildUrl("/meta/${res.type}/$encodedId.json"))
            .parsedSafe<CatalogResponse>()
            ?: throw RuntimeException("Failed to load meta")

        val entry = response.meta
            ?: response.metas?.firstOrNull { it.id == finalProcessedId || it.id == res.id }
            ?: response.metas?.firstOrNull()
            ?: run {
                val fallback = app.get(
                    "$cinemeta/meta/${res.type}/$encodedId.json",
                    timeout = 120L
                ).parsedSafe<CatalogResponse>()

                fallback?.meta
                    ?: fallback?.metas?.firstOrNull()
                    ?: throw RuntimeException("Meta not found (primary + fallback)")
            }

        return entry.toLoadResponse(this, finalProcessedId)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = try { parseJson<LoadData>(data) } catch (e: Exception) { null } ?: return false
        val normalizedId = try { normalizeId(loadData.id) } catch (e: Exception) { loadData.id ?: "" }
        val encodedId = try { URLEncoder.encode(normalizedId, "UTF-8") } catch (e: Exception) { normalizedId }
        
        val targetId = if (loadData.id != null && !(loadData.id.startsWith("tt") || loadData.id.startsWith("tmdb:") || loadData.id.startsWith("kitsu:"))) {
            if (!loadData.imdbId.isNullOrBlank()) {
                println("[StremioC v1.64-TRACKING] 사설 ID 감지됨(${loadData.id}). 대체 탐색용 ID를 imdbId(${loadData.imdbId})로 보정합니다.")
                loadData.imdbId
            } else {
                loadData.id
            }
        } else {
            loadData.id
        }

        val targetType = if ((loadData.season ?: 0) == 0 && (loadData.episode ?: 0) == 0) {
            println("[StremioC v1.64-TRACKING] 시즌과 에피소드가 모두 없거나(null) 0이므로 탐색용 Type을 'movie'로 보정합니다.")
            "movie"
        } else if ((loadData.season ?: 0) > 0 || (loadData.episode ?: 0) > 0) {
            println("[StremioC v1.64-TRACKING] 시즌 또는 에피소드가 존재하므로 탐색용 Type을 'series'로 보정합니다.")
            "series"
        } else {
            loadData.type
        }
        
        runAllAsync(
            {
                try {
                    val url = buildUrl("/stream/${loadData.type}/$encodedId.json")
                    val request = app.get(url, timeout = 120L)
                    val res = if (request.isSuccessful) request.parsedSafe<StreamsResponse>() else null

                    if (!res?.streams.isNullOrEmpty()) {
                        res?.streams?.forEach { stream ->
                            stream.runCallback(subtitleCallback, callback) 
                        }
                    } else {
                        invokeStremioX(targetType, targetId, loadData.season, loadData.episode, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    println("[StremioC v1.64-TRACKING] ❌ [loadLinks] 스트림 로드 / StremioX 대체 검색 중 에러 발생: ${e.message}")
                }
            },
            {
                invokeWatchsomuch(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback)
            },
            {
                invokeOpenSubs(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback)
            },
            {
                val subtitleId = if (!loadData.imdbId.isNullOrBlank()) loadData.imdbId else targetId
                println("[StremioC v1.64-TRACKING] 💬 자막 애드온 호출용 ID 설정: imdbId 우선 적용 -> $subtitleId")
                invokeStremioSubtitles(targetType, subtitleId, loadData.season, loadData.episode, subtitleCallback)
            }
        )

        return true
    }

    private suspend fun invokeStremioX(
        type: String?,
        id: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sites = AcraApplication.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList() ?: mutableListOf()
        val filteredSites = sites.filter { it.parentJavaClass == "StremioX" || it.parentJavaClass == "StremioC" }
        
        val stremioId = if (type == "series" && season != null && episode != null) {
            when {
                id?.startsWith("kitsu:") == true -> {
                    val parts = id.split(":")
                    val baseId = if (parts.size >= 2) "kitsu:${parts[1]}" else id
                    "$baseId:$season:$episode"
                }
                id?.endsWith(":$season:$episode") == true -> id
                else -> "$id:$season:$episode"
            }
        } else {
            id
        }

        coroutineScope {
            filteredSites.map { site ->
                async(Dispatchers.IO) {
                    try {
                        val api = site.url.fixSourceUrl().substringBefore("?").replace("/manifest.json", "").trimEnd('/')
                        val url = "$api/stream/$type/$stremioId.json"
                        
                        val req = app.get(url, timeout = 120L)
                        val res = req.parsedSafe<StreamsResponse>()
                        if (res?.streams != null) {
                            res.streams.forEach { stream ->
                                stream.runCallback(subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        println("[StremioC v1.64-TRACKING] ❌ [invokeStremioX] 애드온 통신 중 에러 발생: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }

    private data class StremioSubtitleResponse(
        @JsonProperty("subtitles") val subtitles: List<Subtitle>?
    )
    
    private suspend fun invokeStremioSubtitles(
        type: String?,
        id: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val sites = AcraApplication.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList() ?: mutableListOf()
        val addonUrls = mutableSetOf<String>()
        
        addonUrls.add(baseUrl())
        
        sites.filter { it.parentJavaClass == "StremioX" || it.parentJavaClass == "StremioC" }.forEach { site ->
            val cleanUrl = site.url.fixSourceUrl().substringBefore("?").replace("/manifest.json", "").trimEnd('/')
            addonUrls.add(cleanUrl)
        }

        val gson = Gson()
        
        val stremioId = if (type == "series" && season != null && episode != null) {
            when {
                id?.startsWith("kitsu:") == true -> {
                    val parts = id.split(":")
                    val baseId = if (parts.size >= 2) "kitsu:${parts[1]}" else id
                    "$baseId:$season:$episode"
                }
                id?.endsWith(":$season:$episode") == true -> id
                else -> "$id:$season:$episode"
            }
        } else {
            id
        }

        coroutineScope {
            addonUrls.toList().map { api ->
                async(Dispatchers.IO) {
                    try {
                        val url = "$api/subtitles/$type/$stremioId.json"
                        val json = app.get(url, timeout = 30L).text
                        val subtitleResponse = gson.fromJson(json, StremioSubtitleResponse::class.java)

                        subtitleResponse?.subtitles?.forEach { sub ->
                            val lang = sub.lang ?: sub.lang_code ?: "Unknown"
                            val fileUrl = sub.url
                            if (!fileUrl.isNullOrBlank()) {
                                subtitleCallback.invoke(
                                    newSubtitleFile(
                                        SubtitleHelper.fromTagToEnglishLanguageName(lang) ?: lang,
                                        fileUrl
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        println("[StremioC v1.64-TRACKING] ❌ [invokeStremioSubtitles] 자막 애드온 통신 중 에러 발생: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    }

    data class LoadData(
        val type: String? = null,
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val imdbId: String? = null,
        val year: Int? = null
    )

    data class CustomSite(
        @JsonProperty("parentJavaClass") val parentJavaClass: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("lang") val lang: String,
    )

    private fun isImdborTmdb(url: String?): Boolean {
        return imdbUrlToIdNullable(url) != null || url?.startsWith("tmdb:") == true
    }

    private fun isImdb(url: String?): Boolean {
        return imdbUrlToIdNullable(url) != null
    }

   private data class Manifest(val catalogs: List<Catalog>)
    
    private data class Extra(
        @JsonProperty("name") val name: String?,
        @JsonProperty("isRequired") val isRequired: Boolean? = false
    )

    private data class Catalog(
        var name: String?,
        val id: String,
        val type: String?,
        val types: MutableList<String> = mutableListOf(),
        @JsonProperty("extra") val extra: List<Extra>? = null,
        @JsonProperty("extraSupported") val extraSupported: List<String>? = null
    ) {
        init {
            if (type != null) types.add(type)
        }

        fun isSearchRequired(): Boolean {
            return extra?.any { it.name == "search" && it.isRequired == true } == true
        }

        fun supportsSearch(): Boolean {
            val hasSearchInExtra = extra?.any { it.name == "search" } == true
            val hasSearchInExtraSupported = extraSupported?.contains("search") == true
            return hasSearchInExtra || hasSearchInExtraSupported
        }

        suspend fun search(query: String, provider: StremioC): List<SearchResponse> {
            val allMetas = coroutineScope {
                types.map { type ->
                    async(Dispatchers.IO) {
                        try {
                            val searchUrl = provider.buildUrl("/catalog/${type}/${id}/search=${URLEncoder.encode(query, "UTF-8")}.json")
                            val req = provider.customSession.get(searchUrl, timeout = 120L)
                            val res = if (req.isSuccessful && req.text.isNotBlank()) {
                                try { parseJson<CatalogResponse>(req.text) } catch (e: Exception) { null }
                            } else null
                            
                            res?.metas ?: emptyList<CatalogEntry>()
                        } catch (e: Exception) {
                            println("[StremioC v1.64-TRACKING] ❌ 카탈로그 검색 중 에러: ${e.message}")
                            emptyList<CatalogEntry>()
                        }
                    }
                }.awaitAll().flatten()
            }

            return allMetas.distinctBy { it.id }.map { it.toSearchResponse(provider) }
        }

        suspend fun toHomePageList(
            provider: StremioC,
            skip: Int
        ): Pair<HomePageList, Int> {
            val allMetas = coroutineScope {
                types.map { type ->
                    async(Dispatchers.IO) {
                        try {
                            val path = if (skip > 0) {
                                "/catalog/$type/$id/skip=$skip.json"
                            } else {
                                "/catalog/$type/$id.json"
                            }
                            val url = provider.buildUrl(path)
                            val currentThread = Thread.currentThread().name
                            
                            val reqStartTime = System.currentTimeMillis()
                            println("[StremioC v1.64-TRACKING] 🌐 [네트워크 요청] URL: $url (Thread: $currentThread)")
                            
                            val req = provider.customSession.get(url, timeout = 120L)
                            
                            val reqEndTime = System.currentTimeMillis()
                            println("[StremioC v1.64-TRACKING] 📡 [네트워크 수신 완료] HTTP 코드: ${req.code}, 순수 대기시간: ${reqEndTime - reqStartTime}ms")

                            val res = if (req.isSuccessful && req.text.isNotBlank()) {
                                try { 
                                    val parsed = parseJson<CatalogResponse>(req.text)
                                    println("[StremioC v1.64-TRACKING] ✅ [파싱 성공] URL: $url, 추출된 metas 사이즈: ${parsed.metas?.size ?: 0}")
                                    parsed 
                                } catch (e: Exception) { 
                                    println("[StremioC v1.64-TRACKING] ❌ [파싱 실패] URL: $url, 에러: ${e.message}")
                                    null 
                                }
                            } else {
                                println("[StremioC v1.64-TRACKING] ⚠️ [요청 실패/빈 응답] URL: $url, isSuccessful: ${req.isSuccessful}, text 길이: ${req.text.length}")
                                null
                            }
                            
                            res?.metas ?: emptyList<CatalogEntry>()
                        } catch (e: Exception) {
                            println("[StremioC v1.64-TRACKING] ❌ 카탈로그 네트워크 로드 중 치명적 에러: ${e.message}")
                            emptyList<CatalogEntry>()
                        }
                    }
                }.awaitAll().flatten()
            }

            val rawCount = allMetas.size
            println("[StremioC v1.64-TRACKING] 🔍 [toHomePageList] 카탈로그 [${id}], distinctBy 수행 전 원본 항목 수(rawCount): $rawCount")
            
            val distinctEntries = allMetas.distinctBy { it.id }.map { it.toSearchResponse(provider) }
            println("[StremioC v1.64-TRACKING] 🔍 [toHomePageList] 카탈로그 [${id}], distinctBy 수행 후 항목 수: ${distinctEntries.size}")

            val displayType = type?.replaceFirstChar { it.uppercase() } ?: ""
            val catalogName = "${name ?: id} - $displayType"

            return Pair(HomePageList(catalogName, distinctEntries), rawCount)
        }
    }

    private data class CatalogResponse(val metas: List<CatalogEntry>?, val meta: CatalogEntry?)

    private data class Trailer(
        val source: String?,
        val type: String?
    )

    private data class TrailerStream(
        @JsonProperty("ytId") val ytId: String?,
        @JsonProperty("title") val title: String? = null        
    )

    private data class Link(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    private data class FetchedTmdbData(
        val fetchedTitle: String?, 
        val fetchedOverview: String?,
        val fetchedLogo: String?,
        val fetchedTrailers: List<String>,
        val fetchedRecommendations: List<SearchResponse>?,
        val fetchedRuntime: Int?,
        val fetchedAgeRating: String?,
        val fetchedActors: List<ActorData>?,
        val episodeTmdbMeta: Map<String, TmdbEpisode>
    )

    private data class CatalogEntry(
        @JsonProperty("name") val name: String,
        @JsonProperty("id") val id: String,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("background") val background: String?,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("description") val description: String?,
        @JsonProperty("imdbRating") val imdbRating: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("videos") val videos: List<Video>?,
        @JsonProperty("genre") val genre: List<String>?,
        @JsonProperty("genres") val genres: List<String> = emptyList(),
        @JsonProperty("cast") val cast: List<String> = emptyList(),
        @JsonProperty("trailers") val trailersSources: List<Trailer> = emptyList(),
        @JsonProperty("trailerStreams") val trailerStreams: List<TrailerStream> = emptyList(),
        @JsonProperty("year") val yearNum: String? = null,
        @JsonProperty("links") val links: List<Link> = emptyList(),
        @JsonProperty("releaseInfo") val releaseInfo: String? = null
    ) {
        fun toSearchResponse(provider: StremioC): SearchResponse {
            return provider.newMovieSearchResponse(
                name,
                this.toJson(),
                TvType.Others
            ) {
                posterUrl = poster
            }
        }

        private suspend fun fetchTmdbDetails(
            provider: StremioC,
            tmdbMediaType: String,
            tmdbIdStr: String,
            isMovie: Boolean
        ): FetchedTmdbData {
            var fetchedTitle: String? = null
            var fetchedOverview: String? = null
            var fetchedLogo: String? = null
            var fetchedTrailers: List<String> = emptyList()
            var fetchedRecommendations: List<SearchResponse>? = null
            var fetchedRuntime: Int? = null
            var fetchedAgeRating: String? = null
            var fetchedActors: List<ActorData>? = null
            val episodeTmdbMeta = mutableMapOf<String, TmdbEpisode>()

            val detailAppend = if (isMovie) "recommendations,release_dates,credits,images,videos" else "recommendations,content_ratings,credits,images,videos"
            val detailUrl = "$tmdbAPI/$tmdbMediaType/$tmdbIdStr?api_key=$apiKey&language=ko-KR&append_to_response=$detailAppend&include_image_language=ko"
            
            val detailRes = app.get(detailUrl).parsedSafe<TmdbDetailResponse>()
            
            if (detailRes != null) {
                fetchedTitle = if (isMovie) detailRes.title ?: detailRes.original_title else detailRes.name ?: detailRes.original_name
                fetchedOverview = detailRes.overview

                fetchedLogo = detailRes.images?.logos?.firstOrNull()?.file_path?.let {
                    "https://image.tmdb.org/t/p/w500$it"
                }

                val rawVideos = detailRes.videos?.results ?: emptyList()
                
                fetchedTrailers = rawVideos.filter { 
                    it.type == "Trailer" || it.type == "Teaser" 
                }.sortedWith(compareBy(
                    { if (it.type == "Trailer") 0 else 1 },
                    { it.publishedAt ?: "9999-12-31" }
                )).mapNotNull { it.key }.map { 
                    "https://m.youtube.com/watch?v=$it" 
                }

                fetchedRecommendations = detailRes.recommendations?.results?.mapNotNull { media ->
                    val recTitle = media.title ?: media.name ?: media.originalTitle ?: return@mapNotNull null
                    val posterUrl = provider.getOriImageUrl(media.posterPath)
                    
                    val rawMediaType = media.mediaType ?: tmdbMediaType
                    val stremioType = if (rawMediaType == "tv") "series" else "movie"
                    
                    val recommendationEntry = CatalogEntry(
                        name = recTitle,
                        id = "tmdb:${media.id}",
                        type = stremioType, 
                        poster = posterUrl,
                        background = null,
                        description = media.overview,
                        imdbRating = null,
                        videos = null,
                        genre = null
                    )
                    
                    provider.newMovieSearchResponse(
                        recTitle,
                        recommendationEntry.toJson(),
                        if (stremioType == "movie") TvType.Movie else TvType.TvSeries
                    ) {
                        this.posterUrl = posterUrl
                    }
                }

                if (isMovie) {
                    fetchedRuntime = detailRes.runtime
                    fetchedAgeRating = detailRes.release_dates?.results
                        ?.firstOrNull { it.iso_3166_1 == "KR" }
                        ?.release_dates
                        ?.firstOrNull { !it.certification.isNullOrEmpty() }
                        ?.certification
                } else {
                    fetchedAgeRating = detailRes.content_ratings?.results
                        ?.firstOrNull { it.iso_3166_1 == "KR" }
                        ?.rating
                }

                val crewList = detailRes.credits?.crew?.filter { 
                    it.job == "Director" || it.job == "Writer" 
                }?.groupBy { it.name ?: it.originalName }?.mapNotNull { (name, roles) ->
                    if (name == null) return@mapNotNull null
                    
                    val sortedJobs = roles.mapNotNull { it.job }.distinct().sortedBy { 
                        if (it == "Director") 1 else 2 
                    }
                    val combinedJobs = sortedJobs.joinToString(", ")
                    
                    val img = roles.firstNotNullOfOrNull { it.profilePath }?.let { 
                        provider.getActorUrl(it)
                    } ?: TRANSPARENT_PIXEL
                    
                    ActorData(Actor(name, img), roleString = combinedJobs)
                }?.sortedBy { actorData ->
                    when {
                        actorData.roleString?.contains("Director, Writer") == true -> 1 
                        actorData.roleString?.contains("Director") == true -> 2 
                        else -> 3 
                    }
                } ?: emptyList()

                val castList = detailRes.credits?.cast?.mapNotNull { cast ->
                    val actorName = cast.name ?: cast.originalName ?: return@mapNotNull null
                    val profileImg = cast.profilePath?.let { 
                        provider.getActorUrl(it)
                    } ?: TRANSPARENT_PIXEL
                    
                    ActorData(Actor(actorName, profileImg), roleString = cast.character)
                } ?: emptyList()

                fetchedActors = crewList + castList
            }
            
            if (!isMovie && !this.videos.isNullOrEmpty()) {
                val requiredSeasons = this.videos.mapNotNull { it.seasonNumber }.distinct().filter { it > 0 }
                if (requiredSeasons.isNotEmpty()) {
                    coroutineScope {
                        requiredSeasons.map { seasonNum ->
                            async(Dispatchers.IO) {
                                try {
                                    val seasonUrl = "$tmdbAPI/tv/$tmdbIdStr/season/$seasonNum?api_key=$apiKey&language=ko-KR"
                                    val seasonRes = app.get(seasonUrl).parsedSafe<TmdbSeasonDetail>()
                                    seasonRes?.episodes?.forEach { ep ->
                                        if (ep.episodeNumber != null) {
                                            episodeTmdbMeta["${seasonNum}_${ep.episodeNumber}"] = ep
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("[StremioC v1.64-TRACKING] ❌ [fetchTmdbDetails] TMDB 시즌 $seasonNum 조회 중 에러: ${e.message}")
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
            
            return FetchedTmdbData(
                fetchedTitle,
                fetchedOverview,
                fetchedLogo,
                fetchedTrailers,
                fetchedRecommendations,
                fetchedRuntime,
                fetchedAgeRating,
                fetchedActors,
                episodeTmdbMeta
            )
        }

        private fun processEpisodes(
            provider: StremioC,
            finalImdbId: String?,
            episodeTmdbMeta: Map<String, TmdbEpisode>
        ): List<Episode> {
            val targetVideos = this.videos ?: emptyList()
            println("[StremioC v1.64-TRACKING] 🔀 [정렬] 비디오 목록 정렬 시작: 동일 시즌/에피소드 내 title A-Z 정렬")
            val sortedVideos = targetVideos.groupBy { Pair(it.seasonNumber ?: 0, it.episode ?: it.number ?: 0) }
                .flatMap { (key, group) ->
                    val sortedGroup = group.sortedBy { it.title ?: it.name ?: "" }
                    if (group.size > 1) {
                        println("[StremioC v1.64-TRACKING] 🔄 시즌 ${key.first}, 에피소드 ${key.second} 내 중복 항목 ${group.size}개 title 기준 A-Z 정렬 적용됨 (예: ${sortedGroup.first().title})")
                    }
                    sortedGroup
                }
            println("[StremioC v1.64-TRACKING] ✅ [정렬 완료] 총 ${sortedVideos.size}개의 비디오 정렬 완료")

            val seasonZeroEpCount = mutableMapOf<Int, Int>()
            val seasonZeroEpCounter = mutableMapOf<Int, Int>()
            
            sortedVideos.forEach { video ->
                val originalSeason = video.seasonNumber ?: 0
                val displaySeason = if (originalSeason == 0) 1 else originalSeason
                val originalEpisode = video.episode ?: video.number ?: 0
                if (originalEpisode == 0) {
                    seasonZeroEpCount[displaySeason] = seasonZeroEpCount.getOrDefault(displaySeason, 0) + 1
                }
            }

            val uniqueEpisodeChecker = mutableSetOf<String>()

            return sortedVideos.mapIndexed { index, video ->
                val originalSeason = video.seasonNumber ?: 0
                val originalEpisode = video.episode ?: video.number ?: 0
                
                val displaySeason = if (originalSeason == 0) 1 else originalSeason
                var displayEpisode: Int
                
                if (originalEpisode == 0) {
                    val currentZeroCount = seasonZeroEpCounter.getOrDefault(displaySeason, 0) + 1
                    seasonZeroEpCounter[displaySeason] = currentZeroCount
                    displayEpisode = currentZeroCount
                } else {
                    val zeroCountForSeason = seasonZeroEpCount.getOrDefault(displaySeason, 0)
                    displayEpisode = originalEpisode + zeroCountForSeason
                }
                
                var key = "${displaySeason}_${displayEpisode}"
                if (uniqueEpisodeChecker.contains(key)) {
                    displayEpisode = (uniqueEpisodeChecker.filter { it.startsWith("${displaySeason}_") }.maxOfOrNull { it.split("_")[1].toInt() } ?: 0) + 1
                    key = "${displaySeason}_${displayEpisode}"
                }
                uniqueEpisodeChecker.add(key)
                
                println("[StremioC v1.64-TRACKING] 🎬 비디오 UI 렌더링 시프트 매핑: index=$index, original=S${originalSeason}E${originalEpisode} -> UI=S${displaySeason}E${displayEpisode}, id=${video.id}")
                
                video.toEpisode(provider, this.type, finalImdbId, episodeTmdbMeta, displaySeason, displayEpisode)
            }
        }

        suspend fun toLoadResponse(provider: StremioC, imdbId: String?): LoadResponse {
            val fallbackTrailers = trailerStreams.mapNotNull { it.ytId }
                .ifEmpty { trailersSources.mapNotNull { it.source } }
                .distinct()
                .map { if (it.startsWith("http")) it else "https://m.youtube.com/watch?v=$it" }

            val validImdbIdParam = imdbId?.takeIf { it.startsWith("tt") }

            val extractedImdbId = links.firstOrNull { it.category == "imdb" }?.url?.substringAfterLast("/")?.takeIf { it.startsWith("tt") }
            val extractedTmdbId = if (this.id.startsWith("tmdb:")) this.id.removePrefix("tmdb:") else null
            
            var finalImdbId = extractedImdbId ?: (if (this.id.startsWith("tt")) this.id else validImdbIdParam)
            var tmdbIdStr: String? = extractedTmdbId

            if (finalImdbId == null && logo != null) {
                finalImdbId = "tt[0-9]+".toRegex().find(logo)?.value
            }

            if (this.id.startsWith("kitsu:") && finalImdbId.isNullOrBlank()) {
                try {
                    val kitsuUrl = "https://anime-kitsu.strem.fun/meta/${this.type}/${this.id}.json"
                    println("[StremioC v1.64-TRACKING] 🦊 로컬에 IMDB ID가 없어 Kitsu API로 Fallback 통신을 시도합니다: $kitsuUrl")
                    
                    val kitsuJson = app.get(kitsuUrl, timeout = 30L).text
                    val metaObj = JSONObject(kitsuJson).optJSONObject("meta")
                    
                    if (metaObj != null) {
                        val fetchedKitsuImdbId = metaObj.optString("imdb_id", "")
                        if (fetchedKitsuImdbId.isNotBlank() && fetchedKitsuImdbId.startsWith("tt")) {
                            finalImdbId = fetchedKitsuImdbId
                            println("[StremioC v1.64-TRACKING] ✅ Kitsu API에서 IMDB ID 성공적으로 추출됨: $finalImdbId")
                        } else {
                            println("[StremioC v1.64-TRACKING] ⚠️ Kitsu API 응답에서 IMDB ID를 찾을 수 없거나 올바르지 않은 형식입니다.")
                        }
                    }
                } catch (e: Exception) {
                    println("[StremioC v1.64-TRACKING] ❌ [toLoadResponse] Kitsu API Fallback 호출 중 에러 발생: ${e.message}")
                }
            } else if (this.id.startsWith("kitsu:") && !finalImdbId.isNullOrBlank()) {
                println("[StremioC v1.64-TRACKING] ⚡ Kitsu 아이템이지만 로컬 JSON에서 유효한 IMDB ID($finalImdbId)를 확보하여 API 통신을 건너뜁니다.")
            }

            val isSingleMovieVideo = (type == "movie" && videos?.size == 1 && videos[0].seasonNumber == 1 && (videos[0].episode == 1 || videos[0].number == 1)) ||
                    (videos?.size == 1 && (videos[0].seasonNumber ?: 0) == 0 && (videos[0].episode ?: videos[0].number ?: 0) == 0)

            val tmdbMediaType = if (isSingleMovieVideo || type == "movie" || videos.isNullOrEmpty()) "movie" else "tv"

            if (tmdbIdStr == null && finalImdbId?.startsWith("tt") == true) {
                try {
                    val findUrl = "$tmdbAPI/find/$finalImdbId?api_key=$apiKey&external_source=imdb_id&language=ko-KR"
                    val findRes = app.get(findUrl).parsedSafe<TmdbFindResponse>()
                    
                    val tmdbId = if (tmdbMediaType == "movie") findRes?.movie_results?.firstOrNull()?.id else findRes?.tv_results?.firstOrNull()?.id
                    if (tmdbId != null) {
                        tmdbIdStr = tmdbId.toString()
                        println("[StremioC v1.64-TRACKING] 🎬 TMDB Find API를 통해 TMDB ID 확보 완료: $tmdbIdStr")
                    }
                } catch (e: Exception) {
                    println("[StremioC v1.64-TRACKING] ❌ [toLoadResponse] TMDB Find API 호출 중 에러: ${e.message}")
                }
            }

            var tmdbData: FetchedTmdbData? = null
            if (tmdbIdStr != null) {
                try {
                    tmdbData = fetchTmdbDetails(provider, tmdbMediaType, tmdbIdStr, tmdbMediaType == "movie")
                } catch (e: Exception) {
                    println("[StremioC v1.64-TRACKING] ❌ [toLoadResponse] TMDB 세부 메타데이터 추출/통신 중 에러 발생: ${e.message}")
                }
            }

            var finalName = this.name
            var finalDescription = this.description
            val originalName = this.name

            if (tmdbData != null) {
                // v1.64: 메인 타이틀은 타입(other) 무관하게 무조건 TMDB 타이틀로 덮어쓰기
                if (!tmdbData.fetchedTitle.isNullOrBlank()) {
                    val tmdbTitle = tmdbData.fetchedTitle
                    println("[StremioC v1.64-TRACKING] 🔠 TMDB 한국어 메인 타이틀로 전면 교체 완료: $tmdbTitle")
                    finalName = tmdbTitle
                }

                val tmdbOverview = tmdbData.fetchedOverview
                if (!tmdbOverview.isNullOrBlank()) {
                    if (finalDescription != null && finalDescription.contains("✨")) {
                        val splitIndex = finalDescription.indexOf("|")
                        if (splitIndex != -1) {
                            finalDescription = finalDescription.substring(0, splitIndex + 1).trim() + " " + tmdbOverview
                            println("[StremioC v1.64-TRACKING] ✨ Kitsu 스타일 개요 감지. 별점 유지 및 TMDB 개요 병합 완료.")
                        } else {
                            finalDescription = tmdbOverview
                        }
                    } else {
                        finalDescription = tmdbOverview
                        println("[StremioC v1.64-TRACKING] 📝 일반 개요. TMDB 개요로 전면 교체 완료.")
                    }
                }
                
                // v1.64: type이 other일 경우에 한해, 기존 json 메인 타이틀명을 개요 앞부분으로 이동시켜 병합
                if (this.type == "other") {
                    finalDescription = if (finalDescription.isNullOrBlank()) {
                        originalName
                    } else {
                        "$originalName\n$finalDescription"
                    }
                    println("[StremioC v1.64-TRACKING] 📝 타입이 'other'이므로 기존 타이틀을 개요 맨 앞에 병합: $finalDescription")
                }
            }

            val finalTrailers = tmdbData?.fetchedTrailers?.ifEmpty { fallbackTrailers } ?: fallbackTrailers

            val movieId = if (!videos.isNullOrEmpty() && !videos[0].id.isNullOrBlank()) {
                println("[StremioC v1.64-TRACKING] 🎥 비디오 목록에서 고유 Video ID를 추출하여 스트림 탐색 ID로 사용합니다: ${videos[0].id}")
                videos[0].id
            } else {
                id
            }

            if (videos.isNullOrEmpty() || isSingleMovieVideo) {
                println("[StremioC v1.64-TRACKING] 🎥 해당 아이템은 단일 영화(Movie)로 강제 분류되어 응답을 생성합니다. (Stream ID: $movieId)")
                return provider.newMovieLoadResponse(
                    finalName,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.Movie,
                    LoadData(type, movieId, imdbId = finalImdbId, year = yearNum?.toIntOrNull())
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    score = Score.from10(imdbRating)
                    plot = finalDescription 
                    year = yearNum?.toIntOrNull()
                    tags = genre ?: genres
                    
                    if (tmdbData?.fetchedActors?.isNotEmpty() == true) {
                        this.actors = tmdbData.fetchedActors
                    } else {
                        addActors(cast)
                    }
                    
                    addTrailer(finalTrailers)                    
                    this.recommendations = tmdbData?.fetchedRecommendations
                    this.duration = tmdbData?.fetchedRuntime
                    this.contentRating = tmdbData?.fetchedAgeRating
                    
                    tmdbData?.fetchedLogo?.let { this.logoUrl = it }
                    
                    tmdbIdStr?.let { 
                        addTMDbId(it)
                    }
                    finalImdbId?.let { 
                        if (it.startsWith("tt")) {
                            addImdbId(it)
                        }
                    }
                }
            } else {
                val episodesList = processEpisodes(provider, finalImdbId, tmdbData?.episodeTmdbMeta ?: emptyMap())

                return provider.newTvSeriesLoadResponse(
                    finalName,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.TvSeries,
                    episodesList
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    score = Score.from10(imdbRating)
                    plot = finalDescription
                    year = yearNum?.toIntOrNull()
                    tags = genre ?: genres
                    
                    if (tmdbData?.fetchedActors?.isNotEmpty() == true) {
                        this.actors = tmdbData.fetchedActors
                    } else {
                        addActors(cast)
                    }
                    
                    addTrailer(finalTrailers.firstOrNull())
                    this.recommendations = tmdbData?.fetchedRecommendations
                    this.contentRating = tmdbData?.fetchedAgeRating
                    
                    tmdbData?.fetchedLogo?.let { this.logoUrl = it }
                    
                    tmdbIdStr?.let { 
                        addTMDbId(it)
                    }
                    finalImdbId?.let { 
                        if (it.startsWith("tt")) {
                            addImdbId(it)
                        }
                    }
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
        fun toEpisode(
            provider: StremioC, 
            type: String?, 
            imdbId: String?, 
            tmdbMetaMap: Map<String, TmdbEpisode>,
            displaySeason: Int,
            displayEpisode: Int
        ): Episode {
            val originalSeason = seasonNumber
            val originalEpisode = episode ?: number
            val epKey = "${originalSeason}_${originalEpisode}"
            val tmdbEp = tmdbMetaMap[epKey]
            
            return provider.newEpisode(
                LoadData(type, id, originalSeason, originalEpisode, imdbId)
            ) {
                val originalTitle = this@Video.name ?: this@Video.title ?: "Episode $displayEpisode"
                val tmdbTitle = tmdbEp?.name
                
                this.name = if (!tmdbTitle.isNullOrBlank()) {
                    if (type == "other" && tmdbTitle != originalTitle) {
                        println("[StremioC v1.64-TRACKING] 🔠 타입이 'other'이므로 에피소드 타이틀 병합 처리: $tmdbTitle | $originalTitle")
                        "$tmdbTitle | $originalTitle"
                    } else {
                        println("[StremioC v1.64-TRACKING] 🔠 순수 TMDB 에피소드 타이틀로 덮어쓰기: $tmdbTitle")
                        tmdbTitle
                    }
                } else {
                    originalTitle
                }
                
                this.posterUrl = thumbnail ?: TRANSPARENT_PIXEL
                
                var finalEpDesc = this@Video.overview ?: this@Video.description
                val tmdbEpOverview = tmdbEp?.overview
                
                if (!tmdbEpOverview.isNullOrBlank()) {
                    if (finalEpDesc != null && finalEpDesc.contains("✨")) {
                        val splitIndex = finalEpDesc.indexOf("|")
                        if (splitIndex != -1) {
                            finalEpDesc = finalEpDesc.substring(0, splitIndex + 1).trim() + " " + tmdbEpOverview
                        } else {
                            finalEpDesc = tmdbEpOverview
                        }
                    } else {
                        finalEpDesc = tmdbEpOverview
                    }
                }
                
                this.description = finalEpDesc
                
                this.season = displaySeason
                this.episode = displayEpisode

                val finalAirDate = tmdbEp?.airDate?.takeIf { it.isNotBlank() } ?: this@Video.firstAired ?: this@Video.released
                finalAirDate?.takeIf { it.isNotBlank() }?.let { this.addDate(it) }

                tmdbEp?.voteAverage?.takeIf { it > 0.0 }?.let { this.score = Score.from10(it) }
                tmdbEp?.runtime?.let { this.runTime = it }
            }
        }
    }

    private data class StreamsResponse(
        @JsonProperty("streams") val streams: List<Stream>
    )
    
    private data class Subtitle(
        @JsonProperty("url") val url: String?,
        @JsonProperty("lang") val lang: String?,
        @JsonProperty("lang_code") val lang_code: String?,
        @JsonProperty("id") val id: String?,
    )

    private data class ProxyHeaders(
        @JsonProperty("request") val request: Map<String, String>?,
    )

    private data class BehaviorHints(
        @JsonProperty("proxyHeaders") val proxyHeaders: ProxyHeaders?,
        @JsonProperty("headers") val headers: Map<String, String>?,
    )

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
        suspend fun runCallback(
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            if (url != null) {
                callback.invoke(
                    newExtractorLink(
                        name ?: "",
                        fixSourceName(name, title),
                        url,
                        INFER_TYPE,
                    )
                    {
                        this.quality=getQuality(listOf(description,title,name))
                        this.headers=behaviorHints?.proxyHeaders?.request ?: behaviorHints?.headers ?: mapOf()
                    }
                )
                subtitles.map { sub ->
                    val lang = sub.lang ?: sub.lang_code ?: "Unknown"
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            SubtitleHelper.fromTagToEnglishLanguageName(lang) ?: lang,
                            sub.url ?: return@map
                        )
                    )
                }
            }
            if (ytId != null) {
                loadExtractor("https://m.youtube.com/watch?v=$ytId", subtitleCallback, callback)
            }
            if (externalUrl != null) {
                loadExtractor(externalUrl, subtitleCallback, callback)
            }
            if (infoHash != null) {
                val resp = app.get(TRACKER_LIST_URL).text
                val otherTrackers = resp
                    .split("\n")
                    .filterIndexed { i, _ -> i % 2 == 0 }
                    .filter { s -> s.isNotEmpty() }.joinToString("") { "&tr=$it" }

                val sourceTrackers = sources
                    .filter { it.startsWith("tracker:") }
                    .map { it.removePrefix("tracker:") }
                    .filter { s -> s.isNotEmpty() }.joinToString("") { "&tr=$it" }

                val magnet = "magnet:?xt=urn:btih:${infoHash}${sourceTrackers}${otherTrackers}"
                callback.invoke(
                    newExtractorLink(
                        name ?: "",
                        title ?: name ?: "",
                        magnet,
                    )
                    {
                        this.quality=Qualities.Unknown.value
                    }
                )
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

private data class TmdbDetailResponse(
    @JsonProperty("title") val title: String? = null, 
    @JsonProperty("name") val name: String? = null, 
    @JsonProperty("original_title") val original_title: String? = null, 
    @JsonProperty("original_name") val original_name: String? = null, 
    @JsonProperty("overview") val overview: String? = null, 
    @JsonProperty("recommendations") val recommendations: TmdbRecommendations? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("release_dates") val release_dates: TmdbReleaseDates? = null,
    @JsonProperty("content_ratings") val content_ratings: TmdbContentRatings? = null,
    @JsonProperty("credits") val credits: TmdbCredits? = null,
    @JsonProperty("original_language") val original_language: String? = null,
    @JsonProperty("images") val images: TmdbImages? = null,
    @JsonProperty("videos") val videos: ResultsTrailer? = null
)

private data class TmdbImages(
    @JsonProperty("logos") val logos: List<TmdbLogo>? = null
)

private data class TmdbLogo(
    @JsonProperty("file_path") val file_path: String? = null
)

private data class TmdbRecommendations(
    @JsonProperty("results") val results: List<TmdbMedia>? = null
)

private data class TmdbMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("overview") val overview: String? = null
)

private data class TmdbReleaseDates(@JsonProperty("results") val results: List<TmdbReleaseDateResult>?)
private data class TmdbReleaseDateResult(@JsonProperty("iso_3166_1") val iso_3166_1: String?, @JsonProperty("release_dates") val release_dates: List<TmdbReleaseDate>?)
private data class TmdbReleaseDate(@JsonProperty("certification") val certification: String?)
private data class TmdbContentRatings(@JsonProperty("results") val results: List<TmdbContentRatingResult>?)
private data class TmdbContentRatingResult(@JsonProperty("iso_3166_1") val iso_3166_1: String?, @JsonProperty("rating") val rating: String?)

private data class TmdbCredits(
    @JsonProperty("cast") val cast: List<TmdbCast>? = null,
    @JsonProperty("crew") val crew: List<TmdbCrew>? = null
)
private data class TmdbCast(
    @JsonProperty("name") val name: String?,
    @JsonProperty("original_name") val originalName: String?,
    @JsonProperty("character") val character: String?,
    @JsonProperty("profile_path") val profilePath: String?
)
private data class TmdbCrew(
    @JsonProperty("name") val name: String?,
    @JsonProperty("original_name") val originalName: String?,
    @JsonProperty("job") val job: String?,
    @JsonProperty("profile_path") val profilePath: String?
)

private data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)
private data class TmdbEpisode(
    @JsonProperty("name") val name: String?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("episode_number") val episodeNumber: Int?,
    @JsonProperty("air_date") val airDate: String?,
    @JsonProperty("vote_average") val voteAverage: Double?,
    @JsonProperty("runtime") val runtime: Int?
)

data class Trailers(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("type") val type: String? = null, 
    @JsonProperty("published_at") val publishedAt: String? = null
)

data class ResultsTrailer(
    @JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
)

data class ExternalIds(
    @JsonProperty("imdb_id") val imdb_id: String? = null,
    @JsonProperty("tvdb_id") val tvdb_id: String? = null,
)
