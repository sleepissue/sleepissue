package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.area
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.north
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 기획서 7-1·7-2·7-3의 휴식 인증 조건을 옮긴 테스트.
class RestCertifierTest {

    private val shelter = area("A", origin)
    private val certifier = RestCertifier(listOf(shelter, area("B", north(50_000.0))))
    private val min = 60_000L

    private fun obs(
        tMs: Long,
        atM: Double? = 0.0,
        gear: Gear? = Gear.P,
        power: PowerState? = PowerState.ON,
        drive: Long? = null,
        stage: InterventionStage? = null,
    ) = RestObservation(tMs, atM?.let { north(it) }, gear, power, drive, stage)

    @Test
    fun `쉼터 150 m 안 P단 15분이면 인증, 인증 시각은 시작 + 15분`() {
        val arrived = certifier.observe(obs(0, atM = 100.0, drive = 8_000))
        assertTrue(arrived.single() is RestEvent.Arrived)
        assertTrue(certifier.observe(obs(14 * min, atM = 100.0)).isEmpty())
        val cert = certifier.observe(obs(15 * min + 500, atM = 100.0)).single() as RestEvent.Certified
        assertEquals("A", cert.area.id)
        assertEquals(15 * min, cert.certifiedAtMs)
        assertEquals(8_000L, cert.driveSecBeforeRest)
        // 같은 정차에서 두 번 인증하지 않는다
        assertTrue(certifier.observe(obs(30 * min, atM = 100.0)).isEmpty())
    }

    @Test
    fun `14분 59초에 출발하면 인증 없이 떠남`() {
        certifier.observe(obs(0))
        val left = certifier.observe(obs(15 * min - 1_000, gear = Gear.D)).single() as RestEvent.Left
        assertEquals(false, left.certified)
    }

    @Test
    fun `반경 밖 151 m에서는 시작하지 않음`() {
        assertTrue(certifier.observe(obs(0, atM = 151.0)).isEmpty())
        assertTrue(certifier.observe(obs(20 * min, atM = 151.0)).isEmpty())
        assertNull(certifier.currentArea)
    }

    @Test
    fun `D단 정차는 휴식이 아님`() {
        assertTrue(certifier.observe(obs(0, gear = Gear.D)).isEmpty())
    }

    @Test
    fun `시동을 끈 채 15분 넘게 지나 켜지면 꺼져 있던 시간으로 인증`() {
        certifier.observe(obs(0))
        // 시동 꺼짐: 위치·기어를 못 받는다 → 마지막 위치 사용
        assertTrue(certifier.observe(obs(1_000, atM = null, gear = null, power = PowerState.OFF)).isEmpty())
        val events = certifier.observe(obs(40 * min, gear = Gear.P, power = PowerState.ON))
        val cert = events.single() as RestEvent.Certified
        assertEquals(15 * min, cert.certifiedAtMs)
    }

    @Test
    fun `시동 꺼짐으로 시작해도 쉼터 안이면 휴식`() {
        certifier.observe(obs(0, gear = Gear.D)) // 쉼터 안으로 들어오며 위치를 받음
        val arrived = certifier.observe(obs(1_000, atM = null, gear = null, power = PowerState.OFF))
        assertTrue(arrived.single() is RestEvent.Arrived)
    }

    @Test
    fun `쉼터가 아닌 곳의 정차는 인증 없음`() {
        assertTrue(certifier.observe(obs(0, atM = 10_000.0)).isEmpty())
        assertTrue(certifier.observe(obs(20 * min, atM = 10_000.0)).isEmpty())
    }

    @Test
    fun `2단계 이상 알림 뒤의 휴식은 그 단계를 들고 감, 인증 뒤에는 다시 0부터`() {
        certifier.observe(obs(0, atM = 5_000.0, gear = Gear.D, stage = InterventionStage.CHECK))
        certifier.observe(obs(1_000, atM = 5_000.0, gear = Gear.D, stage = InterventionStage.NORMAL))
        certifier.observe(obs(2_000))
        // 쉬는 동안의 3단계는 세지 않음
        val cert = certifier.observe(obs(2_000 + 15 * min, stage = InterventionStage.REST_GUIDANCE)).single() as RestEvent.Certified
        assertEquals(InterventionStage.CHECK, cert.maxStageBeforeRest)

        certifier.observe(obs(20 * min, gear = Gear.D, stage = InterventionStage.NORMAL)) // 떠남
        certifier.observe(obs(21 * min, atM = 50_000.0, gear = Gear.P))
        val next = certifier.observe(obs(37 * min, atM = 50_000.0)).single() as RestEvent.Certified
        assertEquals("B", next.area.id)
        assertEquals(InterventionStage.NORMAL, next.maxStageBeforeRest)
    }

    @Test
    fun `시간이 거꾸로 온 관측은 버림`() {
        certifier.observe(obs(10 * min))
        assertTrue(certifier.observe(obs(5 * min, gear = Gear.D)).isEmpty())
        assertEquals("A", certifier.currentArea?.id)
    }
}
