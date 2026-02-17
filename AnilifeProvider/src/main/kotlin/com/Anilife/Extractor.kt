package com.anilife

import com.lagradost.cloudstream3.app

class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://anilife.live/"
    )

    // 단순히 URL을 찾아서 반환하는 함수
    suspend fun fetchPlayerUrl(url: String): String? {
        try {
            val response = app.get(url, headers = commonHeaders)
            val html = response.text

            // 자바스크립트 내의 "https://anilife.live/h/live?p=...&player=..." 패턴 추출
            val regex = Regex("""https://anilife\.live/h/live\?p=[^"']+(?:&player=[^"']+)*""")
            val match = regex.find(html)
            
            return match?.value
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
