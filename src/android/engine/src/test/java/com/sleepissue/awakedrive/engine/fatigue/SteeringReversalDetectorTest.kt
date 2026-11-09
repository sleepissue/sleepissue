package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.FatigueConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** 미세 수정·큰 수정 세기 시험. 기대값은 SRS v0.2 REQ_FAT_006·007 합격기준. */
class SteeringReversalDetectorTest {

    private val config = FatigueConfig()

    private fun countOf(swings: List<Double>, kind: SwingClass) = swings.count { config.swingClassOf(it) == kind }

    @Test
    fun microCountsSwingsFromHalfToThreeDegrees() {
        // 극값 간 변화량 0.4, 0.5, 2.0, 3.0, 3.1° 입력 시 미세 수정 3건(0.5, 2.0, 3.0) (REQ_FAT_006)
        // 0.4°는 반대쪽으로 0.5° 되돌아오지 않아 방향 전환으로 보지 않는다
        val path = steeringPath(0.0, 0.4, 0.0, 0.5, -1.5, 1.5, -1.6, -1.0)
        val swings = swingsOf(path)
        assertEquals(listOf(0.5, 2.0, 3.0, 3.1), swings.map { Math.round(it * 10) / 10.0 })
        assertEquals(3, countOf(swings, SwingClass.MICRO))
        assertEquals(0, countOf(swings, SwingClass.LARGE))
    }

    @Test
    fun largeCountsSwingsFromFiveDegrees() {
        // 변화량 4.9, 5.0, 8.0° 입력 시 큰 수정 2건(5.0, 8.0), 3.1~4.9°는 어느 쪽에도 계수하지 않음 (REQ_FAT_007)
        val path = steeringPath(0.0, 4.9, -0.1, 7.9, 7.0)
        val swings = swingsOf(path)
        assertEquals(listOf(4.9, 5.0, 8.0), swings.map { Math.round(it * 10) / 10.0 })
        assertEquals(2, countOf(swings, SwingClass.LARGE))
        assertEquals(0, countOf(swings, SwingClass.MICRO))
    }

    @Test
    fun betweenThreeAndFiveDegreesIsNeither() {
        assertEquals(SwingClass.NONE, config.swingClassOf(3.1))
        assertEquals(SwingClass.NONE, config.swingClassOf(4.9))
        assertEquals(SwingClass.NONE, config.swingClassOf(0.4))
    }

    @Test
    fun resetForgetsLastExtremum() {
        // 게이트를 못 넘은 구간을 건너 각도를 이으면 가짜 조향이 생기므로 reset 뒤에는 새로 시작한다
        val detector = SteeringReversalDetector(reversalMinDeg = 0.5)
        steeringPath(0.0, 2.0).forEachIndexed { i, a -> detector.add(i * 100L, a) }
        detector.reset()
        val afterReset = steeringPath(8.0, 7.0).mapIndexedNotNull { i, a -> detector.add(10_000L + i * 100L, a) }
        assertEquals("reset 뒤 8°에서 새로 시작했으므로 0°→8° 조향은 생기지 않아야 함", 0, afterReset.size)
    }
}
