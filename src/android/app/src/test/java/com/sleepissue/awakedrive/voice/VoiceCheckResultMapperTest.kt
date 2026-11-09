package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.check.CheckResult as VoiceCheckResult
import com.sleepissue.awakedrive.engine.check.CheckType
import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.CheckOutcome
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.ResponseChannel
import com.sleepissue.awakedrive.engine.model.ResponseIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCheckResultMapperTest {
    @Test
    fun `음성 판정과 응답 시간을 개입 결과로 보존한다`() {
        val result = VoiceCheckResult(
            type = CheckType.VOICE,
            latencyMs = 2_300,
            verdict = CheckVerdict.SLOW,
            intent = SpeechIntent.REST,
            ts = 10_000L,
        ).toInterventionResult(RequestId("check-1"), CheckContext.DRIVER_ALERTNESS)

        assertEquals(RequestId("check-1"), result.requestId)
        assertEquals(CheckContext.DRIVER_ALERTNESS, result.context)
        assertEquals(CheckOutcome.SLOW, result.outcome)
        assertEquals(2_300L, result.responseTimeMs)
        assertEquals(ResponseIntent.REST, result.intent)
        assertEquals(ResponseChannel.VOICE, result.channel)
    }

    @Test
    fun `무응답은 응답 시간과 의도 없이 전달한다`() {
        val result = VoiceCheckResult(
            type = CheckType.VOICE,
            latencyMs = null,
            verdict = CheckVerdict.NO_RESPONSE,
            intent = SpeechIntent.NONE,
            ts = 10_000L,
        ).toInterventionResult(RequestId("check-2"), CheckContext.RECOVERY)

        assertEquals(CheckOutcome.NO_RESPONSE, result.outcome)
        assertNull(result.responseTimeMs)
        assertNull(result.intent)
    }

    @Test
    fun `출발 전 세 가지 컨디션을 공용 의도로 변환한다`() {
        assertEquals(ResponseIntent.SLEEPY, SpeechIntent.COND_SLEEPY.toResponseIntent())
        assertEquals(ResponseIntent.REFRESHED, SpeechIntent.COND_FRESH.toResponseIntent())
        assertEquals(ResponseIntent.NEUTRAL, SpeechIntent.COND_NORMAL.toResponseIntent())
    }

    @Test
    fun `동승자 음성도 음성 채널로 전달한다`() {
        val result = VoiceCheckResult(
            type = CheckType.PASSENGER,
            latencyMs = 800,
            verdict = CheckVerdict.NORMAL,
            intent = SpeechIntent.OK,
            ts = 10_000L,
        ).toInterventionResult(RequestId("check-3"), CheckContext.PASSENGER_ALERTNESS)

        assertEquals(CheckOutcome.NORMAL, result.outcome)
        assertEquals(ResponseChannel.VOICE, result.channel)
        assertEquals(ResponseIntent.OK, result.intent)
    }
}
