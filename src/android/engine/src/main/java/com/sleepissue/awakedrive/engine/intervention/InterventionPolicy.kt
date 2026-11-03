package com.sleepissue.awakedrive.engine.intervention

import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import com.sleepissue.awakedrive.engine.config.EngineConfig

/** Android 실행부와 분리된 0~3단계 전환 규칙이다. */
class InterventionPolicy(private val config: EngineConfig = EngineConfig()) {
    fun reduce(state: InterventionState, event: InterventionEvent): InterventionDecision = when (event) {
        is InterventionEvent.RiskUpdated -> onRisk(state, event)
        is InterventionEvent.CheckCompleted -> onCheck(state, event)
        is InterventionEvent.PassengerAnswered -> onPassenger(state, event)
        is InterventionEvent.SafetyBypass -> immediateRest(state, event.nowMs, InterventionReason.SAFETY_BYPASS)
        is InterventionEvent.RestRecovered -> transition(
            state,
            InterventionStage.NORMAL,
            event.nowMs,
            InterventionReason.REST_RECOVERED,
            consecutiveSlowChecks = 0,
        )
    }

    private fun onRisk(
        state: InterventionState,
        event: InterventionEvent.RiskUpdated,
    ): InterventionDecision {
        val offset = if (event.sensitiveMode) config.sensitiveThresholdOffset else 0
        val stage1 = config.normalStage1Threshold - offset
        val stage2 = config.normalStage2Threshold - offset
        val stage3 = config.normalStage3Threshold - offset
        val target = when {
            event.score >= stage3 && event.trend == PatternTrend.TREND -> InterventionStage.REST
            event.score >= stage2 || event.suddenCorrection -> InterventionStage.CHECK
            event.score >= stage1 || event.stage1ContextReached -> InterventionStage.CAUTION
            else -> InterventionStage.NORMAL
        }

        if (target.level > state.stage.level) {
            if (!canPromote(state, event.nowMs)) return unchanged(state)
            val next = InterventionStage.entries.first { it.level == state.stage.level + 1 }
            return transition(
                state,
                next,
                event.nowMs,
                if (event.stage1ContextReached && next == InterventionStage.CAUTION) {
                    InterventionReason.STAGE1_CONTEXT
                } else if (event.suddenCorrection && next == InterventionStage.CHECK) {
                    InterventionReason.SUDDEN_CORRECTION
                } else {
                    InterventionReason.RISK_SCORE
                },
            )
        }

        if (target.level >= state.stage.level || state.stage == InterventionStage.REST) {
            return unchanged(state.copy(belowCurrentStageSinceMs = null))
        }
        val since = state.belowCurrentStageSinceMs ?: event.nowMs
        if (event.nowMs - since < config.stageRecoveryHoldMs) {
            return unchanged(state.copy(belowCurrentStageSinceMs = since))
        }
        val lower = InterventionStage.entries.first { it.level == state.stage.level - 1 }
        return transition(state, lower, event.nowMs, InterventionReason.RISK_SCORE)
    }

    private fun onCheck(
        state: InterventionState,
        event: InterventionEvent.CheckCompleted,
    ): InterventionDecision {
        if (event.intent == SpeechIntent.REST) {
            return immediateRest(state, event.nowMs, InterventionReason.DRIVER_REQUESTED_REST)
        }
        if (event.verdict == CheckVerdict.NO_RESPONSE) {
            return immediateRest(state, event.nowMs, InterventionReason.NO_RESPONSE)
        }
        if (event.verdict == CheckVerdict.SLOW) {
            val count = state.consecutiveSlowChecks + 1
            if (count >= 2) {
                return immediateRest(
                    state.copy(consecutiveSlowChecks = count),
                    event.nowMs,
                    InterventionReason.TWO_SLOW_CHECKS,
                )
            }
            return unchanged(
                state.copy(consecutiveSlowChecks = count),
                recheckAtMs = event.nowMs + config.voiceRecheckDelayMs,
            )
        }

        val reset = state.copy(consecutiveSlowChecks = 0)
        return if (event.trend == PatternTrend.SPIKE && state.stage.level > InterventionStage.CAUTION.level) {
            transition(reset, InterventionStage.CAUTION, event.nowMs, InterventionReason.NORMAL_CHECK)
        } else {
            unchanged(reset)
        }
    }

    private fun onPassenger(
        state: InterventionState,
        event: InterventionEvent.PassengerAnswered,
    ): InterventionDecision {
        if (event.restRequested) {
            return immediateRest(state, event.nowMs, InterventionReason.PASSENGER_REQUESTED_REST)
        }
        return unchanged(
            state.copy(consecutiveSlowChecks = 0),
            recheckAtMs = if (event.trendContinues) event.nowMs + config.passengerTrendRecheckMs else null,
        )
    }

    private fun canPromote(state: InterventionState, nowMs: Long): Boolean =
        state.lastStageChangeAtMs == null ||
            nowMs - state.lastStageChangeAtMs >= config.stagePromotionIntervalMs

    private fun immediateRest(
        state: InterventionState,
        nowMs: Long,
        reason: InterventionReason,
    ) = transition(state, InterventionStage.REST, nowMs, reason, consecutiveSlowChecks = 0)

    private fun transition(
        state: InterventionState,
        target: InterventionStage,
        nowMs: Long,
        reason: InterventionReason,
        consecutiveSlowChecks: Int = state.consecutiveSlowChecks,
    ) = InterventionDecision(
        state = state.copy(
            stage = target,
            lastStageChangeAtMs = nowMs,
            belowCurrentStageSinceMs = null,
            consecutiveSlowChecks = consecutiveSlowChecks,
        ),
        changed = target != state.stage,
        reason = reason,
    )

    private fun unchanged(
        state: InterventionState,
        recheckAtMs: Long? = null,
    ) = InterventionDecision(state, changed = false, reason = null, recheckAtMs = recheckAtMs)
}

class StageNotificationPolicy(private val config: EngineConfig = EngineConfig()) {
    fun shouldNotify(stage: InterventionStage, nowMs: Long, lastNotifiedAtMs: Long?): Boolean {
        if (lastNotifiedAtMs == null) return true
        val interval = if (stage == InterventionStage.REST) {
            config.stage3RenotificationMs
        } else {
            config.sameStageNotificationCooldownMs
        }
        return nowMs - lastNotifiedAtMs >= interval
    }
}
