package com.sleepissue.awakedrive.engine.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceBaselineSamplesTest {
    @Test
    fun `정상 응답 첫 두 개만 오늘 기준선에 사용한다`() {
        val one = VoiceBaselineSamples().record(result(800, CheckVerdict.NORMAL))
        assertNull(one.todayBaselineMs())
        val two = one.record(result(1_000, CheckVerdict.NORMAL))
        assertEquals(900L, two.todayBaselineMs())
        assertEquals(two, two.record(result(2_000, CheckVerdict.NORMAL)))
    }

    @Test
    fun `느림과 무응답은 기준선 표본에서 제외한다`() {
        var samples = VoiceBaselineSamples()
        samples = samples.record(result(2_300, CheckVerdict.SLOW))
        samples = samples.record(result(null, CheckVerdict.NO_RESPONSE))
        assertEquals(emptyList<Long>(), samples.normalResponsesMs)
    }

    private fun result(latencyMs: Int?, verdict: CheckVerdict) = CheckResult(
        type = CheckType.VOICE,
        latencyMs = latencyMs,
        verdict = verdict,
        ts = 0L,
    )
}
