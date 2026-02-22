package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.io.*
import java.net.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * v124-4: 비디오 ID 파싱 디버깅 강화 (Hybrid Proxy 기반)
 * [변경 이력]
 * - v124-4: 비디오 ID 감지 누락 원인 분석을 위해 extractVideoIdDeep 및 진입점에 상세 디버그 로그 추가.
 * - v124-3: 하이브리드 프록시 (세그먼트 다이렉트 링크 주입).
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v124-4] getUrl Start (Hybrid Proxy 구조) ===")
        println("[MovieKing v124-4] [DEBUG] 플레이어 원본 URL: $url")
        
        try {
            val videoId = extractVideoIdDeep(url)
            println("[MovieKing v124-4] [DEBUG] 최종 확정된 비디오 ID: $videoId")
            
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            println("[MovieKing v124-4] 플레이어 HTML 및 원본 M3U8 요청 시작")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val hexIv = keyMatch?.groupValues?.get(2)
            
            val candidates = if (keyMatch != null) solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            
            val lines = playlistRes.lines()
            var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            val firstSeq = currentSeq
            var firstSegmentUrl: String? = null
            
            // 정답 키 추출을 위해 첫 번째 세그먼트 주소만 선탐색
            for (line in lines) {
                if (line.isNotBlank() && !line.startsWith("#")) {
                    firstSegmentUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    break
                }
            }

            var confirmedKey = ByteArray(16)
            var confirmedIvType = -1

            // 재생 시작 전, 첫 세그먼트 단 한 개만 선행 다운로드하여 16바이트짜리 완벽한 정답 키를 찾아냄
            if (firstSegmentUrl != null && candidates.isNotEmpty()) {
                try {
                    println("[MovieKing v124-4] 정답 키 조합을 위해 첫 세그먼트 단발성 다운로드: $firstSegmentUrl")
                    val firstSegBytes = app.get(firstSegmentUrl, headers = baseHeaders).body.bytes()
                    val ivs = getIvList(firstSeq, hexIv)
                    val checkSize = Math.min(firstSegBytes.size, 188 * 2)
                    
                    outer@ for ((keyIdx, key) in candidates.withIndex()) {
                        for ((ivIdx, iv) in ivs.withIndex()) {
                            try {
                                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                                val head = cipher.update(firstSegBytes.take(checkSize).toByteArray())
                                
                                if (head.isNotEmpty() && head[0] == 0x47.toByte() && head.size > 188 && head[188] == 0x47.toByte()) {
                                    println("[MovieKing v124-4] 정답 키 선행 검증 완료! Key#$keyIdx, IV_Type#$ivIdx")
                                    confirmedKey = key
                                    confirmedIvType = ivIdx
                                    break@outer
                                }
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    println("[MovieKing v124-4] 세그먼트 선행 다운로드 또는 복호화 에러: ${e.message}")
                }
            }

            // M3U8 포트 지정을 위해 초경량 프록시 서버 시작
            proxyServer?.stop()
            proxyServer = ProxyWebServer()
            proxyServer!!.start()

            // 플레이어(ExoPlayer)에게 전달할 하이브리드 M3U8 조합
            val newLines = mutableListOf<String>()
            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    // 키는 로컬 프록시의 순수 16바이트 바이너리를 참조하도록 우회
                    var newKeyLine = """#EXT-X-KEY:METHOD=AES-128,URI="http://127.0.0.1:${proxyServer!!.port}/key.bin""""
                    
                    // 정답으로 판명된 IV 타입에 맞게 명시적 IV 주입
                    if (confirmedIvType == 0 && hexIv != null) {
                        newKeyLine += """,IV=$hexIv"""
                    } else if (confirmedIvType == 2) {
                        newKeyLine += """,IV=0x00000000000000000000000000000000"""
                    }
                    newLines.add(newKeyLine)
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    // 핵심 변경점: TS 세그먼트 주소는 원본 서버(CDN)의 절대 주소로 직접 주입. 프록시를 완전히 건너뜀!
                    val segmentUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    newLines.add(segmentUrl)
                } else {
                    newLines.add(line)
                }
            }
            
            val finalM3u8 = newLines.joinToString("\n")
            proxyServer!!.updateData(finalM3u8, confirmedKey)
            
            // 재생 URL에 감지된 Video ID 반영
            val finalUrl = "http://127.0.0.1:${proxyServer!!.port}/$videoId/playlist.m3u8"
            println("[MovieKing v124-4] 클라우드스트림 플레이어로 하이브리드 링크 전달: $finalUrl")
            
            callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/" 
            })
        } catch (e: Exception) { 
            println("[MovieKing v124-4] FATAL Error: $e") 
        }
    }

    private fun extractVideoIdDeep(url: String): String {
        println("[MovieKing v124-4] [DEBUG] extractVideoIdDeep 분석 시작. 입력 URL: $url")
        try {
            val parts = url.split("/v1/")
            if (parts.size < 2) {
                println("[MovieKing v124-4] [DEBUG] URL에 '/v1/' 문자열이 포함되어 있지 않습니다. 새로운 구조일 수 있습니다.")
                return "ID_ERR"
            }
            
            val tokenStr = parts[1]
            println("[MovieKing v124-4] [DEBUG] /v1/ 이후 추출된 토큰 원본: $tokenStr")
            
            val tokenParts = tokenStr.split(".")
            val payload = tokenParts.getOrNull(1)
            
            if (payload != null) {
                println("[MovieKing v124-4] [DEBUG] JWT Payload 분리 성공: $payload")
                
                // 패딩 처리를 위해 URL_SAFE 디코딩 적용
                val decoded = String(Base64.decode(payload, Base64.URL_SAFE))
                println("[MovieKing v124-4] [DEBUG] Base64 디코딩된 JSON 텍스트: $decoded")
                
                val idMatch = Regex(""""id"\s*:\s*(\d+)""").find(decoded)
                if (idMatch != null) {
                    val id = idMatch.groupValues[1]
                    println("[MovieKing v124-4] [DEBUG] 'id' 정규식 매칭 성공: $id")
                    return id
                } else {
                    println("[MovieKing v124-4] [DEBUG] 디코딩된 텍스트에서 '\"id\": 숫자' 패턴을 찾을 수 없습니다.")
                }
            } else {
                println("[MovieKing v124-4] [DEBUG] 토큰이 '.'으로 분리되지 않습니다. JWT 형식이 아닐 수 있습니다.")
            }
        } catch (e: Exception) {
            println("[MovieKing v124-4] [DEBUG] ID 파싱 중 예외 발생: ${e.message}")
        }
        return "ID_ERR"
    }

    private fun getIvList(seq: Long, hexIv: String?): List<ByteArray> {
        val ivs = mutableListOf<ByteArray>()
        if (!hexIv.isNullOrEmpty()) {
            try {
                val hex = hexIv.removePrefix("0x")
                val iv = ByteArray(16)
                hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                ivs.add(iv)
            } catch(e:Exception) { ivs.add(ByteArray(16)) }
        } else ivs.add(ByteArray(16))
        
        val seqIv = ByteArray(16)
        for (i in 0..7) seqIv[15 - i] = (seq shr (i * 8)).toByte()
        ivs.add(seqIv)
        ivs.add(ByteArray(16))
        return ivs
    }

    private suspend fun solveKeyCandidatesCombinatorial(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
            val b64 = try { Base64.decode(encStr, Base64.DEFAULT) } catch (e: Exception) { byteArrayOf() }

            if (b64.size == 16) list.add(b64)

            if (b64.size >= 22) { 
                val src = b64
                val targetGaps = listOf(0, 2, 2, 2, 2)
                val allPerms = generatePermutations(listOf(0, 1, 2, 3))

                try {
                    val segs = mutableListOf<ByteArray>()
                    var idx = targetGaps[0]
                    segs.add(src.copyOfRange(idx, idx + 4))
                    idx += 4 + targetGaps[1]
                    segs.add(src.copyOfRange(idx, idx + 4))
                    idx += 4 + targetGaps[2]
                    segs.add(src.copyOfRange(idx, idx + 4))
                    idx += 4 + targetGaps[3]
                    segs.add(src.copyOfRange(idx, idx + 4))

                    for (perm in allPerms) {
                        val k = ByteArray(16)
                        for (j in 0 until 4) {
                            System.arraycopy(segs[perm[j]], 0, k, j * 4, 4)
                        }
                        list.add(k)
                    }
                } catch (e: Exception) {}
            }
            return list.distinctBy { it.contentHashCode() }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun generatePermutations(list: List<Int>): List<List<Int>> {
        if (list.isEmpty()) return listOf(emptyList())
        val result = mutableListOf<List<Int>>()
        for (i in list.indices) {
            val elem = list[i]
            val rest = list.take(i) + list.drop(i + 1)
            for (p in generatePermutations(rest)) {
                result.add(listOf(elem) + p)
            }
        }
        return result
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var finalM3u8: String = ""
        @Volatile private var finalKey: ByteArray = ByteArray(16)

        fun updateData(m3u8: String, key: ByteArray) {
            finalM3u8 = m3u8
            finalKey = key
        }

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                thread(isDaemon = true) { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                    } 
                }
                println("[MovieKing v124-4] 초경량 하이브리드 서버 시작 완료 (Port: $port)")
            } catch (e: Exception) { println("[MovieKing v124-4] Server Start Failed: $e") }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }
        
        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n" + finalM3u8
                    output.write(response.toByteArray(charset("UTF-8")))
                } else if (path.contains("/key.bin")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(finalKey)
                }
                output.flush()
            } catch (e: Exception) { 
            } finally {
                try { socket.close() } catch(e2:Exception){} 
            }
        }
    }
}
