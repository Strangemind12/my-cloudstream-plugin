package com.anilife

/**
 * Extractor v26.0
 * - [Fix] '플레이어 선택 페이지.txt' 소스 기반 정밀 파싱 (location.href 최우선)
 */
class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"

    fun extractPlayerUrl(html: String, domain: String): String? {
        println("$TAG [Parser] HTML 파싱 시작 (길이: ${html.length})")
        
        val patterns = listOf(
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""["']([^"']*h\/live\?p=[^"']+)["']""")
        )

        for ((index, regex) in patterns.withIndex()) {
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues[1]
                
                if (url.contains("h/live") && url.contains("p=")) {
                    println("$TAG [Parser] 패턴 #${index + 1} 매칭 성공! -> $url")
                    
                    if (!url.startsWith("http")) {
                        url = if (url.startsWith("/")) "$domain$url" else "$domain/$url"
                    }
                    return url.replace("\\/", "/")
                }
            }
        }
        
        println("$TAG [Parser] 실패: 모든 패턴 매칭 실패")
        return null
    }
}
