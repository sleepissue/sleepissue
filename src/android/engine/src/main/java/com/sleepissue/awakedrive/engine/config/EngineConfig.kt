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
 * 공통 계약과 운행·개입 규칙의 수치를 모은다.
 * 정차 보정은 호출자가 실행 계획의 정책을 명시적으로 선택할 때만 적용한다.
 * 미확정 최신성·혼합 출처 유지시간에는 임의 기본값을 두지 않는다.
 */
data class EngineConfig(
    val signalFrameIntervalMs: Long = 100,
    val stateSaveIntervalMs: Long = 10_000,
    val normalStageThresholds: StageThresholds = StageThresholds(40, 60, 80),
    val sensitiveStageThresholds: StageThresholds = StageThresholds(30, 50, 70),
    // 실행 계획 v0.3 9-2: P단·전원 OFF가 이어진 정차 구간의 경계다.
    val tripStopPauseMs: Long = 300_000,
    val tripRestResetMs: Long = 900_000,
    // 현재기능 기획서 7-2: 정확히 2시간은 같은 운행, 초과하면 새 운행이다.
    val newTripAfterOffMs: Long = 7_200_000,
    // 실행 계획 v0.3 9-3: 날짜가 아닌 최근 구간을 합산한다.
    val rollingDriveWindowMs: Long = 86_400_000,
    val interventionStepMs: Long = 30_000,
    val interventionLowScoreHoldMs: Long = 600_000,
    val normalDriveCautionSec: Long = 7_200,
    val sensitiveDriveCautionSec: Long = 3_600,
    val slowCheckCountForRest: Int = 2,
    val slowCheckRetryMs: Long = 30_000,
    val trendCheckRetryMs: Long = 600_000,
    val stageNoticeCooldownMs: Long = 600_000,
    val contextCautionReminderMs: Long = 1_800_000,
    val restGuidanceReminderMs: Long = 300_000,
    val waypointRefusalRetryMs: Long = 300_000,
    val waypointAutoAddGraceMs: Long = 10_000,
    val assistedCheckIntervalMs: Long = 1_200_000,
    val assistedRiskCheckIntervalMs: Long = 600_000,
    val assistedOperationGapSec: Long = 900, // [가설] 조작 공백은 2번이 계산해 전달한다.
    val restDistanceReminderM: Float = 2_000f,
) {
    init {
        require(signalFrameIntervalMs > 0) { "신호 발행 간격은 양수여야 합니다." }
        require(stateSaveIntervalMs > 0) { "상태 저장 간격은 양수여야 합니다." }
        require(tripStopPauseMs > 0 && tripStopPauseMs < tripRestResetMs) {
            "정차 일시 중단 경계는 양수이며 연속 시간 초기화 경계보다 작아야 합니다."
        }
        require(newTripAfterOffMs >= tripRestResetMs) {
            "새 운행의 OFF 경계는 연속 시간 초기화 경계 이상이어야 합니다."
        }
        require(rollingDriveWindowMs > 0) { "누적 운전 시간 창은 양수여야 합니다." }
        require(rollingDriveWindowMs >= tripStopPauseMs) {
            "잠정 정차 구간을 보관할 수 있도록 누적 창은 짧은 정차 경계 이상이어야 합니다."
        }
        require(listOf(interventionStepMs, interventionLowScoreHoldMs, normalDriveCautionSec,
            sensitiveDriveCautionSec, slowCheckRetryMs, trendCheckRetryMs, stageNoticeCooldownMs,
            contextCautionReminderMs, restGuidanceReminderMs, waypointRefusalRetryMs,
            waypointAutoAddGraceMs, assistedCheckIntervalMs, assistedRiskCheckIntervalMs,
            assistedOperationGapSec).all { it > 0 }) { "개입의 시간 경계는 양수여야 합니다." }
        require(slowCheckCountForRest > 0) { "느림 확인 횟수는 양수여야 합니다." }
        require(restDistanceReminderM.isFinite() && restDistanceReminderM > 0) { "쉼터 재안내 거리는 양수여야 합니다." }
    }
}
