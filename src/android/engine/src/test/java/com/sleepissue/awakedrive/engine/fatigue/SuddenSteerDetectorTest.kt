package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.FatigueConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** 멈춤 뒤 급조향 시험. 기대값은 SRS v0.2 REQ_FAT_008 합격기준. */
class SuddenSteerDetectorTest {

    /**
     * 0°로 [stillMs]만큼 멈춘 뒤(마지막 멈춤 샘플 시각 = stillMs), [turnMs] 동안 일정하게 돌려 [targetDeg]에 닿고,
     * 그 뒤 2초 동안 그대로 둔다. 100 ms 간격.
     */
    private fun countSudden(stillMs: Long, turnMs: Long, targetDeg: Double): Int {
        val detector = SuddenSteerDetector(FatigueConfig())
        var count = 0
        var t = 0L
        while (t <= stillMs) {
            if (detector.add(t, 0f)) count++
            t += 100
        }
        val steps = (turnMs / 100).toInt()
        for (s in 1..steps) {
            if (detector.add(stillMs + s * 100L, (targetDeg * s / steps).toFloat())) count++
        }
        t = stillMs + turnMs + 100
        while (t <= stillMs + turnMs + 2_000) {
            if (detector.add(t, targetDeg.toFloat())) count++
            t += 100
        }
        return count
    }

    @Test
    fun stillOnlyFourSecondsIsNotCounted() {
        // 정지 4.0 s + 1 s 안 6° → 0건 (4초를 '넘게' 멈추지 않음)
        assertEquals(0, countSudden(stillMs = 4_000, turnMs = 900, targetDeg = 6.0))
    }

    @Test
    fun stillOverFourSecondsThenSixDegreesWithinASecondIsCounted() {
        // 정지 4.1 s + 0.9 s 안 6° → 1건
        assertEquals(1, countSudden(stillMs = 4_100, turnMs = 900, targetDeg = 6.0))
    }

    @Test
    fun turnBelowSixDegreesIsNotCounted() {
        // 정지 4.1 s + 1 s 안 5.9° → 0건
        assertEquals(0, countSudden(stillMs = 4_100, turnMs = 1_000, targetDeg = 5.9))
    }

    @Test
    fun turnSlowerThanOneSecondIsNotCounted() {
        // 정지 4.1 s + 1.1 s에 6° → 0건
        assertEquals(0, countSudden(stillMs = 4_100, turnMs = 1_100, targetDeg = 6.0))
    }

    @Test
    fun suddenJumpInOneSampleIsCounted() {
        // 한 샘플 만에 6° 꺾인 경우도 1건
        assertEquals(1, countSudden(stillMs = 4_100, turnMs = 100, targetDeg = 6.0))
    }
}
