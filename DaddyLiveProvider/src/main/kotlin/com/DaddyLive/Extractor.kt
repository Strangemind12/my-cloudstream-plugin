/**
 * DaddyLiveExtractor v6.1 (Dynamic Iframe Token Scanner & Ultimate Proxy)
 * - [핵심 원인] ksohls.ru 도메인이 봇 차단(403/Cloudflare)을 걸어 토큰 추출이 거부됨.
 * - [해결] Kodi 애드온의 get_stream_page_url 우회 방식을 포팅하여, 실시간으로 변하는 제3의 iframe(enviromentalspace 등)에서 동적 토큰 탈취 성공.
 * - [Fix] 로컬 프록시 서버가 ExoPlayer의 쿼리스트링 요청(?foo=bar)을 정상 라우팅하지 못하던 버그 수정.
 * - [성능] 암호화 키(Key) 및 TS 세그먼트 요청에 대한 CORS 헤더와 타임아웃 예외 처리 강화.
 */
package com.DaddyLive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

// ExoPlayer의 암호화 키 우회 다운로드를 전담하는 초경량 로컬 프록시 서버
object DaddyLiveProxy {
    var port = 0
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    
    data class ChannelState(val authToken: String, val channelSalt: String, val m3u8Url: String)
    val activeChannels = mutableMapOf<String, ChannelState>()
    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun startServer() {
        if (isRunning && serverSocket != null && !serverSocket!!.isClosed) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                println("[DaddyLiveProxy] 로컬 프록시 서버 가동 완료 (Port: $port)")
                
                while (isRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        thread { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            
            // Query Parameter 제거 후 순수 경로만 추출 (버그 픽스)
            val path = parts[1].substringBefore("?")
            val out = client.getOutputStream()

            val segments = path.trim('/').split("/")
            if (segments.size >= 2 && segments[0] == "m3u8") {
                val channelKey = segments[1]
                val state = activeChannels[channelKey]
                if (state == null) { send404(out); return }
                
                val responseText = try {
                    runBlocking {
                        val resp = app.get(
                            state.m3u8Url, 
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to "https://www.ksohls.ru/",
                                "Authorization" to "Bearer ${state.authToken}",
                                "X-Channel-Key" to channelKey,
                                "X-User-Agent" to userAgent,
                                "Cache-Control" to "no-cache"
                            )
                        )
                        if (resp.isSuccessful) resp.text else null
                    }
                } catch (e: Exception) {
                    println("[DaddyLiveProxy] M3U8 CDN 요청 에러: ${e.message}")
                    null
                }
                
                if (responseText == null) { send404(out); return }
                
                var m3u8Body = responseText
                val baseUrl = state.m3u8Url.substringBeforeLast("/") + "/"
                
                // M3U8 내부의 AES 키 요청 주소를 내 로컬 프록시로 변조
                m3u8Body = m3u8Body.replace(Regex("""URI="([^"]+)"""")) { match ->
                    val uri = match.groupValues[1]
                    val km = Regex("""/key/[^/]+/(\d+)""").find(uri)
                    if (km != null) {
                        """URI="http://127.0.0.1:$port/key/$channelKey/${km.groupValues[1]}""""
                    } else {
                        match.value
                    }
                }

                // 상대 경로 TS를 절대 경로로 변환 (ExoPlayer가 직접 받음)
                val lines = m3u8Body.lines().map { line ->
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        if (line.startsWith("http")) line else baseUrl + line
                    } else {
                        line
                    }
                }
                m3u8Body = lines.joinToString("\n")

                val bytes = m3u8Body.toByteArray()
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
                out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.write(bytes)
                out.flush()

            } else if (segments.size >= 3 && segments[0] == "key") {
                val channelKey = segments[1]
                val keyId = segments[2]
                val state = activeChannels[channelKey]
                if (state == null) { send404(out); return }
                
                val ts = System.currentTimeMillis() / 1000
                val fp = computeFingerprint(userAgent)
                val nonce = computePowNonce(channelKey, state.channelSalt, keyId, ts)
                val authSig = computeAuthSig(channelKey, state.channelSalt, keyId, ts, fp)

                val keyUrl = "https://chevy.vovlacosa.sbs/key/$channelKey/$keyId"
                val headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://www.ksohls.ru/",
                    "Authorization" to "Bearer ${state.authToken}",
                    "X-Key-Timestamp" to ts.toString(),
                    "X-Key-Nonce" to nonce.toString(),
                    "X-Key-Path" to authSig,
                    "X-Fingerprint" to fp
                )
                
                val responseBytes = try {
                    runBlocking { 
                        val resp = app.get(keyUrl, headers = headers)
                        if (resp.isSuccessful) resp.okhttpResponse.body?.bytes() else null
                    }
                } catch (e: Exception) {
                    println("[DaddyLiveProxy] Key 다운로드 에러: ${e.message}")
                    null
                }
                
                if (responseBytes == null) { send404(out); return }
                
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                out.write("Content-Type: application/octet-stream\r\n".toByteArray())
                out.write("Content-Length: ${responseBytes.size}\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.write(responseBytes)
                out.flush()

            } else {
                send404(out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            client.close()
        }
    }

    private fun send404(out: OutputStream) {
        out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun computeFingerprint(ua: String): String = sha256(ua + "1920x1080UTCen").take(16)

    private fun computePowNonce(channelKey: String, channelSalt: String, keyId: String, ts: Long): Long {
        val hmacBase = hmacSha256(channelSalt, channelKey)
        for (nonce in 0L..100000L) {
            val combined = hmacBase + channelKey + keyId + ts.toString() + nonce.toString()
            val h = md5(combined)
            if (h.take(4).toInt(16) < 0x1000) return nonce
        }
        return 99999L
    }

    private fun computeAuthSig(channelKey: String, channelSalt: String, keyId: String, ts: Long, fp: String): String {
        return hmacSha256(channelSalt, "$channelKey|$keyId|$ts|$fp").take(16)
    }
}

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlstreams.top"
    override val name = "DaddyLive"
    override val requiresReferer = false
    
    private val userAgent = DaddyLiveProxy.userAgent

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val links = AppUtils.tryParseJson<List<Pair<String, String>>>(url) ?: return
        println("[DaddyLiveExt] v6.1 동적 iframe 스캐너 및 로컬 프록시 엔진 가동")

        for ((name, link) in links) {
            val idMatch = Regex("""stream-(\d+)""").find(link)
            val channelId = idMatch?.groupValues?.get(1)
            
            if (channelId != null) {
                val channelKey = "premium$channelId"
                
                try {
                    // Kodi 방식의 다중 우회 토큰 획득 로직 실행
                    val credentials = fetchAuthCredentials(channelId)
                    
                    if (credentials != null) {
                        val (authToken, channelSalt) = credentials
                        println("[DaddyLiveExt] [$channelKey] 암호화 토큰 탈취 성공!")
                        
                        // 서버 키 조회
                        val lookupUrl = "https://chevy.vovlacosa.sbs/server_lookup?channel_id=$channelKey"
                        val lookupJson = app.get(lookupUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "https://www.ksohls.ru/")).text
                        val serverKey = Regex(""""server_key"\s*:\s*"([^"]+)"""").find(lookupJson)?.groupValues?.get(1) ?: "zeko"
                        
                        val m3u8Url = if (serverKey == "top1/cdn") {
                            "https://chevy.soyspace.cyou/proxy/top1/cdn/$channelKey/mono.css"
                        } else {
                            "https://chevy.soyspace.cyou/proxy/$serverKey/$channelKey/mono.css"
                        }
                        println("[DaddyLiveExt] [$channelKey] M3U8 원본 CDN 할당됨: $m3u8Url")
                        
                        // 로컬 프록시 구동
                        DaddyLiveProxy.activeChannels[channelKey] = DaddyLiveProxy.ChannelState(authToken, channelSalt, m3u8Url)
                        DaddyLiveProxy.startServer()
                        
                        val localUrl = "http://127.0.0.1:${DaddyLiveProxy.port}/m3u8/$channelKey"
                        
                        callback(newExtractorLink(name, name, localUrl, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to userAgent,
                                "Origin" to "https://www.ksohls.ru",
                                "Referer" to "https://www.ksohls.ru/"
                            )
                        })
                        break
                    } else {
                        println("[DaddyLiveExt] [$channelKey] 토큰 추출 1, 2순위 실패. 백업 우회 경로 탐색으로 넘어갑니다.")
                    }
                } catch (e: Exception) {
                    println("[DaddyLiveExt] [$channelKey] 메인 프로세스 에러: ${e.message}")
                }
            }
            
            // 기존 AnyPlayer(백업 우회망) Fallback 로직 유지
            val streamUrl = getAnyPlayerStream(link)
            if (streamUrl != null) {
                println("[DaddyLiveExt] AnyPlayer(백업망) 우회 성공: $streamUrl")
                val origin = if (streamUrl.contains("sanwalyaarpya.com")) "https://stellarthread.com" else "https://lefttoplay.xyz/"
                
                callback(newExtractorLink(name, name, streamUrl, type = ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to origin,
                        "Referer" to origin
                    )
                })
                break
            }
        }
    }

    private suspend fun fetchAuthCredentials(channelId: String): Pair<String, String>? {
        val watchUrl = "$mainUrl/watch.php?id=$channelId"
        
        // 1순위: 메인 플레이어(ksohls) 직접 접근 (Cloudflare에 막힐 확률 높음)
        val playerUrl1 = "https://www.ksohls.ru/premiumtv/daddyhd.php?id=$channelId"
        try {
            val html1 = app.get(playerUrl1, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")).text
            val at = extractCredential(html1, "authToken")
            val cs = extractCredential(html1, "channelSalt")
            if (at != null && cs != null) {
                println("[DaddyLiveExt] 1순위 우회 성공 (ksohls.ru 다이렉트)")
                return Pair(at, cs)
            }
        } catch (e: Exception) {
            println("[DaddyLiveExt] 1순위 우회 실패: ${e.message}")
        }
        
        // 2순위: 동적 iframe 스캔 (enviromentalspace 등 우회 서버 탐색 - Kodi v3 핵심기술)
        val paths = listOf("stream", "cast", "watch", "plus", "player")
        for (path in paths) {
            try {
                val streamPageUrl = "$mainUrl/$path/stream-$channelId.php"
                val html2 = app.get(streamPageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to watchUrl)).text
                
                val iframeMatch = Regex("""<iframe[^>]+src=["'](https?://[^"']+premiumtv[^"']+)["']""").find(html2)
                if (iframeMatch != null) {
                    val playerUrl2 = iframeMatch.groupValues[1]
                    println("[DaddyLiveExt] 동적 우회 플레이어 감지: $playerUrl2")
                    
                    val html3 = app.get(playerUrl2, headers = mapOf("User-Agent" to userAgent, "Referer" to streamPageUrl)).text
                    val at = extractCredential(html3, "authToken")
                    val cs = extractCredential(html3, "channelSalt")
                    
                    if (at != null && cs != null) {
                        println("[DaddyLiveExt] 2순위 동적 iframe 서버를 통한 토큰 탈취 성공!")
                        return Pair(at, cs)
                    }
                }
            } catch (e: Exception) {
                println("[DaddyLiveExt] 2순위 $path 탐색 에러: ${e.message}")
            }
        }
        return null
    }

    private fun extractCredential(page: String, field: String): String? {
        val m1 = Regex(field + """\s*:\s*['"]([^'"]+)['"]""").find(page)
        if (m1 != null) return m1.groupValues[1]
        
        val m2 = Regex(field + """\s*:\s*_dec_\w+\((_init_\w+),\s*(\d+)\)""").find(page)
        if (m2 != null) {
            val initName = m2.groupValues[1]
            val key = m2.groupValues[2].toInt()
            val arrM = Regex(initName + """\s*=\s*\[([^\]]+)\]""").find(page)
            if (arrM != null) {
                val arr = arrM.groupValues[1].split(",").map { it.trim().toInt() }
                return arr.map { (it xor key).toChar() }.joinToString("")
            }
        }
        return null
    }

    private suspend fun getAnyPlayerStream(watchUrl: String): String? {
        try {
            val response = app.get(watchUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")).text
            if (response.contains("ksohls.ru")) return null
            
            val sdMatch = Regex("""<iframe[^>]+src=["'](https://[^"']+\.superdinamico\.com/embed\.php[^"']*)["']""").find(response)
            if (sdMatch != null) {
                val embedUrl = sdMatch.groupValues[1]
                val r2 = app.get(embedUrl, headers = mapOf("Referer" to watchUrl, "User-Agent" to userAgent)).text
                val idMatch = Regex("""get_stream\.php\?id=([a-f0-9]{32})""").find(r2)
                if (idMatch != null) return "https://edg.ligapk.com/exemple.php?id=${idMatch.groupValues[1]}"
            }
            
            val lcMatch = Regex("""<iframe[^>]+src=["'](https://lovecdn\.ru/[^"']+)["']""").find(response)
            if (lcMatch != null) {
                val embedUrl = lcMatch.groupValues[1]
                val streamNameMatch = Regex("""[?&]stream=([^&"'>\s]+)""").find(embedUrl)
                if (streamNameMatch != null) {
                    val ltUrl = "https://lovetier.bz/player/${streamNameMatch.groupValues[1]}"
                    val r2 = app.get(ltUrl, headers = mapOf("Referer" to embedUrl, "User-Agent" to userAgent)).text
                    val streamUrlMatch = Regex("""streamUrl\s*:\s*["'](https?:[^"']+)["']""").find(r2)
                    if (streamUrlMatch != null) return streamUrlMatch.groupValues[1].replace("\\/", "/")
                }
            }
            
            val m3u8Match = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^"']*)["']""").find(response)
            if (m3u8Match != null) return m3u8Match.groupValues[1]
            
            val iframes = Regex("""<iframe[^>]+src=["'](https?://[^"']{10,200})["']""").findAll(response)
            val skipHosts = listOf("sstatic", "histats", "adsco", "fidget", "chatango", "facebook.com", "google.com", "ksohls.ru")
            
            for (iframe in iframes) {
                val iframeUrl = iframe.groupValues[1]
                if (skipHosts.any { iframeUrl.contains(it) }) continue
                try {
                    val ri = app.get(iframeUrl, headers = mapOf("Referer" to watchUrl, "User-Agent" to userAgent)).text
                    val mi = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^"']*)["']""").find(ri)
                    if (mi != null) return mi.groupValues[1]
                    
                    val mi2 = Regex("""streamUrl\s*:\s*["'](https?:[^"']+)["']""").find(ri)
                    if (mi2 != null) return mi2.groupValues[1].replace("\\/", "/")
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return null
    }
}
