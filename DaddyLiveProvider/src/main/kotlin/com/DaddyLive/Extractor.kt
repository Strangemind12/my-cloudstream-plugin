/**
 * DaddyLiveExtractor v3.0
 * - [Reassemble] 원본 Newzar 파싱 로직 + dvalna.ru 신규 도메인 결합
 * - [Optimize] WebView 제거: app.get 정적 파싱으로 30개 링크를 3초 내에 추출
 * - [Fix] mono.css 주소 체계 대응 및 403 에러 방지 헤더 주입
 */
package com.DaddyLive

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.link"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v3.0 고속 엔진 가동")

        // amap을 사용하여 30개 채널을 병렬로 초고속 파싱
        links.amap { (name, link) ->
            try {
                val extractorLink = extractVideoDirectly(link, name)
                if (extractorLink != null) {
                    callback(extractorLink)
                }
            } catch (e: Exception) {
                println("[DaddyLiveExt] $name 파싱 오류: ${e.message}")
            }
        }
    }

    private suspend fun extractVideoDirectly(url: String, sourceName: String): ExtractorLink? {
        println("[DaddyLiveExt] 분석 중: $url")
        
        // 1. 플레이어 페이지 HTML 가져오기
        val page = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
        val iframe = page.select("iframe").firstOrNull() ?: return null
        val iframeSrc = iframe.attr("src")
        
        // 2. iframe 내부 페이지 접속 (Newzar 로직 실행)
        val parsedIframeUrl = URL(iframeSrc)
        val serverBase = "${parsedIframeUrl.protocol}://${parsedIframeUrl.host}"
        
        val iframePage = app.get(iframeSrc, headers = mapOf("Referer" to url)).document
        val scripts = iframePage.select("script").map { it.data() }
        
        // 3. CHANNEL_KEY 및 IJXX(Bundle) 데이터 추출
        val scriptWithKey = scripts.firstOrNull { it.contains("CHANNEL_KEY") } ?: return null
        val channelKey = Regex("""const CHANNEL_KEY\s*=\s*["']([^"']+)["']""").find(scriptWithKey)?.groupValues?.get(1) ?: return null
        val ijxxEncoded = Regex("""const IJXX\s*=\s*["']([^"']+)["']""").find(scriptWithKey)?.groupValues?.get(1) ?: return null
        
        val bundleJson = base64Decode(ijxxEncoded)
        val bundle = parseJson<Bundle>(bundleJson)
        
        // 4. Auth 단계 (서버 세션 획득)
        val authParams = mapOf(
            "channel_id" to channelKey,
            "ts" to base64Decode(bundle.bTs),
            "rnd" to base64Decode(bundle.bRnd),
            "sig" to base64Decode(bundle.bSig)
        )
        
        // dvalna.ru 망의 auth 서버 호출
        app.get("https://top2new.dvalna.ru/auth.php", params = authParams, headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$serverBase/",
            "Origin" to serverBase
        ))

        // 5. Server Lookup 단계 (어느 노드에서 가져올지 결정)
        val lookupUrl = "$serverBase/server_lookup.php?channel_id=$channelKey"
        val lookupResp = app.get(lookupUrl, headers = mapOf("Referer" to iframeSrc)).text
        val serverData = try { parseJson<DataResponse>(lookupResp) } catch (e: Exception) { null } ?: return null
        
        // 6. [핵심] 사용자가 발견한 mono.css 주소 체계로 최종 조립
        // 구조: https://{serverKey}new.dvalna.ru/{serverKey}/premium{id}/mono.css
        val finalUrl = "https://${serverData.serverKey}new.dvalna.ru/${serverData.serverKey}/premium$channelKey/mono.css"
        
        println("[DaddyLiveExt] ★최종 주소 조립 성공: $finalUrl")

        return newExtractorLink(sourceName, sourceName, finalUrl, type = ExtractorLinkType.M3U8) {
            this.quality = Qualities.Unknown.value
            this.referer = "$serverBase/"
            this.headers = mapOf(
                "User-Agent" to userAgent,
                "Origin" to serverBase,
                "Referer" to "$serverBase/",
                "Accept" to "*/*"
            )
        }
    }

    // 데이터 모델 정의
    data class DataResponse(@JsonProperty("server_key") val serverKey: String)
    data class Bundle(
        @JsonProperty("b_ts") val bTs: String,
        @JsonProperty("b_rnd") val bRnd: String,
        @JsonProperty("b_sig") val bSig: String
    )
}
