/**
 * DaddyLiveScheduleProvider v2.2
 * - [Fix] 2004 에러 해결을 위한 헤더 구조 개선
 * - [Debug] 데이터 로드 및 링크 추출 단계별 로그 강화
 */
package com.DaddyLive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.*

class DaddyLiveScheduleProvider : MainAPI() {
    override var mainUrl = "https://dlhd.link"
    override var name = "DaddyLive"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "un"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false

    @Suppress("ConstPropertyName")
    companion object {
        private const val posterUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/DaddyLiveProvider/daddylive.jpg"

        fun convertGMTToLocalTime(gmtTime: String): String {
            return try {
                val gmtFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val date: Date = gmtFormat.parse(gmtTime) ?: return gmtTime
                val localFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
                    timeZone = TimeZone.getDefault()
                }
                localFormat.format(date)
            } catch (e: Exception) {
                gmtTime
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("[DaddyLive] getMainPage 시작")
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").map {
            val sectionTitle = it.select(".card__meta").text()
            val events = it.select(".schedule__event").map { e ->
                val dataTitle = e.select(".schedule__eventHeader").attr("data-title")
                val eventTitle = e.select(".schedule__eventTitle").text()
                val formattedTime = convertGMTToLocalTime(e.select(".schedule__time").text())
                newLiveSearchResponse("$formattedTime - $eventTitle", dataTitle) {
                    this.posterUrl = Companion.posterUrl
                }
            }
            HomePageList(sectionTitle, events, false)
        }
        return newHomePageResponse(schedule, false)
    }

    override suspend fun load(url: String): LoadResponse {
        println("[DaddyLive] load 요청: $url")
        val doc = app.get(mainUrl).document
        val dataTitle = url.removePrefix("$mainUrl/")
        val event = doc.select(".schedule__event").first {
            it.select("div.schedule__eventHeader").attr("data-title") == dataTitle
        }
        val channels = event.select(".schedule__channels > a").map {
            val id = it.attr("href").substringAfter("id=")
            Channel(it.text(), "$mainUrl/%s/stream-$id.php")
        }
        return newLiveStreamLoadResponse(event.select(".schedule__eventTitle").text(), url, dataUrl = channels.toJson()) {
            this.posterUrl = Companion.posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        println("[DaddyLive] loadLinks 시작 (v2.2)")
        val channels = AppUtils.tryParseJson<List<Channel>>(data) ?: return false
        val firstChannel = channels.firstOrNull() ?: return false
        
        val priorityPlayers = listOf("stream", "player")
        val targetLinks = priorityPlayers.map { p ->
            firstChannel.channelName + " - $p" to firstChannel.channelId.format(p)
        }

        println("[DaddyLive] 최적화 경로 추출 시도")
        DaddyLiveExtractor().getUrl(targetLinks.toJson(), null, subtitleCallback, callback)
        return true
    }

    data class Channel(val channelName: String, val channelId: String)
}
