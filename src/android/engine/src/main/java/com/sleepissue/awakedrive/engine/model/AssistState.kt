package com.sleepissue.awakedrive.engine.model

/**
 * AssistModeDetector가 신호마다(100 ms) 내보내는 보조 주행 판정. (SRS Data Interface MSG-04)
 *
 * 받는 쪽: FatigueEngine(보조 주행이면 조향 계산을 멈춤), 단계 전환(INT), 화면(UI).
 */
data class AssistState(
    /** 보조 주행 모드인지. true면 조향을 졸음 판단에서 빼고 정기 확인으로 판단한다. (REQ_AST_001·003·004) */
    val assistMode: Boolean = false,
    /** 보조 주행으로 본 이유. */
    val reason: AssistReason = AssistReason.NONE,
    /** 보조 주행이 꺼진 직후 30초 안에 인계 품질 이상이 있었는지. true면 2단계로. (REQ_AST_007) */
    val handoverAbnormal: Boolean = false,
    /** 조작 공백(방향지시등·브레이크·크루즈 속도·공조·통풍 조작이 없던 시간), 초. (REQ_AST_006, 2차) */
    val opGapSec: Int = 0,
)

/** 보조 주행으로 본 이유. */
enum class AssistReason {
    /** 보조 주행 아님 */
    NONE,

    /** 크루즈 작동 중 (REQ_AST_001) */
    CRUISE,

    /** 조향이 기계처럼 매끈함 (REQ_AST_002, 2차) */
    SMOOTH_STEERING,

    /** 조향 값을 받지 못해 판정할 수 없음 → 보조 주행으로 간주 (REQ_AST_003) */
    UNCERTAIN,
}
