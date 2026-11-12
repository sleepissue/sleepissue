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
class RealCheckpointAuditTest {
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
            val navi = NaviState(epoch, lat = location.lat, lon = location.lon,
                quality = listOf(NaviField.LAT, NaviField.LON).associateWith {
                    SignalQuality(SignalAvailability.AVAILABLE, Source.REAL, observedAtMs = epoch) })
            hub.latestNavi.value = navi
            hub.naviSamples.emit(navi)
            // 유효한 확인 사건으로 제품 manager를 3단계로 올린다. 저장 경로를 우회해서 값을 넣지 않는다.
            manager.submit(id(), InterventionInput.Safety(SafetyEvent(epoch, SafetyEventType.REPEATED_RAPID_STEERING, Source.REAL)))
            runCurrent()
            assertEquals(InterventionStage.REST_GUIDANCE, manager.state.value.stage)
            assertTrue(session.saveNow())
            val restored = PlaygroundTripSession(db, clock)
            restored.initialize()
            evidence("real-checkpoint", mapOf("inputNaviQuality" to navi.quality, "liveStage" to manager.state.value.stage, "savedStage" to db.saved!!.state.stage,
                "restoredStage" to restored.trip.value.stage, "liveLocation" to location, "savedLocation" to db.saved!!.state.lastLocation,
                "savedBaselineRef" to db.saved!!.state.baselineRef, "ownerState" to db.saved!!.ownerState))
            assertEquals("TRP001/009: 현재 개입 단계를 저장해야 함", manager.state.value.stage, db.saved!!.state.stage)
            assertEquals("TRP001: 현재 위치를 저장해야 함", location, db.saved!!.state.lastLocation)
        } finally { job.cancel(); manager.stop(id()) }
    }

}
