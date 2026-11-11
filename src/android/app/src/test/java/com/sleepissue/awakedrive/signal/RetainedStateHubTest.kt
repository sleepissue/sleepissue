package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.pleos.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetainedStateHubTest {
    @Test fun partialStateConfirmationPreservesOtherFieldsAndTransmitsLeaseSourceAndUnknownNativeTime() = runTest {
        val clock = FakeClock()
        val source = FakeSignalSource()
        val sdk = SdkSignalStore(clock, SdkProbePolicy(3_000, 1_000))
        val values = mapOf<String, Any>("TURN_SIGNAL" to TurnSignal.OFF, "BRAKE_ON" to false,
            "CRUISE_ACTIVE" to false, "CRUISE_TARGET_KPH" to 0f)
        val fresh = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, source, rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
            HubPolicy(SignalField.entries.associateWith { fresh }, NaviField.entries.associateWith { fresh }, fresh,
                SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME), ObservationTimeBasis.ELAPSED_REALTIME,
                HubTickDriver.MANUAL), EngineConfig())
        hub.start(backgroundScope)
        val originals = mutableListOf<VehicleObservation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.rawVehicleObservations.collect { originals += it } }
        values.forEach { (key, value) ->
            sdk.declare(key, "permission", true); sdk.capability(key, true)
            sdk.receive(key, value, value, SdkDelivery.CALLBACK)
            source.emit(sdk.vehicleInput(key)!!); runCurrent()
        }
        clock.elapsedMs = 100; clock.scenarioMs = 100; hub.publishDue(); runCurrent()
        val sequence = values.keys.associateWith { hub.latestFrame.value!!.qualityOf(SignalField.valueOf(it)).observationSequence }
        val callbacks = hub.diagnostics.value.inputs.getValue(Source.REAL).vehicleCallbacks
        for (second in 1..20) {
            clock.elapsedMs = second * 1_000L; clock.scenarioMs = clock.elapsedMs
            values.forEach { (key, value) ->
                sdk.receive(key, value, value, SdkDelivery.REQUERY, queryStartedMs = clock.elapsedMs - 10)
                source.emit(sdk.vehicleInput(key)!!); runCurrent()
            }
            hub.publishDue(); runCurrent()
        }
        val frame = hub.latestFrame.value!!
        assertEquals(TurnSignal.OFF, frame.turnSignal); assertEquals(false, frame.brakeOn)
        assertEquals(false, frame.cruiseActive); assertEquals(0f, frame.cruiseTargetKph!!, 0f)
        values.keys.forEach { key ->
            val q = frame.qualityOf(SignalField.valueOf(key))
            assertEquals(Source.REAL, q.source); assertEquals(SignalAvailability.AVAILABLE, q.availability)
            assertEquals(SignalSelectionReason.REAL_FRESH, q.selectionReason)
            assertEquals(0L, q.receivedAtElapsedMs); assertNull(q.observedAtMs)
            assertEquals(ObservationTimeBasis.UNKNOWN, q.observedTimeBasis)
            assertEquals(20_000L, q.stateConfirmedAtElapsedMs); assertEquals(23_000L, q.stateLeaseUntilElapsedMs)
            assertEquals(sequence[key], q.observationSequence)
        }
        assertEquals(4, originals.size)
        assertEquals(callbacks, hub.diagnostics.value.inputs.getValue(Source.REAL).vehicleCallbacks)
        sdk.receive("BRAKE_ON", true, true, SdkDelivery.REQUERY, queryStartedMs = clock.elapsedMs - 10)
        source.emit(sdk.vehicleInput("BRAKE_ON")!!); runCurrent()
        clock.elapsedMs = 20_100; clock.scenarioMs = clock.elapsedMs; hub.publishDue(); runCurrent()
        assertEquals(true, hub.latestFrame.value!!.brakeOn)
        assertEquals(sequence["BRAKE_ON"], hub.latestFrame.value!!.qualityOf(SignalField.BRAKE_ON).observationSequence)
        assertEquals(4, originals.size)
        sdk.receive("BRAKE_ON", null, null, SdkDelivery.CALLBACK)
        source.emit(sdk.vehicleInput("BRAKE_ON")!!); runCurrent()
        clock.elapsedMs = 20_200; clock.scenarioMs = clock.elapsedMs; hub.publishDue(); runCurrent()
        assertNull(hub.latestFrame.value!!.brakeOn)
        assertEquals(TurnSignal.OFF, hub.latestFrame.value!!.turnSignal)
        clock.elapsedMs = 23_100; clock.scenarioMs = clock.elapsedMs; hub.publishDue(); runCurrent()
        assertNull(hub.latestFrame.value!!.turnSignal)
        assertEquals(SignalAvailability.STALE, hub.latestFrame.value!!.qualityOf(SignalField.TURN_SIGNAL).availability)
        hub.stop()
    }
}
