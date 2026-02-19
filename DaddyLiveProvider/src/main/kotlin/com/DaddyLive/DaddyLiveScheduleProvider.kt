/**
 * DaddyLiveScheduleProvider v2.21
 * - [Optimize] 추출 대상을 watch, plus, player 3개 요소로 제한
 * - [Fix] 모든 채널에 대해 해당 3개 요소의 조합을 생성하여 추출기 전달
 * - [Debug] 실행 단계별 상세 println 로그 포함
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
        println("[DaddyLive] getMainPage 로드 시작")
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").map {
            val sectionTitle = it.select(".card__meta").text()
            val events = it.select(".schedule__event").map { e ->
                val dataTitle = e.select(".schedule__eventHeader").attr("data-title")
                val formattedTime = convertGMTToLocalTime(e.select(".schedule__time").text())
                newLiveSearchResponse("$formattedTime - ${e.select(".schedule__eventTitle").text()}", dataTitle) {
                    this.posterUrl = Companion.posterUrl
                }
            }
            HomePageList(sectionTitle, events, false)
        }
        return newHomePageResponse(schedule, false)
    }

    override suspend fun load(url: String): LoadResponse {
        println("[DaddyLive] 상세 페이지 분석: $url")
        val doc = app.get(mainUrl).document
        val dataTitle = url.removePrefix("$mainUrl/")
        val event = doc.select(".schedule__event").first { it.select("div.schedule__eventHeader").attr("data-title") == dataTitle }
        val channels = event.select(".schedule__channels > a").map {
            val id = it.attr("href").substringAfter("id=")
            Channel(it.text(), "$mainUrl/%s/stream-$id.php")
        }
        return newLiveStreamLoadResponse(event.select(".schedule__eventTitle").text(), url, dataUrl = channels.toJson())
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        println("[DaddyLive] loadLinks v2.21 (watch, plus, player) 모드 시작")
        val channels = AppUtils.tryParseJson<List<Channel>>(data) ?: return false
        
        // [핵심] 요청하신 3개 요소로 타겟 한정
        val selectedPlayers = listOf("watch", "plus", "player")
        
        val targetLinks = channels.flatMap { ch ->
            selectedPlayers.map { p -> ch.channelName + " - $p" to ch.channelId.format(p) }
        }

        println("[DaddyLive] 총 ${targetLinks.size}개 경로에 대해 정밀 추출 시작")
        DaddyLiveExtractor().getUrl(targetLinks.toJson(), null, subtitleCallback, callback)
        return true
    }

    data class Channel(val channelName: String, val channelId: String)
}
