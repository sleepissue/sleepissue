package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.intervention.InterventionPolicyState
import com.sleepissue.awakedrive.engine.model.*

/** 실행 요청 ID·오디오·경유지 타이머는 저장하지 않는다. 판단을 이어갈 근거만 보존한다. */
data class InterventionCheckpoint(
    val policy: InterventionPolicyState,
    val assessment: DrivingAssessment,
    val tripContextAvailable: Boolean,
    val forecast: ForecastAssessment?,
    val sensitive: Boolean,
    val vulnerable: Boolean,
    val ventilationConsent: Boolean,
    val alertnessContext: CheckContext,
    val restSessionId: String?,
    val seenRestSessions: Set<String>,
    val latestRestCertificationMs: Long?,
    val recoveryCompletedAtMs: Long?,
    val routeAdded: Boolean,
    val interruptedChecks: Map<CheckContext, Long>,
    val enteredAt: Map<InterventionStage, Long>,
    val assistedAnchor: Long?,
) {
    fun requireValid(nowMs: Long, trip: TripState) {
        require(policy.publicState.stage == trip.stage) { "저장된 운행 단계와 개입 단계가 다릅니다." }
        require(policy.publicState.pendingRequestId == null) { "과거 실행 요청 ID는 복원할 수 없습니다." }
        require(policy.lastEvaluatedMs == nowMs) { "개입 평가가 저장 시각보다 오래됐습니다." }
        require(assessment.riskScore == null || assessment.riskScore.isFinite() && assessment.riskScore in 0f..100f)
        require(policy.consecutiveSlow >= 0)
        val times = listOfNotNull(policy.lastEvaluatedMs, policy.firstEvaluatedMs,
            policy.lastTransitionMs, policy.lowScoreSinceMs, assessment.tMs, forecast?.tMs,
            latestRestCertificationMs, recoveryCompletedAtMs, assistedAnchor) + enteredAt.values
        require(times.all { it in 0..nowMs }) { "개입 근거가 저장 시각 범위를 벗어났습니다." }
        require(forecast?.dueAtMs == null || forecast.dueAtMs >= 0)
        require(alertnessContext in setOf(CheckContext.DRIVER_ALERTNESS, CheckContext.PASSENGER_ALERTNESS))
        require(seenRestSessions.all { it.isNotBlank() })
        require(restSessionId == null || restSessionId in seenRestSessions && latestRestCertificationMs != null)
        require(interruptedChecks.keys.all { it in setOf(CheckContext.DRIVER_ALERTNESS,
            CheckContext.PASSENGER_ALERTNESS, CheckContext.RECOVERY) })
        require(interruptedChecks.values.all { it >= 0 })
        require(CheckContext.RECOVERY !in interruptedChecks || restSessionId != null) { "회복 확인의 인증 근거가 없습니다." }
        require(interruptedChecks.keys.none { it != CheckContext.RECOVERY } || trip.stage == InterventionStage.CHECK)
    }
}
