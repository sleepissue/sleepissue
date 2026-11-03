package com.sleepissue.awakedrive.engine.intervention

import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.check.SpeechIntent

enum class InterventionStage(val level: Int) {
    NORMAL(0),
    CAUTION(1),
    CHECK(2),
    REST(3),
}

enum class PatternTrend { NONE, SPIKE, TREND }

sealed interface InterventionEvent {
    val nowMs: Long

    data class RiskUpdated(
        override val nowMs: Long,
        val score: Int,
        val trend: PatternTrend,
        val sensitiveMode: Boolean = false,
        val stage1ContextReached: Boolean = false,
        val suddenCorrection: Boolean = false,
    ) : InterventionEvent

    data class CheckCompleted(
        override val nowMs: Long,
        val verdict: CheckVerdict,
        val intent: SpeechIntent,
        val trend: PatternTrend,
        val score: Int,
    ) : InterventionEvent

    data class PassengerAnswered(
        override val nowMs: Long,
        val restRequested: Boolean,
        val trendContinues: Boolean,
    ) : InterventionEvent

    data class SafetyBypass(override val nowMs: Long) : InterventionEvent
    data class RestRecovered(override val nowMs: Long) : InterventionEvent
}

data class InterventionState(
    val stage: InterventionStage = InterventionStage.NORMAL,
    val lastStageChangeAtMs: Long? = null,
    val belowCurrentStageSinceMs: Long? = null,
    val consecutiveSlowChecks: Int = 0,
)

enum class InterventionReason {
    RISK_SCORE,
    STAGE1_CONTEXT,
    SUDDEN_CORRECTION,
    NORMAL_CHECK,
    TWO_SLOW_CHECKS,
    NO_RESPONSE,
    DRIVER_REQUESTED_REST,
    PASSENGER_REQUESTED_REST,
    SAFETY_BYPASS,
    REST_RECOVERED,
}

data class InterventionDecision(
    val state: InterventionState,
    val changed: Boolean,
    val reason: InterventionReason?,
    val recheckAtMs: Long? = null,
)
