package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

class KingkongTv : MainAPI() {
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Others)
    
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val portalUrl = "https://kingkongtv.org"

    companion object {
        var lastPortalCheckTime: Long = 0
        var cachedMainUrl: String? = null
    }

    override val mainPage = mainPageOf("/live/sportstv" to "실시간 스포츠 중계")

    private suspend fun updateMainUrlIfNeeded() {
        // [고유 개선] 포털 요청에 1시간 캐시 적용 (홈 로딩 지연 방지)
        if (cachedMainUrl != null && (System.currentTimeMillis() - lastPortalCheckTime) < 3600000) {
            mainUrl = cachedMainUrl!!
            return
        }

        try {
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")?.trimEnd('/')
            if (!latestUrl.isNullOrBlank()) {
                mainUrl = latestUrl
                cachedMainUrl = mainUrl
                lastPortalCheckTime = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()
        val url = "$mainUrl${request.data}"
        
        try {
            val mainDoc = app.get(url).document
            var iframeSrc = mainDoc.selectFirst("iframe#broadcastFrame")?.attr("src")
            if (iframeSrc.isNullOrBlank()) iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"

            val realUrl = fixUrl(url, iframeSrc)
            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr").sortedByDescending { it.selectFirst("td.subject")?.hasClass("bold") == true }

            val home = rows.mapNotNull { it.toSearchResult(realUrl) }
            return newHomePageResponse(HomePageList(request.name, home, true), hasNext = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e; return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        if (this.selectFirst("span.off") != null) return null

        val channelDiv = this.selectFirst("div.channel_on") ?: return null
        var title = channelDiv.attr("data-title")
        val streamId = channelDiv.attr("data-stream")
        if (title.isBlank() || streamId.isBlank()) return null

        if (this.selectFirst("td.subject")?.hasClass("bold") == true) title = "🔥 $title"

        val thumbUrlRaw = this.selectFirst("td.thumb img")?.attr("src")
        val thumbUrl = if (thumbUrlRaw != null) "${fixUrl(refererUrl, thumbUrlRaw)}${if(thumbUrlRaw.contains("?")) "&" else "?"}t=${System.currentTimeMillis()}" else ""

        val fakeUrl = "$mainUrl/live_stream/$streamId?title=${URLEncoder.encode(title, "UTF-8")}&poster=${URLEncoder.encode(thumbUrl, "UTF-8")}&ref=${URLEncoder.encode(refererUrl, "UTF-8")}"
        return newMovieSearchResponse(title, fakeUrl, TvType.Others) { this.posterUrl = thumbUrl }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val title = uri.getQueryParameter("title")?.let { URLDecoder.decode(it, "UTF-8") } ?: "Live Stream"
        val poster = uri.getQueryParameter("poster")?.let { URLDecoder.decode(it, "UTF-8") }
        return newMovieLoadResponse(title, url, TvType.Others, url) { this.posterUrl = poster; this.plot = "실시간 중계" }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val uri = Uri.parse(data)
        val streamId = data.substringAfter("/live_stream/").substringBefore("?")
        val refererUrl = uri.getQueryParameter("ref")?.let { URLDecoder.decode(it, "UTF-8") } ?: "https://kktv.speed10-1.com/kktv/index.php"
        val playerPageUrl = "https://kktv.speed10-1.com/kktv/pc_view.php?stream=$streamId"
        
        try {
            val response = app.get(playerPageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to refererUrl, "Origin" to "https://kktv.speed10-1.com")).text
            var m3u8Url = Regex("""['"](http[^'"]+\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            
            if (m3u8Url == null) {
                val webrtcMatch = Regex("""['"](webrtc://[^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (webrtcMatch != null) m3u8Url = webrtcMatch.replace("webrtc://", "https://").replace(streamId, "$streamId.m3u8") 
            }
            if (m3u8Url == null) m3u8Url = "https://play.ogtv3.com/live/$streamId.m3u8?site=kktv"

            // [사용자 요청] 기존 HTTP 강제 우회 로직 보존 (복구 완료)
            val finalUrl = if (m3u8Url.contains("play.ogtv3.com") && m3u8Url.startsWith("https://")) {
                m3u8Url.replaceFirst("https://", "http://")
            } else {
                m3u8Url
            }

            callback.invoke(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://kktv.speed10-1.com/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Origin" to "https://kktv.speed10-1.com", "Referer" to "https://kktv.speed10-1.com/", "User-Agent" to userAgent)
            })
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e; return false
        }
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String) = if (relativeUrl.startsWith("http")) relativeUrl else try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
}
