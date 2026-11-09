package com.sleepissue.awakedrive.engine.session

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import org.junit.Assert.*
import org.junit.Test

class TripUnavailableResumeTest {
    private val config = EngineConfig()
    private val policy = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)
    private val rules = TripRules(config, policy) { "trip-sdk" }
    private val epoch = 1_800_000_000_000L

    @Test fun drivingRestoreDoesNotCountClosedTimeOrInventPowerAndKeepsMilliseconds() {
        var saved = rules.reduce(TripRuleState(), TripObservation(epoch, Gear.D, null)).state
        saved = rules.reduce(saved, TripObservation(epoch + 12_345, Gear.D, null)).state
        val restored = rules.resumeWithUnavailableInput(saved, epoch + 612_345)
        assertEquals(12_345L, restored.continuousDriveMs)
        assertEquals(12L, restored.trip.last24hDriveSec)
        assertEquals(saved.trip.tripId, restored.trip.tripId)
        assertEquals(saved.driveSegments, restored.driveSegments)
        assertEquals(DriveState.UNKNOWN, restored.trip.driveState)
        assertNull(restored.lastObservation?.gear)
        assertNull(restored.lastObservation?.power)
        val fresh = rules.reduce(restored, TripObservation(epoch + 612_345, Gear.D, null)).state
        val next = rules.reduce(fresh, TripObservation(epoch + 613_000, Gear.D, null)).state
        assertEquals(13_000L, next.continuousDriveMs)
        assertEquals(13L, next.trip.last24hDriveSec)
    }

    @Test fun parkedOriginalRemainsExactButUnknownGapCannotProveFifteenMinuteRest() {
        var saved = rules.reduce(TripRuleState(), TripObservation(epoch, Gear.D, null)).state
        saved = rules.reduce(saved, TripObservation(epoch + 120_000, Gear.P, null)).state
        saved = rules.reduce(saved, TripObservation(epoch + 300_000, Gear.P, null)).state
        val original = saved.copy()
        val restored = rules.resumeWithUnavailableInput(saved, epoch + 1_200_000)
        assertEquals(original, saved)
        assertEquals(epoch + 120_000, saved.stopStartedAtMs)
        assertEquals(120_000L, saved.continuousBeforeStopMs)
        assertEquals(300_000L, restored.continuousDriveMs)
        assertNull(restored.stopStartedAtMs)
        assertNull(restored.offStartedAtMs)
        val freshP = rules.reduce(restored, TripObservation(epoch + 1_200_000, Gear.P, null))
        assertEquals(epoch + 1_200_000, freshP.state.stopStartedAtMs)
        assertFalse(freshP.events.any { it is TripRuleEvent.RestResetReached })
    }

    @Test fun rollingHistoryExpiresByEpochWindowWithoutMidnightOrNewTripReset() {
        var saved = rules.reduce(TripRuleState(), TripObservation(epoch, Gear.D, null)).state
        saved = rules.reduce(saved, TripObservation(epoch + 60_000, Gear.D, null)).state
        val restored = rules.resumeWithUnavailableInput(saved, epoch + config.rollingDriveWindowMs + 30_000)
        assertEquals(30L, restored.trip.last24hDriveSec)
        assertEquals(60_000L, restored.continuousDriveMs)
        assertEquals(saved.trip.tripId, restored.trip.tripId)
        assertEquals(listOf(DriveSegment(epoch + 30_000, epoch + 60_000)), restored.driveSegments)
    }

    @Test fun offGapCannotCreateNewTripAndClockReversalIsRejected() {
        var saved = rules.reduce(TripRuleState(), TripObservation(epoch, Gear.D, PowerState.ON)).state
        saved = rules.reduce(saved, TripObservation(epoch + 60_000, Gear.P, PowerState.OFF)).state
        val restored = rules.resumeWithUnavailableInput(saved, epoch + config.newTripAfterOffMs + 60_001)
        assertFalse(restored.pendingNewTrip)
        assertNull(restored.offStartedAtMs)
        val fresh = rules.reduce(restored, TripObservation(epoch + config.newTripAfterOffMs + 60_001, Gear.D, null))
        assertEquals(saved.trip.tripId, fresh.state.trip.tripId)
        assertFalse(fresh.events.any { it is TripRuleEvent.NewTrip })
        assertThrows(IllegalArgumentException::class.java) {
            rules.resumeWithUnavailableInput(saved, epoch)
        }
    }
}
