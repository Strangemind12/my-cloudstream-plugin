// v1.5 - 헤더 설정 로직 정상화 (KKTV 참조)
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
        println("[DaddyLive] 메인 페이지 로드 시작: $mainUrl")
        return try {
            val doc = app.get(mainUrl).document
            val schedule = doc.select(".schedule__category").map {
                val sectionTitle = it.select(".card__meta").text()
                val events = searchResponseBuilder(it)
                HomePageList(sectionTitle, events, false)
            }
            println("[DaddyLive] 메인 페이지 파싱 완료: ${schedule.size}개 카테고리")
            newHomePageResponse(schedule, false)
        } catch (e: Exception) {
            println("[DaddyLive] 메인 페이지 로드 실패: ${e.message}")
            e.printStackTrace()
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("[DaddyLive] 검색 수행: $query")
        return try {
            val doc = app.get(mainUrl).document
            val schedule = doc.select(".schedule__category").map { searchResponseBuilder(it) }.flatten()
            val matches = schedule.filter {
                query.lowercase().replace(" ", "") in it.name.lowercase().replace(" ", "")
            }
            println("[DaddyLive] 검색 결과: ${matches.size}건 발견")
            matches
        } catch (e: Exception) {
            println("[DaddyLive] 검색 실패: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val dataTitle = url.removePrefix("$mainUrl/")
        println("[DaddyLive] 상세 정보 로드 요청: Title=$dataTitle")
        
        return try {
            val doc = app.get(mainUrl).document
            
            val event = doc.select(".schedule__event").firstOrNull {
                it.select("div.schedule__eventHeader").attr("data-title") == dataTitle
            } ?: throw Exception("이벤트를 찾을 수 없습니다: $dataTitle")

            val eventTitle = event.select(".schedule__eventTitle").text()
            val time = event.select(".schedule__time").text()
            
            val channels = event.select(".schedule__channels > a").mapNotNull {
                val name = it.text().trim()
                val href = it.attr("href")
                if (href.isNotEmpty()) {
                    val fullUrl = fixUrl(href)
                    println("[DaddyLive] 채널 발견: $name -> $fullUrl")
                    Channel(name, fullUrl)
                } else {
                    null
                }
            }
            
            if (channels.isEmpty()) {
                println("[DaddyLive] 경고: 이 이벤트에 연결된 채널이 없습니다.")
            }

            val formattedTime = convertGMTToLocalTime(time)
            
            newLiveStreamLoadResponse("$formattedTime - $eventTitle", url, dataUrl = channels.toJson()) {
                this.posterUrl = Companion.posterUrl
            }
        } catch (e: Exception) {
            println("[DaddyLive] Load Error: ${e.message}")
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        println("[DaddyLive] loadLinks 시작. 데이터 파싱 중...")
        
        val channels = try {
            parseJson<List<Channel>>(data)
        } catch (e: Exception) {
            println("[DaddyLive] loadLinks JSON 파싱 실패: ${e.message}")
            emptyList()
        }

        if (channels.isEmpty()) {
            println("[DaddyLive] 로드할 채널 링크가 없습니다.")
            return false
        }

        println("[DaddyLive] 총 ${channels.size}개의 채널 소스 처리 시작")
        val extractor = DaddyLiveExtractor()

        channels.forEachIndexed { index, channel ->
            println("[DaddyLive] 소스 요청 ($index/${channels.size}): ${channel.name} -> ${channel.url}")
            
            extractor.getUrl(channel.url, mainUrl, subtitleCallback) { link ->
                println("[DaddyLive] 링크 추출 성공! 소스 등록: ${channel.name}")
                
                // [수정됨] KKTV 스타일 참조: newExtractorLink 사용 및 헤더 복사
                callback(
                    newExtractorLink(link.source, channel.name, link.url, link.type) {
                        this.referer = link.referer
                        this.quality = link.quality
                        // 원본 링크에 설정된 헤더가 있다면 그대로 가져옴
                        this.headers = link.headers 
                    }
                )
            }
        }
        
        return true
    }

    data class Channel(
        val name: String,
        val url: String
    )
}
