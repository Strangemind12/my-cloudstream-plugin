/**
 * DaddyLiveScheduleProvider v1.1
 * - [Fix] 패키지명을 com.DaddyLive로 수정하여 DaddyLivePlugin에서의 참조 에러 해결
 * - [Debug] 주요 메서드(getMainPage, search, load, loadLinks) 실행 로그 추가
 */
package com.DaddyLive

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DaddyLiveScheduleProvider : MainAPI() {
    override var mainUrl = "https://dlhd.link"
    override var name = "DaddyLive"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "un"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    @Suppress("ConstPropertyName")
    companion object {
        private const val posterUrl =
            "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"

        fun convertGMTToLocalTime(gmtTime: String): String {
            val gmtFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            gmtFormat.timeZone = TimeZone.getTimeZone("GMT")
            val date: Date = gmtFormat.parse(gmtTime) ?: throw IllegalArgumentException("Invalid time format")
            val localFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            localFormat.timeZone = TimeZone.getDefault()
            return localFormat.format(date)
        }
    }

    private fun searchResponseBuilder(doc: Element): List<LiveSearchResponse> {
        return doc.select(".schedule__event").map { e ->
            val dataTitle = e.select(".schedule__eventHeader").attr("data-title")
            val eventTitle = e.select(".schedule__eventTitle").text()
            val time = e.select(".schedule__time").text()
            val formattedTime = convertGMTToLocalTime(time)
            newLiveSearchResponse("$formattedTime - $eventTitle", dataTitle){
                this.posterUrl = Companion.posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("[DaddyLive] getMainPage 시작: $mainUrl")
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").map {
            val sectionTitle = it.select(".card__meta").text()
            val events = searchResponseBuilder(it)
            HomePageList(sectionTitle, events, false)
        }
        println("[DaddyLive] getMainPage 완료: ${schedule.size}개 섹션 발견")
        return newHomePageResponse(schedule, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("[DaddyLive] search 시작: $query")
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").map { searchResponseBuilder(it) }.flatten()
        val matches = schedule.filter { query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "") }
        println("[DaddyLive] search 결과: ${matches.size}건")
        return matches
    }

    override suspend fun load(url: String): LoadResponse {
        println("[DaddyLive] load 시작: $url")
        val doc = app.get(mainUrl).document
        val dataTitle = url.removePrefix("$mainUrl/")
        val event = doc.select(".schedule__event").first { it.select("div.schedule__eventHeader").attr("data-title") == dataTitle }
        val eventTitle = event.select(".schedule__eventTitle").text()
        val channels = event.select(".schedule__channels > a").map {
            val id = it.attr("href").substringAfter("id=")
            Channel(it.text(), "$mainUrl/%s/stream-$id.php")
        }
        val time = event.select(".schedule__time").text()
        val formattedTime = convertGMTToLocalTime(time)
        println("[DaddyLive] load 완료: $eventTitle (${channels.size}개 채널)")
        return newLiveStreamLoadResponse("$formattedTime - $eventTitle", url, dataUrl = channels.toJson()){
            this.posterUrl = Companion.posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        println("[DaddyLive] loadLinks 시작: $data")
        val players = listOf("stream", "cast", "watch", "plus", "casting", "player")
        val channels = tryParseJson<List<Channel>>(data) ?: return false

        val links = channels.map { ch ->
            players.map { l ->
                val url = ch.channelId.format(l)
                ch.channelName + " - $l" to url
            }
        }.flatten()

        println("[DaddyLive] Extractor 호출 시도 (${links.size}개 후보)")
        DaddyLiveExtractor().getUrl(links.toJson(), null, subtitleCallback, callback)
        return true
    }

    data class Channel(val channelName: String, val channelId: String)
}
