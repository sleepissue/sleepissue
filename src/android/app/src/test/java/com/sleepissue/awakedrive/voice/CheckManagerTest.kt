package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser
import com.sleepissue.awakedrive.signal.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckManagerTest {
    @Test
    fun `STT 완료가 늦어져도 목소리 시작 시각으로 판정한다`() {
        val clock = FakeClock()
        val manager = CheckManager(clock, CheckPolicy(), SpeechIntentParser())

        manager.onBeepEnded()
        clock.elapsedMs = 2_300L
        manager.onVoiceStarted()
        clock.elapsedMs = 3_300L // STT 처리에 1초가 더 걸림

        val result = manager.finish(900L, QuestionType.DRIVER_CHECK, "네")

        assertEquals(2_300, result.latencyMs)
        assertEquals(CheckVerdict.SLOW, result.verdict)
        assertEquals(SpeechIntent.OK, result.intent)
    }

    @Test
    fun `쉴게는 응답 속도와 별개로 3단계 조건이다`() {
        val clock = FakeClock()
        val manager = CheckManager(clock, CheckPolicy(), SpeechIntentParser())
        manager.onBeepEnded()
        clock.elapsedMs = 500L
        manager.onVoiceStarted()

        val result = manager.finish(900L, QuestionType.DRIVER_CHECK, "쉴게")

        assertEquals(CheckVerdict.NORMAL, result.verdict)
        assertEquals(SpeechIntent.REST, result.intent)
        assertEquals(true, result.shouldEscalateToStage3)
    }
}
