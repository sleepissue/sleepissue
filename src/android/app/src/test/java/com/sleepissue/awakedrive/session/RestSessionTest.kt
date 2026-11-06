package com.sleepissue.awakedrive.session

import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.rest.NapEndReason
import com.sleepissue.awakedrive.engine.rest.NapEvent
import com.sleepissue.awakedrive.engine.rest.NapStart
import com.sleepissue.awakedrive.engine.rest.NapTimer
import com.sleepissue.awakedrive.engine.rest.RestArea
import com.sleepissue.awakedrive.engine.rest.RestAreaKind
import com.sleepissue.awakedrive.engine.rest.RestCertifier
import com.sleepissue.awakedrive.engine.rest.RestEvent
import com.sleepissue.awakedrive.engine.rest.RestObservation
import com.sleepissue.awakedrive.engine.rest.RewardOutcome
import com.sleepissue.awakedrive.signal.ClimateAction
import com.sleepissue.awakedrive.signal.ClimatePort
import com.sleepissue.awakedrive.signal.ClimateRequest
import com.sleepissue.awakedrive.signal.FakeClock
import com.sleepissue.awakedrive.signal.RestCertification
import com.sleepissue.awakedrive.voice.VoiceScripts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 기획서 7장 흐름: 쉼터 도착 → 15분 → 인증(1번에 전달)·스탬프·통풍 되돌리기 / 파워냅 15분 알람 → 회복 확인.
@OptIn(ExperimentalCoroutinesApi::class)
class RestSessionTest {

    private val here = GeoPoint(36.5, 127.4)
    private val shelter = RestArea(
        id = "DS-001", name = "옥산졸음쉼터", kind = RestAreaKind.DROWSY_SHELTER, location = here,
        routeName = "경부선", directionLabel = "서울방향", carriagewayBearingDeg = 0f,
    )
    private val clock = FakeClock(wallMs = 1_793_000_000_000L) // 2026-11월쯤
    private val min = 60_000L

    private class FakeClimate : ClimatePort {
        val requests = mutableListOf<ClimateRequest>()
        override val results = MutableSharedFlow<ExecutionResult>()
        override suspend fun start(request: ClimateRequest): ExecutionResult {
            requests += request
            return ExecutionResult(request.requestId, ExecutionStatus.REQUESTED)
        }
        override suspend fun cancel(requestId: RequestId) = ExecutionResult(requestId, ExecutionStatus.CANCELLED)
    }

    private fun obs(tMs: Long, gear: Gear? = Gear.P, power: PowerState = PowerState.ON, drive: Long? = 8_000) =
        RestObservation(tMs, here, gear, power, drive, InterventionStage.NORMAL)

    @Test
    fun `15분 휴식 - 1번에 인증 전달, 스탬프 기록, 통풍 되돌리기`() = runTest {
        val store = InMemoryRestRecordStore()
        val climate = FakeClimate()
        val monitor = RestSessionMonitor(RestCertifier(listOf(shelter)), RewardLedger(store, clock), clock, climate)
        val certs = mutableListOf<RestCertification>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.certifications.toList(certs) }

        assertTrue(monitor.observe(obs(0), Source.DEMO).single() is RestEvent.Arrived)
        assertEquals("옥산졸음쉼터", monitor.ui.value.areaName)
        monitor.observe(obs(1_000, gear = null, power = PowerState.OFF), Source.DEMO) // 시동 끔
        monitor.observe(obs(20 * min), Source.DEMO) // 20분 뒤 시동 켬

        assertEquals(RestCertification("DS-001@0", 15 * min, Source.DEMO), certs.single())
        val record = store.all().single()
        assertEquals(RewardOutcome.AWARDED, record.outcome) // 2시간 넘게(8,000초) 운전 뒤
        assertTrue(monitor.ui.value.certified)
        assertEquals(ClimateAction.RESTORE_DRIVER_VENTILATION, climate.requests.single().action)
    }

    @Test
    fun `같은 휴식은 장부에 한 번만`() = runTest {
        val store = InMemoryRestRecordStore()
        val ledger = RewardLedger(store, clock)
        val certifier = RestCertifier(listOf(shelter))
        certifier.observe(obs(0))
        val cert = certifier.observe(obs(15 * min)).single() as RestEvent.Certified
        ledger.record(cert)
        ledger.record(cert)
        assertEquals(1, store.all().size)
        assertEquals(1, ledger.stamps.first().size)
    }

    @Test
    fun `짧게 운전하고 알림도 없던 휴식은 인증만, 스탬프 없음`() = runTest {
        val store = InMemoryRestRecordStore()
        val monitor = RestSessionMonitor(RestCertifier(listOf(shelter)), RewardLedger(store, clock), clock)
        monitor.observe(obs(0, drive = 1_800), Source.REAL)
        monitor.observe(obs(15 * min, drive = 0), Source.REAL)
        assertEquals(RewardOutcome.NOT_ELIGIBLE, store.all().single().outcome)
        assertTrue(RewardLedger(store, clock).stamps.first().isEmpty())
    }

    private class FakeAlarm : NapAlarm {
        var ringing = false
        override fun start(rampMs: Long, maxMs: Long) { ringing = true }
        override fun stop() { ringing = false }
    }

    @Test
    fun `파워냅 - 쉼터 밖이면 안전 안내 먼저, 15분 알람, 끄면 회복 확인`() = runTest {
        val spoken = mutableListOf<String>()
        val alarm = FakeAlarm()
        val nap = NapSession(NapTimer(), alarm, { spoken += it }, clock)
        val events = mutableListOf<NapEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { nap.events.toList(events) }

        assertTrue(nap.request(Gear.P, atRestArea = false) is NapStart.Started)
        assertEquals(listOf(VoiceScripts.NAP_SAFETY_CHECK, VoiceScripts.NAP_START), spoken)

        clock.scenarioMs = 15 * min
        nap.tick(Gear.P)
        assertTrue(alarm.ringing)
        nap.wake()
        assertTrue(!alarm.ringing)
        assertEquals(listOf(NapEvent.AlarmDue, NapEvent.Ended(NapEndReason.ALARM_STOPPED, recoveryCheck = true)), events)
    }

    @Test
    fun `파워냅 - 주행 중 요청은 거절하고 말하지 않음`() = runTest {
        val spoken = mutableListOf<String>()
        val nap = NapSession(NapTimer(), FakeAlarm(), { spoken += it }, clock)
        assertTrue(nap.request(Gear.D, atRestArea = true) is NapStart.Rejected)
        assertTrue(spoken.isEmpty())
    }
}
