/**
 * DaddyLiveScheduleProvider v3.7
 * - [Optimize] 상위 1개 채널 집중 타겟팅 (성공률 극대화)
 * - [Fix] mono.css 추출을 위해 모든 플레이어 요소(6종) 전수 조사
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
        println("[DaddyLive] 메인 페이지 로드 시도 (v3.7)")
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
        println("[DaddyLive] 상세 정보 분석: $url")
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
        println("[DaddyLive] loadLinks v3.7 (Node Sniper) 가동")
        val channels = AppUtils.tryParseJson<List<Channel>>(data) ?: return false
        
        // 모든 플레이어 탭 정의
        val allPlayers = listOf("stream", "cast", "watch", "plus", "casting", "player")
        
        // 상위 1개 채널의 6개 모든 경로 생성
        val targetLinks = channels.take(1).flatMap { ch ->
            allPlayers.map { p -> ch.channelName + " - $p" to ch.channelId.format(p) }
        }

        println("[DaddyLive] 총 ${targetLinks.size}개 후보 경로 추출 시작")
        DaddyLiveExtractor().getUrl(targetLinks.toJson(), null, subtitleCallback, callback)
        return true
    }

    data class Channel(val channelName: String, val channelId: String)
}
