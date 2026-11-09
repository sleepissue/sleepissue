package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.signal.FakeClock
import org.junit.Assert.*
import org.junit.Test

class SdkGearLeaseOrderingTest {
    private val clock = FakeClock()
    private val policy = SdkProbePolicy(3_000, 1_000)
    private val store = SdkSignalStore(clock, policy).apply {
        declare("GEAR", "powertrain", true); capability("GEAR", true)
        receive("GEAR", 8, Gear.D, SdkDelivery.CALLBACK, nativeMs = 0)
    }
    private fun selected() = SignalCandidate(store.vehicleInput("GEAR")!!.gear, store.quality("GEAR"))
        .freshness(SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE), clock)

    @Test fun olderUnchangedRequeryCannotMoveNewerConfirmationAndLeaseBackwards() {
        clock.elapsedMs = 2_100
        store.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY, nativeMs = 0, receivedMs = 2_000, queryStartedMs = 1_900)
        val confirmed = store.quality("GEAR")
        clock.elapsedMs = 2_200
        store.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY, nativeMs = 0, receivedMs = 1_000, queryStartedMs = 900)
        assertEquals(2_000L, store.quality("GEAR").stateConfirmedAtElapsedMs)
        assertEquals(5_000L, store.quality("GEAR").stateLeaseUntilElapsedMs)
        assertEquals(confirmed.observedAtMs, store.quality("GEAR").observedAtMs)
        assertEquals(confirmed.receivedAtElapsedMs, store.quality("GEAR").receivedAtElapsedMs)
        assertEquals(confirmed.observationSequence, store.quality("GEAR").observationSequence)
        assertEquals(1L, store.readings().single().rejectedDeliveries)
        clock.elapsedMs = 4_001
        assertEquals(Gear.D, selected().value)
    }

    @Test fun delayedStoreApplicationCannotTurnAnExpiredResponseIntoAFreshConfirmation() {
        clock.elapsedMs = 5_001
        store.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY, nativeMs = 0, receivedMs = 1_000, queryStartedMs = 900)
        assertEquals(1_000L, store.quality("GEAR").stateConfirmedAtElapsedMs)
        assertEquals(4_000L, store.quality("GEAR").stateLeaseUntilElapsedMs)
        assertEquals(0L, store.quality("GEAR").receivedAtElapsedMs)
        assertEquals(1L, store.quality("GEAR").observationSequence)
        assertNull(selected().value); assertEquals(SignalAvailability.STALE, selected().quality.availability)
    }

    @Test fun exactLeaseEndIsAvailableAndOneMillisecondAfterwardsIsStale() {
        clock.elapsedMs = 3_000; assertEquals(Gear.D, selected().value)
        clock.elapsedMs = 3_001; assertNull(selected().value)
        assertEquals(SignalAvailability.STALE, selected().quality.availability)
    }

    @Test fun queryStartedBeforeDToPCallbackCannotReplaceTheCallbackOrRenewItsLease() {
        clock.elapsedMs = 2_000
        store.receive("GEAR", 4, Gear.P, SdkDelivery.CALLBACK, nativeMs = 2_000)
        clock.elapsedMs = 2_500
        store.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY, nativeMs = 0, receivedMs = 2_400, queryStartedMs = 1_900)
        assertEquals(Gear.P, selected().value)
        assertEquals(2_000L, store.quality("GEAR").stateConfirmedAtElapsedMs)
        assertEquals(2L, store.quality("GEAR").observationSequence)
    }

    @Test fun queryFailureBlocksEvenALateSuccessfulResponseAndPermissionLossIsNotRecoverableByRequery() {
        clock.elapsedMs = 2_000
        store.block("GEAR", SignalAvailability.ERROR, "조회 실패")
        clock.elapsedMs = 2_500
        store.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY, nativeMs = 0, receivedMs = 1_900, queryStartedMs = 1_500)
        assertEquals(SignalAvailability.ERROR, selected().quality.availability)
        assertNull(store.quality("GEAR").stateLeaseUntilElapsedMs)
        store.block("GEAR", SignalAvailability.PERMISSION_DENIED, "권한 철회")
        store.beginCollection("GEAR")
        store.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY, nativeMs = 0)
        assertEquals(SignalAvailability.PERMISSION_DENIED, selected().quality.availability)
        assertNull(selected().value)
    }

    @Test fun delayedDuplicateCallbackCannotRollBackTheLaterRequeryConfirmation() {
        clock.elapsedMs = 2_100
        store.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY, nativeMs = 0, receivedMs = 2_000, queryStartedMs = 1_900)
        clock.elapsedMs = 2_200
        store.receive("GEAR", 8, Gear.D, SdkDelivery.CALLBACK, nativeMs = 0, receivedMs = 1_500)
        assertEquals(2_000L, store.quality("GEAR").stateConfirmedAtElapsedMs)
        assertEquals(1L, store.quality("GEAR").observationSequence)
        assertEquals(1L, store.readings().single().rejectedDeliveries)
    }

    @Test fun lateCallbackWithActuallyNewerNativeChangeStillWinsOverAnOlderQuerySnapshot() {
        clock.elapsedMs = 2_100
        store.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY, nativeMs = 0, receivedMs = 2_000, queryStartedMs = 900)
        clock.elapsedMs = 2_200
        store.receive("GEAR", 4, Gear.P, SdkDelivery.CALLBACK, nativeMs = 1_500, receivedMs = 1_600)
        assertEquals(Gear.P, selected().value)
        assertEquals(1_500L, store.quality("GEAR").observedAtMs)
        assertEquals(1_600L, store.quality("GEAR").receivedAtElapsedMs)
        assertEquals(1_600L, store.quality("GEAR").stateConfirmedAtElapsedMs)
    }

}
