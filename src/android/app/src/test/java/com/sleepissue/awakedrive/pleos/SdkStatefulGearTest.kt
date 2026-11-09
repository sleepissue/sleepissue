package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.signal.*
import org.junit.Assert.*
import org.junit.Test

class SdkStatefulGearTest {
    private val clock = FakeClock()
    private val policy = SdkProbePolicy(3_000, 1_000)
    private val freshness = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
    private fun store() = SdkSignalStore(clock, policy).also { it.declare("GEAR", "powertrain", true); it.capability("GEAR", true) }
    private fun candidate(s: SdkSignalStore) = SignalCandidate(s.vehicleInput("GEAR")!!.gear, s.quality("GEAR")).freshness(freshness, clock)

    @Test fun unchangedRequeryKeepsChangeReceiptAndObservationSequenceButRenewsLease() {
        val s = store(); s.receive("GEAR", 8, Gear.D, SdkDelivery.CALLBACK, nativeMs = 0)
        val first = s.quality("GEAR")
        for (t in 1_000L..20_000L step 1_000) {
            clock.elapsedMs = t
            s.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY, nativeMs = 0, queryStartedMs = t)
            assertEquals(Gear.D, candidate(s).value)
            assertEquals(first.observedAtMs, s.quality("GEAR").observedAtMs)
            assertEquals(first.receivedAtElapsedMs, s.quality("GEAR").receivedAtElapsedMs)
            assertEquals(first.observationSequence, s.quality("GEAR").observationSequence)
        }
        assertEquals(1L, s.readings().single().callbacks)
        assertEquals(20L, s.readings().single().queries)
        assertEquals(20_000L, s.quality("GEAR").stateConfirmedAtElapsedMs)
        clock.elapsedMs = 23_000; assertEquals(Gear.D, candidate(s).value)
        clock.elapsedMs++; assertNull(candidate(s).value)
        assertEquals(SignalAvailability.STALE, candidate(s).quality.availability)
    }

    @Test fun explicitUnavailableInvalidatesLeaseAndQueryErrorCannotReviveBlockedSubscription() {
        val s = store(); s.receive("GEAR", 8, Gear.D, SdkDelivery.CALLBACK)
        s.receive("GEAR", null, null, SdkDelivery.REQUERY, availability = SignalAvailability.UNAVAILABLE)
        assertNull(candidate(s).value); assertNull(s.quality("GEAR").stateConfirmedAtElapsedMs)
        s.block("GEAR", SignalAvailability.ERROR, "구독 실패")
        s.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY)
        s.receive("GEAR", 8, Gear.D, SdkDelivery.CALLBACK)
        assertEquals(SignalAvailability.ERROR, candidate(s).quality.availability)
        s.beginCollection("GEAR"); s.receive("GEAR", 4, Gear.P, SdkDelivery.QUERY)
        assertEquals(Gear.P, candidate(s).value)
    }

    @Test fun oldQueryCannotReplaceCallbackAndPermissionLossRequiresNewAdapter() {
        val s = store(); clock.elapsedMs = 200
        s.receive("GEAR", 8, Gear.D, SdkDelivery.CALLBACK, nativeMs = 200)
        clock.elapsedMs = 300
        s.receive("GEAR", 4, Gear.P, SdkDelivery.REQUERY, nativeMs = 100, queryStartedMs = 100)
        assertEquals(Gear.D, candidate(s).value)
        s.block("GEAR", SignalAvailability.PERMISSION_DENIED, "권한 상실")
        s.beginCollection("GEAR"); s.receive("GEAR", 8, Gear.D, SdkDelivery.REQUERY)
        assertNull(candidate(s).value)
        assertEquals(SignalAvailability.PERMISSION_DENIED, candidate(s).quality.availability)
    }
}
