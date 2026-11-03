package com.sleepissue.awakedrive.engine.fatigue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3분 지수평균 시험. 기대값은 SRS v0.2 REQ_FAT_020 합격기준·비고. */
class RiskSmootherTest {

    @Test
    fun stepToHundredReachesSixtyThreeAfterThreeMinutes() {
        // R을 0→100으로 고정 입력 시 180 s 후 평활값 63.2 ±1 (시정수 180 s, 갱신 30 s, α=0.1535) (REQ_FAT_020)
        val smoother = RiskSmoother()
        assertEquals(0f, smoother.value, 0f)
        val values = (1..6).map { smoother.update(100f) }
        assertEquals("첫 갱신 = 100 × α", 15.35f, values.first(), 0.01f)
        assertEquals(63.2f, values.last(), 1f)
        assertTrue(values.zipWithNext().all { (before, after) -> after > before })
    }

    @Test
    fun steadySixtyOneCrossesSixtyOnlyAfterTwelveAndAHalfMinutes() {
        // SRS REQ_FAT_020 비고: 순간 점수 61이 계속 나와도 평활값이 60을 넘기까지 약 12~13분 걸림
        // → '졸음' 데모 시나리오는 순간 점수가 60을 충분히 넘게(예: 70 이상) 만든다
        val smoother = RiskSmoother()
        repeat(24) { smoother.update(61f) } // 12분
        assertTrue("12분: ${smoother.value}", smoother.value < 60f)
        smoother.update(61f) // 12분 30초
        assertTrue("12분 30초: ${smoother.value}", smoother.value >= 60f)
    }

    @Test
    fun resetStartsFromZeroAgain() {
        val smoother = RiskSmoother()
        repeat(3) { smoother.update(80f) }
        smoother.reset()
        assertEquals(0f, smoother.value, 0f)
        assertEquals(15.35f, smoother.update(100f), 0.01f)
    }
}
