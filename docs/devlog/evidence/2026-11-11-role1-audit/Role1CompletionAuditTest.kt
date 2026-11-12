package com.sleepissue.awakedrive.audit

import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.forecast.ForecastEngine
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.fatigue.*
import com.sleepissue.awakedrive.service.AwakeDrivePipeline
import com.sleepissue.awakedrive.session.ForecastCoordinator
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 현행 제품 조립의 요구 미충족을 찾는 임시 감사다. 사용자 DB와 SDK를 사용하지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class Role1CompletionAuditTest {
    private val epoch = 1_800_050_400_000L
    private class Memory : TripRepository {
        var saved: TripSnapshot? = null
        override suspend fun load() = saved?.let(TripLoadResult::Loaded) ?: TripLoadResult.Empty
        override suspend fun save(snapshot: TripSnapshot): RepositoryResult { saved = snapshot; return RepositoryResult.Succeeded }
        override suspend fun delete() = error("사용하지 않음")
    }
    private class Hub : SignalHubSession {
        override val latestFrame = MutableStateFlow<SignalFrame?>(null)
        override val latestNavi = MutableStateFlow<NaviState?>(null)
        override val latestPower = MutableStateFlow<PowerEvent?>(null)
        override val samples = MutableSharedFlow<SignalFrame>()
        override val naviSamples = MutableSharedFlow<NaviState>()
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
    private fun evidence(name: String, values: Map<String, Any?>) {
        File(System.getProperty("role1.audit.output"), "$name.json").writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(values))
    }

    @Test fun realProductAssemblyMustSaveCurrentStageAndLocation() = runTest {
        val clock = FakeClock(epoch, 0, epoch)
        val db = Memory()
        val session = PlaygroundTripSession(db, clock)
        session.initialize()
        val token = session.beginInput()
        val config = EngineConfig()
        var serial = 0
        fun id() = RequestId("audit-${++serial}")
        val manager = PolicyInterventionManager(InterventionPolicy(config), config, clock, Source.REAL,
            RequestTimerBasis.ELAPSED_REALTIME, ::id)
        val runner = FatigueAssessmentRunner(FatigueBridge(config))
        val hub = Hub()
        val forecast = ForecastCoordinator(ForecastEngine(clock), clock, manager,
            { ForecastCoordinator.inputFrom(session.trip.value, hub.latestNavi.value) }, ::id)
        val router = InterventionRouter(manager, newInputId = ::id)
        val pipeline = AwakeDrivePipeline(hub, session.trip, manager, runner, router, forecast, MutableStateFlow(false), clock, config)
        val job = pipeline.start(backgroundScope)
        try {
            runCurrent()
            session.accept(token, SignalFrame(epoch, gear = Gear.D,
                quality = mapOf(SignalField.GEAR to SignalQuality(SignalAvailability.AVAILABLE, Source.REAL))))
            runCurrent()
            val location = GeoPoint(37.5, 127.0)
            val navi = NaviState(epoch, lat = location.lat, lon = location.lon)
            hub.latestNavi.value = navi
            hub.naviSamples.emit(navi)
            // 유효한 확인 사건으로 제품 manager를 3단계로 올린다. 저장 경로를 우회해서 값을 넣지 않는다.
            manager.submit(id(), InterventionInput.Safety(SafetyEvent(epoch, SafetyEventType.REPEATED_RAPID_STEERING, Source.REAL)))
            runCurrent()
            assertEquals(InterventionStage.REST_GUIDANCE, manager.state.value.stage)
            assertTrue(session.saveNow())
            val restored = PlaygroundTripSession(db, clock)
            restored.initialize()
            evidence("real-checkpoint", mapOf("liveStage" to manager.state.value.stage, "savedStage" to db.saved!!.state.stage,
                "restoredStage" to restored.trip.value.stage, "liveLocation" to location, "savedLocation" to db.saved!!.state.lastLocation,
                "savedBaselineRef" to db.saved!!.state.baselineRef, "ownerState" to db.saved!!.ownerState))
            assertEquals("TRP001/009: 현재 개입 단계를 저장해야 함", manager.state.value.stage, db.saved!!.state.stage)
            assertEquals("TRP001: 현재 위치를 저장해야 함", location, db.saved!!.state.lastLocation)
        } finally { job.cancel(); manager.stop(id()) }
    }

    @Test fun sensitiveFirstCheckDueMustReachInterventionManager() = runTest {
        val clock = FakeClock(epoch, 0, epoch)
        val config = EngineConfig()
        var serial = 0
        fun id() = RequestId("audit-first-${++serial}")
        val manager = PolicyInterventionManager(InterventionPolicy(config), config, clock, Source.DEMO, RequestTimerBasis.SCENARIO, ::id)
        val bridge = FatigueBridge(config)
        val runner = FatigueAssessmentRunner(bridge)
        val hub = Hub()
        val trip = MutableStateFlow(TripState(tripId = "first-check", runStartMs = epoch, driveState = DriveState.DRIVING))
        val forecast = ForecastCoordinator(ForecastEngine(clock), clock, manager, { ForecastCoordinator.inputFrom(trip.value, null) }, ::id)
        val pipeline = AwakeDrivePipeline(hub, trip, manager, runner, InterventionRouter(manager, newInputId = ::id), forecast,
            MutableStateFlow(false), clock, config)
        val job = pipeline.start(backgroundScope)
        try {
            runCurrent()
            hub.samples.emit(SignalFrame(epoch, steeringDeg = 0f, speedKph = 80f, gear = Gear.D,
                turnSignal = TurnSignal.OFF, brakeOn = false, cruiseActive = false))
            runner.reportCondition(true, epoch, manager)
            runCurrent()
            val due = requireNotNull(bridge.firstCheckDueMs)
            clock.scenarioMs = due; clock.wallMs = due; clock.elapsedMs = due - epoch
            manager.submit(id(), InterventionInput.Tick)
            runCurrent()
            evidence("first-check", mapOf("firstCheckDueMs" to due, "nowMs" to clock.scenarioMs,
                "sensitive" to manager.checkpoint().sensitive, "stage" to manager.state.value.stage,
                "pending" to manager.state.value.pendingRequestId))
            assertTrue("BSL006/007 인계: 첫 확인 예약에 도달하면 2단계 확인이 필요함", manager.state.value.stage.level >= InterventionStage.CHECK.level)
        } finally { job.cancel(); manager.stop(id()) }
    }
    @Test fun idealEngineReplayOfAllSixOriginalsRecordsActualOutcomes() {
        val rows = linkedMapOf<String, Any?>()
        for (name in listOf("normal", "distraction", "drowsy", "talk", "assist", "departure")) {
            val loaded = com.sleepissue.awakedrive.demo.DemoJsonLoader().loadFile(File("../../../data/scenarios/$name.json"))
            val scenario = (loaded as com.sleepissue.awakedrive.demo.DemoLoadResult.Loaded).scenario
            val bridge = FatigueBridge()
            val meter = com.sleepissue.awakedrive.engine.signal.RateMeter()
            meter.start(0, ObservationTimeBasis.SCENARIO)
            var firstDeparture: Long? = null
            var at900: BaselineState? = null
            var at915: BaselineState? = null
            var maximumRisk = 0f
            var valid = 0
            for (sample in scenario.samples) {
                meter.observe(sample.tMs, ObservationTimeBasis.SCENARIO, sample.tMs)
                val rate = meter.sample(sample.tMs)
                val q = SignalQuality(SignalAvailability.AVAILABLE, Source.DEMO,
                    scenario.startEpochMs + sample.tMs, observedTimeBasis = ObservationTimeBasis.SCENARIO, sourceRateInfo = rate)
                val v = sample.vehicle
                val frame = SignalFrame(scenario.startEpochMs + sample.tMs, v.steeringDeg, v.speed, v.gear, v.turnSignal,
                    v.brakeOn, v.cruiseActive, v.cruiseTargetKph, v.seats, v.ventilationLevel, v.cabinTempC,
                    SignalField.entries.associateWith { q })
                val trip = TripState(tripId = "control-$name", continuousDriveSec = (scenario.tripPreset?.continuousDriveSec ?: 0) + sample.tMs / 1000,
                    last24hDriveSec = (scenario.tripPreset?.last24hDriveSec ?: 0) + sample.tMs / 1000)
                bridge.onFrame(frame, sample.navi.copy(tMs = frame.tMs), trip)
                val snap = bridge.snapshot
                if (firstDeparture == null && snap.baseline.departureFatigue) firstDeparture = sample.tMs
                if (sample.tMs == 900_000L) at900 = snap.baseline
                if (sample.tMs == 915_000L) at915 = snap.baseline
                snap.fatigue?.takeIf { it.valid }?.let { valid++; maximumRisk = maxOf(maximumRisk, it.riskSmoothed) }
            }
            rows[name] = mapOf("scope" to "JVM ideal 100ms frames directly into bridge; not product or SDK timing",
                "samples" to scenario.samples.size, "firstDepartureMs" to firstDeparture,
                "baselineAt900000" to at900, "baselineAt915000" to at915, "finalBaseline" to bridge.snapshot.baseline,
                "maximumSmoothedRisk" to maximumRisk, "validFrameObservations" to valid)
        }
        evidence("six-scenario-engine-control", rows)
        assertEquals(6, rows.size)
    }

}
