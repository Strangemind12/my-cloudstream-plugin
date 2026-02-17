package com.anilife

/**
 * Extractor v19.0
 * - [Fix] 빌드 에러 원인인 WebViewResolver 의존성을 완전히 제거
 * - [Fix] 오직 HTML 문자열에서 URL을 추출하는 순수 파싱 기능만 수행
 * - [Debug] 상세 파싱 로그 포함
 */
class AnilifeExtractor {
    private val TAG = "[AnilifeExtractor]"

    fun extractPlayerUrl(html: String, domain: String): String? {
        println("$TAG [Parser] 플레이어 URL 추출 시작...")
        
        // 자바스크립트 내의 "https://anilife.live/h/live?p=...&player=..." 패턴 추출
        // 정규식 대폭 완화하여 따옴표 안의 매칭 문자열 확보
        val regex = Regex("""["']([^"']*\/?h\/live\?p=[^"']+)["']""")
        val match = regex.find(html)
        var playerUrl = match?.groupValues?.get(1)

        if (playerUrl != null) {
            // 상대 경로일 경우 도메인 추가
            if (!playerUrl.startsWith("http")) {
                playerUrl = if (playerUrl.startsWith("/")) "$domain$playerUrl" else "$domain/$playerUrl"
            }
            // 이스케이프 문자(\/) 제거
            playerUrl = playerUrl.replace("\\/", "/")
            println("$TAG [Parser] 추출 성공: $playerUrl")
            return playerUrl
        }
        
        println("$TAG [Parser] 추출 실패")
        return null
    }
}
