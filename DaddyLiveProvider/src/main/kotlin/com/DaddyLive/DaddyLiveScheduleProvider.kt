/**
 * DaddyLiveScheduleProvider v2.11
 * - [Version] v2.11
 * - [Fix] 상위 3개 채널 x 3개 핵심 플레이어 = 총 9개 경로 추출 요청 유지
 */
package com.DaddyLive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.text.SimpleDateFormat
import java.util.*

class DaddyLiveScheduleProvider : MainAPI() {
    override var mainUrl = "https://dlhd.link"
    override var name = "DaddyLive"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "un"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded

    @Suppress("ConstPropertyName")
    companion object {
        private const val posterUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/DaddyLiveProvider/daddylive.jpg"

        fun convertGMTToLocalTime(gmtTime: String): String {
            return try {
                val gmtFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("GMT") }
                val date: Date = gmtFormat.parse(gmtTime) ?: return gmtTime
                val localFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() }
                localFormat.format(date)
            } catch (e: Exception) { gmtTime }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("[DaddyLive] getMainPage 시작: $mainUrl")
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
        println("[DaddyLive] load 요청 상세: $url")
        val doc = app.get(mainUrl).document
        val dataTitle = url.removePrefix("$mainUrl/")
        val event = doc.select(".schedule__event").first { it.select("div.schedule__eventHeader").attr("data-title") == dataTitle }
        val channels = event.select(".schedule__channels > a").map {
            val id = it.attr("href").substringAfter("id=")
            Channel(it.text(), "$mainUrl/%s/stream-$id.php")
        }
        return newLiveStreamLoadResponse(event.select(".schedule__eventTitle").text(), url, dataUrl = channels.toJson()) {
            this.posterUrl = Companion.posterUrl
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        println("[DaddyLive] loadLinks 시작 (v2.11)")
        val channels = AppUtils.tryParseJson<List<Channel>>(data) ?: return false
        
        val top3Channels = channels.take(3)
        val targetPlayers = listOf("stream", "cast", "player")

        val targetLinks = top3Channels.flatMap { ch ->
            targetPlayers.map { p -> ch.channelName + " - $p" to ch.channelId.format(p) }
        }

        println("[DaddyLive] 총 ${targetLinks.size}개 경로 병렬 추출 시도")
        DaddyLiveExtractor().getUrl(targetLinks.toJson(), null, subtitleCallback, callback)
        return true
    }

    data class Channel(val channelName: String, val channelId: String)
}
