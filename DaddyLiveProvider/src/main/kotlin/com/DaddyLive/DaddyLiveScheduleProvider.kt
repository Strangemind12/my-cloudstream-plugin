// v1.10 - 동적 Referer 적용을 위한 코드 수정
package com.DaddyLive

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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
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

    @Suppress("ConstPropertyName")
    companion object {
        private const val posterUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/DaddyLiveProvider/daddylive.jpg"

        fun convertGMTToLocalTime(gmtTime: String): String {
            return try {
                val gmtFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                gmtFormat.timeZone = TimeZone.getTimeZone("GMT")
                val date: Date = gmtFormat.parse(gmtTime) ?: throw IllegalArgumentException("Invalid time format")
                val localFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                localFormat.timeZone = TimeZone.getDefault()
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
        return try {
            val doc = app.get(mainUrl).document
            val schedule = doc.select(".schedule__category").map {
                val sectionTitle = it.select(".card__meta").text()
                val events = searchResponseBuilder(it)
                HomePageList(sectionTitle, events, false)
            }
            newHomePageResponse(schedule, false)
        } catch (e: Exception) {
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get(mainUrl).document
            val schedule = doc.select(".schedule__category").map { searchResponseBuilder(it) }.flatten()
            val matches = schedule.filter {
                query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
            }
            matches
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val dataTitle = url.removePrefix("$mainUrl/")
        
        return try {
            val doc = app.get(mainUrl).document
            
            val event = doc.select(".schedule__event").firstOrNull {
                it.select("div.schedule__eventHeader").attr("data-title") == dataTitle
            } ?: throw Exception("Event not found")

            val eventTitle = event.select(".schedule__eventTitle").text()
            val time = event.select(".schedule__time").text()
            
            val channels = event.select(".schedule__channels > a").mapNotNull {
                val name = it.text().trim()
                val href = it.attr("href")
                if (href.isNotEmpty()) {
                    val fullUrl = fixUrl(href)
                    Channel(name, fullUrl)
                } else {
                    null
                }
            }
            
            val formattedTime = convertGMTToLocalTime(time)
            
            newLiveStreamLoadResponse("$formattedTime - $eventTitle", url, dataUrl = channels.toJson()) {
                this.posterUrl = Companion.posterUrl
            }
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val channels = try {
            parseJson<List<Channel>>(data)
        } catch (e: Exception) {
            emptyList()
        }

        if (channels.isEmpty()) return false

        val extractor = DaddyLiveExtractor()

        for ((index, channel) in channels.withIndex()) {
            println("[DaddyLive] 소스 처리 ($index/${channels.size}): ${channel.name}")
            
            // [변경] URL과 Referer를 쌍으로 받아옴
            val result = extractor.fetchM3u8Url(channel.url, mainUrl)
            
            if (result != null) {
                val (m3u8Url, refererUrl) = result
                println("[DaddyLive] M3U8 확보: $m3u8Url (Ref: $refererUrl)")
                
                val link = newExtractorLink(name, channel.name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = refererUrl // [중요] 동적으로 획득한 Referer 사용
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                        "Referer" to refererUrl,
                        "Origin" to "https://dlhd.link" // Origin은 고정일 수 있으나, 필요시 refererUrl 기반으로 변경 고려
                    )
                }
                callback(link)
            } else {
                println("[DaddyLive] M3U8 확보 실패: ${channel.name}")
            }
        }
        
        return true
    }

    data class Channel(
        val name: String,
        val url: String
    )
}
