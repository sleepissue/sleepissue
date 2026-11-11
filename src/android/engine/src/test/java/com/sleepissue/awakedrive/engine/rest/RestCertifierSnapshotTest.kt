package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.area
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.north
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 기획서 7-3 "시동을 껐다 켜도 끊김 없음" — 재부팅(앱 프로세스 재시작) 뒤에도 15분을 이어서 센다.
class RestCertifierSnapshotTest {
    private val areas = listOf(area("A", origin), area("B", north(50_000.0)))
    private val min = 60_000L

    private fun obs(tMs: Long, gear: Gear? = Gear.P, power: PowerState? = PowerState.ON, stage: InterventionStage? = null) =
        RestObservation(tMs, north(100.0), gear, power, 9_000, stage)

    @Test
    fun `쉼터 도착 5분 뒤 재부팅, 켜진 뒤 15분이 차면 바로 인증`() {
        val before = RestCertifier(areas)
        before.observe(obs(0, stage = InterventionStage.REST_GUIDANCE)) // 단계 기록용 (쉼터 밖이 아니어도 첫 관측에서 반영)
        before.observe(obs(5 * min))
        val saved = before.snapshot().encode()

        val after = RestCertifier(areas)
        assertTrue(after.restore(RestCertifierSnapshot.decode(saved)!!, nowMs = 16 * min))
        assertEquals("A", after.currentArea?.id)
        // 꺼져 있던 11분도 휴식 시간 — 위치 없이 켜져도 마지막 위치로 이어 간다
        val events = after.observe(RestObservation(16 * min, null, Gear.P, PowerState.ON))
        val cert = events.single() as RestEvent.Certified
        assertEquals(15 * min, cert.certifiedAtMs)
        assertEquals(9_000L, cert.driveSecBeforeRest)
    }

    @Test
    fun `인증된 상태도 이어 가 같은 정차에서 두 번 인증하지 않는다`() {
        val before = RestCertifier(areas)
        before.observe(obs(0)); before.observe(obs(15 * min))
        assertTrue(before.currentCertified)
        val after = RestCertifier(areas)
        assertTrue(after.restore(before.snapshot(), nowMs = 20 * min))
        assertTrue(after.observe(obs(20 * min)).isEmpty())
    }

    @Test
    fun `저장 시각이 지금보다 미래면 되돌리지 않는다`() {
        val before = RestCertifier(areas)
        before.observe(obs(10 * min))
        val after = RestCertifier(areas)
        assertFalse(after.restore(before.snapshot(), nowMs = 5 * min))
        assertNull(after.currentArea)
    }

    @Test
    fun `쉼터 표에 없는 쉼터면 되돌리지 않는다`() {
        val before = RestCertifier(areas)
        before.observe(obs(0))
        val after = RestCertifier(listOf(area("Z", north(90_000.0))))
        assertFalse(after.restore(before.snapshot(), nowMs = 1 * min))
    }

    @Test
    fun `인코딩은 왕복해도 같고 깨진 값은 null`() {
        val before = RestCertifier(areas)
        before.observe(obs(0, gear = Gear.D, stage = InterventionStage.CHECK))
        before.observe(obs(1 * min))
        val snapshot = before.snapshot()
        assertEquals(snapshot, RestCertifierSnapshot.decode(snapshot.encode()))
        assertEquals(InterventionStage.CHECK, snapshot.maxStageBeforeRest)
        assertNull(RestCertifierSnapshot.decode("v=1\nstartedAtMs=abc"))
        assertNull(RestCertifierSnapshot.decode("v=99"))
        assertNull(RestCertifierSnapshot.decode(null))
        assertEquals(RestCertifierSnapshot(), RestCertifierSnapshot.decode(RestCertifierSnapshot().encode()))
    }
}
