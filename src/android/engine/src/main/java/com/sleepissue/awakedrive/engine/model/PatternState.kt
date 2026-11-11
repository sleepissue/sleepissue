package com.sleepissue.awakedrive.engine.model

/**
 * PatternClassifier가 30초마다 내보내는 딴짓·졸음 패턴. (SRS Data Interface MSG-03)
 *
 * 받는 쪽: 단계 전환(INT), 화면(UI).
 * 단계 전환에서 쓰는 법 (REQ_PAT_004·006·007은 1번 InterventionPolicy가 이 값과 음성 확인 결과로 판단)
 * - trending = (patternType == TREND)
 * - 확인 정상 + SPIKE·AMBIGUOUS → 졸음 아님(딴짓). 확인 정상 + TREND → 2단계 유지, 10분 뒤 다시 확인
 * - 딴짓 기록(REQ_PAT_004)은 2번이 한다: 연결기가 확인 결과를 엔진에 넣으면 [distractionCount]가 오른다
 * - safetyBypass = true → 원인과 상관없이 3단계
 */
data class PatternState(
    /** 최근 10분 가운데 '이상 분'의 수, 0~10. (REQ_PAT_001) */
    val abnormalMinutes10m: Int = 0,
    /** 이상이 이어지는 모양. (REQ_PAT_002·003) */
    val patternType: PatternType = PatternType.NONE,
    /**
     * 5분 안에 급조향 3회. 원인을 따지지 않고 3단계로 보내는 안전 우회 신호. (REQ_PAT_005)
     * 3회째 급조향부터 5분 동안 true로 유지한다 [제안값] (PatternConfig.safetyBypassHoldMs).
     */
    val safetyBypass: Boolean = false,
    /**
     * 이번 운행에서 딴짓으로 기록한 횟수 (REQ_PAT_004). 운전자·동승자 주의 확인이 정상이었고 그 뒤 패턴이 튐형으로
     * 확인된 경우다. 새 운행·데모 다시 재생 때 0. 단계 하강은 1번 InterventionPolicy가 따로 한다.
     */
    val distractionCount: Int = 0,
    /** 마지막으로 딴짓으로 기록한 확인(정상 대답)의 시각(ms). 없으면 null (REQ_PAT_004) */
    val lastDistractionMs: Long? = null,
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
