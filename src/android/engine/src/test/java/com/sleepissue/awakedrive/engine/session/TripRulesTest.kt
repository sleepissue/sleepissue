package com.sleepissue.awakedrive.engine.session

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.DriveState
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.TripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRulesTest {
    private fun rules(
        shortStops: ShortStopAccumulationPolicy = ShortStopAccumulationPolicy.EXCLUDE,
        stopTime: StopTimePolicy = StopTimePolicy.V03_RETROACTIVE,
        config: EngineConfig = EngineConfig(),
    ): TripRules {
        var id = 0
        return TripRules(config, TripRulePolicy(stopTime, shortStops)) { "trip-${++id}" }
    }

    private fun TripRules.observe(
        state: TripRuleState,
        tMs: Long,
        gear: Gear? = Gear.D,
        power: PowerState? = PowerState.ON,
    ): TripRuleResult = reduce(state, TripObservation(tMs, gear, power))

    @Test
    fun firstDriveStartsAtFirstDAndEarlierParkOrReverseDoesNotAccumulate() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0, Gear.P).state
        state = rules.observe(state, 600_000, Gear.R).state
        state = rules.observe(state, 601_000, Gear.N).state
        assertNull(state.trip.runStartMs)
        assertEquals(0L, state.continuousDriveMs)
        assertEquals(0L, state.trip.last24hDriveSec)

        val started = rules.observe(state, 602_000)
        assertEquals(602_000L, started.state.trip.runStartMs)
        assertEquals(listOf(TripRuleEvent.NewTrip("trip-1", 602_000), TripRuleEvent.BaselineResetRequested("trip-1")), started.events)
        state = rules.observe(started.state, 603_000, Gear.R).state
        state = rules.observe(state, 604_000, Gear.N).state
        state = rules.observe(state, 605_000).state
        assertEquals(3_000L, state.continuousDriveMs)
        assertEquals(3L, state.trip.last24hDriveSec)
    }

    @Test
    fun subsecondTicksAndDuplicateTimestampsDoNotLoseOrDoubleTime() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        repeat(15) { index -> state = rules.observe(state, (index + 1) * 100L).state }
        assertEquals(1_500L, state.continuousDriveMs)
        assertEquals(1L, state.trip.continuousDriveSec)
        assertEquals(1_500L, state.driveSegments.single().endMs)

        val duplicate = rules.observe(state, 1_500)
        assertEquals(state, duplicate.state)
        assertTrue(duplicate.events.isEmpty())
        val sameTimeStop = rules.observe(state, 1_500, Gear.P)
        assertEquals(1_500L, sameTimeStop.state.continuousDriveMs)
        assertEquals(1_500L, sameTimeStop.state.stopStartedAtMs)
        assertThrows(IllegalArgumentException::class.java) { rules.observe(sameTimeStop.state, 1_499) }
    }

    @Test
    fun stopBoundariesRetroactivelyRemoveFiveMinutesAndResetAtFifteen() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 6_600_000, Gear.P).state
        val stoppedAt = 6_600_000L
        state = rules.observe(state, stoppedAt + 299_999, Gear.P).state
        assertEquals(6_899_999L, state.continuousDriveMs)
        state = rules.observe(state, stoppedAt + 300_000, Gear.P).state
        assertEquals(6_600_000L, state.continuousDriveMs)
        state = rules.observe(state, stoppedAt + 899_999, Gear.P).state
        assertEquals(6_600_000L, state.continuousDriveMs)
        val reset = rules.observe(state, stoppedAt + 900_000, Gear.P)
        assertEquals(0L, reset.state.continuousDriveMs)
        assertEquals(listOf(TripRuleEvent.RestResetReached(stoppedAt + 900_000)), reset.events)
        assertEquals(6_600L, reset.state.trip.last24hDriveSec)
        val repeated = rules.observe(reset.state, stoppedAt + 901_000, Gear.P)
        assertTrue(repeated.events.isEmpty())
    }

    @Test
    fun sevenMinuteStopResumesFromPreStopAnchorThenTenMinutesReachesTwoHours() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 6_600_000, Gear.P).state
        state = rules.observe(state, 7_020_000).state
        assertEquals(6_600L, state.trip.continuousDriveSec)
        state = rules.observe(state, 7_620_000).state
        assertEquals(7_200L, state.trip.continuousDriveSec)
        assertEquals(7_200L, state.trip.last24hDriveSec)
    }

    @Test
    fun parkAndOffFormOneStopButTheirOffTimerStartsSeparately() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 60_000, Gear.P).state
        state = rules.observe(state, 240_000, null, PowerState.OFF).state
        assertEquals(60_000L, state.stopStartedAtMs)
        assertEquals(240_000L, state.offStartedAtMs)
        state = rules.observe(state, 420_000, Gear.P, PowerState.ON).state
        assertEquals(60_000L, state.continuousDriveMs)
        assertEquals(60_000L, state.stopStartedAtMs)
        assertNull(state.offStartedAtMs)
        assertFalse(state.pendingNewTrip)
    }

    @Test
    fun longParkWithOnlyShortOffDoesNotStartANewTrip() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 60_000, Gear.P).state
        state = rules.observe(state, 7_260_000, null, PowerState.OFF).state
        state = rules.observe(state, 7_320_000, Gear.P).state
        val resumed = rules.observe(state, 7_380_000)
        assertEquals("trip-1", resumed.state.trip.tripId)
        assertTrue(resumed.events.isEmpty())
        assertEquals(0L, resumed.state.continuousDriveMs)
    }

    @Test
    fun offExactlyTwoHoursKeepsTripAndOneMillisecondBeyondCreatesNextTripAtD() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 60_000, null, PowerState.OFF).state
        val exactly = rules.observe(state, 7_260_000)
        assertEquals("trip-1", exactly.state.trip.tripId)
        assertTrue(exactly.events.none { it is TripRuleEvent.NewTrip })

        val overRules = rules()
        var over = overRules.observe(TripRuleState(), 0).state
        over = overRules.observe(over, 60_000, null, PowerState.OFF).state
        over = overRules.observe(over, 7_260_001, Gear.P).state
        assertTrue(over.pendingNewTrip)
        assertEquals("trip-1", over.trip.tripId)
        val next = overRules.observe(over, 7_261_000)
        assertEquals("trip-2", next.state.trip.tripId)
        assertEquals(7_261_000L, next.state.trip.runStartMs)
        assertEquals(60L, next.state.trip.last24hDriveSec)
        assertTrue(next.events.contains(TripRuleEvent.BaselineResetRequested("trip-2")))
    }

    @Test
    fun missingObservationFreezesTimeAndDoesNotPretendToBeParkedOrOff() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 1_500, null, null).state
        assertEquals(1_500L, state.continuousDriveMs)
        state = rules.observe(state, 10_000_000, null, PowerState.UNKNOWN).state
        assertEquals(1_500L, state.continuousDriveMs)
        assertEquals(DriveState.UNKNOWN, state.trip.driveState)
        assertNull(state.stopStartedAtMs)
        assertNull(state.offStartedAtMs)
        val resumed = rules.observe(state, 10_001_000)
        assertEquals("trip-1", resumed.state.trip.tripId)
        assertEquals(1_500L, resumed.state.continuousDriveMs)
        assertTrue(resumed.events.isEmpty())
    }

    @Test
    fun onWithoutGearEndsConfirmedOffWithoutReusingOldDriveGear() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 1_000, null, PowerState.OFF).state
        state = rules.observe(state, 2_000, null, PowerState.ON).state
        assertEquals(DriveState.UNKNOWN, state.trip.driveState)
        assertNull(state.offStartedAtMs)
        assertNull(state.stopStartedAtMs)
        val frozen = state.continuousDriveMs
        state = rules.observe(state, 5_000, null, PowerState.ON).state
        assertEquals(frozen, state.continuousDriveMs)
    }

    @Test
    fun shortStopAccumulationIsAnIndependentRequiredChoiceAndCanBeRolledBack() {
        val include = rules(ShortStopAccumulationPolicy.INCLUDE)
        var state = include.observe(TripRuleState(), 0).state
        state = include.observe(state, 60_000, Gear.P).state
        state = include.observe(state, 359_999, Gear.P).state
        assertEquals(359L, state.trip.last24hDriveSec)
        state = include.observe(state, 360_000, Gear.P).state
        assertEquals(60L, state.trip.last24hDriveSec)
        assertEquals(60_000L, state.driveSegments.single().endMs)

        val shortRules = rules(ShortStopAccumulationPolicy.INCLUDE)
        var short = shortRules.observe(TripRuleState(), 0).state
        short = shortRules.observe(short, 60_000, Gear.P).state
        short = shortRules.observe(short, 120_000).state
        assertEquals(120L, short.trip.last24hDriveSec)
        short = shortRules.observe(short, 121_000).state
        assertEquals(121L, short.trip.last24hDriveSec)

        val exclude = rules(ShortStopAccumulationPolicy.EXCLUDE)
        var excluded = exclude.observe(TripRuleState(), 0).state
        excluded = exclude.observe(excluded, 60_000, Gear.P).state
        excluded = exclude.observe(excluded, 120_000).state
        assertEquals(60L, excluded.trip.last24hDriveSec)
    }

    @Test
    fun legacyFreezeDoesNotSilentlyUseRetroactiveShortStopCounting() {
        val rules = rules(stopTime = StopTimePolicy.LEGACY_FREEZE)
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 60_000, Gear.P).state
        state = rules.observe(state, 359_999, Gear.P).state
        assertEquals(60_000L, state.continuousDriveMs)
        assertEquals(0L, state.trip.provisionalStopSec)
        state = rules.observe(state, 960_000, Gear.P).state
        assertEquals(0L, state.continuousDriveMs)
    }

    @Test
    fun resetPreservesStageBaselineLocationAndRecentDriveHistory() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = state.copy(trip = state.trip.copy(
            baselineRef = "baseline-1",
            stage = InterventionStage.REST_GUIDANCE,
            lastLocation = GeoPoint(37.0, 127.0),
        ))
        state = rules.observe(state, 60_000, Gear.P).state
        state = rules.observe(state, 960_000, Gear.P).state
        assertEquals(0L, state.continuousDriveMs)
        assertEquals(InterventionStage.REST_GUIDANCE, state.trip.stage)
        assertEquals("baseline-1", state.trip.baselineRef)
        assertEquals(GeoPoint(37.0, 127.0), state.trip.lastLocation)
        assertEquals(60L, state.trip.last24hDriveSec)
    }

    @Test
    fun unknownBreaksStopContinuityAndCannotEarnRestResetFromUnobservedGap() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 60_000, Gear.P).state
        state = rules.observe(state, 61_000, null, null).state
        val afterGap = rules.observe(state, 2_000_000, Gear.P)
        assertTrue(afterGap.events.isEmpty())
        assertEquals(2_000_000L, afterGap.state.stopStartedAtMs)
        assertEquals(61_000L, afterGap.state.continuousDriveMs)
    }

    @Test
    fun countersCannotBeRestoredWithoutConsistentPreciseStateAndHistory() {
        assertThrows(IllegalArgumentException::class.java) {
            TripRuleState(trip = TripState(continuousDriveSec = 1), continuousDriveMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TripRuleState(trip = TripState(last24hDriveSec = 60))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TripRuleState(trip = TripState(continuousDriveSec = 1, runStartMs = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TripRuleState(
                trip = TripState(last24hDriveSec = 60),
                lastObservation = TripObservation(60_000, Gear.D, PowerState.ON),
            )
        }
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 1_500).state
        val copied = state.copy(trip = state.trip.copy(stage = InterventionStage.CHECK))
        assertEquals(1_500L, copied.continuousDriveMs)
        assertEquals(1L, copied.trip.last24hDriveSec)
    }

    @Test
    fun rollingTotalSurvivesMidnightAndRestThenExpiresOnlyOutsideWindow() {
        val hourMs = 3_600_000L
        val rules = rules()
        var state = rules.observe(TripRuleState(), 23 * hourMs).state
        state = rules.observe(state, 25 * hourMs, Gear.P).state
        assertEquals(7_200L, state.trip.last24hDriveSec)
        state = rules.observe(state, 33 * hourMs).state
        assertEquals(0L, state.continuousDriveMs)
        assertEquals(7_200L, state.trip.last24hDriveSec)
        state = rules.observe(state, 35 * hourMs, Gear.P).state
        assertEquals(14_400L, state.trip.last24hDriveSec)
        state = rules.observe(state, 48 * hourMs, Gear.P).state
        assertEquals(10_800L, state.trip.last24hDriveSec)
        assertEquals(24 * hourMs, state.driveSegments.first().startMs)
        state = rules.observe(state, 49 * hourMs + 60_000, Gear.P).state
        assertEquals(0L, state.continuousDriveMs)
        assertEquals(7_200L, state.trip.last24hDriveSec)
        assertEquals("trip-1", state.trip.tripId)
    }

    @Test
    fun injectedConfigurationControlsEveryStopAndOffBoundary() {
        val config = EngineConfig(
            tripStopPauseMs = 300,
            tripRestResetMs = 900,
            newTripAfterOffMs = 7_200,
            rollingDriveWindowMs = 86_400,
        )
        val rules = rules(config = config)
        var state = rules.observe(TripRuleState(), 0).state
        state = rules.observe(state, 100, Gear.P).state
        state = rules.observe(state, 399, Gear.P).state
        assertEquals(399L, state.continuousDriveMs)
        state = rules.observe(state, 400, Gear.P).state
        assertEquals(100L, state.continuousDriveMs)
        val rest = rules.observe(state, 1_000, Gear.P)
        assertEquals(0L, rest.state.continuousDriveMs)
        assertEquals(listOf(TripRuleEvent.RestResetReached(1_000)), rest.events)
        state = rules.observe(rest.state, 1_100, null, PowerState.OFF).state
        val resumed = rules.observe(state, 8_301)
        assertEquals("trip-2", resumed.state.trip.tripId)
        assertTrue(resumed.events.contains(TripRuleEvent.NewTrip("trip-2", 8_301)))
    }

    @Test
    fun parkingBeforeFirstDDoesNotEmitARestResetOrInventASession() {
        val rules = rules()
        val parked = rules.observe(TripRuleState(), 0, Gear.P).state
        val shortParked = rules.observe(parked, 1_000, Gear.P).state
        assertEquals(0L, shortParked.trip.provisionalStopSec)
        assertEquals(0L, shortParked.continuousDriveMs)
        val stillParked = rules.observe(shortParked, 900_000, Gear.P)
        assertNull(stillParked.state.trip.tripId)
        assertNull(stillParked.state.trip.runStartMs)
        assertEquals(0L, stillParked.state.continuousDriveMs)
        assertEquals(0L, stillParked.state.trip.last24hDriveSec)
        assertTrue(stillParked.events.isEmpty())
    }

    @Test
    fun nextTripRequestsNewBaselineButPreservesExternallyOwnedStageAndLocation() {
        val rules = rules()
        var state = rules.observe(TripRuleState(), 0).state
        state = state.copy(trip = state.trip.copy(
            stage = InterventionStage.REST_GUIDANCE,
            baselineRef = "baseline-old-trip",
            lastLocation = GeoPoint(37.0, 127.0),
        ))
        state = rules.observe(state, 60_000, null, PowerState.OFF).state
        val resumed = rules.observe(state, 7_260_001)
        assertNull(resumed.state.trip.baselineRef)
        assertEquals(InterventionStage.REST_GUIDANCE, resumed.state.trip.stage)
        assertEquals(GeoPoint(37.0, 127.0), resumed.state.trip.lastLocation)
        assertEquals(60L, resumed.state.trip.last24hDriveSec)
        assertTrue(resumed.events.contains(TripRuleEvent.BaselineResetRequested("trip-2")))
    }
}
