package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SdkStateDeliveryHubTest {
    private class Fixture(val owner: TestScope) {
        val clock = FakeClock()
        val store = SdkSignalStore(clock, SdkProbePolicy(3_000, 1_000)).apply {
            declare("BRAKE_ON", "permission", true); capability("BRAKE_ON", true)
        }
        private val bus = MutableSharedFlow<VehicleSignalInput>(extraBufferCapacity = 128,
            onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private val source = object : VehicleSignalSource { override val samples = bus.asSharedFlow() }
        private val freshness = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, source, rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
            HubPolicy(SignalField.entries.associateWith { freshness }, NaviField.entries.associateWith { freshness }, freshness,
                SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME),
                ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.MANUAL), EngineConfig())
        val queued = mutableListOf<() -> Unit>()
        val emitted = mutableListOf<VehicleSignalInput>()
        val originals = mutableListOf<VehicleObservation>()
        // 실제 Adapter.stateSubscription이 사용하는 저장·전달 판단이다. 테스트에서 emit 조건을 재구현하지 않는다.
        val delivery = SdkStateDelivery<Boolean>(store, "BRAKE_ON", { it }, "회귀시험") { key ->
            val input = requireNotNull(store.vehicleInput(key))
            emitted += input; check(bus.tryEmit(input))
        }
        lateinit var reply: (Boolean?) -> Unit
        val subscription = SdkStateSubscription(owner.backgroundScope, clock, store.policy,
            query = { success: (Boolean?) -> Unit, _ -> reply = success },
            deliver = { raw, kind, receipt, began, order, valid -> queued += {
                delivery.deliver(raw, kind, receipt, began, order, valid)
            } },
            failed = { error -> queued += { delivery.failed(error) } },
            dispatcher = StandardTestDispatcher(owner.testScheduler))
        suspend fun start() {
            hub.start(owner.backgroundScope)
            owner.backgroundScope.launch(UnconfinedTestDispatcher(owner.testScheduler)) {
                hub.rawVehicleObservations.collect { originals += it }
            }
        }
        fun callback(at: Long, value: Boolean?) { clock.elapsedMs = at; subscription.callback(value) }
        fun drain() { while (queued.isNotEmpty()) queued.removeAt(0).invoke(); owner.runCurrent() }
        suspend fun tick(at: Long) {
            clock.elapsedMs = at; clock.scenarioMs = at
            owner.runCurrent(); hub.publishDue(); owner.runCurrent()
        }
        fun callbacks() = hub.diagnostics.value.inputs.getValue(Source.REAL).vehicleCallbacks
        fun quality() = hub.latestFrame.value!!.qualityOf(SignalField.BRAKE_ON)
        suspend fun stop() { subscription.close(); hub.stop() }
    }

    @Test fun newerCallbackThenOlderCallbackDoesNotReplayTheStoredCallbackToHub() = runTest {
        reverseCallbacks(50, 100)
    }

    @Test fun reversedDeliveryOrderWithinTheSameMillisecondDoesNotReplayTheStoredCallback() = runTest {
        reverseCallbacks(100, 100)
    }

    private suspend fun TestScope.reverseCallbacks(oldAt: Long, newAt: Long) {
        val f = Fixture(this); f.start()
        f.callback(oldAt, false); f.callback(newAt, true)
        // Android Main에 더 최신 배송이 먼저 반영된 상황을 실제 구독의 배송 큐로 만든다.
        f.queued.removeAt(1).invoke(); f.tick(100)
        val before = f.quality()
        f.drain(); f.tick(200)
        assertEquals(true, f.hub.latestFrame.value!!.brakeOn)
        assertEquals(newAt, f.quality().stateConfirmedAtElapsedMs)
        assertEquals(before, f.quality())
        assertEquals(1L, f.store.readings().single().rejectedDeliveries)
        println("거부 후 Hub callbacks=${f.callbacks()} raw=${f.originals.size} emit=${f.emitted.size} · 확인=${f.quality().stateConfirmedAtElapsedMs}")
        assertEquals(1L, f.callbacks()); assertEquals(1, f.originals.size); assertEquals(1, f.emitted.size)
        f.stop()
    }

    @Test fun rejectedRequeryAfterLatestCallbackCannotEmitItsStoredCallbackAgain() = runTest {
        val f = Fixture(this); f.start(); f.callback(100, true); f.drain(); f.tick(100)
        f.delivery.deliver(false, SdkDelivery.REQUERY, 50, 40, 2) { true }
        f.tick(200)
        assertEquals(true, f.hub.latestFrame.value!!.brakeOn)
        assertEquals(100L, f.quality().stateConfirmedAtElapsedMs)
        assertEquals(1L, f.store.readings().single().rejectedDeliveries)
        println("과거 재조회 거부 후 callbacks=${f.callbacks()} raw=${f.originals.size}")
        assertEquals(1L, f.callbacks()); assertEquals(1, f.originals.size); f.stop()
    }

    @Test fun ignoredInitialQueryAfterCallbackUpdatesOnlyItsDiagnosticQueryCount() = runTest {
        val f = Fixture(this); f.start(); f.callback(100, true); f.drain(); f.tick(100)
        f.delivery.deliver(false, SdkDelivery.QUERY, 150, 50, 2) { true }
        f.tick(200)
        assertEquals(1L, f.store.readings().single().queries)
        assertEquals(true, f.hub.latestFrame.value!!.brakeOn)
        assertEquals(100L, f.quality().stateConfirmedAtElapsedMs)
        println("최초 조회 무시 후 callbacks=${f.callbacks()} raw=${f.originals.size}")
        assertEquals(1L, f.callbacks()); assertEquals(1, f.originals.size); f.stop()
    }

    @Test fun realSubscriptionRequeryChangedValueAndNullRemainConfirmationsWhileCallbackNullIsAnObservation() = runTest {
        val f = Fixture(this); f.start(); f.subscription.start(); runCurrent()
        f.clock.elapsedMs = 10; f.reply(false); runCurrent(); f.drain(); f.tick(100)
        assertEquals(false, f.hub.latestFrame.value!!.brakeOn)
        assertEquals(0L, f.callbacks()); assertTrue(f.originals.isEmpty())
        assertEquals(0L, f.store.readings().single().observations)
        f.callback(200, true); f.drain(); f.tick(300)
        assertEquals(1L, f.callbacks()); assertEquals(1, f.originals.size)
        val sequence = f.quality().observationSequence
        f.clock.elapsedMs = 1_000; testScheduler.advanceTimeBy(1_000); runCurrent()
        f.clock.elapsedMs = 1_100; f.reply(false); runCurrent(); f.drain(); f.tick(1_200)
        assertEquals(false, f.hub.latestFrame.value!!.brakeOn)
        assertEquals(SignalAvailability.AVAILABLE, f.quality().availability)
        assertEquals(1_100L, f.quality().stateConfirmedAtElapsedMs)
        assertEquals(4_100L, f.quality().stateLeaseUntilElapsedMs)
        assertEquals(sequence, f.quality().observationSequence)
        assertEquals(1L, f.callbacks()); assertEquals(1, f.originals.size)
        f.clock.elapsedMs = 2_000; testScheduler.advanceTimeBy(1_000); runCurrent()
        f.clock.elapsedMs = 2_100; f.reply(null); runCurrent(); f.drain(); f.tick(2_200)
        assertNull(f.hub.latestFrame.value!!.brakeOn)
        assertEquals(SignalAvailability.UNAVAILABLE, f.quality().availability)
        assertNull(f.quality().stateConfirmedAtElapsedMs)
        assertEquals(1L, f.callbacks()); assertEquals(1, f.originals.size)
        f.callback(2_300, null); f.drain(); f.tick(2_400)
        assertNull(f.hub.latestFrame.value!!.brakeOn)
        assertEquals(2L, f.callbacks()); assertEquals(2, f.originals.size)
        f.stop()
    }

    @Test fun errorAndPermissionLossAreStillEmittedAndInvalidatePendingCallbacks() = runTest {
        for (error in listOf(IllegalStateException("조회 실패"), SecurityException("권한 상실"))) {
            val f = Fixture(this); f.start(); f.callback(100, true); f.drain(); f.tick(100)
            f.callback(150, false); f.subscription.error(error); f.drain(); f.tick(200)
            assertNull(f.hub.latestFrame.value!!.brakeOn)
            assertNull(f.quality().stateConfirmedAtElapsedMs)
            assertNull(f.quality().stateLeaseUntilElapsedMs)
            assertEquals(if (error is SecurityException) SignalAvailability.PERMISSION_DENIED else SignalAvailability.ERROR,
                f.quality().availability)
            assertEquals(2, f.emitted.size) // 정상 콜백1 + 명시 무효화1. 대기 중 콜백은 폐기한다.
            f.callback(250, true); f.drain(); f.tick(300)
            assertEquals(2, f.emitted.size); assertNull(f.hub.latestFrame.value!!.brakeOn)
            f.stop()
        }
    }
}
