package com.sleepissue.awakedrive.engine.demo

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*
import org.junit.Assert.*
import org.junit.Test

class DemoTripPresetTest {
    private val epoch = 1_800_000_000_000L
    private val rules = TripRules(EngineConfig(), TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)) { "next" }
    @Test fun presetHasHistoryAndSurvivesFirstDrivingFrame() {
        val seed = DemoTripPreset(7800, 9000).seed(epoch, "demo")
        val next = rules.reduce(seed, TripObservation(epoch + 100, Gear.D, PowerState.ON)).state
        assertEquals(7_800_100L, next.continuousDriveMs)
        assertEquals(9000L, next.trip.last24hDriveSec)
        assertEquals(epoch - 7_800_000, next.trip.runStartMs)
        assertEquals("demo", next.trip.tripId)
    }
    @Test fun twentyTwoMinuteOffKeepsHistoryIdentityStageAndBaseline() {
        val initial = DemoTripPreset(7800, 9000).seed(epoch, "demo")
        val seed = initial.copy(trip = initial.trip.copy(stage = InterventionStage.CHECK, baselineRef = "baseline", lastLocation = GeoPoint(37.0, 127.0)))
        val off = rules.reduce(seed, TripObservation(epoch, Gear.P, PowerState.OFF)).state
        val resumed = rules.reduce(off, TripObservation(epoch + 22 * 60_000, Gear.P, PowerState.ON)).state
        assertEquals(0L, resumed.continuousDriveMs)
        assertEquals(9000L, resumed.trip.last24hDriveSec)
        assertEquals(seed.driveSegments, resumed.driveSegments)
        assertEquals(seed.trip.tripId, resumed.trip.tripId)
        assertEquals(seed.trip.stage, resumed.trip.stage)
        assertEquals(seed.trip.baselineRef, resumed.trip.baselineRef)
        assertEquals(seed.trip.lastLocation, resumed.trip.lastLocation)
    }
    @Test fun recentTwentyFourHoursExpiresOnlyTheOldSliceDuringOffGap() {
        val seed = DemoTripPreset(7800, 86400).seed(epoch, "demo")
        val off = rules.reduce(seed, TripObservation(epoch, Gear.P, PowerState.OFF)).state
        val resumed = rules.reduce(off, TripObservation(epoch + 22 * 60_000, Gear.P, PowerState.ON)).state
        assertEquals(85_080L, resumed.trip.last24hDriveSec)
        assertEquals(0L, resumed.continuousDriveMs)
    }
    @Test(expected = IllegalArgumentException::class) fun countersWithoutEnoughHistoryAreRejected() { DemoTripPreset(7800, 100) }
    @Test(expected = IllegalArgumentException::class) fun timeBeforeEpochCannotSeedHistory() { DemoTripPreset(7800, 7800).seed(0, "demo") }
}
