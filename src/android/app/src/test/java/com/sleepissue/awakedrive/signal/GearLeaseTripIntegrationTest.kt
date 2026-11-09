package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.pleos.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GearLeaseTripIntegrationTest {
    @Test fun expiryExcludesTwoHundredMillisecondsAndDToPCheckpointResumesUnknownWithoutClosedTime() = runTest {
        val epoch = 1_800_000_000_000L
        val clock = FakeClock(scenarioMs = epoch, wallMs = epoch)
        fun advance(to: Long) { clock.elapsedMs = to; clock.scenarioMs = epoch + to; clock.wallMs = epoch + to }
        val source = FakeSignalSource()
        val sdk = SdkSignalStore(clock, SdkProbePolicy(3_000, 1_000)).also {
            it.declare("GEAR", "powertrain", true); it.capability("GEAR", true)
        }
        val audit = GearCollectionAudit(clock)
        val fresh = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, source, rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
            HubPolicy(SignalField.entries.associateWith { fresh }, NaviField.entries.associateWith { fresh }, fresh,
                SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME), ObservationTimeBasis.ELAPSED_REALTIME,
                HubTickDriver.MANUAL), EngineConfig(), gearObserver = audit::selected)
        var saved: TripSnapshot? = null
        val repository = object : TripRepository {
            override suspend fun load() = saved?.let(TripLoadResult::Loaded) ?: TripLoadResult.Empty
            override suspend fun save(snapshot: TripSnapshot): RepositoryResult { saved = snapshot; return RepositoryResult.Succeeded }
            override suspend fun delete() = error("원본 삭제 금지")
        }
        val session = PlaygroundTripSession(repository, clock, newTripId = { "lease-trip" })
        session.initialize(); val token = session.beginInput()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.samples.collect { session.accept(token, it) } }
        hub.start(backgroundScope)
        sdk.receive("GEAR", 8, Gear.D, SdkDelivery.CALLBACK, nativeMs = 0)
        source.emit(sdk.vehicleInput("GEAR")!!); runCurrent(); hub.publishDue(); runCurrent()
        for (t in 100L..3_000L step 100) { advance(t); hub.publishDue(); runCurrent() }
        // Hub 시작 시 UNKNOWN 발행 뒤 첫 D는 100ms다. 관측하지 않은 첫 tick을 합산하지 않는다.
        assertEquals(2_900L, session.preciseState().continuousDriveMs)
        assertEquals(Gear.D, hub.latestFrame.value!!.gear)
        advance(3_100); hub.publishDue(); runCurrent()
        assertEquals(DriveState.UNKNOWN, session.trip.value.driveState)
        assertEquals(SignalAvailability.STALE, hub.latestFrame.value!!.qualityOf(SignalField.GEAR).availability)
        advance(3_200)
        sdk.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY, nativeMs = 0, receivedMs = 3_150, queryStartedMs = 3_120)
        source.emit(sdk.vehicleInput("GEAR")!!); runCurrent(); hub.publishDue(); runCurrent()
        assertEquals(DriveState.DRIVING, session.trip.value.driveState)
        assertEquals(2_900L, session.preciseState().continuousDriveMs)
        assertEquals(200L, audit.snapshot().staleBracketMs)
        assertEquals(1L, audit.snapshot().staleTransitions)
        assertEquals(1L, sdk.quality("GEAR").observationSequence)
        advance(3_300); sdk.receive("GEAR", 4, Gear.P, SdkDelivery.CALLBACK, nativeMs = 3_300)
        source.emit(sdk.vehicleInput("GEAR")!!); runCurrent(); hub.publishDue(); runCurrent()
        assertEquals(DriveState.PAUSED, session.trip.value.driveState)
        assertEquals(3_000L, session.preciseState().continuousDriveMs)
        assertTrue(session.saveNow(session.finishInputAndCapture(token)))
        assertEquals(Gear.P, saved!!.preciseState!!.lastObservation!!.gear)
        hub.stop()
        advance(63_300)
        val restored = PlaygroundTripSession(repository, clock); restored.initialize()
        assertEquals("lease-trip", restored.trip.value.tripId)
        assertEquals(3_000L, restored.preciseState().continuousDriveMs)
        assertEquals(DriveState.UNKNOWN, restored.trip.value.driveState)
        assertNull(restored.preciseState().stopStartedAtMs)
        assertNull(restored.preciseState().lastObservation?.power)
    }
}
