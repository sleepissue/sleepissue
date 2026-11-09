package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlaygroundTripSessionTest {
    private class Memory : TripRepository {
        var snapshot: TripSnapshot? = null
        var saves = 0
        var fail = false
        override suspend fun load() = snapshot?.let(TripLoadResult::Loaded) ?: TripLoadResult.Empty
        override suspend fun save(snapshot: TripSnapshot): RepositoryResult {
            if (fail) return RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE)
            this.snapshot = snapshot; saves++; return RepositoryResult.Succeeded
        }
        override suspend fun delete() = error("삭제 경로를 사용하면 안 됩니다.")
    }
    private val clock = FakeClock(scenarioMs = 1_800_000_000_000, wallMs = 1_800_000_000_000)
    private fun advance(ms: Long) { clock.scenarioMs += ms; clock.elapsedMs += ms; clock.wallMs += ms }
    private fun frame(gear: Gear?, source: Source = Source.REAL, status: SignalAvailability = SignalAvailability.AVAILABLE,
        sequence: Long? = null) = SignalFrame(clock.scenarioMs, gear = gear,
        quality = mapOf(SignalField.GEAR to SignalQuality(status, source)),
        publication = sequence?.let { FramePublication(1, it, clock.elapsedMs) })

    @Test fun dToPToDAndFiveFifteenMinuteBoundariesPreserveRollingHistory() = runTest {
        val c = PlaygroundTripSession(Memory(), clock); c.initialize(); val token = c.beginInput()
        c.accept(token, frame(Gear.D)); advance(120_000); c.accept(token, frame(Gear.P))
        advance(180_000); c.accept(token, frame(Gear.P))
        assertEquals(300L, c.trip.value.continuousDriveSec); assertEquals(120L, c.trip.value.last24hDriveSec)
        advance(120_000); c.accept(token, frame(Gear.P))
        assertEquals(120L, c.trip.value.continuousDriveSec)
        advance(600_000); c.accept(token, frame(Gear.P))
        assertEquals(0L, c.trip.value.continuousDriveSec); assertEquals(120L, c.trip.value.last24hDriveSec)
        c.accept(token, frame(Gear.D)); advance(12_345); c.accept(token, frame(Gear.D))
        assertEquals(12_345L, c.preciseState().continuousDriveMs); assertEquals(132L, c.trip.value.last24hDriveSec)
    }

    @Test fun errorsStopAtLastConfirmedFrameAndReconnectRejectsOldSessionAndDemo() = runTest {
        val c = PlaygroundTripSession(Memory(), clock); c.initialize(); val first = c.beginInput()
        c.accept(first, frame(Gear.D)); advance(4_000); c.accept(first, frame(Gear.D))
        advance(1_000); c.accept(first, frame(null, status = SignalAvailability.PERMISSION_DENIED))
        assertEquals(4_000L, c.preciseState().continuousDriveMs)
        assertEquals(DriveState.UNKNOWN, c.trip.value.driveState); assertNull(c.preciseState().lastObservation?.power)
        c.endInput(first); advance(600_000); val second = c.beginInput()
        assertFalse(c.accept(first, frame(Gear.D))); assertFalse(c.accept(second, frame(Gear.D, Source.DEMO)))
        c.accept(second, frame(Gear.D)); advance(1_000); c.accept(second, frame(Gear.D))
        assertEquals(5_000L, c.preciseState().continuousDriveMs)
    }

    @Test fun elapsedTenSecondSavesAndRestoreDoNotAddProcessGapOrChangeTripId() = runTest {
        val db = Memory(); val c = PlaygroundTripSession(db, clock); c.initialize(); val token = c.beginInput()
        c.accept(token, frame(Gear.D)); advance(9_999); c.accept(token, frame(Gear.D))
        assertFalse(c.autoSave()); assertEquals(0, db.saves)
        advance(1); c.accept(token, frame(Gear.D)); assertTrue(c.autoSave()); assertEquals(1, db.saves)
        val id = c.trip.value.tripId
        advance(600_000)
        val resumed = PlaygroundTripSession(db, clock); resumed.initialize()
        assertEquals(id, resumed.trip.value.tripId); assertEquals(10L, resumed.trip.value.continuousDriveSec)
        assertEquals(DriveState.UNKNOWN, resumed.trip.value.driveState)
        assertFalse(resumed.autoSave()); assertEquals(1, db.saves)
        val next = resumed.beginInput(); resumed.accept(next, frame(Gear.D)); advance(1_000); resumed.accept(next, frame(Gear.D))
        assertEquals(11L, resumed.trip.value.continuousDriveSec)
    }

    @Test fun failedSaveKeepsLastSuccessfulSnapshotAndRestoredStopProofIsNotExtended() = runTest {
        val db = Memory(); val c = PlaygroundTripSession(db, clock); c.initialize(); val token = c.beginInput()
        c.accept(token, frame(Gear.D)); advance(120_000); c.accept(token, frame(Gear.P))
        advance(180_000); c.accept(token, frame(Gear.P)); assertTrue(c.saveNow())
        val saved = db.snapshot!!; db.fail = true
        advance(1_000); c.accept(token, frame(Gear.P)); assertFalse(c.saveNow()); assertEquals(saved, db.snapshot)
        val resumed = PlaygroundTripSession(db, clock); advance(600_000); resumed.initialize()
        assertEquals(saved, resumed.restoredSnapshot); assertNull(resumed.preciseState().stopStartedAtMs)
        assertEquals(300L, resumed.trip.value.continuousDriveSec)
        assertEquals(120L, resumed.trip.value.last24hDriveSec)
    }

    @Test fun missingHubPublicationDoesNotProveTimeBetweenFrames() = runTest {
        val c = PlaygroundTripSession(Memory(), clock); c.initialize(); val token = c.beginInput()
        c.accept(token, frame(Gear.D, sequence = 1)); advance(1_000); c.accept(token, frame(Gear.D, sequence = 2))
        advance(60_000); c.accept(token, frame(Gear.D, sequence = 4))
        assertEquals(1_000L, c.preciseState().continuousDriveMs)
        assertFalse(c.accept(token, frame(Gear.P, sequence = 3)))
    }

    @Test fun epochClockUsesElapsedTimeWhileWallClockChanges() {
        val anchored = AnchoredTripClock(clock); val start = anchored.scenarioTimeMs()
        clock.wallMs -= 86_400_000; clock.elapsedMs += 10_000
        assertEquals(start + 10_000, anchored.scenarioTimeMs()); assertEquals(clock.wallMs, anchored.wallTimeMs())
    }

    @Test fun delayedOldSaveCannotReplaceNewerPreciseCheckpoint() = runTest {
        val db = Memory(); val c = PlaygroundTripSession(db, clock); c.initialize(); val token = c.beginInput()
        c.accept(token, frame(Gear.D)); advance(10_000); c.accept(token, frame(Gear.D))
        val old = c.capture()!!
        advance(10_000); c.accept(token, frame(Gear.P)); val newer = c.capture()!!
        assertTrue(c.saveNow(newer)); assertFalse(c.saveNow(old))
        assertEquals(newer, db.snapshot); assertEquals(1L, c.persistence.state.value.successfulSaves)
    }

    private fun confirmedFrame(gear: Gear, sequence: Long, confirmationMs: Long = clock.elapsedMs) =
        frame(gear, sequence = sequence).copy(quality = mapOf(SignalField.GEAR to SignalQuality(
            SignalAvailability.AVAILABLE, Source.REAL, stateConfirmedAtElapsedMs = confirmationMs,
            stateLeaseUntilElapsedMs = confirmationMs + 3_000)))

    @Test fun contiguousHubSequenceAfterSuspendCannotProveDrivingDuringExpiredLease() = runTest {
        val c = PlaygroundTripSession(Memory(), clock); c.initialize(); val token = c.beginInput()
        c.accept(token, confirmedFrame(Gear.D, 1))
        advance(1_000); c.accept(token, confirmedFrame(Gear.D, 2))
        val id = c.trip.value.tripId
        advance(900_000)
        assertTrue(c.accept(token, confirmedFrame(Gear.D, 3)))
        assertEquals(id, c.trip.value.tripId)
        assertEquals(1_000L, c.preciseState().continuousDriveMs)
        assertEquals(1L, c.trip.value.last24hDriveSec)
        assertNull(c.preciseState().lastObservation?.power)
        advance(1_000); c.accept(token, confirmedFrame(Gear.D, 4))
        assertEquals(2_000L, c.preciseState().continuousDriveMs)
    }

    @Test fun expiredLeaseCannotTurnUnobservedParkedGapIntoFifteenMinuteRest() = runTest {
        val c = PlaygroundTripSession(Memory(), clock); c.initialize(); val token = c.beginInput()
        c.accept(token, confirmedFrame(Gear.D, 1))
        advance(1_000); c.accept(token, confirmedFrame(Gear.P, 2))
        advance(1_000); c.accept(token, confirmedFrame(Gear.P, 3))
        assertEquals(2_000L, c.preciseState().continuousDriveMs)
        advance(900_000); assertTrue(c.accept(token, confirmedFrame(Gear.P, 4)))
        assertEquals(2_000L, c.preciseState().continuousDriveMs)
        assertEquals(clock.scenarioMs, c.preciseState().stopStartedAtMs)
        assertFalse(c.preciseState().restResetEmitted)
        assertNull(c.preciseState().offStartedAtMs)
    }

    @Test fun overlappingLeaseIncludingExactBoundaryPreservesObservedDriving() = runTest {
        val c = PlaygroundTripSession(Memory(), clock); c.initialize(); val token = c.beginInput()
        c.accept(token, confirmedFrame(Gear.D, 1))
        advance(3_000); assertFalse(c.accept(token, confirmedFrame(Gear.D, 2)))
        assertEquals(3_000L, c.preciseState().continuousDriveMs)
        advance(1_000); c.accept(token, confirmedFrame(Gear.D, 3))
        assertEquals(4_000L, c.preciseState().continuousDriveMs)
    }

    @Test fun atomicStopCapturesLastObservationAndRejectsLateCollectorAndTimer() = runTest {
        val db = Memory(); val c = PlaygroundTripSession(db, clock); c.initialize(); val token = c.beginInput()
        c.accept(token, frame(Gear.D)); advance(12_345); c.accept(token, frame(Gear.D))
        val final = c.finishInputAndCapture(token)!!
        assertFalse(c.connected); assertEquals(DriveState.UNKNOWN, c.trip.value.driveState)
        advance(60_000); assertFalse(c.accept(token, frame(Gear.D)))
        assertFalse(c.autoSave()); assertNull(c.finishInputAndCapture(token))
        assertTrue(c.saveNow(final))
        assertEquals(12_345L, db.snapshot?.preciseState?.continuousDriveMs)
        assertEquals(Gear.D, db.snapshot?.preciseState?.lastObservation?.gear)
        assertNull(db.snapshot?.preciseState?.lastObservation?.power)
    }

    @Test fun simultaneousInitializationWaitsForSingleCompletedRestore() = runTest {
        val loading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var loads = 0
        val db = object : TripRepository {
            override suspend fun load(): TripLoadResult { loads++; loading.complete(Unit); release.await(); return TripLoadResult.Empty }
            override suspend fun save(snapshot: TripSnapshot) = RepositoryResult.Succeeded
            override suspend fun delete() = error("삭제 금지")
        }
        val c = PlaygroundTripSession(db, clock)
        val first = async { c.initialize() }; loading.await()
        val second = async { c.initialize() }; runCurrent()
        assertFalse(c.initialized); assertFalse(second.isCompleted)
        assertThrows(IllegalStateException::class.java) { c.beginInput() }
        release.complete(Unit); first.await(); second.await()
        assertEquals(1, loads); assertTrue(c.initialized)
    }

    @Test fun rebootRestoresUnknownAndStartsSaveTimerFromNewBootElapsedClock() = runTest {
        val db = Memory(); clock.elapsedMs = 8_000_000
        val c = PlaygroundTripSession(db, clock); c.initialize(); val token = c.beginInput()
        c.accept(token, frame(Gear.D)); advance(12_345); c.accept(token, frame(Gear.D)); assertTrue(c.saveNow())
        val saved = db.snapshot!!
        val rebootClock = FakeClock(scenarioMs = clock.scenarioMs + 600_000,
            wallMs = clock.wallMs + 600_000, elapsedMs = 100)
        val resumed = PlaygroundTripSession(db, rebootClock); resumed.initialize()
        assertEquals(saved.state.tripId, resumed.trip.value.tripId)
        assertEquals(12_345L, resumed.preciseState().continuousDriveMs)
        assertEquals(DriveState.UNKNOWN, resumed.trip.value.driveState)
        assertNull(resumed.preciseState().lastObservation?.gear)
        assertNull(resumed.preciseState().lastObservation?.power)
        assertFalse(resumed.autoSave())
        val next = resumed.beginInput()
        fun nextFrame() = SignalFrame(rebootClock.scenarioMs, gear = Gear.D,
            quality = mapOf(SignalField.GEAR to SignalQuality(SignalAvailability.AVAILABLE, Source.REAL)))
        resumed.accept(next, nextFrame())
        rebootClock.scenarioMs += 9_999; rebootClock.elapsedMs += 9_999; rebootClock.wallMs += 9_999
        resumed.accept(next, nextFrame()); assertFalse(resumed.autoSave())
        rebootClock.scenarioMs++; rebootClock.elapsedMs++; rebootClock.wallMs++
        resumed.accept(next, nextFrame()); assertTrue(resumed.autoSave())
        assertEquals(22_345L, resumed.preciseState().continuousDriveMs)
        assertEquals(10_100L, db.snapshot?.savedAtElapsedMs)
    }
}
