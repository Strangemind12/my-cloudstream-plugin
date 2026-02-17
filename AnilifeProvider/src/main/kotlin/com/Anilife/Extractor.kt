package com.anilife

/**
 * Extractor v23.0
 * - [Fix] 업로드된 '플레이어 선택 페이지.txt'의 자바스크립트 패턴 완벽 대응
 * - [Debug] 매칭 과정 상세 로그
 */
class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"

    fun extractPlayerUrl(html: String, domain: String): String? {
        println("$TAG [Parser] HTML 파싱 시작 (길이: ${html.length})")
        
        // 정규식 리스트
        // 1. location.href = "..." (사용자 제공 파일 패턴)
        // 2. 일반적인 "h/live?p=..." 패턴
        val patterns = listOf(
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""["']([^"']*h\/live\?p=[^"']+)["']""")
        )

        for ((index, regex) in patterns.withIndex()) {
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues[1]
                
                if (url.contains("h/live") && url.contains("p=")) {
                    println("$TAG [Parser] 정규식 #${index + 1} 매칭 성공: $url")
                    
                    if (!url.startsWith("http")) {
                        url = if (url.startsWith("/")) "$domain$url" else "$domain/$url"
                    }
                    return url.replace("\\/", "/")
                }
            }
        }
        
        println("$TAG [Parser] 매칭 실패. (target pattern not found)")
        return null
    }
}
