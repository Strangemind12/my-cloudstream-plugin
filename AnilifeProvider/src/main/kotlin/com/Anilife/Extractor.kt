package com.anilife

/**
 * Extractor v21.0
 * - [Debug] 정규식 매칭 시도마다 로그 출력
 * - [Debug] 매칭된 문자열 원본 출력
 */
class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"

    fun extractPlayerUrl(html: String, domain: String): String? {
        println("$TAG [Parser] 파싱 함수 진입. HTML 길이: ${html.length}")
        
        // 정규식 리스트
        val patterns = listOf(
            // 1. location.href 직접 할당
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            // 2. 따옴표 안의 h/live?p= 패턴
            Regex("""["']([^"']*h\/live\?p=[^"']+)["']"""),
            // 3. onclick 이벤트
            Regex("""onclick\s*=\s*["'].*?['"]([^"']*h\/live\?p=[^"']+)['"]""")
        )

        for ((index, regex) in patterns.withIndex()) {
            println("$TAG [Parser] 패턴 #${index + 1} 시도 중... 정규식: ${regex.pattern}")
            
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues[1]
                println("$TAG [Parser] 패턴 #${index + 1} 매칭 성공! 추출된 값: $url")
                
                // 유효성 검사
                if (url.contains("h/live") && url.contains("p=")) {
                    if (!url.startsWith("http")) {
                        url = if (url.startsWith("/")) "$domain$url" else "$domain/$url"
                    }
                    val finalUrl = url.replace("\\/", "/")
                    println("$TAG [Parser] 최종 가공된 URL: $finalUrl")
                    return finalUrl
                } else {
                    println("$TAG [Parser] 매칭은 됐으나 유효한 플레이어 URL 패턴이 아님 (무시됨)")
                }
            } else {
                println("$TAG [Parser] 패턴 #${index + 1} 매칭 실패")
            }
        }
        
        println("$TAG [Parser] 모든 패턴 매칭 실패. HTML 내용을 확인하세요.")
        return null
    }
}
