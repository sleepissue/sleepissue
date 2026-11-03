package com.sleepissue.awakedrive.engine.model

/**
 * 설정의 연령대. (SRS Data Interface MSG-12 Settings.ageGroup)
 *
 * 엔진이 연령대별 취약 시간대(REQ_FAT_015)를 계산하려고 여기 둔다.
 * 설정 화면(5번)도 이 enum을 그대로 쓴다.
 */
enum class AgeGroup {
    /** 설정 안 함 → 기본 취약 시간대만 씀 */
    UNSET,
    TWENTIES,
    THIRTIES_FORTIES,
    FIFTIES_PLUS,
}
