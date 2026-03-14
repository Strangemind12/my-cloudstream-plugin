// v1.21
package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.amap
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
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class StremioC(override var mainUrl: String, override var name: String) : MainAPI() {
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
     
    private var cachedManifest: Manifest? = null
    private var lastManifestUrl: String = ""
    private var lastCacheTime: Long = 0
    private val catalogSentIds = mutableMapOf<String, MutableSet<String>>()
    private val pageContentCache = mutableMapOf<String, List<SearchResponse>>()
    
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
        return mainUrl.substringBefore("?").trimEnd('/')
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
        val currentUrl = buildUrl("/manifest.json")
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
        }      
        return res ?: cachedManifest
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (mainUrl.isEmpty()) throw IllegalArgumentException("Configure in Extension Settings\n")
        mainUrl = mainUrl.fixSourceUrl()

        if (page <= 1) {
            catalogSentIds.clear()
        }

        val skip = (page - 1) * 100
        val manifest = getManifest()
        val targetCatalogs = manifest?.catalogs?.filter { !it.isSearchRequired() } ?: emptyList()

        val lists = targetCatalogs.amap { catalog ->
            val catalogKey = "${catalog.id}-${catalog.type}"
            val cacheKey = "${catalogKey}_$skip"

            val cachedItems = pageContentCache[cacheKey]
            
            val row = if (cachedItems != null) {
                val displayType = catalog.type?.replaceFirstChar { it.uppercase() } ?: ""
                val catalogName = "${catalog.name ?: catalog.id} - $displayType"
                HomePageList(catalogName, cachedItems)
            } else {
                val freshRow = catalog.toHomePageList(provider = this, skip = skip)
                if (freshRow.list.isNotEmpty()) {
                    pageContentCache[cacheKey] = freshRow.list
                }
                freshRow
            }
            
            val seenForThisCatalog = catalogSentIds.getOrPut(catalogKey) { mutableSetOf() }
            val filteredItems = row.list.filter { item ->
                seenForThisCatalog.add(item.url)
            }
            
            row.copy(list = filteredItems)
        }.filter { it.list.isNotEmpty() }

        return newHomePageResponse(
            lists,
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = mainUrl.fixSourceUrl()
        val manifest = getManifest()
        val supportedCatalogs = manifest?.catalogs?.filter { it.supportsSearch() } ?: emptyList()
        val addonResults = supportedCatalogs.amap { catalog -> catalog.search(query, this) }.flatten().distinctBy { it.url }
        if (addonResults.isNotEmpty()) {
            return addonResults
        }
        return searchTMDb(query)
    }

    private suspend fun searchTMDb(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$tmdbAPI/search/multi?api_key=$apiKey&language=ko-KR&query=$encoded&page=1&include_adult=false"
        val results = app.get(url, timeout = 120L).parsedSafe<Results>()?.results ?: emptyList()
        return results.filter { it.mediaType == "movie" || it.mediaType == "tv" }.distinctBy { "${it.mediaType}:${it.id}" }.mapNotNull { media ->
                val stremioType =
                    if (media.mediaType == "tv") "series"
                    else "movie"

                val title = media.title ?: media.name ?: media.originalTitle ?: return@mapNotNull null
                val poster = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

                val entry = CatalogEntry(
                    name = title,
                    id = "tmdb:${media.id}",
                    type = stremioType,
                    poster = poster,
                    background = poster,
                    description = null,
                    imdbRating = null,
                    videos = null,
                    genre = null
                )

                newMovieSearchResponse(
                    title,
                    entry.toJson(),
                    if (stremioType == "series") TvType.TvSeries else TvType.Movie
                ) {
                    posterUrl = poster
                }
            }
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
        println("[디버그] === loadLinks 시작 ===")
        println("[디버그] 전달된 원본 data: $data")
        
        val loadData = try { 
            parseJson<LoadData>(data) 
        } catch (e: Exception) { 
            println("[디버그] loadData 파싱 실패: ${e.message}")
            null 
        } ?: return false
        
        println("[디버그] 파싱 성공: type=${loadData.type}, id=${loadData.id}, imdbId=${loadData.imdbId}")

        val normalizedId = try { normalizeId(loadData.id) } catch (e: Exception) { loadData.id ?: "" }
        val encodedId = try { URLEncoder.encode(normalizedId, "UTF-8") } catch (e: Exception) { normalizedId }
        println("[디버그] URL 인코딩 처리됨: encodedId = $encodedId")
        
        runAllAsync(
            {
                println("[디버그] [1] 메인 API 영상 스트림 탐색 시작")
                try {
                    val url = buildUrl("/stream/${loadData.type}/$encodedId.json")
                    println("[디버그] [1] 요청 URL: $url")
                    val request = app.get(url, timeout = 120L)
                    println("[디버그] [1] 응답 코드: ${request.code}")
                    
                    val res = if (request.isSuccessful) {
                        request.parsedSafe<StreamsResponse>()
                    } else {
                        println("[디버그] [1] 실패 응답 바디: ${request.text.take(300)}")
                        null
                    }

                    if (!res?.streams.isNullOrEmpty()) {
                        println("[디버그] [1] 메인 API 내장 스트림 발견, 개수: ${res?.streams?.size}")
                        res?.streams?.forEach { stream ->
                            stream.runCallback(subtitleCallback, callback) 
                        }
                    } else {
                        println("[디버그] [1] 메인 API 스트림 없음 -> StremioX 폴백 실행")
                        invokeStremioX(loadData.type, loadData.id, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    println("[디버그] [1] 메인 API 영상 스트림 로드 예외: ${e.message}")
                    e.printStackTrace()
                }
            },
            {
                println("[디버그] [2] Watchsomuch 자체 API 자막 로드 시작")
                try {
                    invokeWatchsomuch(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback)
                } catch (e: Exception) {
                    println("[디버그] [2] Watchsomuch 예외 발생: ${e.message}")
                }
            },
            {
                println("[디버그] [3] OpenSubs 자체 API 자막 로드 시작")
                try {
                    invokeOpenSubs(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback)
                } catch (e: Exception) {
                    println("[디버그] [3] OpenSubs 예외 발생: ${e.message}")
                }
            },
            {
                println("[디버그] [4] Stremio 자막 전역 애드온 로드 시작")
                try {
                    invokeStremioSubtitles(loadData.type, loadData.id, subtitleCallback)
                } catch (e: Exception) {
                    println("[디버그] [4] Stremio 자막 애드온 로드 전체 예외 발생: ${e.message}")
                }
            }
        )

        println("[디버그] === loadLinks 비동기 함수 등록 완료 (true 반환) ===")
        return true
    }

    private suspend fun invokeStremioX(
        type: String?,
        id: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[디버그] invokeStremioX 시작: type=$type, id=$id")
        val sites = AcraApplication.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList() ?: mutableListOf()
        val filteredSites = sites.filter { it.parentJavaClass == "StremioX" }
        println("[디버그] invokeStremioX 대상 사이트 개수: ${filteredSites.size}")

        filteredSites.amap { site ->
            try {
                val url = "${site.url.fixSourceUrl()}/stream/${type}/${id}.json"
                println("[디버그] invokeStremioX 요청 URL = $url")
                
                val req = app.get(url, timeout = 120L)
                println("[디버그] invokeStremioX 응답 코드 = ${req.code}")
                println("[디버그] invokeStremioX 응답 내용: ${req.text.take(300)}")

                val res = req.parsedSafe<StreamsResponse>()
                if (res?.streams != null) {
                    println("[디버그] invokeStremioX 스트림 발견 개수: ${res.streams.size}")
                    res.streams.forEach { stream ->
                        stream.runCallback(subtitleCallback, callback)
                    }
                } else {
                    println("[디버그] invokeStremioX 파싱 결과(streams)가 null 입니다.")
                }
            } catch (e: Exception) {
                println("[디버그] invokeStremioX 단일 사이트 요청 실패 - ${site.url} - ${e.message}")
            }
        }
    }

    // 1.19 버전으로 원복한 데이터 클래스 (어노테이션 제외)
    private data class StremioSubtitleResponse(val subtitles: List<Subtitle>?)

    private suspend fun invokeStremioSubtitles(
        type: String?,
        id: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        println("[디버그] invokeStremioSubtitles 시작: type=$type, id=$id")
        val sites = AcraApplication.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList() ?: mutableListOf()
        
        val filteredSites = sites.filter { it.parentJavaClass == "StremioX" || it.parentJavaClass == "StremioC" }
        println("[디버그] invokeStremioSubtitles 대상 사이트 개수(StremioX, StremioC): ${filteredSites.size}")

        filteredSites.amap { site ->
            try {
                val url = "${site.url.fixSourceUrl()}/subtitles/$type/$id.json"
                println("[디버그] Stremio 자막 요청: ${site.name} -> URL: $url")
                
                val req = app.get(url, timeout = 30L)
                println("[디버그] Stremio 자막 응답 코드: ${req.code}")
                println("[디버그] Stremio 자막 응답 원본: ${req.text.take(500)}") // 원본 데이터 첫 500자 확인
                
                val res = req.parsedSafe<StremioSubtitleResponse>()
                
                if (res?.subtitles != null) {
                    println("[디버그] Stremio 자막 파싱 성공, 추출된 개수: ${res.subtitles.size}")
                    res.subtitles.forEach { sub ->
                        if (!sub.url.isNullOrBlank()) {
                            println("[디버그] 자막 콜백 넘김 -> 언어: ${sub.lang}, 링크: ${sub.url}")
                            subtitleCallback.invoke(
                                newSubtitleFile(
                                    SubtitleHelper.fromTagToEnglishLanguageName(sub.lang ?: "") ?: sub.lang ?: "Unknown",
                                    sub.url
                                )
                            )
                        }
                    }
                } else {
                    println("[디버그] Stremio 자막 파싱 결과 subtitles 배열이 null 입니다.")
                }
            } catch (e: Exception) {
                println("[디버그] Stremio 자막 요청 중 예외 발생 - 사이트: ${site.url}, 사유: ${e.message}")
                e.printStackTrace()
            }
        }
        println("[디버그] invokeStremioSubtitles 종료")
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
            val allMetas = types.amap { type ->
                val searchUrl = provider.buildUrl("/catalog/${type}/${id}/search=${URLEncoder.encode(query, "UTF-8")}.json")
                val res = app.get(searchUrl, timeout = 120L).parsedSafe<CatalogResponse>()
                res?.metas ?: emptyList()
            }.flatten()

            return allMetas.distinctBy { it.id }.map { it.toSearchResponse(provider) }
        }

        suspend fun toHomePageList(
            provider: StremioC,
            skip: Int
        ): HomePageList {
            val allMetas = types.amap { type ->
                val path = if (skip > 0) {
                    "/catalog/$type/$id/skip=$skip.json"
                } else {
                    "/catalog/$type/$id.json"
                }
                val url = provider.buildUrl(path)

                val res = app.get(url, timeout = 120L).parsedSafe<CatalogResponse>()
                res?.metas ?: emptyList()
            }.flatten()

            val distinctEntries = allMetas.distinctBy { it.id }.map { it.toSearchResponse(provider) }

            val displayType = type?.replaceFirstChar { it.uppercase() } ?: ""
            val catalogName = "${name ?: id} - $displayType"

            return HomePageList(
                catalogName,
                distinctEntries
            )
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

        suspend fun toLoadResponse(provider: StremioC, imdbId: String?): LoadResponse {
            val fallbackTrailers = trailerStreams.mapNotNull { it.ytId }
                .ifEmpty { trailersSources.mapNotNull { it.source } }
                .distinct()
                .map { if (it.startsWith("http")) it else "https://m.youtube.com/watch?v=$it" }
            
            var fetchedRecommendations: List<SearchResponse>? = null
            var fetchedRuntime: Int? = null
            var fetchedAgeRating: String? = null
            var fetchedLogo: String? = null
            var fetchedTrailers: List<String> = emptyList()
            
            var fetchedActors: List<ActorData>? = null
            val episodeTmdbMeta = mutableMapOf<String, TmdbEpisode>()

            val extractedImdbId = links.firstOrNull { it.category == "imdb" }?.url?.substringAfterLast("/")?.takeIf { it.startsWith("tt") }
            val extractedTmdbId = if (this.id.startsWith("tmdb:")) this.id.removePrefix("tmdb:") else null
            var finalImdbId = extractedImdbId ?: (if (this.id.startsWith("tt")) this.id else imdbId)
            var tmdbIdStr: String? = extractedTmdbId

            if (finalImdbId == null && logo != null) {
                finalImdbId = "tt[0-9]+".toRegex().find(logo)?.value
            }

            try {
                val isMovie = type == "movie" || videos.isNullOrEmpty()
                val tmdbMediaType = if (isMovie) "movie" else "tv"

                if (tmdbIdStr == null && finalImdbId?.startsWith("tt") == true) {
                    val findUrl = "$tmdbAPI/find/$finalImdbId?api_key=$apiKey&external_source=imdb_id&language=ko-KR"
                    val findRes = app.get(findUrl).parsedSafe<TmdbFindResponse>()
                    
                    val tmdbId = if (isMovie) findRes?.movie_results?.firstOrNull()?.id else findRes?.tv_results?.firstOrNull()?.id
                    if (tmdbId != null) {
                        tmdbIdStr = tmdbId.toString()
                    }
                }

                if (tmdbIdStr == null && !videos.isNullOrEmpty()) {
                    val tvdbId = videos.firstNotNullOfOrNull { it.tvdb_id } ?: videos.firstNotNullOfOrNull { "thetvdb.com/banners/episodes/(\\d+)".toRegex().find(it.thumbnail ?: "")?.groupValues?.get(1)?.toIntOrNull() }
                    if (tvdbId != null) {
                        val findUrl = "$tmdbAPI/find/$tvdbId?api_key=$apiKey&external_source=tvdb_id&language=ko-KR"
                        val findRes = app.get(findUrl).parsedSafe<TmdbFindResponse>()
                        val tmdbId = if (isMovie) findRes?.movie_results?.firstOrNull()?.id else findRes?.tv_results?.firstOrNull()?.id
                        if (tmdbId != null) {
                            tmdbIdStr = tmdbId.toString()
                        }
                    }
                }

                if (tmdbIdStr == null) {
                    val searchYear = releaseInfo?.substringBefore("-")?.toIntOrNull() ?: yearNum?.toIntOrNull()
                    val query = URLEncoder.encode(name, "UTF-8")
                    val searchUrl = if (isMovie) {
                        "$tmdbAPI/search/movie?api_key=$apiKey&language=ko-KR&query=$query${if (searchYear != null) "&primary_release_year=$searchYear" else ""}"
                    } else {
                        "$tmdbAPI/search/tv?api_key=$apiKey&language=ko-KR&query=$query${if (searchYear != null) "&first_air_date_year=$searchYear" else ""}"
                    }
                    val searchRes = app.get(searchUrl).parsedSafe<Results>()
                    val tmdbId = searchRes?.results?.firstOrNull()?.id
                    if (tmdbId != null) {
                        tmdbIdStr = tmdbId.toString()
                    }
                }

                if (tmdbIdStr != null) {
                    val detailAppend = if (isMovie) "recommendations,release_dates,credits,images,videos" else "recommendations,content_ratings,credits,images,videos"
                    val detailUrl = "$tmdbAPI/$tmdbMediaType/$tmdbIdStr?api_key=$apiKey&language=ko-KR&append_to_response=$detailAppend&include_image_language=ko"
                    
                    val detailRes = app.get(detailUrl).parsedSafe<TmdbDetailResponse>()
                    
                    if (detailRes != null) {
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
                    
                    if (!isMovie && !videos.isNullOrEmpty()) {
                        val requiredSeasons = videos.mapNotNull { it.seasonNumber }.distinct().filter { it > 0 }
                        if (requiredSeasons.isNotEmpty()) {
                            requiredSeasons.amap { seasonNum ->
                                try {
                                    val seasonUrl = "$tmdbAPI/tv/$tmdbIdStr/season/$seasonNum?api_key=$apiKey&language=ko-KR"
                                    val seasonRes = app.get(seasonUrl).parsedSafe<TmdbSeasonDetail>()
                                    seasonRes?.episodes?.forEach { ep ->
                                        if (ep.episodeNumber != null) {
                                            episodeTmdbMeta["${seasonNum}_${ep.episodeNumber}"] = ep
                                        }
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }

            val isSingleMovieVideo = type == "movie" && videos?.size == 1 && videos[0].seasonNumber == 1 && (videos[0].episode == 1 || videos[0].number == 1)
            val finalTrailers = fetchedTrailers.ifEmpty { fallbackTrailers }

            if (videos.isNullOrEmpty() || isSingleMovieVideo) {
                return provider.newMovieLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.Movie,
                    LoadData(type, id, imdbId = finalImdbId, year = yearNum?.toIntOrNull())
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    score = Score.from10(imdbRating)
                    plot = description
                    year = yearNum?.toIntOrNull()
                    tags = genre ?: genres
                    
                    if (!fetchedActors.isNullOrEmpty()) {
                        this.actors = fetchedActors
                    } else {
                        addActors(cast)
                    }
                    
                    addTrailer(finalTrailers)                    
                    this.recommendations = fetchedRecommendations
                    this.duration = fetchedRuntime
                    this.contentRating = fetchedAgeRating
                    
                    fetchedLogo?.let { this.logoUrl = it }
                    
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
                return provider.newTvSeriesLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.TvSeries,
                    videos.map {
                        it.toEpisode(provider, type, finalImdbId, episodeTmdbMeta)
                    }
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    score = Score.from10(imdbRating)
                    plot = description
                    year = yearNum?.toIntOrNull()
                    tags = genre ?: genres
                    
                    if (!fetchedActors.isNullOrEmpty()) {
                        this.actors = fetchedActors
                    } else {
                        addActors(cast)
                    }
                    
                    addTrailer(finalTrailers.firstOrNull())
                    this.recommendations = fetchedRecommendations
                    this.contentRating = fetchedAgeRating
                    
                    fetchedLogo?.let { this.logoUrl = it }
                    
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
        fun toEpisode(provider: StremioC, type: String?, imdbId: String?, tmdbMetaMap: Map<String, TmdbEpisode>): Episode {
            val epKey = "${seasonNumber}_${episode ?: number}"
            val tmdbEp = tmdbMetaMap[epKey]
            
            return provider.newEpisode(
                LoadData(type, id, seasonNumber, episode ?: number, imdbId)
            ) {
                this.name = this@Video.name ?: title ?: "Episode ${this@Video.episode ?: number}"
                this.posterUrl = thumbnail ?: TRANSPARENT_PIXEL
                this.description = overview ?: this@Video.description
                this.season = seasonNumber
                this.episode = this@Video.episode ?: number

                val finalAirDate = tmdbEp?.airDate?.takeIf { it.isNotBlank() } ?: this@Video.firstAired ?: this@Video.released
                finalAirDate?.takeIf { it.isNotBlank() }?.let { this.addDate(it) }

                tmdbEp?.voteAverage?.takeIf { it > 0.0 }?.let { this.score = Score.from10(it) }
                tmdbEp?.runtime?.let { this.runTime = it }
            }
        }
    }

    private data class StreamsResponse(val streams: List<Stream>)
    private data class Subtitle(
        val url: String?,
        val lang: String?,
        val id: String?,
    )

    private data class ProxyHeaders(
        val request: Map<String, String>?,
    )

    private data class BehaviorHints(
        val proxyHeaders: ProxyHeaders?,
        val headers: Map<String, String>?,
    )

    private data class Stream(
        val name: String?,
        val title: String?,
        val url: String?,
        val description: String?,
        val ytId: String?,
        val externalUrl: String?,
        val behaviorHints: BehaviorHints?,
        val infoHash: String?,
        val sources: List<String> = emptyList(),
        val subtitles: List<Subtitle> = emptyList()
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
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            SubtitleHelper.fromTagToEnglishLanguageName(sub.lang ?: "") ?: sub.lang
                            ?: "",
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

data class Results(
    @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
)

data class Media(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
)

data class Genres(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

data class Keywords(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

data class KeywordResults(
    @JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
    @JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
)

data class Seasons(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("air_date") val airDate: String? = null,
)

data class Cast(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("known_for_department") val knownForDepartment: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
)

data class Episodes(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
)

data class MediaDetailEpisodes(
    @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
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

data class Credits(
    @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
)

data class ResultsRecommendations(
    @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
)

data class LastEpisodeToAir(
    @JsonProperty("episode_number") val episode_number: Int? = null,
    @JsonProperty("season_number") val season_number: Int? = null,
)

data class MediaDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val vote_average: Any? = null,
    @JsonProperty("original_language") val original_language: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
    @JsonProperty("keywords") val keywords: KeywordResults? = null,
    @JsonProperty("last_episode_to_air") val last_episode_to_air: LastEpisodeToAir? = null,
    @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
    @JsonProperty("videos") val videos: ResultsTrailer? = null,
    @JsonProperty("external_ids") val external_ids: ExternalIds? = null,
    @JsonProperty("credits") val credits: Credits? = null,
    @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
)
