package com.sleepissue.awakedrive.engine.fatigue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 60초 창·30초 갱신·분당 환산 시험. 기대값은 SRS v0.2 REQ_FAT_005·011 합격기준. */
class SteeringMetricsTest {

    private fun passed(tMs: Long, deg: Float) = GateDecision(tMs, deg, GateReason.PASS)
    private fun excluded(tMs: Long, deg: Float) = GateDecision(tMs, deg, GateReason.LOW_SPEED)

    /** 100 ms 간격 판정을 모두 넣고 나온 지표를 돌려준다 */
    private fun run(decisions: List<GateDecision>): List<SteeringIndicators> {
        val metrics = SteeringMetrics()
        return decisions.mapNotNull { metrics.accept(it) }
    }

    /** ±1° 삼각파: 1초마다 방향이 바뀌는 2° 조향(미세 수정) */
    private fun zigzagDeg(tMs: Long): Float {
        val phase = (tMs % 2_000L) / 1_000.0
        return (if (phase < 1.0) -1.0 + 2.0 * phase else 3.0 - 2.0 * phase).toFloat()
    }

    @Test
    fun updatesEveryThirtySecondsAfterFirstMinute() {
        // 300 s 연속 통과 입력 시 산출 9회(t=60, 90, …, 300 s), 갱신 간격 30 s, 창 길이 60 s (REQ_FAT_005)
        val out = run((0L..300_000L step 100L).map { passed(it, 0f) })
        assertEquals((60_000L..300_000L step 30_000L).toList(), out.map { it.tMs })
        assertTrue(out.all { it.gatedMsInWindow == 60_000L && it.valid })
    }

    @Test
    fun countsMicroCorrectionsPerMinute() {
        // 1초마다 2° 방향 전환 → 분당 약 60회
        val out = run((0L..120_000L step 100L).map { passed(it, zigzagDeg(it)) })
        val atTwoMinutes = out.last()
        assertEquals(60f, atTwoMinutes.microPerMin, 2f)
        assertEquals(0f, atTwoMinutes.largePerMin, 0f)
    }

    @Test
    fun scalesToPerMinuteWhenOnlyPartOfWindowPassed() {
        // 창 60초 중 45초만 통과해도 분당으로 환산하면 같은 운전은 같은 값 (REQ_FAT_005 "분당 횟수를 환산")
        val decisions = (0L..120_000L step 100L).map { t ->
            if (t in 60_000L..75_000L) excluded(t, zigzagDeg(t)) else passed(t, zigzagDeg(t))
        }
        val atNinety = run(decisions).first { it.tMs == 90_000L }
        assertEquals(45_000.0, atNinety.gatedMsInWindow.toDouble(), 200.0)
        assertEquals(60f, atNinety.microPerMin, 3f)
        assertTrue(atNinety.valid)
    }

    @Test
    fun tooLittleGatedTimeIsNotValid() {
        // 창 안에서 통과 시간이 30초 미만이면 이번 갱신은 쓰지 않음 [제안값] (REQ_FAT_005 비고)
        val decisions = (0L..60_000L step 100L).map { t -> if (t < 40_000L) excluded(t, 0f) else passed(t, 0f) }
        val atSixty = run(decisions).single()
        assertEquals(20_000L, atSixty.gatedMsInWindow)
        assertFalse(atSixty.valid)
    }

    @Test
    fun doesNotJoinAnglesAcrossExcludedGap() {
        // 0°로 달리다 게이트 밖(10~20 s)에서 8°가 되고, 다시 통과 구간에서 7°로 돌아옴.
        // 끊긴 구간을 건너 0°→8°를 큰 수정으로 세면 안 된다
        val decisions = (0L..60_000L step 100L).map { t ->
            when {
                t < 10_000L -> passed(t, 0f)
                t < 20_000L -> excluded(t, 8f)
                t < 40_000L -> passed(t, 8f)
                else -> passed(t, 7f)
            }
        }
        assertEquals(0f, run(decisions).single().largePerMin, 0f)
    }

    @Test
    fun suddenSteerCountKeepsOnlyLastFiveMinutes() {
        // 4.2 s에 멈춤 뒤 급조향 1건. 300 s(295.8 s 전) → 셈, 330 s(325.8 s 전) → 제외 (REQ_FAT_011 "5분 1초 전 급조향은 제외")
        val decisions = (0L..330_000L step 100L).map { t -> passed(t, if (t <= 4_100L) 0f else 6f) }
        val out = run(decisions).associateBy { it.tMs }
        assertEquals(1, out.getValue(300_000L).suddenCount5m)
        assertEquals(0, out.getValue(330_000L).suddenCount5m)
        // 60초 창 안의 횟수(이상 분 판정용, REQ_PAT_001): 창 (0, 60] → 1, 창 (30, 90] → 0
        assertEquals(1, out.getValue(60_000L).suddenInWindow)
        assertEquals(0, out.getValue(90_000L).suddenInWindow)
    }
}
