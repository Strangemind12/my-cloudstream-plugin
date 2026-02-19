/**
 * DaddyLiveExtractor v1.2
 * - [Fix] 패키지명 com.DaddyLive로 수정
 * - [Debug] UnknownHostException 발생 시 어떤 URL에서 터지는지 상세 로그 추가
 */
package com.DaddyLive

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url)
        links?.forEach { (name, link) ->
            extractVideo(link, name)?.let { callback(it) }
        }
    }

    private suspend fun extractVideo(url: String, sourceName: String): ExtractorLink? {
        if (!url.contains("dlhd")) return null
        println("[DaddyLiveExt] Extracting from: $url")

        return try {
            val resp = app.post(url, headers = mapOf("Referer" to mainUrl, "user-agent" to userAgent)).document
            val iframeSrc = resp.selectFirst("iframe")?.attr("src") ?: return null
            println("[DaddyLiveExt] Found Iframe: $iframeSrc")

            // topembed.pw 가 죽었을 경우를 대비한 체크
            if (iframeSrc.contains("topembed.pw")) {
                println("[DaddyLiveExt] Warning: topembed.pw is known to be down. Attempting to proceed anyway...")
            }

            val finalUrl = if (iframeSrc.contains("vidembed")) null // Vidembed 로직은 현재 생략
                           else extractFromNewzar(iframeSrc, "${URL(iframeSrc).protocol}://${URL(iframeSrc).host}")

            finalUrl?.let {
                newExtractorLink(sourceName, sourceName, it, type = ExtractorLinkType.M3U8) {
                    this.referer = "${URL(iframeSrc).protocol}://${URL(iframeSrc).host}/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Origin" to referer!!, "User-Agent" to userAgent)
                }
            }
        } catch (e: Exception) {
            println("[DaddyLiveExt] Error extracting $sourceName: ${e.message}")
            null
        }
    }

    private suspend fun extractFromNewzar(urlNextPage: String, serverUrl: String): String? {
        return try {
            val page = app.get(urlNextPage, mapOf("user-agent" to userAgent)).document
            val script = page.select("script").firstOrNull { it.data().contains("CHANNEL_KEY") }?.data() ?: return null
            
            val channelKey = Regex("""const CHANNEL_KEY="([^"]+)""").find(script)?.groupValues?.get(1) ?: return null
            val serverKeyRaw = app.get("$serverUrl/server_lookup.php?channel_id=$channelKey").body.string()
            val serverKey = AppUtils.parseJson<DataResponse>(serverKeyRaw).serverKey

            if (serverKey == "top1/cdn") "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
            else "https://${serverKey}new.newkso.ru/${serverKey}/$channelKey/mono.m3u8"
        } catch (e: Exception) {
            println("[DaddyLiveExt] Newzar Error: ${e.message}")
            null
        }
    }

    data class DataResponse(@JsonProperty("server_key") val serverKey: String)
}
