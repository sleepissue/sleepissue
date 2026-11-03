package com.sleepissue.awakedrive.engine.check

import com.sleepissue.awakedrive.engine.config.EngineConfig
import kotlin.math.roundToLong

class CheckPolicy(private val config: EngineConfig = EngineConfig()) {
    fun verdict(responseLatencyMs: Long?, baselineMs: Long): CheckVerdict {
        if (responseLatencyMs == null || responseLatencyMs >= config.voiceResponseTimeoutMs) {
            return CheckVerdict.NO_RESPONSE
        }

        val relativeLimitMs = (baselineMs * config.voiceSlowRatio).roundToLong()
        return if (
            responseLatencyMs > relativeLimitMs ||
            responseLatencyMs > config.voiceSlowAbsoluteMs
        ) {
            CheckVerdict.SLOW
        } else {
            CheckVerdict.NORMAL
        }
    }

    fun nextRecheckAtMs(result: CheckResult): Long? =
        if (result.verdict == CheckVerdict.SLOW) result.ts + config.voiceRecheckDelayMs else null

    fun twoConsecutiveSlow(previous: CheckResult?, current: CheckResult): Boolean =
        previous?.verdict == CheckVerdict.SLOW && current.verdict == CheckVerdict.SLOW

    fun canStart(context: CheckContext): Boolean {
        val turnIsSafe = context.distToNextTurnM == null ||
            context.distToNextTurnM >= config.minimumTurnDistanceForCheckM
        return turnIsSafe && !context.turnSignalOn && !context.brakeOn
    }

    /** 20초가 지나면 거리 조건은 풀되, 실제 방향지시등·제동이 끝나기 전에는 묻지 않는다. */
    fun canStartAfterDeferral(
        requestedAtMs: Long,
        nowMs: Long,
        context: CheckContext,
    ): Boolean {
        if (context.turnSignalOn || context.brakeOn) return false
        if (nowMs - requestedAtMs >= config.maximumCheckDeferralMs) return true
        return canStart(context)
    }

    fun selectPlan(context: OccupancyContext): CheckPlan {
        val passengerPresent = context.frontPassengerPresent || context.rearPassengerPresent
        if (!context.microphoneAvailable) {
            return CheckPlan(
                target = if (passengerPresent) CheckTarget.PASSENGER_BUTTON else CheckTarget.DRIVER_BUTTON,
                useLowerVolume = passengerPresent,
            )
        }

        return when {
            context.speechDetected && context.frontPassengerPresent ->
                CheckPlan(CheckTarget.PASSENGER, useLowerVolume = false)
            context.speechDetected && context.rearPassengerPresent ->
                CheckPlan(CheckTarget.REAR_PASSENGER, useLowerVolume = false)
            else -> CheckPlan(CheckTarget.DRIVER_VOICE, useLowerVolume = passengerPresent)
        }
    }
}
