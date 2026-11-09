package com.sleepissue.awakedrive.engine.check

import com.sleepissue.awakedrive.engine.config.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckPolicyTest {
    private val policy = CheckPolicy(EngineConfig())

    @Test
    fun `기준 0점9초의 1점5배 경계는 정상이고 초과하면 느림이다`() {
        assertEquals(CheckVerdict.NORMAL, policy.verdict(1_350L, 900L))
        assertEquals(CheckVerdict.SLOW, policy.verdict(1_360L, 900L))
    }

    @Test
    fun `절대 2초 경계는 정상이고 초과하면 느림이다`() {
        assertEquals(CheckVerdict.NORMAL, policy.verdict(2_000L, 2_000L))
        assertEquals(CheckVerdict.SLOW, policy.verdict(2_100L, 2_000L))
    }

    @Test
    fun `5초 안에 목소리가 시작되지 않으면 무응답이다`() {
        assertEquals(CheckVerdict.NO_RESPONSE, policy.verdict(null, 900L))
        assertEquals(CheckVerdict.NO_RESPONSE, policy.verdict(5_000L, 900L))
    }

    @Test
    fun `느림이면 30초 뒤 한 번 더 확인한다`() {
        val slow = result(CheckVerdict.SLOW, ts = 10_000L)
        assertEquals(40_000L, policy.nextRecheckAtMs(slow))
        assertNull(policy.nextRecheckAtMs(result(CheckVerdict.NORMAL)))
        assertTrue(policy.twoConsecutiveSlow(slow, result(CheckVerdict.SLOW)))
        assertFalse(policy.twoConsecutiveSlow(slow, result(CheckVerdict.NORMAL)))
    }

    @Test
    fun `회전 거리와 차량 조작이 안전할 때만 확인을 시작한다`() {
        assertFalse(policy.canStart(context(distanceM = 299f)))
        assertTrue(policy.canStart(context(distanceM = 300f)))
        assertTrue(policy.canStart(context(distanceM = null)))
        assertFalse(policy.canStart(context(turnSignalOn = true)))
        assertFalse(policy.canStart(context(brakeOn = true)))
    }

    @Test
    fun `회전 거리로 미룬 확인은 최대 20초 뒤 진행하지만 차량 조작 중이면 끝날 때까지 기다린다`() {
        val unsafeTurn = context(distanceM = 299f)
        assertFalse(policy.canStartAfterDeferral(0L, 19_999L, unsafeTurn))
        assertTrue(policy.canStartAfterDeferral(0L, 20_000L, unsafeTurn))
        assertFalse(
            policy.canStartAfterDeferral(
                0L,
                20_000L,
                context(distanceM = 299f, turnSignalOn = true),
            ),
        )
    }

    @Test
    fun `동승자와 대화가 있어도 확인 대상은 항상 운전자다`() {
        assertEquals(
            CheckPlan(CheckTarget.DRIVER_VOICE, useLowerVolume = false),
            policy.selectPlan(OccupancyContext(false, false, false)),
        )
        assertEquals(
            CheckPlan(CheckTarget.DRIVER_VOICE, useLowerVolume = true),
            policy.selectPlan(OccupancyContext(true, false, false)),
        )
        assertEquals(CheckTarget.DRIVER_VOICE, policy.selectPlan(OccupancyContext(true, false, true)).target)
        assertEquals(CheckTarget.DRIVER_VOICE, policy.selectPlan(OccupancyContext(false, true, true)).target)
    }

    @Test
    fun `마이크를 쓸 수 없을 때도 운전자 대체 입력만 쓴다`() {
        assertEquals(
            CheckTarget.DRIVER_BUTTON,
            policy.selectPlan(OccupancyContext(false, false, false, microphoneAvailable = false)).target,
        )
        assertEquals(
            CheckTarget.DRIVER_BUTTON,
            policy.selectPlan(OccupancyContext(true, false, false, microphoneAvailable = false)).target,
        )
    }

    private fun result(verdict: CheckVerdict, ts: Long = 0L) = CheckResult(
        type = CheckType.VOICE,
        latencyMs = null,
        verdict = verdict,
        ts = ts,
    )

    private fun context(
        distanceM: Float? = 500f,
        turnSignalOn: Boolean = false,
        brakeOn: Boolean = false,
    ) = CheckContext(
        distToNextTurnM = distanceM,
        turnSignalOn = turnSignalOn,
        brakeOn = brakeOn,
    )
}
