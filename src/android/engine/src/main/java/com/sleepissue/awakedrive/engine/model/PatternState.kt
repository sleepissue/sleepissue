package com.sleepissue.awakedrive.engine.model

/**
 * PatternClassifier가 30초마다 내보내는 딴짓·졸음 패턴. (SRS Data Interface MSG-03)
 *
 * 받는 쪽: 단계 전환(INT), 화면(UI).
 */
data class PatternState(
    /** 최근 10분 가운데 '이상 분'의 수, 0~10. (REQ_PAT_001) */
    val abnormalMinutes10m: Int = 0,
    /** 이상이 이어지는 모양. (REQ_PAT_002·003) */
    val patternType: PatternType = PatternType.NONE,
    /** 5분 안에 급조향 3회. 원인을 따지지 않고 3단계로 보내는 안전 우회 신호. (REQ_PAT_005) */
    val safetyBypass: Boolean = false,
)

/** 최근 10분 이상 분으로 나눈 패턴. */
enum class PatternType {
    /** 이상 분 없음 */
    NONE,

    /** 추세형(졸음 쪽): 이상 분 6분 이상 */
    TREND,

    /** 튐형(딴짓 쪽): 이상 분 3분 이하이고 매번 1분 안에 기준으로 복귀 */
    SPIKE,

    /** 애매: 이상 분 4~5분, 또는 3분 이하인데 복귀 실패. 음성 확인 결과로 정한다 */
    AMBIGUOUS,
}
