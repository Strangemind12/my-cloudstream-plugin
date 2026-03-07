// v4.0 - True Ultra-Lightweight Hybrid Proxy (Memory Serving, No Dynamic Fetching)
package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread

class AnilifeProxyExtractor : ExtractorApi() {
    override val name = "AnilifeExtractor"
    override val mainUrl = "https://api.gcdn.app"
    override val requiresReferer = false

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
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
        println("[Anilife][Extractor] v4.0 - True Ultra-Lightweight Memory Proxy 시작")
        
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
                
                apiUrl = apiUrl?.replace("\\/", "/")
                
                if (!apiUrl.isNullOrBlank() && apiUrl != "none") {
                    val fullApiUrl = if (apiUrl.startsWith("http")) apiUrl else "https://$apiUrl"
                    val apiRes = app.get(fullApiUrl, headers = cleanHeaders).text
                    val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
                    val extractedUrl = urlRegex.find(apiRes)?.groupValues?.get(1)
                    
                    if (!extractedUrl.isNullOrBlank()) {
                        finalM3u8 = extractedUrl.replace("\\/", "/")
                    }
                }
            }

            if (finalM3u8.isBlank()) {
                println("[Anilife][Extractor] M3U8 주소 확보 실패")
                return false
            }

            // [핵심 변경] 프록시를 띄우기 전에 모든 M3U8 통신 및 전처리(파싱)를 여기서 1회성으로 끝냅니다. (Movieking 방식)
            var m3u8Content = app.get(finalM3u8, headers = cleanHeaders).text
            var baseUri = try { URI(finalM3u8) } catch (e: Exception) { null }

            // 만약 해상도 분기용 마스터 플레이리스트라면 최고 화질 서브 플레이리스트를 찾아 들어갑니다. (TVWiki 방식)
            if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                val subUrlLine = m3u8Content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
                if (subUrlLine != null) {
                    finalM3u8 = resolveUrl(baseUri, finalM3u8, subUrlLine)
                    m3u8Content = app.get(finalM3u8, headers = cleanHeaders).text
                    baseUri = try { URI(finalM3u8) } catch (e: Exception) { null }
                }
            }

            // 가짜 키 삭제 및 세그먼트 절대 주소 매핑
            val sb = StringBuilder()
            for (lineText in m3u8Content.lines()) {
                val trimmed = lineText.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    println("[Anilife][Extractor] 가짜 키(Fake Key) 사전 제거됨: $trimmed")
                    continue
                } else if (trimmed.startsWith("#")) {
                    sb.append(trimmed).append("\n")
                } else {
                    // 세그먼트는 무조건 CDN 절대 주소로 다이렉트 처리 (프록시 안 거침)
                    val absUrl = resolveUrl(baseUri, finalM3u8, trimmed)
                    sb.append(absUrl).append("\n")
                }
            }

            val processedPlaylist = sb.toString()

            // [초경량 프록시 구동] 사전 조립된 텍스트만 메모리로 전달
            synchronized(this) { currentProxyServer?.stop(); currentProxyServer = null }
            val proxy = ProxyWebServer().apply { 
                updatePlaylist(processedPlaylist)
                start() 
            }
            currentProxyServer = proxy

            // ExoPlayer에게는 단순한 127.0.0.1 로컬 주소만 전달
            val proxyUrl = "http://127.0.0.1:${proxy.port}/playlist.m3u8"
            println("[Anilife][Extractor] 초경량 메모리 프록시 세팅 완료 URL: $proxyUrl")
            
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

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
            if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
            else "${baseUrlStr.substringBeforeLast("/")}/$target"
        }
    }

    // [초경량 구조 개편] 동적 통신(get)이 완전히 배제된 순수 메모리 서빙 프록시
    class ProxyWebServer {
        var port: Int = 0
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        @Volatile private var playlistData: String = ""

        fun updatePlaylist(data: String) {
            playlistData = data
        }

        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                thread { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                    } 
                }
            } catch (e: Exception) { println("[Anilife][Proxy] Server Start Failed: $e") }
        }

        fun stop() { 
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {} 
        }

        private fun handleClient(socket: Socket) {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val path = line.split(" ")[1]

                while (true) {
                    val headerLine = reader.readLine()
                    if (headerLine.isNullOrEmpty()) break
                }

                val out = socket.getOutputStream()

                // 메모리에 올려둔 playlistData 문자열만 즉시 반환 (I/O 병목 없음)
                if (path.contains("playlist.m3u8")) {
                    println("[Anilife][Proxy] 초경량 M3U8 메모리 서빙 수행")
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    out.write(playlistData.toByteArray(charset("UTF-8")))
                } else {
                    out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                }
                out.flush()
            } catch (e: Exception) {
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }
}
