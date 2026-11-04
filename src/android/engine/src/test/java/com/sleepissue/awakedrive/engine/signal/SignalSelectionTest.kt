package com.sleepissue.awakedrive.engine.signal

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.Clock
import org.junit.Assert.*
import org.junit.Test

class SignalSelectionTest {
    private class Time(var scenario: Long = 0, var elapsed: Long = 0) : Clock {
        override fun scenarioTimeMs() = scenario
        override fun elapsedRealtimeMs() = elapsed
        override fun wallTimeMs() = 0L
    }
    private val strict = SignalFreshnessPolicy(1_000, 2_000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
    private fun candidate(source: Source, value: Int? = 1, availability: SignalAvailability = SignalAvailability.AVAILABLE) =
        SignalCandidate(value, SignalQuality(availability, source, 0, 0, observedTimeBasis = ObservationTimeBasis.SCENARIO))
    private fun selector(hold: Long = 0, allowDemo: Boolean = true) = SignalSelector(
        SignalFallbackPolicy(allowDemo, hold, ObservationTimeBasis.SCENARIO))

    @Test fun freshnessBoundaryPreservesObservationAndReceiptMetadataAndNullsExpiredValues() {
        val time = Time(1_000, 2_000)
        val c = candidate(Source.REAL)
        assertEquals(SignalAvailability.AVAILABLE, c.freshness(strict, time).quality.availability)
        time.scenario++
        val stale = c.freshness(strict, time)
        assertNull(stale.value)
        assertEquals(SignalAvailability.STALE, stale.quality.availability)
        assertEquals(0L, stale.quality.observedAtMs)
        assertEquals(0L, stale.quality.receivedAtElapsedMs)
        time.scenario = 0; time.elapsed = 2_001
        assertEquals(SignalAvailability.STALE, c.freshness(strict, time).quality.availability)
    }

    @Test fun unavailableReasonsNeverTurnIntoZeroAndUnknownTimeNeedsExplicitReceiptPolicy() {
        SignalAvailability.entries.filter { it != SignalAvailability.AVAILABLE }.forEach { availability ->
            val c = candidate(Source.REAL, availability = availability).freshness(strict, Time())
            assertNull(c.value); assertEquals(availability, c.quality.availability)
        }
        val c = SignalCandidate(false, SignalQuality(SignalAvailability.AVAILABLE, Source.REAL, receivedAtElapsedMs = 0))
        assertNull(c.freshness(strict, Time()).value)
        assertEquals(false, c.freshness(strict.copy(missingObservation = MissingObservationPolicy.USE_RECEIPT_AGE), Time()).value)
    }

    @Test fun futureTimestampAndReversedReceiptAreErrorsRatherThanFreshInput() {
        val future = candidate(Source.REAL).copy(quality = candidate(Source.REAL).quality.copy(observedAtMs = 1))
        assertEquals(SignalAvailability.ERROR, future.freshness(strict, Time()).quality.availability)
        val futureReceipt = future.copy(quality = future.quality.copy(receivedAtElapsedMs = 1))
        assertEquals(SignalAvailability.ERROR, futureReceipt.freshness(strict, Time()).quality.availability)
    }

    @Test fun realWinsThenDemoFallbackAndBothUnavailableRemainExplicit() {
        val selector = selector()
        val real = candidate(Source.REAL, 80); val demo = candidate(Source.DEMO, 40)
        assertEquals(real.value, selector.select(real, demo, 0).value)
        assertEquals(SignalSelectionReason.REAL_FRESH, selector.select(real, demo, 0).quality.selectionReason)
        val expired = real.freshness(strict, Time(1_001))
        assertEquals(40, selector.select(expired, demo, 1_001).value)
        val neither = selector.select(expired, demo.copy(value = null, quality = demo.quality.copy(availability = SignalAvailability.UNSUPPORTED)), 1_002)
        assertNull(neither.value)
        assertEquals(SignalAvailability.STALE, neither.quality.availability)
        assertEquals(SignalSelectionReason.NO_VALID_INPUT, neither.quality.selectionReason)
    }

    @Test fun injectedHoldAndNoFallbackPoliciesAreEnforcedPerSelector() {
        val selector = selector(500)
        val real = candidate(Source.REAL); val demo = candidate(Source.DEMO, 40)
        selector.select(real, demo, 0)
        val gone = real.copy(value = null, quality = real.quality.copy(availability = SignalAvailability.UNAVAILABLE))
        assertEquals(SignalSelectionReason.REAL_LOSS_HOLD, selector.select(gone, demo, 100).quality.selectionReason)
        assertNull(selector.select(gone, demo, 599).value)
        assertEquals(40, selector.select(gone, demo, 600).value)
        assertNull(selector(allowDemo = false).select(gone, demo, 0).value)
    }

    @Test fun locationExceptionChoosesAWholeDemoPairAndFallsBackToReal() {
        val selector = selector(allowDemo = false)
        val real = SignalCandidate(GeoPoint(37.0, 127.0), candidate(Source.REAL).quality)
        val demo = SignalCandidate(GeoPoint(35.0, 129.0), candidate(Source.DEMO).quality)
        val chosen = selector.select(real, demo, 0, demoLocationFirst = true)
        assertEquals(demo.value, chosen.value)
        assertEquals(SignalSelectionReason.DEMO_LOCATION, chosen.quality.selectionReason)
        val fallback = selector.select(real, null, 1, demoLocationFirst = true)
        assertEquals(real.value, fallback.value)
        assertEquals(SignalSelectionReason.REAL_LOCATION, fallback.quality.selectionReason)
    }
}
