package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SdkClimateStateHubTest {
    private suspend fun TestScope.checkState(key: String, first: Any, changed: Any, invalid: Any,
        normalize: (Any?) -> Any?) {
        val clock = FakeClock()
        val store = SdkSignalStore(clock, SdkProbePolicy(3_000, 1_000)).apply {
            declare(key, "permission", true); capability(key, true)
        }
        val field = SignalField.valueOf(key)
        val bus = MutableSharedFlow<VehicleSignalInput>(extraBufferCapacity = 16)
        val source = object : VehicleSignalSource { override val samples = bus.asSharedFlow() }
        val fresh = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, source, rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
            HubPolicy(SignalField.entries.associateWith { fresh }, NaviField.entries.associateWith { fresh }, fresh,
                SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME),
                ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.MANUAL), EngineConfig())
        val observations = mutableListOf<VehicleObservation>()
        hub.start(backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.rawVehicleObservations.toList(observations) }
        val delivery = SdkStateDelivery(store, key, normalize, "현재값 조회") {
            check(bus.tryEmit(requireNotNull(store.vehicleInput(it))))
        }
        suspend fun send(value: Any?, kind: SdkDelivery, at: Long, order: Long) {
            clock.elapsedMs = at; clock.scenarioMs = at
            delivery.deliver(value, kind, at, if (kind == SdkDelivery.REQUERY) at - 10 else null, order) { true }
            runCurrent(); hub.publishDue(); runCurrent()
        }
        fun quality() = hub.latestFrame.value!!.qualityOf(field)
        send(first, SdkDelivery.CALLBACK, 100, 1)
        val observed = quality().observationSequence
        for (second in 1..20) send(first, SdkDelivery.REQUERY, second * 1_000L, second + 1L)
        assertEquals(SignalAvailability.AVAILABLE, quality().availability)
        assertEquals(20_000L, quality().stateConfirmedAtElapsedMs)
        assertEquals(100L, store.readings().single().receivedMs)
        send(changed, SdkDelivery.REQUERY, 21_000, 22)
        val input = store.vehicleInput(key)!!
        assertEquals(changed, if (field == SignalField.VENTILATION_LEVEL) input.ventilationLevel else input.cabinTempC)
        assertEquals(observed, quality().observationSequence)
        assertEquals(1L, hub.diagnostics.value.inputs.getValue(Source.REAL).vehicleCallbacks)
        assertEquals(1, observations.size)
        clock.elapsedMs = 24_000; clock.scenarioMs = 24_000; hub.publishDue()
        assertEquals(SignalAvailability.AVAILABLE, quality().availability)
        clock.elapsedMs = 24_100; clock.scenarioMs = 24_100; hub.publishDue()
        assertEquals(SignalAvailability.STALE, quality().availability)
        send(null, SdkDelivery.REQUERY, 25_000, 23)
        assertEquals(SignalAvailability.UNAVAILABLE, quality().availability)
        assertNull(quality().stateConfirmedAtElapsedMs)
        send(invalid, SdkDelivery.REQUERY, 26_000, 24)
        assertEquals(SignalAvailability.ERROR, quality().availability)
        assertEquals(1, observations.size)
        send(first, SdkDelivery.CALLBACK, 27_000, 25)
        val before = store.readings().single()
        delivery.deliver(changed, SdkDelivery.CALLBACK, 27_000, null, 24) { true }
        runCurrent()
        assertEquals(before.copy(rejectedDeliveries = before.rejectedDeliveries + 1), store.readings().single())
        assertEquals(2, observations.size)
        delivery.failed(SecurityException("권한 상실")); runCurrent()
        clock.elapsedMs = 28_000; clock.scenarioMs = 28_000; hub.publishDue()
        assertEquals(SignalAvailability.PERMISSION_DENIED, quality().availability)
        hub.stop()
    }

    @Test fun ventilationKeepsZeroAndChangedQueryWithoutCreatingObservationsAndExpires() = runTest {
        checkState("VENTILATION_LEVEL", 0, 1, -1) { SdkStateNormalization.ventilation(it as Int?, 0, 3) }
    }
    @Test fun currentCabinTemperatureStaysFreshAndChangedQueryIsNotASensorObservation() = runTest {
        checkState("CABIN_TEMP_C", 24.9f, 25f, Float.NaN) { SdkStateNormalization.cabinTemperature(it as Float?) }
    }
}
