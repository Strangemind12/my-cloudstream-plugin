package com.ohli24

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class MichealCDN : Cdndania() {
    override val name = "MichealCDN"
    override val mainUrl = "https://michealcdn.com"
}

open class Cdndania : ExtractorApi() {
    override val name = "CDNdania"
    override val mainUrl = "https://cdndania.com"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[OHLI24 v1.0] getUrl 호출됨 - url: $url, referer: $referer")
        val host= getBaseUrl(url)
        
        if (url.contains("/video/")) {
            try {
                val doc = app.get(url, referer = referer).document.selectFirst("script:containsData(playerjsSubtitle)")?.data().orEmpty()
                val srtRegex = Regex("""playerjsSubtitle\s*=\s*"[^"]*(https?://[^"]+\.srt)"""")
                val srtUrl = srtRegex.find(doc)?.groupValues?.get(1) ?: ""
                println("[OHLI24 v1.0] 자막 URL 추출: $srtUrl")

                val extractedHash = url.substringAfterLast("/")
                val m3u8Url = "$host/player/index.php?data=$extractedHash&do=getVideo"
                val header= mapOf("x-requested-with" to "XMLHttpRequest")
                val formdata= mapOf("hash" to extractedHash,"r" to "$referer")
                
                println("[OHLI24 v1.0] 비디오 소스(m3u8/txt) API 요청: $m3u8Url")
                val response = app.post(m3u8Url, headers=header, data = formdata).parsedSafe<Response>()
                
                response?.videoSource?.let { m3u8 ->
                    println("[OHLI24 v1.0] API 응답 비디오 소스 획득: $m3u8")

                    // [v1.0 추가] ExoPlayer가 차단당하지 않도록 위장 헤더 맵 구성
                    val streamHeaders = mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Referer" to url, // 기존의 빈 Referer("") 대신 실제 iframe url 사용
                        "Origin" to host
                    )

                    // [v1.0 디버깅 로직] 실제로 txt/m3u8 파일이 무엇을 뱉는지 검증
                    try {
                        println("[OHLI24 v1.0] M3U8(.txt) 텍스트 내부 검증 시작...")
                        val m3u8Res = app.get(m3u8, headers = streamHeaders)
                        val content = m3u8Res.text.trim()
                        
                        println("[OHLI24 v1.0] ================= 본문 내용 시작 =================")
                        println(content.take(2000))
                        if (content.length > 2000) println("... (이하 생략됨) ...")
                        println("[OHLI24 v1.0] ================= 본문 내용 끝 ===================")
                        
                        if (!content.startsWith("#EXTM3U")) {
                            println("[OHLI24 v1.0] ❌ 경고: 응답이 #EXTM3U로 시작하지 않습니다. (에러 3002의 원인일 수 있음)")
                        } else {
                            println("[OHLI24 v1.0] ✅ 정상적인 M3U8 텍스트로 확인됨.")
                        }
                    } catch (e: Exception) {
                        println("[OHLI24 v1.0] M3U8 사전 검증 중 에러 발생: ${e.message}")
                    }

                    callback(
                        newExtractorLink(
                            "CDN",
                            "CDN",
                            url = m3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            // 빈 Referer를 제거하고 올바른 streamHeaders 적용
                            this.headers = streamHeaders
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                    println("[OHLI24 v1.0] ExtractorLink 콜백 전달 완료")

                    if (srtUrl.isNotBlank()) {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                "Korean",
                                srtUrl
                            )
                        )
                        println("[OHLI24 v1.0] SubtitleFile 콜백 전달 완료")
                    }
                } ?: run {
                    println("[OHLI24 v1.0] API 응답에서 videoSource가 null입니다.")
                }
            } catch (e: Exception) {
                println("[OHLI24 v1.0] 파싱 중 예외 발생: ${e.message}")
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}

data class Response(
    val hls: Boolean,
    val videoImage: String,
    val videoSource: String,
    val securedLink: String,
    val downloadLinks: List<Any?>,
    val attachmentLinks: List<Any?>,
    val ck: String,
)
