package com.sleepissue.awakedrive.engine.signal

import com.sleepissue.awakedrive.engine.model.ObservationTimeBasis
import org.junit.Assert.*
import org.junit.Test

class RateMeterTest {
    private val elapsed = ObservationTimeBasis.ELAPSED_REALTIME

    @Test fun startupIsUnknownUntilAFullWindowAndMonitoredSilenceIsZero() {
        val meter = RateMeter()
        assertEquals(RateStatus.NOT_STARTED, meter.sample(0).status)
        meter.start(0, elapsed)
        assertNull(meter.sample(9_999).hz)
        assertEquals(RateStatus.WARMING_UP, meter.sample(9_999).status)
        assertEquals(0f, meter.sample(10_000).hz!!, 0f)
        assertEquals(true, meter.sample(10_000).belowMinimum)
        meter.stop()
        assertEquals(RateStatus.INTERRUPTED, meter.sample(20_000).status)
        assertNull(meter.sample(20_000).hz)
    }

    @Test fun twoFiveAndTenHzUseTheLeftOpenRightClosedTenSecondWindow() {
        listOf(2, 5, 10).forEach { hz ->
            val meter = RateMeter(); meter.start(0, elapsed)
            val interval = 1_000L / hz
            for (at in 0L..10_000 step interval) assertEquals(RateObservationResult.ACCEPTED, meter.observe(at, elapsed, at))
            val measured = meter.sample(10_000)
            assertEquals(hz.toFloat(), measured.hz!!, 0.0001f)
            assertEquals(hz * 10, measured.observationsInWindow)
            assertEquals(hz < 5, measured.belowMinimum)
            assertEquals((hz * 10 + 1).toLong(), measured.acceptedObservations)
        }
    }

    @Test fun exactlyFiftyObservationsAreFiveHzAndFortyNineAreBelow() {
        val meter = RateMeter(); meter.start(0, elapsed)
        for (at in 200L..10_000 step 200) meter.observe(at, elapsed, at)
        assertEquals(false, meter.sample(10_000).belowMinimum)
        assertEquals(4.9f, meter.sample(10_200).hz!!, 0.001f)
        assertEquals(true, meter.sample(10_200).belowMinimum)
        assertEquals(0f, meter.sample(20_000).hz!!, 0f)
    }

    @Test fun duplicateTimestampsNeverIncreaseTheSourceRate() {
        val meter = RateMeter(); meter.start(0, elapsed)
        meter.observe(0, elapsed, 0)
        repeat(100) { assertEquals(RateObservationResult.DUPLICATE, meter.observe(0, elapsed, 100)) }
        val result = meter.sample(10_000)
        assertEquals(1L, result.acceptedObservations)
        assertEquals(100L, result.duplicateObservations)
        assertEquals(0f, result.hz!!, 0f)
    }

    @Test fun reversedOrFutureObservationsRequireAnExplicitNewMeasurementSession() {
        val meter = RateMeter(); meter.start(0, elapsed)
        meter.observe(100, elapsed, 100)
        assertEquals(RateObservationResult.REVERSED, meter.observe(99, elapsed, 100))
        assertEquals(RateStatus.UNMEASURABLE, meter.sample(10_000).status)
        meter.start(10_000, elapsed)
        assertEquals(0f, meter.sample(20_000).hz!!, 0f)
        meter.start(0, elapsed)
        assertEquals(RateObservationResult.REVERSED, meter.observe(101, elapsed, 100))
        assertNull(meter.sample(10_000).hz)
    }

    @Test fun unknownAndWallTimeAreNotSensorRateEvidenceAndClockReversalIsVisible() {
        val meter = RateMeter()
        meter.start(0, ObservationTimeBasis.UNKNOWN)
        assertEquals(RateStatus.UNMEASURABLE, meter.sample(10_000).status)
        meter.start(0, elapsed)
        assertEquals(RateObservationResult.INVALID_TIME_BASIS, meter.observe(0, ObservationTimeBasis.WALL_TIME, 0))
        assertNull(meter.sample(10_000).belowMinimum)
        meter.start(100, elapsed)
        assertEquals(RateStatus.UNMEASURABLE, meter.sample(99).status)
    }

    @Test fun scenarioRateUsesSourceTimeInsteadOfAcceleratedArrivalTime() {
        val meter = RateMeter(); val basis = ObservationTimeBasis.SCENARIO
        meter.start(0, basis)
        // 60배 빨리 도착해도 원본 시나리오 10초에 20회면 2Hz다.
        for (scenario in 0L..10_000 step 500) meter.observe(scenario, basis, scenario)
        assertEquals(2f, meter.sample(10_000).hz!!, 0f)
        assertEquals(basis, meter.sample(10_000).timeBasis)
        assertEquals(RateObservationResult.INVALID_TIME_BASIS, meter.observe(10_500, elapsed, 10_500))
    }

    @Test fun observationCreatedBeforeSubscriptionIsExcludedWithoutPoisoningTheNewWindow() {
        val meter = RateMeter(); meter.start(100, elapsed)
        assertEquals(RateObservationResult.BEFORE_START, meter.observe(99, elapsed, 101))
        for (at in 300L..10_100 step 200) meter.observe(at, elapsed, at)
        assertEquals(5f, meter.sample(10_100).hz!!, 0f)
        assertEquals(50L, meter.sample(10_100).acceptedObservations)
    }

    @Test fun denseObservationsGrowAndWrapWithoutDroppingAnySourceTimestamp() {
        val meter = RateMeter(); meter.start(0, elapsed)
        for (at in 0L..20_000L) assertEquals(RateObservationResult.ACCEPTED, meter.observe(at, elapsed, at))
        val full = meter.sample(20_000)
        assertEquals(20_001L, full.acceptedObservations)
        assertEquals(10_000, full.observationsInWindow)
        assertEquals(1_000f, full.hz!!, 0f)
        assertEquals(9_000, meter.sample(21_000).observationsInWindow)
        assertEquals(0, meter.sample(30_000).observationsInWindow)
        meter.stop(); meter.start(30_000, elapsed)
        assertEquals(RateObservationResult.ACCEPTED, meter.observe(30_000, elapsed, 30_000))
        assertEquals(1L, meter.sample(30_000).acceptedObservations)
        assertEquals(1, meter.sample(30_000).observationsInWindow)
    }

    @Test fun samplingAnInvalidClockStillRecordsEveryFurtherReversal() {
        val meter = RateMeter(); meter.start(0, elapsed)
        assertEquals(1L, meter.sample(-1).reversedObservations)
        assertEquals(2L, meter.sample(-2).reversedObservations)
        meter.start(0, elapsed)
        assertEquals(RateObservationResult.ACCEPTED, meter.observe(0, elapsed, 0))
        assertEquals(RateObservationResult.DUPLICATE, meter.observe(0, elapsed, 0))
        assertEquals(1L, meter.sample(0).acceptedObservations)
        assertEquals(1L, meter.sample(0).duplicateObservations)
    }


    @Test fun lastKnownSnapshotUsesOnlyTheRecordedTimeAndKeepsCountersAcrossStop() {
        val meter = RateMeter()
        assertEquals(SourceRate(), meter.lastKnownSnapshot())
        meter.start(0, elapsed)
        for (at in 0L..10_000L step 100) meter.observe(at, elapsed, at)
        val measured = meter.sample(10_000)
        assertEquals(10f, measured.hz!!, 0f)
        assertEquals(measured, meter.lastKnownSnapshot())
        assertEquals(measured, meter.lastKnownSnapshot())
        val reversed = meter.sample(9_999)
        assertEquals(RateStatus.UNMEASURABLE, reversed.status)
        assertEquals(1L, reversed.reversedObservations)
        assertEquals(reversed, meter.lastKnownSnapshot())
        meter.stop()
        val stopped = meter.lastKnownSnapshot()
        assertEquals(RateStatus.INTERRUPTED, stopped.status)
        assertNull(stopped.hz)
        assertEquals(101L, stopped.acceptedObservations)
        assertEquals(1L, stopped.reversedObservations)
        assertEquals(0, stopped.observationsInWindow)
        assertEquals(stopped, meter.lastKnownSnapshot())
        meter.start(20_000, elapsed)
        val restarted = meter.lastKnownSnapshot()
        assertEquals(RateStatus.WARMING_UP, restarted.status)
        assertEquals(0L, restarted.acceptedObservations)
        assertEquals(0L, restarted.reversedObservations)
    }

}
