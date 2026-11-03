package com.sleepissue.awakedrive.engine.intervention

import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionPolicyTest {
    private val policy = InterventionPolicy()

    @Test
    fun `위험 점수로는 30초마다 한 단계만 오른다`() {
        val first = policy.reduce(
            InterventionState(),
            risk(nowMs = 0L, score = 90, trend = PatternTrend.TREND),
        )
        assertEquals(InterventionStage.CAUTION, first.state.stage)

        val tooSoon = policy.reduce(first.state, risk(29_999L, 90, PatternTrend.TREND))
        assertEquals(InterventionStage.CAUTION, tooSoon.state.stage)

        val next = policy.reduce(tooSoon.state, risk(30_000L, 90, PatternTrend.TREND))
        assertEquals(InterventionStage.CHECK, next.state.stage)
    }

    @Test
    fun `민감 모드는 30점부터 1단계다`() {
        val decision = policy.reduce(
            InterventionState(),
            risk(0L, 30, PatternTrend.NONE, sensitiveMode = true),
        )
        assertEquals(InterventionStage.CAUTION, decision.state.stage)
    }

    @Test
    fun `느림 두 번은 즉시 3단계로 간다`() {
        val state = InterventionState(stage = InterventionStage.CHECK)
        val first = policy.reduce(state, check(1_000L, CheckVerdict.SLOW))
        assertEquals(31_000L, first.recheckAtMs)
        assertFalse(first.changed)

        val second = policy.reduce(first.state, check(31_000L, CheckVerdict.SLOW))
        assertEquals(InterventionStage.REST, second.state.stage)
        assertEquals(InterventionReason.TWO_SLOW_CHECKS, second.reason)
    }

    @Test
    fun `무응답과 쉴게와 안전 우회는 즉시 3단계다`() {
        val state = InterventionState(stage = InterventionStage.CAUTION, lastStageChangeAtMs = 1_000L)
        assertEquals(
            InterventionStage.REST,
            policy.reduce(state, check(2_000L, CheckVerdict.NO_RESPONSE)).state.stage,
        )
        assertEquals(
            InterventionStage.REST,
            policy.reduce(state, check(2_000L, CheckVerdict.NORMAL, SpeechIntent.REST)).state.stage,
        )
        assertEquals(
            InterventionStage.REST,
            policy.reduce(state, InterventionEvent.SafetyBypass(2_000L)).state.stage,
        )
    }

    @Test
    fun `정상 응답과 튐형이면 1단계로 내린다`() {
        val decision = policy.reduce(
            InterventionState(stage = InterventionStage.CHECK),
            check(10_000L, CheckVerdict.NORMAL, trend = PatternTrend.SPIKE),
        )
        assertEquals(InterventionStage.CAUTION, decision.state.stage)
    }

    @Test
    fun `동승자가 괜찮다고 해도 추세가 이어지면 10분 뒤 재확인한다`() {
        val decision = policy.reduce(
            InterventionState(stage = InterventionStage.CHECK),
            InterventionEvent.PassengerAnswered(10_000L, restRequested = false, trendContinues = true),
        )
        assertEquals(610_000L, decision.recheckAtMs)
    }

    @Test
    fun `같은 단계는 10분 동안 알리지 않고 3단계는 5분 뒤 재안내한다`() {
        val notification = StageNotificationPolicy()
        assertFalse(notification.shouldNotify(InterventionStage.CHECK, 599_999L, 0L))
        assertTrue(notification.shouldNotify(InterventionStage.CHECK, 600_000L, 0L))
        assertFalse(notification.shouldNotify(InterventionStage.REST, 299_999L, 0L))
        assertTrue(notification.shouldNotify(InterventionStage.REST, 300_000L, 0L))
    }

    private fun risk(
        nowMs: Long,
        score: Int,
        trend: PatternTrend,
        sensitiveMode: Boolean = false,
    ) = InterventionEvent.RiskUpdated(nowMs, score, trend, sensitiveMode)

    private fun check(
        nowMs: Long,
        verdict: CheckVerdict,
        intent: SpeechIntent = SpeechIntent.OK,
        trend: PatternTrend = PatternTrend.TREND,
    ) = InterventionEvent.CheckCompleted(nowMs, verdict, intent, trend, score = 64)
}
