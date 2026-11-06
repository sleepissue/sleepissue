package com.sleepissue.awakedrive.engine.check

import com.sleepissue.awakedrive.engine.config.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceBaselineTest {
    private val baseline = VoiceBaseline(EngineConfig(defaultVoiceBaselineMs = 1_000L))

    @Test
    fun `평소 기록이 있으면 그 값을 우선한다`() {
        assertEquals(900L, baseline.resolve(900L, listOf(800L, 1_000L)))
    }

    @Test
    fun `평소 기록이 없으면 오늘 첫 두 번의 평균을 쓴다`() {
        assertEquals(900L, baseline.resolve(null, listOf(800L, 1_000L, 2_000L)))
    }

    @Test
    fun `오늘 기록도 두 번 미만이면 기본값을 쓴다`() {
        assertEquals(1_000L, baseline.resolve(null, listOf(800L)))
    }
}
