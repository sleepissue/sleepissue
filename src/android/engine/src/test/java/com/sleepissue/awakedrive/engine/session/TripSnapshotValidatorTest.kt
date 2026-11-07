package com.sleepissue.awakedrive.engine.session

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import org.junit.Assert.*
import org.junit.Test

class TripSnapshotValidatorTest {
    private val config = EngineConfig()
    private val policy = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)
    private val rules = TripRules(config, policy) { "trip-one" }
    private fun parked(): TripRuleState {
        val driving = rules.reduce(TripRuleState(), TripObservation(0, Gear.D, PowerState.ON)).state
        val parked = rules.reduce(driving, TripObservation(120_000, Gear.P, PowerState.ON)).state
        return rules.reduce(parked, TripObservation(300_000, Gear.P, PowerState.ON)).state
    }
    private fun snapshot(state: TripRuleState) = TripSnapshot(state.trip, 1_000_000, state.driveSegments, 50_000,
        state, state.lastObservation?.tMs, policy, Source.DEMO, TripTimeBasis.MANUAL_SCENARIO)

    @Test fun preciseParkedStateContinuesAcrossFiveAndFifteenMinuteBoundaries() {
        val saved = snapshot(parked())
        val restored = TripSnapshotValidator.requireValid(saved)
        assertEquals(300L, restored.trip.continuousDriveSec)
        assertEquals(120_000L, restored.continuousBeforeStopMs)
        val atFive = rules.reduce(restored, TripObservation(420_000, Gear.P, PowerState.ON)).state
        assertEquals(120L, atFive.trip.continuousDriveSec)
        val atFifteen = rules.reduce(atFive, TripObservation(1_020_000, Gear.P, PowerState.ON)).state
        assertEquals(0L, atFifteen.trip.continuousDriveSec)
        assertEquals(120L, atFifteen.trip.last24hDriveSec)
        assertEquals(saved.state.tripId, atFifteen.trip.tripId)
    }

    @Test fun countersWithoutPreciseEvidenceAreNotAValidRestore() {
        assertThrows(IllegalArgumentException::class.java) {
            TripSnapshotValidator.requireValid(TripSnapshot(TripState(continuousDriveSec = 120), 0))
        }
    }

    @Test fun mismatchedPublicStateTimeOrStopPolicyIsRejected() {
        val saved = snapshot(parked())
        listOf(saved.copy(state = saved.state.copy(continuousDriveSec = 1)),
            saved.copy(savedAtScenarioMs = 300_001),
            saved.copy(rulePolicy = policy.copy(shortStopAccumulation = ShortStopAccumulationPolicy.INCLUDE)))
            .forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { TripSnapshotValidator.requireValid(invalid) } }
    }

    @Test fun subsecondDrivePrecisionSurvivesSnapshotAndFurtherCalculation() {
        var state = rules.reduce(TripRuleState(), TripObservation(0, Gear.D, PowerState.ON)).state
        state = rules.reduce(state, TripObservation(1_600, Gear.D, PowerState.ON)).state
        val restored = TripSnapshotValidator.requireValid(snapshot(state))
        val next = rules.reduce(restored, TripObservation(2_100, Gear.D, PowerState.ON)).state
        assertEquals(2_100L, next.continuousDriveMs)
        assertEquals(2L, next.trip.last24hDriveSec)
    }
}
