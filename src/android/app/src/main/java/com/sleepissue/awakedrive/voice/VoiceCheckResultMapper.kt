package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.check.CheckResult as VoiceCheckResult
import com.sleepissue.awakedrive.engine.check.CheckType
import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.CheckOutcome
import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.ResponseChannel
import com.sleepissue.awakedrive.engine.model.ResponseIntent

/** 3번 내부 음성 판정을 1번 개입 관리자 공용 계약으로 바꾼다. */
internal fun VoiceCheckResult.toInterventionResult(
    requestId: RequestId,
    context: CheckContext,
): CheckResult = CheckResult(
    requestId = requestId,
    context = context,
    outcome = when (verdict) {
        CheckVerdict.NORMAL -> CheckOutcome.NORMAL
        CheckVerdict.SLOW -> CheckOutcome.SLOW
        CheckVerdict.NO_RESPONSE -> CheckOutcome.NO_RESPONSE
    },
    responseTimeMs = latencyMs?.toLong(),
    intent = intent.toResponseIntent(),
    channel = when (type) {
        CheckType.VOICE -> ResponseChannel.VOICE
        CheckType.PASSENGER -> ResponseChannel.VOICE
    },
)

internal fun SpeechIntent.toResponseIntent(): ResponseIntent? = when (this) {
    SpeechIntent.OK -> ResponseIntent.OK
    SpeechIntent.REST -> ResponseIntent.REST
    SpeechIntent.NAP -> ResponseIntent.NAP
    SpeechIntent.YES -> ResponseIntent.YES
    SpeechIntent.NO -> ResponseIntent.NO
    SpeechIntent.VENT_OFF -> ResponseIntent.VENT_OFF
    SpeechIntent.COND_FRESH -> ResponseIntent.REFRESHED
    SpeechIntent.COND_NORMAL -> ResponseIntent.NEUTRAL
    SpeechIntent.COND_SLEEPY -> ResponseIntent.SLEEPY
    SpeechIntent.NONE -> null
}
