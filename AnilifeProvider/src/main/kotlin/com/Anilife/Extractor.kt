package com.anilife

/**
 * Extractor v24.0
 * - [Fix] '플레이어 선택 페이지.txt'에 있는 location.href 패턴 완벽 대응
 * - [Debug] 매칭 과정 및 결과 상세 로그
 */
class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"

    fun extractPlayerUrl(html: String, domain: String): String? {
        println("$TAG [Parser] HTML 파싱 시작 (데이터 길이: ${html.length})")
        
        // 정규식 리스트
        // 1. location.href = "..." (사용자 제공 파일 패턴)
        // 2. 일반적인 "h/live?p=..." 패턴
        val patterns = listOf(
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""), // 파일과 일치하는 패턴
            Regex("""["']([^"']*h\/live\?p=[^"']+)["']""")
        )

        for ((index, regex) in patterns.withIndex()) {
            println("$TAG [Parser] 패턴 #${index + 1} 검사 중...")
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues[1]
                
                // 추출된 URL이 유효한지 검증
                if (url.contains("h/live") && url.contains("p=")) {
                    println("$TAG [Parser] 패턴 #${index + 1} 매칭 성공! -> $url")
                    
                    if (!url.startsWith("http")) {
                        url = if (url.startsWith("/")) "$domain$url" else "$domain/$url"
                    }
                    return url.replace("\\/", "/")
                }
            }
        }
        
        println("$TAG [Parser] 실패: 모든 패턴 매칭 실패 (타겟 자바스크립트가 없음)")
        return null
    }
}
