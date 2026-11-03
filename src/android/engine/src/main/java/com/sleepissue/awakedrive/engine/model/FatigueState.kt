package com.sleepissue.awakedrive.engine.model

/**
 * FatigueEngine이 30초마다 내보내는 조향 지표와 위험 점수. (SRS Data Interface MSG-02)
 *
 * 받는 쪽: 단계 전환(INT), 화면(UI).
 * ROS로 치면 FatigueEngine 노드가 30초 주기로 발행하는 토픽 메시지다.
 *
 * 단계 판단에는 [riskSmoothed]를 쓴다. [valid]가 false면 지표·점수 값으로 판단하지 않는다.
 * valid가 false일 때 f값·B·R은 0(그 창은 계산하지 않음)이고, riskSmoothed는 마지막으로 갱신한 값 그대로다 [해석].
 */
data class FatigueState(
    /**
     * 이 값을 계산한 게이트 판정 시각(ms). 주입된 Clock 기준이며, 데모 중에는 시나리오 시계다.
     * 게이트가 판정을 10초 늦게 확정하므로 실제 시각보다 10초 늦다 (FatigueEngine 참고).
     */
    val tMs: Long,
    /** 미세 수정(방향이 바뀌는 0.5~3° 조향) 분당 횟수, 최근 60초 창. (REQ_FAT_006) */
    val microPerMin: Float = 0f,
    /** 큰 수정(방향이 바뀌는 5° 이상 조향) 분당 횟수. (REQ_FAT_007) */
    val largePerMin: Float = 0f,
    /** 최근 5분 동안의 멈춤 뒤 급조향 횟수. (REQ_FAT_008) */
    val suddenCount5m: Int = 0,
    /** f미세, 0~1. (REQ_FAT_009) */
    val fMicro: Float = 0f,
    /** f큰, 0~1. (REQ_FAT_010) */
    val fLarge: Float = 0f,
    /** f급조향, 0~1. (REQ_FAT_011) */
    val fSudden: Float = 0f,
    /** 행동 점수 B, 0~100. (REQ_FAT_012) */
    val behaviorB: Float = 0f,
    /** 맥락 가중 K, 1.00~1.35. (REQ_FAT_013) */
    val contextK: Float = 1f,
    /** 순간 위험 점수 R = min(100, B×K), 0~100. (REQ_FAT_012) */
    val riskR: Float = 0f,
    /** R의 3분 지수평균, 0~100. 단계 판단에 쓰는 값. (REQ_FAT_020) */
    val riskSmoothed: Float = 0f,
    /** 지표가 유효한지. 보조 주행 중이거나 게이트를 못 넘었으면 false. (REQ_AST_004) */
    val valid: Boolean = false,
    /**
     * [valid]가 false인 이유. 화면이 '측정 대기'와 '보조 주행(회색)'을 구분하는 데 쓴다. (REQ_UI_003)
     * valid=true일 때만 NONE이다.
     * Data Interface 시트의 기본값은 NONE이지만 valid 기본값(false)과 맞지 않아 GATE_WAIT로 뒀다.
     */
    val invalidReason: InvalidReason = InvalidReason.GATE_WAIT,
)

/** [FatigueState.valid]가 false인 이유. */
enum class InvalidReason {
    /** 지표가 유효함 (valid = true) */
    NONE,

    /** 게이트를 통과한 구간이 아직 부족함. 화면에는 '측정 대기' */
    GATE_WAIT,

    /** 보조 주행 모드라 조향을 판단에서 뺌. 화면에는 회색 게이지 */
    ASSIST,
}
