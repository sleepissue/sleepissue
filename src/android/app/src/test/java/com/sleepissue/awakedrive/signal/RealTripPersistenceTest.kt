package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*
import org.junit.Assert.*
import org.junit.Test

class RealTripPersistenceTest {
    private val policy = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)
    private val rules = TripRules(EngineConfig(), policy) { "sdk-checkpoint" }
    private val clock = FakeClock(scenarioMs = 1_800_000_000_000, elapsedMs = 10_000, wallMs = 1_800_000_000_000)

    private fun snapshot(): TripSnapshot {
        var state = rules.reduce(TripRuleState(), TripObservation(clock.scenarioMs, Gear.D, null)).state
        clock.scenarioMs += 12_345
        state = rules.reduce(state, TripObservation(clock.scenarioMs, Gear.D, null)).state
        return RealTripPersistence.capture(state, policy, clock)
    }

    @Test fun realOwnerUsesDistinctVersionAndAllowsUnconnectedPower() {
        listOf(RealTripOwnerState(Gear.D, null), RealTripOwnerState(null, null),
            RealTripOwnerState(Gear.P, PowerState.OFF)).forEach { owner ->
            assertEquals(owner, RealTripCheckpointCodec.decode(RealTripCheckpointCodec.encode(owner)))
        }
        assertThrows(IllegalArgumentException::class.java) { RealTripCheckpointCodec.decode("invalid") }
        assertThrows(IllegalArgumentException::class.java) { RealTripCheckpointCodec.decode("REAL_TRIP_V1:" + "x".repeat(256)) }
    }

    @Test fun captureSeparatesDataTimestampFromActualSaveClocksAndRestoresUnknown() {
        val saved = snapshot()
        clock.wallMs += 5_000; clock.elapsedMs += 5_000
        val delayedSave = RealTripPersistence.capture(requireNotNull(saved.preciseState), policy, clock)
        assertEquals(saved.savedAtScenarioMs, delayedSave.savedAtScenarioMs)
        assertEquals(clock.wallMs, delayedSave.savedAtWallMs)
        assertEquals(clock.elapsedMs, delayedSave.savedAtElapsedMs)
        assertEquals(Source.REAL, delayedSave.source)
        assertEquals(TripTimeBasis.WALL_EPOCH, delayedSave.timeBasis)
        val restored = RealTripPersistence.restore(delayedSave, clock.scenarioMs + 600_000)
        assertEquals(saved.state.tripId, restored.trip.tripId)
        assertEquals(saved.preciseState?.continuousDriveMs, restored.continuousDriveMs)
        assertEquals(saved.driveSegments, restored.driveSegments)
        assertEquals(DriveState.UNKNOWN, restored.trip.driveState)
        assertNull(restored.lastObservation?.gear)
        assertNull(restored.lastObservation?.power)
    }

    @Test fun wrongTimeSourceOwnerOrReversedTimeIsNotAValidRestore() {
        val saved = snapshot()
        listOf(saved.copy(source = Source.DEMO), saved.copy(timeBasis = TripTimeBasis.MANUAL_SCENARIO),
            saved.copy(timeBasis = TripTimeBasis.ELAPSED_REALTIME)).forEach { invalid ->
            val error = assertThrows(RealTripRestoreException::class.java) {
                RealTripPersistence.restore(invalid, clock.scenarioMs)
            }
            assertEquals(RepositoryFailure.UNSUPPORTED_TIME_BASIS, error.reason)
        }
        val reversed = assertThrows(RealTripRestoreException::class.java) {
            RealTripPersistence.restore(saved, clock.scenarioMs - 1)
        }
        assertEquals(RepositoryFailure.UNSUPPORTED_TIME_BASIS, reversed.reason)
        val corrupt = saved.copy(ownerState = RealTripCheckpointCodec.encode(RealTripOwnerState(Gear.P, null)))
        val error = assertThrows(RealTripRestoreException::class.java) {
            RealTripPersistence.restore(corrupt, clock.scenarioMs)
        }
        assertEquals(RepositoryFailure.INVALID_DATA, error.reason)
    }
}
