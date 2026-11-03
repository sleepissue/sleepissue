package com.sleepissue.awakedrive.engine.intervention

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*

/** SRS v0.2 INT_004의 적용 기준은 IMMEDIATE다. AFTER_INTERVAL은 기존 비교 시험용이다. */
enum class FirstEscalation { IMMEDIATE, AFTER_INTERVAL }

data class InterventionSnapshot(
    val tMs: Long,
    val evaluation: EvaluationStatus = EvaluationStatus.NOT_EVALUATED,
    val score: Float? = null,
    val trending: Boolean = false,
    val continuousDriveSec: Long? = null,
    val forecastDue: Boolean = false,
    val vulnerableTime: Boolean = false,
    val sensitive: Boolean = false,
    val checkRequired: Boolean = false,
    val safetyBypass: Boolean = false,
    val confirmation: CheckResult? = null,
    val recoveredAfterCertifiedRest: Boolean = false,
    val restCertified: Boolean = false,
) {
    init {
        require(tMs >= 0) { "개입 논리 시각은 음수일 수 없습니다." }
        require(continuousDriveSec == null || continuousDriveSec >= 0) { "연속 시간은 음수일 수 없습니다." }
    }
}

data class InterventionPolicyState(
    val publicState: InterventionState = InterventionState(),
    val lastEvaluatedMs: Long? = null,
    val firstEvaluatedMs: Long? = null,
    val lastTransitionMs: Long? = null,
    val lowScoreSinceMs: Long? = null,
    val normalCheckReceived: Boolean = false,
    val consecutiveSlow: Int = 0,
    val cautionHeldUntilRest: Boolean = false,
    val pendingCheckEscalation: Boolean = false,
)

data class StageTransition(
    val tMs: Long,
    val from: InterventionStage,
    val to: InterventionStage,
    val reason: TransitionReason,
    val sensitive: Boolean,
)

data class InterventionDecision(val state: InterventionPolicyState, val transition: StageTransition?)

/** 확정된 단계 규칙만 계산한다. 음성 판정·실행·요청 ID 검증은 외부 담당의 책임이다. */
class InterventionPolicy(
    private val config: EngineConfig,
    private val firstEscalation: FirstEscalation = FirstEscalation.IMMEDIATE,
) {
    fun evaluate(previous: InterventionPolicyState, input: InterventionSnapshot): InterventionDecision {
        require(previous.lastEvaluatedMs == null || input.tMs >= previous.lastEvaluatedMs) {
            "개입 논리 시각이 역행했습니다."
        }
        val thresholds = if (input.sensitive) config.sensitiveStageThresholds else config.normalStageThresholds
        val score = input.score?.takeIf { input.evaluation == EvaluationStatus.EVALUATED && it.isFinite() && it in 0f..100f }
        val evaluation = when {
            score != null -> EvaluationStatus.EVALUATED
            input.evaluation == EvaluationStatus.NOT_EVALUATED -> EvaluationStatus.NOT_EVALUATED
            else -> EvaluationStatus.UNAVAILABLE
        }
        val oldStage = previous.publicState.stage
        var stage = oldStage
        var reason = previous.publicState.reason
        val alertness = input.confirmation?.takeIf {
            it.context == CheckContext.DRIVER_ALERTNESS || it.context == CheckContext.PASSENGER_ALERTNESS
        }
        var normal = previous.normalCheckReceived
        var slow = previous.consecutiveSlow
        when (alertness?.outcome) {
            CheckOutcome.NORMAL -> { normal = true; slow = 0 }
            CheckOutcome.SLOW -> { normal = false; slow = slow.coerceAtMost(config.slowCheckCountForRest - 1) + 1 }
            else -> Unit // 장치 오류·취소를 사람의 정상 또는 무응답으로 바꾸지 않는다.
        }
        val timeDue = input.continuousDriveSec?.let {
            it >= if (input.sensitive) config.sensitiveDriveCautionSec else config.normalDriveCautionSec
        } ?: false
        // 인증은 과거 맥락의 유지 조건만 푼다. 현재 점수·맥락·확인 요청은 다시 평가한다.
        val certifiedHeldCaution = input.restCertified && oldStage == InterventionStage.CAUTION &&
            previous.cautionHeldUntilRest
        var held = (!input.restCertified && previous.cautionHeldUntilRest) ||
            timeDue || input.forecastDue || input.vulnerableTime
        var pendingCheck = previous.pendingCheckEscalation || input.checkRequired
        val humanRest = alertness != null && (alertness.intent == ResponseIntent.REST ||
            alertness.outcome == CheckOutcome.NO_RESPONSE || slow >= config.slowCheckCountForRest)
        val lowThreshold = if (oldStage == InterventionStage.CHECK) thresholds.check else thresholds.caution
        var lowSince = if (score != null && score < lowThreshold) previous.lowScoreSinceMs ?: input.tMs else null

        if (input.safetyBypass || humanRest) {
            stage = InterventionStage.REST_GUIDANCE
            reason = if (input.safetyBypass) TransitionReason.SAFETY_EVENT else TransitionReason.CHECK_RESULT
        } else if (input.recoveredAfterCertifiedRest) {
            stage = InterventionStage.NORMAL
            reason = TransitionReason.REST_COMPLETED
            held = false; pendingCheck = false; normal = false; slow = 0; lowSince = null
        } else if (oldStage != InterventionStage.REST_GUIDANCE) {
            val target = when {
                score != null && score >= thresholds.restGuidance && input.trending -> InterventionStage.REST_GUIDANCE
                pendingCheck || score != null && score >= thresholds.check -> InterventionStage.CHECK
                held || score != null && score >= thresholds.caution -> InterventionStage.CAUTION
                else -> InterventionStage.NORMAL
            }
            val anchor = previous.lastTransitionMs ?: previous.firstEvaluatedMs ?: input.tMs
            val mayRise = if (previous.lastTransitionMs == null && firstEscalation == FirstEscalation.IMMEDIATE) true
                else input.tMs - anchor >= config.interventionStepMs
            if (certifiedHeldCaution && target == InterventionStage.NORMAL) {
                stage = InterventionStage.NORMAL
                reason = TransitionReason.REST_COMPLETED
            } else if (target.level > oldStage.level && mayRise) {
                stage = InterventionStage.entries[oldStage.level + 1]
                reason = when {
                    // 일반 경로의 3단계 목표는 점수·추세형으로만 정해진다.
                    // 2단계 확인 대기나 1단계 시간·예보가 그 상승 원인을 덮지 않게 한다.
                    target == InterventionStage.REST_GUIDANCE -> TransitionReason.SCORE
                    pendingCheck -> TransitionReason.SAFETY_EVENT
                    timeDue -> TransitionReason.TIME
                    input.forecastDue || input.vulnerableTime -> TransitionReason.FORECAST
                    else -> TransitionReason.SCORE
                }
            } else if (target.level < oldStage.level && lowSince != null && input.tMs - lowSince >= config.interventionLowScoreHoldMs) {
                if (oldStage == InterventionStage.CAUTION && !held) {
                    stage = InterventionStage.NORMAL; reason = TransitionReason.SCORE
                } else if (oldStage == InterventionStage.CHECK && normal && !input.trending) {
                    stage = InterventionStage.CAUTION; reason = TransitionReason.CHECK_RESULT
                }
            }
        }
        if (certifiedHeldCaution && stage == InterventionStage.CAUTION) {
            // 단계가 같아도 인증 뒤의 실제 유지 사유로 갱신한다.
            reason = when {
                pendingCheck -> TransitionReason.SAFETY_EVENT
                timeDue -> TransitionReason.TIME
                input.forecastDue || input.vulnerableTime -> TransitionReason.FORECAST
                else -> TransitionReason.SCORE
            }
        }
        val changed = stage != oldStage
        if (stage.level >= InterventionStage.CHECK.level) pendingCheck = false
        if (changed) lowSince = null
        if (stage != InterventionStage.CHECK) normal = false
        val public = previous.publicState.copy(stage = stage, reason = reason,
            evaluationStatus = if (input.recoveredAfterCertifiedRest && stage == InterventionStage.NORMAL) EvaluationStatus.NOT_EVALUATED else evaluation)
        return InterventionDecision(
            previous.copy(publicState = public, lastEvaluatedMs = input.tMs,
                firstEvaluatedMs = previous.firstEvaluatedMs ?: input.tMs,
                lastTransitionMs = if (changed) input.tMs else previous.lastTransitionMs,
                lowScoreSinceMs = lowSince, normalCheckReceived = normal, consecutiveSlow = slow,
                cautionHeldUntilRest = held, pendingCheckEscalation = pendingCheck),
            if (changed) StageTransition(input.tMs, oldStage, stage, requireNotNull(reason), input.sensitive) else null,
        )
    }
}
