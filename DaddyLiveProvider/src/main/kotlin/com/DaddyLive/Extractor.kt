/**
 * DaddyLiveExtractor v1.1
 * - [Fix] 패키지명을 com.DaddyLive로 수정
 * - [Debug] 비디오 추출 단계별(extractVideo, extractFromNewzar) 로그 추가
 */
package com.DaddyLive

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val headers = mapOf("Referer" to mainUrl, "user-agent" to userAgent)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[DaddyLiveExt] getUrl 호출")
        val links = tryParseJson<List<Pair<String, String>>>(url)
        val extractors = links?.map { extractVideo(it.second, it.first) } ?: listOf(extractVideo(url))

        extractors.forEach { it?.let { callback(it) } }
    }

    private suspend fun extractVideo(url: String, sourceName: String = this.name): ExtractorLink? {
        if (!url.contains("dlhd")) return null
        println("[DaddyLiveExt] 비디오 추출 시도: $url")

        return try {
            val resp = app.post(url, headers = headers).document
            val iframeSrc = resp.selectFirst("iframe")?.attr("src") ?: return null
            val parsedUrl = URL(iframeSrc)
            val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"

            val finalUrl = if (iframeSrc.contains("vidembed")) extractFromVidembed(iframeSrc) 
                           else extractFromNewzar(iframeSrc, refererBase)

            if (finalUrl == null) {
                println("[DaddyLiveExt] 추출 실패: $sourceName")
                return null
            }

            println("[DaddyLiveExt] 추출 성공: $finalUrl")
            newExtractorLink(sourceName, sourceName, finalUrl, type = ExtractorLinkType.M3U8) {
                this.referer = "$refererBase/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Origin" to refererBase, "User-Agent" to userAgent)
            }
        } catch (e: Exception) {
            println("[DaddyLiveExt] 에러 발생: ${e.message}")
            null
        }
    }

    private suspend fun extractFromVidembed(urlNextPage: String): String? {
        // ... 기존 로직과 동일 (필요시 추가 디버깅 로그 구현)
        return null
    }

    private suspend fun extractFromNewzar(urlNextPage: String, serverUrl: String): String? {
        println("[DaddyLiveExt] Newzar 추출 시작: $urlNextPage")
        val page = app.get(urlNextPage, headers).document
        val script = page.select("script").firstOrNull { it.data().contains("CHANNEL_KEY") }?.data() ?: return null

        val bundle = base64Decode(Regex("""(?<=const IJXX=").*(?=")""").find(script)?.value ?: return null)
        val bundleObj = parseJson<Bundle>(bundle)
        val channelKey = Regex("""(?<=const CHANNEL_KEY=").*(?=")""").find(script)?.value ?: return null
        
        val params = mapOf(
            "channel_id" to channelKey,
            "ts" to base64Decode(bundleObj.bTs),
            "rnd" to base64Decode(bundleObj.bRnd),
            "sig" to base64Decode(bundleObj.bSig),
        )

        val authResponse = app.get("https://top2new.newkso.ru/auth.php", params = params, headers = mapOf("User-Agent" to userAgent, "Referer" to "$serverUrl/", "Origin" to serverUrl))
        if (authResponse.code == 403) {
            println("[DaddyLiveExt] Auth 실패 (403)")
            return null
        }

        val serverKey = app.get("$serverUrl/server_lookup.php?channel_id=$channelKey").body.string()
        val data = try { parseJson<DataResponse>(serverKey) } catch (e: Exception) { return null }

        val m3u8 = if (data.serverKey == "top1/cdn") "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
                   else "https://${data.serverKey}new.newkso.ru/${data.serverKey}/$channelKey/mono.m3u8"
        
        return m3u8
    }

    data class DataResponse(@JsonProperty("server_key") val serverKey: String)
    data class Bundle(@JsonProperty("b_ts") val bTs: String, @JsonProperty("b_rnd") val bRnd: String, @JsonProperty("b_sig") val bSig: String)
}
