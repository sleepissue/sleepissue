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

    /**
     * 꺾임이 끝나는 샘플들(1.55°, 1.08°, 0.615°) 뒤에 [stillMs] 동안 0.148° → 0.005°로 거의 멈췄다가 −12°.
     * 11/10 drowsy 데모 17:30 장면을 줄인 것이다. 100 ms 간격.
     */
    private fun countAfterRampTail(stillMs: Long): Int {
        val detector = SuddenSteerDetector(FatigueConfig())
        var count = 0
        listOf(1.55, 1.08, 0.615).forEachIndexed { i, deg -> if (detector.add(i * 100L, deg.toFloat())) count++ }
        val steps = (stillMs / 100).toInt()
        for (i in 0..steps) {
            if (detector.add(300L + i * 100L, (0.148 - 0.143 * i / steps).toFloat())) count++
        }
        if (detector.add(300L + stillMs + 100L, -12f)) count++
        return count
    }

    @Test
    fun rampTailDoesNotCutTheStillApart() {
        // 꺾임 끝 0.615° 샘플이 멈춤 맨 앞에 남으면 0.115° 아래로 내려갈 때 멈춤이 끊겨 4.4 s가 1.8 s + 2.6 s로 나뉘었다(고치기 전 0건)
        assertEquals(1, countAfterRampTail(stillMs = 4_400))
    }

    @Test
    fun rampTailIsNotCountedAsStill() {
        // 꺾임 끝 샘플은 멈춤 길이에 넣지 않는다: 0.148°부터 정확히 4.0 s → 0건
        assertEquals(0, countAfterRampTail(stillMs = 4_000))
    }
}
