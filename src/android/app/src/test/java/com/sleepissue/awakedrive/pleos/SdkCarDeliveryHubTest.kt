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
class SdkCarDeliveryHubTest {
    private class Fixture(val owner: TestScope) {
        val clock = FakeClock()
        val store = SdkSignalStore(clock, SdkProbePolicy(3_000, 1_000)).apply {
            declare("GEAR", "permission", true); capability("GEAR", true)
        }
        private val bus = MutableSharedFlow<VehicleSignalInput>(extraBufferCapacity = 16)
        private val source = object : VehicleSignalSource { override val samples = bus.asSharedFlow() }
        private val limits = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, source,
            rateTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME)), clock,
            HubPolicy(SignalField.entries.associateWith { limits }, NaviField.entries.associateWith { limits }, limits,
                SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME),
                ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.MANUAL), EngineConfig())
        val raw = mutableListOf<VehicleObservation>()
        var emitted = 0
        // Android 어댑터의 실제 AAOS 전달 판단을 사용한다.
        val delivery = SdkCarDelivery(store, "GEAR") { emitted++; check(bus.tryEmit(store.vehicleInput(it)!!)) }
        suspend fun start() {
            hub.start(owner.backgroundScope)
            owner.backgroundScope.launch(UnconfinedTestDispatcher(owner.testScheduler)) { hub.rawVehicleObservations.toList(raw) }
        }
        suspend fun send(gear: Gear, kind: SdkDelivery, receipt: Long, native: Long = receipt) {
            clock.elapsedMs = receipt; clock.scenarioMs = receipt
            assertTrue(delivery.deliver(gear.name, gear, kind, native, receipt,
                if (kind == SdkDelivery.REQUERY) receipt - 10 else null, "기어 배송", null))
            owner.runCurrent(); assertTrue(hub.publishDue()); owner.runCurrent()
        }
    }

    @Test fun initialAaosQuerySuppliesStateWithoutInventingCallbacksOrSensorObservations() = runTest {
        val f = Fixture(this); f.start()
        f.send(Gear.P, SdkDelivery.QUERY, 100)
        assertEquals(Gear.P, f.hub.latestFrame.value!!.gear)
        assertEquals(SignalAvailability.AVAILABLE, f.hub.latestFrame.value!!.qualityOf(SignalField.GEAR).availability)
        assertEquals(0L, f.hub.latestFrame.value!!.qualityOf(SignalField.GEAR).observationSequence)
        assertEquals(0L, f.store.readings().single().observations)
        assertEquals(0L, f.store.readings().single().callbacks)
        assertEquals(1L, f.store.readings().single().queries)
        assertEquals(0L, f.hub.diagnostics.value.inputs.getValue(Source.REAL).vehicleCallbacks)
        assertTrue(f.raw.isEmpty())
        f.hub.stop()
    }

    @Test fun sameAndChangedGearQueriesUpdateStateWithoutInventingRawObservationsOrCallbacks() = runTest {
        val f = Fixture(this); f.start()
        f.send(Gear.D, SdkDelivery.CALLBACK, 100)
        val sequence = f.hub.latestFrame.value!!.qualityOf(SignalField.GEAR).observationSequence
        f.send(Gear.D, SdkDelivery.REQUERY, 1_100, 100)
        f.send(Gear.P, SdkDelivery.REQUERY, 2_100, 2_000)
        assertEquals(Gear.P, f.hub.latestFrame.value!!.gear)
        val q = f.hub.latestFrame.value!!.qualityOf(SignalField.GEAR)
        assertEquals(2_000L, q.observedAtMs)
        assertEquals(2_100L, q.stateConfirmedAtElapsedMs)
        assertEquals(sequence, q.observationSequence)
        assertEquals(1L, f.hub.diagnostics.value.inputs.getValue(Source.REAL).vehicleCallbacks)
        assertEquals(1, f.raw.size)
        assertEquals(3, f.emitted)
        f.hub.stop()
    }

    @Test fun rejectedOldAaosDeliveryCannotReplayThePreviousCallbackOrRollBackItsTimes() = runTest {
        val f = Fixture(this); f.start()
        f.send(Gear.D, SdkDelivery.CALLBACK, 100)
        val before = f.store.readings().single()
        assertFalse(f.delivery.deliver("P", Gear.P, SdkDelivery.CALLBACK, 50, 50, null, "과거 기어", null))
        runCurrent()
        assertEquals(before.copy(rejectedDeliveries = before.rejectedDeliveries + 1), f.store.readings().single())
        assertEquals(1, f.emitted); assertEquals(1, f.raw.size)
        assertEquals(1L, f.hub.diagnostics.value.inputs.getValue(Source.REAL).vehicleCallbacks)
        assertEquals(Gear.D, f.hub.latestFrame.value!!.gear)
        f.hub.stop()
    }
}
