package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 기획서 7-1 파워냅: 원할 때만, P단에서만, 15분 알람 + 회복 확인.
class NapTimerTest {

    private val timer = NapTimer()
    private val min = 60_000L

    @Test
    fun `P단이 아니면 시작하지 않음`() {
        assertEquals(NapStart.Rejected(NapRejectReason.NOT_PARKED), timer.start(0, Gear.D, atRestArea = true))
        assertEquals(NapStart.Rejected(NapRejectReason.NOT_PARKED), timer.start(0, null, atRestArea = true))
    }

    @Test
    fun `쉼터가 아니면 안전 확인 안내부터`() {
        val started = timer.start(0, Gear.P, atRestArea = false) as NapStart.Started
        assertTrue(started.needsSafetyNotice)
        assertEquals(15 * min, started.endsAtMs)
        assertEquals(NapStart.Rejected(NapRejectReason.ALREADY_RUNNING), timer.start(1, Gear.P, atRestArea = false))
    }

    @Test
    fun `15분에 알람, 끄면 회복 확인`() {
        timer.start(0, Gear.P, atRestArea = true)
        assertNull(timer.tick(15 * min - 1, Gear.P))
        assertEquals(NapEvent.AlarmDue, timer.tick(15 * min, Gear.P))
        assertNull(timer.tick(15 * min + 1_000, Gear.P)) // 알람은 한 번만 시작
        assertEquals(NapEvent.Ended(NapEndReason.ALARM_STOPPED, recoveryCheck = true), timer.wake())
        assertEquals(NapState.Idle, timer.state)
    }

    @Test
    fun `지금 깨우기`() {
        timer.start(0, Gear.P, atRestArea = true)
        assertEquals(10 * min, timer.remainingMs(5 * min))
        assertEquals(NapEvent.Ended(NapEndReason.WOKE_EARLY, recoveryCheck = true), timer.wake())
    }

    @Test
    fun `D단으로 바꾸면 끝, 정차 중 회복 확인은 하지 않음`() {
        timer.start(0, Gear.P, atRestArea = true)
        assertNull(timer.tick(1 * min, null)) // 기어를 못 받으면 단을 바꾼 것으로 보지 않음
        assertEquals(NapEvent.Ended(NapEndReason.DROVE_OFF, recoveryCheck = false), timer.tick(2 * min, Gear.D))
    }

    @Test
    fun `아무도 안 끄면 알람 한도 뒤 멈추고 회복 확인`() {
        timer.start(0, Gear.P, atRestArea = true)
        timer.tick(15 * min, Gear.P)
        assertNull(timer.tick(15 * min + 119_999, Gear.P))
        assertEquals(NapEvent.Ended(NapEndReason.ALARM_TIMEOUT, recoveryCheck = true), timer.tick(17 * min, Gear.P))
    }
}
