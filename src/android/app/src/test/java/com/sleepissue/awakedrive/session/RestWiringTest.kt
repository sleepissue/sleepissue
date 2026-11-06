package com.sleepissue.awakedrive.session

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.CheckOutcome
import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.PowerEvent
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.ResponseChannel
import com.sleepissue.awakedrive.engine.model.ResponseIntent
import com.sleepissue.awakedrive.engine.model.SafetyEvent
import com.sleepissue.awakedrive.engine.model.SafetyEventType
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.rest.NapState
import com.sleepissue.awakedrive.engine.rest.NapTimer
import com.sleepissue.awakedrive.engine.rest.RestArea
import com.sleepissue.awakedrive.engine.rest.RestAreaKind
import com.sleepissue.awakedrive.engine.rest.RestAreaSelector
import com.sleepissue.awakedrive.engine.rest.RestCertifier
import com.sleepissue.awakedrive.navi.NaviBridge
import com.sleepissue.awakedrive.navi.RestAreaRestPort
import com.sleepissue.awakedrive.navi.WaypointOutcome
import com.sleepissue.awakedrive.signal.CheckRequest
import com.sleepissue.awakedrive.signal.ClimateAction
import com.sleepissue.awakedrive.signal.ClimatePort
import com.sleepissue.awakedrive.signal.ClimateRequest
import com.sleepissue.awakedrive.signal.FakeCheckPort
import com.sleepissue.awakedrive.signal.FakeClock
import com.sleepissue.awakedrive.signal.HubCollectionState
import com.sleepissue.awakedrive.signal.HubDiagnostics
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.signal.InterventionManager
import com.sleepissue.awakedrive.signal.InterventionRouter
import com.sleepissue.awakedrive.signal.PolicyInterventionManager
import com.sleepissue.awakedrive.signal.RequestTimerBasis
import com.sleepissue.awakedrive.signal.RestAction
import com.sleepissue.awakedrive.signal.RestRequest
import com.sleepissue.awakedrive.signal.SignalHubSession
import com.sleepissue.awakedrive.signal.SpeechRequest
import com.sleepissue.awakedrive.signal.VehicleObservation
import com.sleepissue.awakedrive.voice.VoiceScripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 진짜 1번 PolicyInterventionManager + 4번 실제 구현을 InterventionRouter·RestWiring으로 이은 통합 테스트.
 * 3번 Check 포트만 가짜(FakeCheckPort), Speech 포트는 비워 둔다(NOT_CONNECTED 경로 확인).
 * 무한 루프(1초 관측)가 있어 advanceUntilIdle 대신 runCurrent·advanceTimeBy로 시간을 민다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestWiringTest {

    private val shelter = RestArea(
        id = "DS-001", name = "옥산졸음쉼터", kind = RestAreaKind.DROWSY_SHELTER,
        location = GeoPoint(36.59, 127.4), routeName = "경부선", directionLabel = "서울방향", carriagewayBearingDeg = 0f,
    )
    private val south10km = GeoPoint(36.5, 127.4) // 쉼터 10 km 남쪽, 북쪽으로 주행
    private val min = 60_000L

    /** 1번 매니저를 그대로 쓰고 submit만 기록한다. */
    private class RecordingManager(val inner: PolicyInterventionManager) : InterventionManager by inner {
        val inputs = mutableListOf<InterventionInput>()
        override suspend fun submit(requestId: RequestId, input: InterventionInput): ExecutionResult {
            inputs += input
            return inner.submit(requestId, input)
        }
    }

    private class FakeHub : SignalHubSession {
        override val latestFrame = MutableStateFlow<SignalFrame?>(null)
        override val latestNavi = MutableStateFlow<NaviState?>(null)
        override val latestPower = MutableStateFlow<PowerEvent?>(null)
        override val samples: Flow<SignalFrame> = emptyFlow()
        override val naviSamples: Flow<NaviState> = emptyFlow()
        override val powerEvents: Flow<PowerEvent> = emptyFlow()
        override val rawVehicleObservations: Flow<VehicleObservation> = emptyFlow()
        override val collectionState = MutableStateFlow(HubCollectionState())
        override val diagnostics = MutableStateFlow(HubDiagnostics())
        override suspend fun start(ownerScope: CoroutineScope) = true
        override suspend fun stop() = Unit
        override suspend fun publishDue() = true
        override suspend fun startInput(source: Source) = true
        override suspend fun stopInput(source: Source) = true

        fun at(tMs: Long, gear: Gear, where: GeoPoint) {
            latestFrame.value = SignalFrame(tMs = tMs, gear = gear)
            latestNavi.value = NaviState(tMs, where.lat, where.lon, headingDeg = 0f)
        }
    }

    private class FakeBridge : NaviBridge {
        val added = mutableListOf<RestArea>()
        override suspend fun addRestWaypoint(area: RestArea): WaypointOutcome {
            added += area
            return WaypointOutcome.Added
        }
    }

    private class FakeClimate : ClimatePort {
        val requests = mutableListOf<ClimateRequest>()
        override val results = MutableSharedFlow<ExecutionResult>()
        override suspend fun start(request: ClimateRequest): ExecutionResult {
            requests += request
            return ExecutionResult(request.requestId, ExecutionStatus.REQUESTED)
        }
        override suspend fun cancel(requestId: RequestId) = ExecutionResult(requestId, ExecutionStatus.CANCELLED)
    }

    private inner class Rig(scope: TestScope) {
        val clock = FakeClock()
        val work = CoroutineScope(StandardTestDispatcher(scope.testScheduler))
        private var action = 0
        private var input = 0
        val manager = RecordingManager(PolicyInterventionManager(
            InterventionPolicy(EngineConfig()), EngineConfig(), clock, Source.REAL, RequestTimerBasis.ELAPSED_REALTIME,
        ) { RequestId("action-${++action}") })
        val check = FakeCheckPort()
        val bridge = FakeBridge()
        val climate = FakeClimate()
        val hub = FakeHub()
        val spoken = mutableListOf<String>()
        val rest = RestAreaRestPort({ listOf(shelter) }, { id -> shelter.takeIf { it.id == id } }, RestAreaSelector(), bridge, work)
        private val newInput = { RequestId("input-${++input}") }
        val router = InterventionRouter(manager, check = check, speech = null, rest = rest, climate = climate, newInputId = newInput)
        val monitor = RestSessionMonitor(RestCertifier(listOf(shelter)), RewardLedger(InMemoryRestRecordStore(), clock), clock, climate)
        val nap = NapSession(NapTimer(), object : NapAlarm {
            override fun start(rampMs: Long, maxMs: Long) = Unit
            override fun stop() = Unit
        }, { spoken += it }, clock)
        val wiring = RestWiring(manager, router, monitor, nap, climate, hub, { spoken += it }, { 9_000L }, newInput)

        suspend fun start() {
            router.start(work)
            wiring.start(work)
            manager.start(RequestId("start"))
        }

        suspend fun toStage3(at: GeoPoint) {
            clock.scenarioMs += 1_000
            manager.submit(RequestId("nav-${clock.scenarioMs}"), InterventionInput.NavigationChanged(NaviState(clock.scenarioMs, at.lat, at.lon, headingDeg = 0f)))
            manager.submit(RequestId("safety"), InterventionInput.Safety(SafetyEvent(clock.scenarioMs, SafetyEventType.REPEATED_RAPID_STEERING, Source.REAL)))
        }

        fun checks(context: CheckContext) = check.recorder.requests.filterIsInstance<CheckRequest>().filter { it.context == context }
    }

    private fun TestScope.step(rig: Rig, ms: Long = 1_000) {
        rig.clock.scenarioMs += ms
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun `3단계 - 쉼터 찾기, 경유지 동의 응, 내비 경유지 추가까지`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.hub.at(0, Gear.D, south10km)
        rig.toStage3(south10km)
        runCurrent()
        assertEquals(InterventionStage.REST_GUIDANCE, rig.manager.state.value.stage)

        // FIND_AHEAD가 4번 RestPort로 가서 후보를 찾고, 1번이 경유지 동의 질문을 3번에 요청
        val consent = rig.checks(CheckContext.WAYPOINT_CONSENT).single()
        assertEquals("DS-001", consent.restArea?.id)

        rig.check.progress(ExecutionResult(consent.requestId, ExecutionStatus.STARTED))
        rig.check.complete(CheckResult(consent.requestId, CheckContext.WAYPOINT_CONSENT, CheckOutcome.NORMAL, 900, ResponseIntent.YES, ResponseChannel.VOICE))
        runCurrent()

        assertEquals(listOf(shelter), rig.bridge.added)
        // Speech 포트가 없어도 멈추지 않는다 (NOT_CONNECTED로 돌려줌)
        assertTrue(rig.manager.inputs.any { it is InterventionInput.RestAreaResolved })
        rig.work.cancel()
    }

    @Test
    fun `쉼터 후보까지 직선 거리를 1번에 넘김`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.hub.at(0, Gear.D, south10km)
        rig.toStage3(south10km)
        runCurrent()
        step(rig)
        val distance = rig.manager.inputs.filterIsInstance<InterventionInput.RestAreaDistance>().last()
        assertEquals("DS-001", distance.candidateId)
        assertEquals(10_000f, distance.distanceM!!, 100f)
        rig.work.cancel()
    }

    @Test
    fun `쉼터 도착 안내, 15분 휴식 인증 → 1번이 회복 확인 요청`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.hub.at(0, Gear.P, shelter.location)
        step(rig)
        assertEquals(VoiceScripts.restArrival("옥산졸음쉼터"), rig.spoken.single())

        repeat(15) { step(rig, min) }
        assertTrue(rig.manager.inputs.any { it is InterventionInput.RestCertified })
        assertEquals(1, rig.checks(CheckContext.RECOVERY).size)
        // 인증 뒤 앱이 켰던 통풍을 되돌림
        assertEquals(ClimateAction.RESTORE_DRIVER_VENTILATION, rig.climate.requests.single().action)
        rig.work.cancel()
    }

    @Test
    fun `파워냅 중에 인증되면 깬 뒤에 회복 확인`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.hub.at(0, Gear.P, shelter.location)
        step(rig)
        rig.wiring.requestNap()
        assertTrue(rig.nap.state.value is NapState.Running)

        repeat(15) { step(rig, min) } // 휴식 15분 인증 (자는 중)
        assertTrue(rig.checks(CheckContext.RECOVERY).isEmpty())

        rig.nap.wake() // 알람을 끔
        runCurrent()
        assertEquals(1, rig.checks(CheckContext.RECOVERY).size)
        rig.work.cancel()
    }

    @Test
    fun `대답 잠깐 잘게 → 파워냅, 통풍 꺼 줘 → 되돌리기`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.hub.at(0, Gear.P, shelter.location)
        step(rig)

        rig.check.complete(CheckResult(RequestId("c1"), CheckContext.RECOVERY, CheckOutcome.NORMAL, 800, ResponseIntent.NAP, ResponseChannel.VOICE))
        runCurrent()
        assertTrue(rig.nap.state.value is NapState.Running)
        assertTrue(VoiceScripts.NAP_START in rig.spoken)

        rig.check.complete(CheckResult(RequestId("c2"), CheckContext.DRIVER_ALERTNESS, CheckOutcome.NORMAL, 800, ResponseIntent.VENT_OFF, ResponseChannel.VOICE))
        runCurrent()
        assertEquals(ClimateAction.RESTORE_DRIVER_VENTILATION, rig.climate.requests.last().action)
        rig.work.cancel()
    }

    @Test
    fun `Speech 포트가 없으면 1번에 NOT_CONNECTED`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.hub.at(0, Gear.D, south10km)
        rig.toStage3(south10km)
        runCurrent()
        // 3단계 진입 음성 요청이 막히지 않고 쉼터 찾기·동의 질문까지 진행됨
        assertEquals(1, rig.checks(CheckContext.WAYPOINT_CONSENT).size)
        assertTrue(rig.check.recorder.requests.none { it is SpeechRequest })
        assertTrue(rig.check.recorder.requests.none { it is RestRequest && it.action == RestAction.ADD_WAYPOINT })
        rig.work.cancel()
    }
}
