package com.sleepissue.awakedrive.engine.config

/** 현재기능 기획서 6-1의 단계 점수다. 이 자료형은 단계 전환을 실행하지 않는다. */
data class StageThresholds(val caution: Int, val check: Int, val restGuidance: Int) {
    init {
        require(caution in 0..100 && check in 0..100 && restGuidance in 0..100) {
            "단계 점수는 0~100 범위여야 합니다."
        }
        require(caution < check && check < restGuidance) { "단계 점수는 오름차순이어야 합니다." }
    }
}

/**
 * A의 공통 계약에 필요한 현재 기획 수치만 모은다.
 * 미확정 최신성·혼합 출처 유지시간·정차 보정 정책은 임의 기본값으로 활성화하지 않는다.
 */
data class EngineConfig(
    val signalFrameIntervalMs: Long = 100,
    val stateSaveIntervalMs: Long = 10_000,
    val normalStageThresholds: StageThresholds = StageThresholds(40, 60, 80),
    val sensitiveStageThresholds: StageThresholds = StageThresholds(30, 50, 70),
) {
    init {
        require(signalFrameIntervalMs > 0) { "신호 발행 간격은 양수여야 합니다." }
        require(stateSaveIntervalMs > 0) { "상태 저장 간격은 양수여야 합니다." }
    }
}
