package com.sleepissue.awakedrive.engine.check

enum class CheckType { VOICE, PASSENGER }

enum class CheckVerdict { NORMAL, SLOW, NO_RESPONSE }

enum class SpeechIntent {
    OK,
    REST,
    NAP,
    YES,
    NO,
    VENT_OFF,
    COND_FRESH,
    COND_NORMAL,
    COND_SLEEPY,
    NONE,
}

enum class QuestionType {
    DRIVER_CHECK,
    PASSENGER_CHECK,
    WAYPOINT_CONFIRMATION,
    REST_ARRIVAL,
    PRE_DRIVE_CONDITION,
    VENTILATION_CONTROL,
}

data class CheckResult(
    val type: CheckType,
    val latencyMs: Int?,
    val verdict: CheckVerdict,
    val intent: SpeechIntent = SpeechIntent.NONE,
    val ts: Long,
) {
    val shouldEscalateToStage3: Boolean
        get() = verdict == CheckVerdict.NO_RESPONSE || intent == SpeechIntent.REST
}

data class CheckContext(
    val distToNextTurnM: Float?,
    val turnSignalOn: Boolean,
    val brakeOn: Boolean,
)

enum class CheckTarget { DRIVER_VOICE, PASSENGER, REAR_PASSENGER, DRIVER_BUTTON, PASSENGER_BUTTON }

data class OccupancyContext(
    val frontPassengerPresent: Boolean,
    val rearPassengerPresent: Boolean,
    val speechDetected: Boolean,
    val microphoneAvailable: Boolean = true,
)

data class CheckPlan(
    val target: CheckTarget,
    val useLowerVolume: Boolean,
)
