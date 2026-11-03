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
        assertFalse(policy.canStart(CheckContext(299f, turnSignalOn = false, brakeOn = false)))
        assertTrue(policy.canStart(CheckContext(300f, turnSignalOn = false, brakeOn = false)))
        assertTrue(policy.canStart(CheckContext(null, turnSignalOn = false, brakeOn = false)))
        assertFalse(policy.canStart(CheckContext(500f, turnSignalOn = true, brakeOn = false)))
        assertFalse(policy.canStart(CheckContext(500f, turnSignalOn = false, brakeOn = true)))
    }

    @Test
    fun `회전 거리로 미룬 확인은 최대 60초 뒤 진행하지만 차량 조작 중이면 끝날 때까지 기다린다`() {
        val unsafeTurn = CheckContext(299f, turnSignalOn = false, brakeOn = false)
        assertFalse(policy.canStartAfterDeferral(0L, 59_999L, unsafeTurn))
        assertTrue(policy.canStartAfterDeferral(0L, 60_000L, unsafeTurn))
        assertFalse(
            policy.canStartAfterDeferral(
                0L,
                60_000L,
                CheckContext(299f, turnSignalOn = true, brakeOn = false),
            ),
        )
    }

    @Test
    fun `좌석과 대화 여부에 따라 확인 대상을 고른다`() {
        assertEquals(
            CheckPlan(CheckTarget.DRIVER_VOICE, useLowerVolume = false),
            policy.selectPlan(OccupancyContext(false, false, false)),
        )
        assertEquals(
            CheckPlan(CheckTarget.DRIVER_VOICE, useLowerVolume = true),
            policy.selectPlan(OccupancyContext(true, false, false)),
        )
        assertEquals(
            CheckTarget.PASSENGER,
            policy.selectPlan(OccupancyContext(true, false, true)).target,
        )
        assertEquals(
            CheckTarget.REAR_PASSENGER,
            policy.selectPlan(OccupancyContext(false, true, true)).target,
        )
    }

    @Test
    fun `마이크를 쓸 수 없으면 좌석 여부에 맞는 버튼을 쓴다`() {
        assertEquals(
            CheckTarget.DRIVER_BUTTON,
            policy.selectPlan(OccupancyContext(false, false, false, microphoneAvailable = false)).target,
        )
        assertEquals(
            CheckTarget.PASSENGER_BUTTON,
            policy.selectPlan(OccupancyContext(true, false, false, microphoneAvailable = false)).target,
        )
    }

    private fun result(verdict: CheckVerdict, ts: Long = 0L) = CheckResult(
        type = CheckType.VOICE,
        latencyMs = null,
        verdict = verdict,
        ts = ts,
    )
}
