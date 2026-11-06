package com.sleepissue.awakedrive.engine.check

import com.sleepissue.awakedrive.engine.config.EngineConfig

enum class RearPassengerAction { WAIT, START_DRIVER_CHECK, USE_STEERING_ONLY }

class PassengerCheckPolicy(private val config: EngineConfig = EngineConfig()) {
    fun rearPassengerAction(
        waitStartedAtMs: Long,
        nowMs: Long,
        conversationSilentSinceMs: Long?,
    ): RearPassengerAction {
        if (nowMs - waitStartedAtMs >= config.rearPassengerMaximumWaitMs) {
            return RearPassengerAction.USE_STEERING_ONLY
        }
        if (conversationSilentSinceMs != null &&
            nowMs - conversationSilentSinceMs >= config.conversationSilenceForDriverCheckMs
        ) {
            return RearPassengerAction.START_DRIVER_CHECK
        }
        return RearPassengerAction.WAIT
    }

    fun passengerRecheckAtMs(
        result: SpeechIntent,
        trendContinues: Boolean,
        nowMs: Long,
    ): Long? = if (result == SpeechIntent.OK && trendContinues) {
        nowMs + config.passengerTrendRecheckMs
    } else {
        null
    }
}
