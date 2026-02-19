/**
 * DaddyLiveScheduleProvider v1.3
 * - [Fix] 패키지 명칭을 폴더 구조와 일치하도록 com.DaddyLive로 수정
 * - [Update] 메인 API 이름을 "DaddyLive"로 변경
 * - [Update] posterUrl을 새로운 GitHub 리포지토리 주소로 변경
 * - [Debug] getMainPage, load, loadLinks 메서드에 실행 추적 로그 추가
 */
package com.DaddyLive

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

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

    private fun searchResponseBuilder(doc: Element): List<LiveSearchResponse> {
        return doc.select(".schedule__event").map { e ->
            val dataTitle = e.select(".schedule__eventHeader").attr("data-title")
            val eventTitle = e.select(".schedule__eventTitle").text()
            val time = e.select(".schedule__time").text()
            val formattedTime = convertGMTToLocalTime(time)
            newLiveSearchResponse("$formattedTime - $eventTitle", dataTitle) {
                this.posterUrl = Companion.posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("[DaddyLive] getMainPage 요청 시작: $mainUrl")
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").map {
            val sectionTitle = it.select(".card__meta").text()
            val events = searchResponseBuilder(it)
            HomePageList(sectionTitle, events, false)
        }
        println("[DaddyLive] getMainPage 로드 완료: ${schedule.size}개 카테고리 발견")
        return newHomePageResponse(schedule, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("[DaddyLive] 검색 요청: $query")
        val doc = app.get(mainUrl).document
        val schedule = doc.select(".schedule__category").flatMap { searchResponseBuilder(it) }
        val results = schedule.filter {
            query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
        }
        println("[DaddyLive] 검색 완료: ${results.size}건 발견")
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        println("[DaddyLive] load 요청: $url")
        val doc = app.get(mainUrl).document
        val dataTitle = url.removePrefix("$mainUrl/")
        val event = doc.select(".schedule__event").first {
            it.select("div.schedule__eventHeader").attr("data-title") == dataTitle
        }
        val eventTitle = event.select(".schedule__eventTitle").text()
        val channels = event.select(".schedule__channels > a").map {
            val id = it.attr("href").substringAfter("id=")
            Channel(it.text(), "$mainUrl/%s/stream-$id.php")
        }
        val formattedTime = convertGMTToLocalTime(event.select(".schedule__time").text())
        
        println("[DaddyLive] load 완료: $eventTitle, 채널 개수: ${channels.size}")
        return newLiveStreamLoadResponse("$formattedTime - $eventTitle", url, dataUrl = channels.toJson()) {
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

        val links = channels.flatMap { ch ->
            players.map { p ->
                ch.channelName + " - $p" to ch.channelId.format(p)
            }
        }

        println("[DaddyLive] 추출기(Extractor) 호출 시도: ${links.size}개 경로")
        DaddyLiveExtractor().getUrl(links.toJson(), null, subtitleCallback, callback)
        return true
    }

    data class Channel(val channelName: String, val channelId: String)
}
