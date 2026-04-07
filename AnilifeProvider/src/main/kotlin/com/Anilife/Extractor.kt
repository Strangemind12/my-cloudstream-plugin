package com.anilife

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

class AnilifeProxyExtractor : ExtractorApi() {
    override val name = "Anilife"
    override val mainUrl = "https://api.gcdn.app"
    override val requiresReferer = false

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) { }

    fun extractPlayerUrl(html: String, domain: String): String? {
        listOf(Regex("""location\.href\s*=\s*["']([^"']+)["']"""), Regex("""["']([^"']*h\/live\?p=[^"']+)["']""")).forEach { regex ->
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

    suspend fun extractWithProxy(playerUrl: String, referer: String, videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        val cleanHeaders = mapOf("User-Agent" to DESKTOP_UA, "Referer" to referer, "Origin" to referer.trimEnd('/'), "Accept" to "*/*")

        try {
            var finalM3u8 = ""
            val playerHtml = app.get(playerUrl, headers = cleanHeaders).text
            val b64 = Regex("""_aldata\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)
            
            if (b64 != null) {
                //[고유 개선] AppUtils.parseJson을 통한 안전한 JSON 매핑
                val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                val jsonMap = parseJson<Map<String, Any>>(decoded)
                
                var apiUrl = jsonMap["vid_url_1080"] as? String
                if (apiUrl.isNullOrBlank() || apiUrl == "none") apiUrl = jsonMap["vid_url_720"] as? String
                
                if (!apiUrl.isNullOrBlank() && apiUrl != "none") {
                    val fullApiUrl = if (apiUrl.startsWith("http")) apiUrl else "https://$apiUrl"
                    val apiRes = app.get(fullApiUrl, headers = cleanHeaders).text
                    val urlMap = parseJson<Map<String, Any>>(apiRes)
                    val extractedUrl = urlMap["url"] as? String
                    if (!extractedUrl.isNullOrBlank()) finalM3u8 = extractedUrl.replace("\\/", "/")
                }
            }

            if (finalM3u8.isBlank()) return false

            // [고유 개선] 중첩 M3U8 화질 대응 처리
            var m3u8Content = app.get(finalM3u8, headers = cleanHeaders).text
            if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                M3u8Helper.generateM3u8(name, finalM3u8, referer, headers = cleanHeaders).forEach { callback(it) }
                return true
            }

            val baseUri = try { URI(finalM3u8) } catch (e: Exception) { null }
            val sb = StringBuilder()
            
            for (lineText in m3u8Content.lines()) {
                val trimmed = lineText.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("#EXT-X-KEY")) continue // Fake Key 제거
                else if (trimmed.startsWith("#")) sb.append(trimmed).append("\n")
                else sb.append(resolveUrl(baseUri, finalM3u8, trimmed)).append("\n")
            }

            synchronized(this) { currentProxyServer?.stop(); currentProxyServer = null }
            val proxy = ProxyWebServer().apply { updatePlaylist(sb.toString()); start() }
            currentProxyServer = proxy
            
            callback(newExtractorLink(name, name, "http://127.0.0.1:${proxy.port}/$videoId/playlist.m3u8", ExtractorLinkType.M3U8) {
                this.referer = referer; this.headers = cleanHeaders
            })
            return true
        } catch (e: Exception) { return false }
    }

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
            if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
            else "${baseUrlStr.substringBeforeLast("/")}/$target"
        }
    }

    class ProxyWebServer {
        var port: Int = 0
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        @Volatile private var playlistData: String = ""

        fun updatePlaylist(data: String) { playlistData = data }

        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                //[공통 개선] 쓰레드 자원 낭비 방지용 코루틴 처리
                CoroutineScope(Dispatchers.IO).launch { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                    } 
                }
            } catch (e: Exception) { }
        }

        fun stop() { isRunning = false; try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {} }

        private fun handleClient(socket: Socket) {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return
                while (reader.readLine()?.isNotEmpty() == true) { }

                val out = socket.getOutputStream()
                if (path.contains("playlist.m3u8")) {
                    val payload = playlistData.toByteArray(charset("UTF-8"))
                    // [공통 개선] 무한 버퍼링 방지를 위한 Connection 및 Length 추가
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\nContent-Length: ${payload.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    out.write(payload)
                } else out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                out.flush()
            } catch (e: Exception) {
            } finally { try { socket.close() } catch (e: Exception) {} }
        }
    }
}
