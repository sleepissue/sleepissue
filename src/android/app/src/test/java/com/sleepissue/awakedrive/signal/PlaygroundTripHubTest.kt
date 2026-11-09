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
class PlaygroundTripHubTest {
    @Test fun realGearSurvivesTwentySecondsOfPartialUpdatesAndRequeriesWithoutSyntheticObservations() = runTest {
        val clock = FakeClock(scenarioMs = 1_800_000_000_000, wallMs = 1_800_000_000_000)
        val source = FakeSignalSource()
        val sdk = SdkSignalStore(clock, SdkProbePolicy(3_000, 1_000)).also {
            it.declare("GEAR", "powertrain", true); it.declare("BRAKE_ON", "drive", true)
        }
        val fresh = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, source, rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
            HubPolicy(SignalField.entries.associateWith { fresh }, NaviField.entries.associateWith { fresh }, fresh,
                SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME), ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.MANUAL), EngineConfig())
        val repo = object : TripRepository {
            override suspend fun load() = TripLoadResult.Empty
            override suspend fun save(snapshot: TripSnapshot) = RepositoryResult.Succeeded
            override suspend fun delete() = error("삭제 금지")
        }
        val controller = PlaygroundTripSession(repo, clock); controller.initialize(); val token = controller.beginInput()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.samples.collect { controller.accept(token, it) } }
        hub.start(backgroundScope)
        sdk.receive("GEAR", 8, Gear.D, SdkDelivery.CALLBACK)
        source.emit(sdk.vehicleInput("GEAR")!!); runCurrent()
        var observation: Long? = null
        repeat(201) { i ->
            clock.elapsedMs = (i + 1) * 100L; clock.scenarioMs = 1_800_000_000_000 + clock.elapsedMs
            if (i % 10 == 0) {
                sdk.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY)
                source.emit(sdk.vehicleInput("GEAR")!!)
            }
            sdk.receive("BRAKE_ON", true, true, SdkDelivery.CALLBACK)
            source.emit(sdk.vehicleInput("BRAKE_ON")!!); runCurrent(); hub.publishDue(); runCurrent()
            val q = hub.latestFrame.value!!.qualityOf(SignalField.GEAR)
            assertEquals(Gear.D, hub.latestFrame.value!!.gear)
            assertEquals(0L, q.receivedAtElapsedMs)
            if (observation != null) assertEquals(observation, q.observationSequence)
            observation = q.observationSequence
        }
        assertEquals(20L, controller.trip.value.continuousDriveSec)
        assertNull(controller.preciseState().lastObservation?.power)
        sdk.receive("GEAR", null, null, SdkDelivery.REQUERY, availability = SignalAvailability.ERROR)
        source.emit(sdk.vehicleInput("GEAR")!!); runCurrent()
        clock.elapsedMs += 100; clock.scenarioMs += 100; hub.publishDue(); runCurrent()
        assertNull(hub.latestFrame.value!!.gear); assertEquals(DriveState.UNKNOWN, controller.trip.value.driveState)
        hub.stop()
    }
}
