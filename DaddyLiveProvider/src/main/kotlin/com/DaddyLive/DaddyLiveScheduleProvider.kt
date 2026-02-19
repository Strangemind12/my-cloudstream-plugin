/**
 * DaddyLiveScheduleProvider v3.2
 * - [Optimize] 상위 1개 채널만 집중 타겟팅
 * - [Fix] 해당 채널의 모든 플레이어 요소(6종) 전수 조사
 * - [Debug] v3.2 버전 정보 및 상세 실행 로그 포함
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
        println("[DaddyLive] 메인 페이지 로드 시작 (v3.2)")
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
        println("[DaddyLive] 상세 페이지 로드: $url")
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
        println("[DaddyLive] loadLinks v3.2 상위 1개 채널 전수 조사 모드 가동")
        val channels = AppUtils.tryParseJson<List<Channel>>(data) ?: return false
        
        // 6가지 모든 플레이어 요소 정의
        val allPlayers = listOf("stream", "cast", "watch", "plus", "casting", "player")
        
        // [핵심] 상위 1개 채널만 선택하여 모든 요소(6종) 조합 생성
        val targetLinks = channels.take(1).flatMap { ch ->
            allPlayers.map { p -> ch.channelName + " - $p" to ch.channelId.format(p) }
        }

        println("[DaddyLive] 상위 1개 채널의 총 ${targetLinks.size}개 경로를 분석합니다.")
        DaddyLiveExtractor().getUrl(targetLinks.toJson(), null, subtitleCallback, callback)
        return true
    }

    data class Channel(val channelName: String, val channelId: String)
}
