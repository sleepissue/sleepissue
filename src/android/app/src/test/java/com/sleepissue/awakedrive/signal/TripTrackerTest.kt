package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.DriveSegment
import com.sleepissue.awakedrive.engine.model.DriveState
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.PowerEvent
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.SignalQuality
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.engine.session.ShortStopAccumulationPolicy
import com.sleepissue.awakedrive.engine.session.StopTimePolicy
import com.sleepissue.awakedrive.engine.session.TripObservation
import com.sleepissue.awakedrive.engine.session.TripRulePolicy
import com.sleepissue.awakedrive.engine.session.TripRuleState
import com.sleepissue.awakedrive.engine.session.TripRules
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripTrackerTest {
    @Test
    fun hubSamplesReachTrackerWithoutLosingSubsecondPrecisionAndStopIsExplicitlyUnknown() = runTest {
        val source = FakeSignalSource()
        val hub = VehicleSignalHub(source, FakeClock())
        val store = TripStateStore()
        val tracker = TripTracker(rules(), store, sessionSource = Source.REAL)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            hub.samples.collect { tracker.accept(it) }
        }
        hub.start(backgroundScope)
        listOf(0L, 600L, 1_200L).forEach { tMs ->
            source.emit(VehicleSignalInput(tMs, Source.REAL, gear = Gear.D))
            runCurrent()
        }

        assertEquals(1L, store.state.value.continuousDriveSec)
        assertEquals(1_200L, tracker.preciseState().continuousDriveMs)
        assertEquals(1L, store.state.value.last24hDriveSec)
        hub.stop()
        // 소유자가 수집 STOPPED/FAILED를 보고 명시한다. 앱 종료를 OFF로 바꾸지 않는다.
        tracker.markInputUnavailable(tMs = 1_200)
        source.emit(VehicleSignalInput(2_200, Source.REAL, gear = Gear.D))
        runCurrent()

        assertEquals(DriveState.UNKNOWN, store.state.value.driveState)
        assertEquals(1_200L, tracker.preciseState().continuousDriveMs)
        assertEquals(0, source.activeSubscriptions)
    }

    @Test
    fun missingQualityAndOtherSessionSourceAreIgnoredWithoutAdvancingTheSessionClock() {
        val generated = AtomicInteger()
        val tracker = TripTracker(rules { "trip-${generated.incrementAndGet()}" },
            TripStateStore(), sessionSource = Source.REAL)

        assertNull(tracker.accept(SignalFrame(9_000, gear = Gear.D)))
        assertNull(tracker.accept(frame(9_000, Gear.D, source = Source.DEMO)))
        assertEquals(0, generated.get())
        tracker.accept(frame(0, Gear.D))
        assertNull(tracker.accept(frame(1_000_000, Gear.P, source = Source.DEMO)))
        tracker.accept(frame(1_000, Gear.D))

        assertEquals(1, generated.get())
        assertEquals(1_000L, tracker.preciseState().continuousDriveMs)
        assertEquals(Gear.D, tracker.state.value.lastGear)
    }

    @Test
    fun sameSessionUnavailableGearIsAnUnknownObservationRatherThanADrive() {
        val tracker = TripTracker(rules(), TripStateStore(), sessionSource = Source.REAL)

        val result = tracker.accept(frame(0, Gear.D, availability = SignalAvailability.PERMISSION_DENIED))

        assertNotNull(result)
        assertNull(tracker.state.value.tripId)
        assertEquals(DriveState.UNKNOWN, tracker.state.value.driveState)
        assertNull(tracker.state.value.lastGear)
    }

    @Test
    fun explicitInputLossStopsAccumulationUntilFreshDriveObservations() {
        val tracker = TripTracker(rules(), TripStateStore(), sessionSource = Source.REAL)
        tracker.accept(frame(0, Gear.D))
        tracker.accept(frame(1_000, Gear.D))
        tracker.markInputUnavailable(1_000)
        tracker.markInputUnavailable(100_000)

        assertEquals(1_000L, tracker.preciseState().continuousDriveMs)
        assertEquals(DriveState.UNKNOWN, tracker.state.value.driveState)
        tracker.accept(frame(100_000, Gear.D))
        tracker.accept(frame(101_000, Gear.D))
        assertEquals(2_000L, tracker.preciseState().continuousDriveMs)
    }

    @Test
    fun standalonePowerOnDoesNotReuseThePreviouslyObservedGear() {
        val tracker = TripTracker(rules(), TripStateStore(), sessionSource = Source.REAL)
        tracker.accept(frame(0, Gear.D))
        tracker.observePower(power(1_000, PowerState.OFF))
        tracker.observePower(power(2_000, PowerState.ON))

        assertEquals(DriveState.UNKNOWN, tracker.state.value.driveState)
        assertNull(tracker.preciseState().lastObservation!!.gear)
        assertNull(tracker.observePower(power(3_000, PowerState.OFF, source = Source.DEMO)))
        assertEquals(DriveState.UNKNOWN, tracker.state.value.driveState)
    }

    @Test
    fun externallyOwnedStageBaselineAndLocationSurviveOrdinaryObservations() {
        val store = TripStateStore()
        val tracker = TripTracker(rules(), store, sessionSource = Source.REAL)
        tracker.accept(frame(0, Gear.D))
        val location = GeoPoint(37.0, 127.0)
        store.update { it.copy(stage = InterventionStage.CHECK,
            baselineRef = "baseline-updated", lastLocation = location) }

        tracker.accept(frame(1_000, Gear.D))

        assertEquals(InterventionStage.CHECK, store.state.value.stage)
        assertEquals("baseline-updated", store.state.value.baselineRef)
        assertEquals(location, store.state.value.lastLocation)
        assertEquals(store.state.value, tracker.preciseState().trip)
    }

    @Test
    fun nonemptyStoreRequiresMatchingPreciseInitialStateInsteadOfInventingSegments() {
        val state = TripState(
            tripId = "stored-trip",
            continuousDriveSec = 1,
            last24hDriveSec = 1,
            runStartMs = 0,
            lastGear = Gear.D,
            driveState = DriveState.DRIVING,
        )
        val store = TripStateStore(state)
        assertThrows(IllegalArgumentException::class.java) {
            TripTracker(rules(), store, sessionSource = Source.REAL)
        }
        assertEquals(state, store.state.value)

        val precise = TripRuleState(
            trip = state,
            continuousDriveMs = 1_500,
            driveSegments = listOf(DriveSegment(0, 1_500)),
            lastObservation = TripObservation(1_500, Gear.D, PowerState.ON),
        )
        val tracker = TripTracker(rules(), store, sessionSource = Source.REAL, initialRuleState = precise)
        assertEquals(precise, tracker.preciseState())
        assertEquals(state, store.state.value)

        val continued = tracker.accept(frame(2_100, Gear.D))!!
        assertEquals(2_100L, continued.state.continuousDriveMs)
        assertEquals(2L, store.state.value.continuousDriveSec)
        assertEquals(2L, store.state.value.last24hDriveSec)
        assertEquals("stored-trip", store.state.value.tripId)
        assertEquals(0, continued.events.size)
    }

    @Test
    fun uncoordinatedOwnedStateChangesAreRejectedInsteadOfOverwritten() {
        val store = TripStateStore()
        val tracker = TripTracker(rules(), store, sessionSource = Source.REAL)
        tracker.accept(frame(0, Gear.D))
        store.update { it.copy(continuousDriveSec = 99) }

        assertThrows(IllegalStateException::class.java) { tracker.accept(frame(1_000, Gear.D)) }
        assertEquals(99L, store.state.value.continuousDriveSec)
        assertThrows(IllegalStateException::class.java) { tracker.preciseState() }
    }

    @Test
    fun differentTimestampPowerCannotBeSilentlyCombinedWithAFrame() {
        val tracker = TripTracker(rules(), TripStateStore(), sessionSource = Source.REAL)
        assertThrows(IllegalArgumentException::class.java) {
            tracker.accept(frame(0, Gear.D), power(1_000, PowerState.OFF))
        }
        assertNull(tracker.state.value.tripId)
        assertEquals(TripRuleState(), tracker.preciseState())
    }

    @Test
    fun concurrentSameTimestampFirstDriveRequestsOneIdAndPreservesExternalUpdates() = runTest {
        val generated = AtomicInteger()
        val store = TripStateStore()
        val tracker = TripTracker(rules { "trip-${generated.incrementAndGet()}" },
            store, sessionSource = Source.REAL)
        List(8) {
            launch(Dispatchers.Default) {
                tracker.accept(frame(0, Gear.D))
                store.update { it.copy(stage = InterventionStage.CHECK, baselineRef = "updated") }
            }
        }.joinAll()

        assertEquals(1, generated.get())
        assertEquals("trip-1", store.state.value.tripId)
        assertEquals(InterventionStage.CHECK, store.state.value.stage)
        assertEquals("updated", store.state.value.baselineRef)
    }

    private fun rules(newTripId: () -> String = { "trip-1" }) = TripRules(
        config = EngineConfig(),
        // [가정] 실행 계획 v0.3 정차 보정과 짧은 정차 누적 제외를 명시적으로 고른다.
        policy = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE),
        newTripId = newTripId,
    )

    private fun frame(
        tMs: Long,
        gear: Gear?,
        source: Source = Source.REAL,
        availability: SignalAvailability = SignalAvailability.AVAILABLE,
    ) = SignalFrame(tMs, gear = gear,
        quality = mapOf(SignalField.GEAR to SignalQuality(availability, source)))

    private fun power(tMs: Long, state: PowerState, source: Source = Source.REAL) =
        PowerEvent(tMs, state, SignalQuality(SignalAvailability.AVAILABLE, source))
}
