package com.sleepissue.awakedrive.service

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.forecast.ForecastEngine
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.PowerEvent
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.SafetyEvent
import com.sleepissue.awakedrive.engine.model.SafetyEventType
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.engine.model.TurnSignal
import com.sleepissue.awakedrive.engine.rest.RestArea
import com.sleepissue.awakedrive.engine.rest.RestAreaKind
import com.sleepissue.awakedrive.engine.rest.RestAreaSelector
import com.sleepissue.awakedrive.fatigue.FatigueAssessmentRunner
import com.sleepissue.awakedrive.fatigue.FatigueBridge
import com.sleepissue.awakedrive.navi.NaviBridge
import com.sleepissue.awakedrive.navi.RestAreaRestPort
import com.sleepissue.awakedrive.navi.WaypointOutcome
import com.sleepissue.awakedrive.session.ForecastCoordinator
import com.sleepissue.awakedrive.signal.CheckRequest
import com.sleepissue.awakedrive.signal.FakeCheckPort
import com.sleepissue.awakedrive.signal.FakeClock
import com.sleepissue.awakedrive.signal.HubCollectionState
import com.sleepissue.awakedrive.signal.HubDiagnostics
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.signal.InterventionManager
import com.sleepissue.awakedrive.signal.InterventionRouter
import com.sleepissue.awakedrive.signal.PolicyInterventionManager
import com.sleepissue.awakedrive.signal.RequestTimerBasis
import com.sleepissue.awakedrive.signal.SignalHubSession
import com.sleepissue.awakedrive.signal.VehicleObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 조립 지점이 입력을 매니저에 넣고, 위치만 오는 내비로도 3단계에서 앞쪽 쉼터 이름이 나오는지 본다. */
@OptIn(ExperimentalCoroutinesApi::class)
class AwakeDrivePipelineTest {
    private val shelter = RestArea(
        id = "DS-001", name = "옥산졸음쉼터", kind = RestAreaKind.DROWSY_SHELTER,
        location = GeoPoint(36.59, 127.4), routeName = "경부선", directionLabel = "서울방향", carriagewayBearingDeg = 0f,
    )

    private class RecordingManager(inner: PolicyInterventionManager) : InterventionManager by inner {
        private val delegate = inner
        val inputs = mutableListOf<InterventionInput>()
        override suspend fun submit(requestId: RequestId, input: InterventionInput): ExecutionResult {
            inputs += input
            return delegate.submit(requestId, input)
        }
    }

    private class FakeHub : SignalHubSession {
        override val latestFrame = MutableStateFlow<SignalFrame?>(null)
        override val latestNavi = MutableStateFlow<NaviState?>(null)
        override val latestPower = MutableStateFlow<PowerEvent?>(null)
        override val samples = MutableSharedFlow<SignalFrame>(extraBufferCapacity = 128)
        override val naviSamples = MutableSharedFlow<NaviState>(extraBufferCapacity = 128)
        override val powerEvents: Flow<PowerEvent> = emptyFlow()
        override val rawVehicleObservations: Flow<VehicleObservation> = emptyFlow()
        override val collectionState = MutableStateFlow(HubCollectionState())
        override val diagnostics = MutableStateFlow(HubDiagnostics())
        override suspend fun start(ownerScope: CoroutineScope) = true
        override suspend fun stop() = Unit
        override suspend fun publishDue() = true
        override suspend fun startInput(source: Source) = true
        override suspend fun stopInput(source: Source) = true
    }

    @Test
    fun `엔진의 첫 확인 예정 시각을 제품 조립 경로가 전달하고 해당 시각에 한 번 요청한다`() = runTest {
        val clock = FakeClock()
        val work = CoroutineScope(StandardTestDispatcher(testScheduler))
        val config = EngineConfig()
        var sequence = 0
        val manager = RecordingManager(PolicyInterventionManager(InterventionPolicy(config), config, clock,
            Source.DEMO, RequestTimerBasis.SCENARIO) { RequestId("first-${++sequence}") })
        val runner = FatigueAssessmentRunner(FatigueBridge(config))
        val hub = FakeHub()
        val trip = MutableStateFlow(TripState(tripId = "first-trip", runStartMs = 0))
        val check = FakeCheckPort()
        val forecast = ForecastCoordinator(ForecastEngine(clock), clock, manager,
            { ForecastCoordinator.inputFrom(trip.value, hub.latestNavi.value) }, { RequestId("forecast-${++sequence}") })
        val router = InterventionRouter(manager, check = check, newInputId = { RequestId("router-${++sequence}") })
        val pipeline = AwakeDrivePipeline(hub, trip, manager, runner, router, forecast,
            MutableStateFlow(false), clock, config)
        try {
            pipeline.start(work)
            runCurrent()
            // 기존 2번 API로 컨디션을 입력한다. 예정 시각·점수·단계를 시험에서 주입하지 않는다.
            runner.reportCondition(true, 0, manager)
            clock.scenarioMs = 100
            hub.latestNavi.value = NaviState(100, distToNextTurnM = 1_000f)
            hub.samples.emit(SignalFrame(100, steeringDeg = 1f, speedKph = 80f, gear = Gear.D,
                turnSignal = TurnSignal.OFF, brakeOn = false, cruiseActive = false))
            runCurrent()
            val due = requireNotNull(runner.snapshots.value.baseline.firstCheckDueMs)
            assertEquals(100 + config.baseline.sensitiveFirstCheckDelayMs, due)
            assertTrue(manager.inputs.contains(InterventionInput.FirstCheckScheduleChanged("first-trip", due)))
            clock.scenarioMs = due - 1
            manager.submit(RequestId("before-due"), InterventionInput.Tick)
            runCurrent()
            assertTrue(check.recorder.requests.isEmpty())
            clock.scenarioMs = due
            manager.submit(RequestId("at-due"), InterventionInput.Tick)
            runCurrent()
            assertEquals(listOf(CheckContext.DRIVER_ALERTNESS),
                check.recorder.requests.filterIsInstance<CheckRequest>().map { it.context })
            manager.submit(RequestId("after-due"), InterventionInput.Tick)
            runCurrent()
            assertEquals(1, check.recorder.requests.size)
        } finally {
            work.cancel()
        }
    }

    @Test
    fun `방향 없는 내비로 북쪽 주행, 3단계에서 앞쪽 쉼터를 경유지 동의 질문에 싣는다`() = runTest {
        val clock = FakeClock()
        val work = CoroutineScope(StandardTestDispatcher(testScheduler))
        val config = EngineConfig()
        var n = 0
        val manager = RecordingManager(PolicyInterventionManager(InterventionPolicy(config), config, clock, Source.REAL,
            RequestTimerBasis.ELAPSED_REALTIME) { RequestId("m-${++n}") })
        val runner = FatigueAssessmentRunner(FatigueBridge(config))
        val hub = FakeHub()
        val trip = MutableStateFlow(TripState())
        val consent = MutableStateFlow(false)
        val check = FakeCheckPort()
        val bridge = object : NaviBridge {
            override suspend fun addRestWaypoint(area: RestArea) = WaypointOutcome.Added
        }
        val rest = RestAreaRestPort({ listOf(shelter) }, { shelter.takeIf { s -> s.id == it } }, RestAreaSelector(), bridge, work)
        val forecast = ForecastCoordinator(ForecastEngine(clock), clock, manager,
            { ForecastCoordinator.inputFrom(trip.value, hub.latestNavi.value) }, { RequestId("f-${++n}") })
        val router = InterventionRouter(manager, check = check, rest = rest, newInputId = { RequestId("r-${++n}") })
        val pipeline = AwakeDrivePipeline(hub, trip, manager, runner, router, forecast, consent, clock, config)
        var wired = false
        pipeline.start(work) { wired = true }
        runCurrent()
        assertTrue(wired)

        // 통풍 동의: 처음 값과 바뀐 값을 모두 넘긴다
        consent.value = true
        runCurrent()
        assertEquals(listOf(false, true),
            manager.inputs.filterIsInstance<InterventionInput.VentilationConsentChanged>().map { it.enabled })

        // 새 운행 → TripChanged + 예보 계산
        trip.value = TripState(tripId = "t1")
        runCurrent()
        assertTrue(manager.inputs.any { it is InterventionInput.TripChanged && it.value.tripId == "t1" })
        assertTrue(manager.inputs.any { it is InterventionInput.Forecast })

        // 쉼터 10 km 남쪽에서 1분 동안 북쪽으로 (방향 필드 없음)
        val start = GeoPoint(36.5, 127.4)
        for (s in 0..60) {
            clock.scenarioMs = s * 1_000L
            hub.naviSamples.emit(NaviState(clock.scenarioMs, start.lat + 0.00025 * s, start.lon))
            runCurrent()
        }
        val lastNavi = manager.inputs.filterIsInstance<InterventionInput.NavigationChanged>().last().value!!
        assertEquals(0f, lastNavi.headingDeg!!, 1f)

        manager.submit(RequestId("safety"), InterventionInput.Safety(
            SafetyEvent(clock.scenarioMs, SafetyEventType.REPEATED_RAPID_STEERING, Source.REAL)))
        runCurrent()
        assertEquals(InterventionStage.REST_GUIDANCE, manager.state.value.stage)
        val consentCheck = check.recorder.requests.filterIsInstance<CheckRequest>()
            .single { it.context == CheckContext.WAYPOINT_CONSENT }
        assertEquals("DS-001", consentCheck.restArea?.id)

        // Tick은 1초마다 들어간다
        advanceTimeBy(config.naviStateIntervalMs + 1)
        assertTrue(manager.inputs.any { it == InterventionInput.Tick })
        work.cancel()
    }
}
