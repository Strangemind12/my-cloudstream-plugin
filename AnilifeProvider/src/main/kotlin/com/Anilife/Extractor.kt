// v3.2 - Fixed JSON escaped slashes (\/) in apiUrl causing API fetch failure
package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread

class AnilifeProxyExtractor : ExtractorApi() {
    override val name = "Anilife"
    override val mainUrl = "https://api.gcdn.app"
    override val requiresReferer = false

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    companion object {
        @Volatile private var currentProxyServer: FakeKeyStripperProxy? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) { }

    fun extractPlayerUrl(html: String, domain: String): String? {
        val patterns = listOf(
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""["']([^"']*h\/live\?p=[^"']+)["']""")
        )
        for (regex in patterns) {
            regex.find(html)?.let {
                var url = it.groupValues[1]
                if (url.contains("h/live") && url.contains("p=")) {
                    if (!url.startsWith("http")) url = if (url.startsWith("/")) "$domain$url" else "$domain/$url"
                    return url.replace("\\/", "/")
                }
            }
        }
        return null
    }

    suspend fun extractWithProxy(
        m3u8Url: String,
        playerUrl: String,
        referer: String,
        ssid: String?,
        cookies: String,
        targetKeyUrl: String? = null,
        videoId: String = "unknown_id",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[Anilife][Extractor] v3.2 - Recursive Fake Key Stripping Proxy 시작")
        
        val cleanHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://anilife.live/",
            "Origin" to "https://anilife.live",
            "Accept" to "*/*"
        )

        try {
            var finalM3u8 = ""
            val playerHtml = app.get(playerUrl, headers = cleanHeaders).text
            val aldataRegex = Regex("""_aldata\s*=\s*['"]([^'"]+)['"]""")
            val match = aldataRegex.find(playerHtml)
            
            if (match != null) {
                val b64 = match.groupValues[1]
                val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                val vid1080Regex = Regex(""""vid_url_1080"\s*:\s*"([^"]+)"""")
                val vid720Regex = Regex(""""vid_url_720"\s*:\s*"([^"]+)"""")
                
                var apiUrl = vid1080Regex.find(decoded)?.groupValues?.get(1)
                if (apiUrl.isNullOrBlank() || apiUrl == "none") {
                    apiUrl = vid720Regex.find(decoded)?.groupValues?.get(1)
                }
                
                // [핵심 수정] JSON 내부에 이스케이프 처리된 \/ 문자를 /로 원복하여 정상적인 URL로 만듦
                apiUrl = apiUrl?.replace("\\/", "/")
                
                if (!apiUrl.isNullOrBlank() && apiUrl != "none") {
                    val fullApiUrl = if (apiUrl.startsWith("http")) apiUrl else "https://$apiUrl"
                    println("[Anilife][Extractor] API 정상 호출: $fullApiUrl")
                    
                    val apiRes = app.get(fullApiUrl, headers = cleanHeaders).text
                    val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
                    val extractedUrl = urlRegex.find(apiRes)?.groupValues?.get(1)
                    
                    if (!extractedUrl.isNullOrBlank()) {
                        finalM3u8 = extractedUrl.replace("\\/", "/")
                        println("[Anilife][Extractor] 찐 M3U8 추출 완료: $finalM3u8")
                    }
                }
            }

            if (finalM3u8.isBlank()) {
                println("[Anilife][Extractor] M3U8 주소 확보 실패 (API 통신 오류 또는 _aldata 파싱 실패)")
                return false
            }

            // 가짜 키(Fake Key) 스트리핑 로컬 프록시 구동
            synchronized(this) { currentProxyServer?.stop(); currentProxyServer = null }
            val proxy = FakeKeyStripperProxy().apply { start() }
            currentProxyServer = proxy

            val encodedInitialM3u8 = Base64.encodeToString(finalM3u8.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
            val proxyUrl = "http://127.0.0.1:${proxy.port}/playlist/$encodedInitialM3u8.m3u8"

            println("[Anilife][Extractor] 프록시 URL 전달: $proxyUrl")
            
            // 군더더기 없는 헤더와 프록시 URL을 ExoPlayer로 전달
            callback(newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://anilife.live/"
                this.headers = cleanHeaders
            })
            
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            println("[Anilife][Extractor] 에러 발생: ${e.message}")
            return false
        }
    }

    class FakeKeyStripperProxy() {
        var port: Int = 0
        private var server: ServerSocket? = null
        private var isRunning = false

        fun start() {
            server = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread { 
                while (isRunning) { 
                    try { 
                        val socket = server!!.accept()
                        thread { handle(socket) }
                    } catch (e: Exception) {} 
                } 
            }
        }

        fun stop() { isRunning = false; server?.close() }

        private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
            if (target.startsWith("http")) return target
            return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
                if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
                else "${baseUrlStr.substringBeforeLast("/")}/$target"
            }
        }

        private fun handle(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val path = line.split(" ")[1]

                while (true) {
                    val headerLine = reader.readLine()
                    if (headerLine.isNullOrEmpty()) break
                }

                val out = socket.getOutputStream()

                // 재귀적 M3U8 파싱 및 가짜 키 삭제 로직
                if (path.startsWith("/playlist/")) {
                    val encodedUrl = path.substringAfter("/playlist/").substringBefore(".m3u8")
                    val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE))
                    
                    println("[Anilife][Proxy] 원본 M3U8 요청 수신 (타겟: $targetUrl)")

                    val m3u8Content = try {
                        runBlocking {
                            app.get(
                                targetUrl, 
                                headers = mapOf(
                                    "Referer" to "https://anilife.live/", 
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
                                )
                            ).text
                        }
                    } catch (e: Exception) { "" }

                    val baseUri = try { URI(targetUrl) } catch (e: Exception) { null }
                    val sb = StringBuilder()

                    for (lineText in m3u8Content.lines()) {
                        val trimmed = lineText.trim()
                        if (trimmed.isEmpty()) continue
                        
                        // [핵심] ExoPlayer의 404 원인인 가짜 암호화 키를 원천 삭제하여 무력화시킴
                        if (trimmed.startsWith("#EXT-X-KEY")) {
                            println("[Anilife][Proxy] 가짜 키(Fake Key) 쳐내기 성공: $trimmed")
                            continue
                        } else if (trimmed.startsWith("#")) {
                            sb.append(trimmed).append("\n")
                        } else {
                            val absUrl = resolveUrl(baseUri, targetUrl, trimmed)
                            // 중첩된 M3U8 파일들도 모두 로컬 프록시를 거치도록 Base64 인코딩 주소로 덮어쓰기
                            if (absUrl.contains(".m3u8")) {
                                val enc = Base64.encodeToString(absUrl.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
                                sb.append("http://127.0.0.1:$port/playlist/$enc.m3u8\n")
                            } else {
                                // .ts 파일 등은 절대 주소로 CDN 직행 (오류 방지)
                                sb.append(absUrl).append("\n")
                            }
                        }
                    }

                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    out.write(sb.toString().toByteArray())
                } else {
                    out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                }
                out.flush()
            } catch (e: Exception) {
                println("[Anilife][Proxy] Handle Error: ${e.message}")
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }
}
