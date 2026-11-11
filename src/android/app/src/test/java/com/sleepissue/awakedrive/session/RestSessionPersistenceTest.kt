package com.sleepissue.awakedrive.session

import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.rest.RestArea
import com.sleepissue.awakedrive.engine.rest.RestAreaKind
import com.sleepissue.awakedrive.engine.rest.RestCertifier
import com.sleepissue.awakedrive.engine.rest.RestEvent
import com.sleepissue.awakedrive.engine.rest.RestObservation
import com.sleepissue.awakedrive.signal.FakeClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 기획서 7-3: 쉼터에서 시동을 끄고 앱이 완전히 내려가도, 다시 켜지면 15분을 이어서 센다.
class RestSessionPersistenceTest {
    private val area = RestArea(id = "DS-001", name = "옥산졸음쉼터", kind = RestAreaKind.DROWSY_SHELTER, location = GeoPoint(36.59, 127.4), routeName = "경부선", directionLabel = "서울방향", carriagewayBearingDeg = 0f)
    private val min = 60_000L

    private fun monitor(clock: FakeClock, store: RestStateStore) =
        RestSessionMonitor(RestCertifier(listOf(area)), RewardLedger(InMemoryRestRecordStore(), clock), clock, stateStore = store)

    private fun obs(t: Long, location: GeoPoint? = area.location) = RestObservation(t, location, Gear.P, PowerState.ON)

    @Test
    fun `도착 뒤 프로세스 재시작, 새 모니터가 이어서 인증한다`() = runTest {
        val clock = FakeClock()
        val store = InMemoryRestStateStore()
        val before = monitor(clock, store)
        before.observe(obs(0), Source.REAL)
        before.observe(obs(3 * min), Source.REAL)

        clock.scenarioMs = 16 * min
        val after = monitor(clock, store)
        assertEquals("옥산졸음쉼터", after.ui.value.areaName) // 휴식 화면도 이어서
        val events = after.observe(obs(16 * min, location = null), Source.REAL)
        assertTrue(events.single() is RestEvent.Certified)
        assertTrue(after.ui.value.certified)
    }

    @Test
    fun `시각만 흐르면 10초 간격으로만 저장한다`() = runTest {
        val clock = FakeClock()
        val store = InMemoryRestStateStore()
        val m = monitor(clock, store)
        m.observe(obs(0, GeoPoint(36.0, 127.0)), Source.REAL)
        for (s in 1..20) m.observe(obs(s * 1_000L, GeoPoint(36.0, 127.0)), Source.REAL)
        assertEquals(3, store.saves) // 0초, 10초, 20초
        m.observe(obs(21_000), Source.REAL) // 쉼터 도착은 바로 저장
        assertEquals(4, store.saves)
    }
}
