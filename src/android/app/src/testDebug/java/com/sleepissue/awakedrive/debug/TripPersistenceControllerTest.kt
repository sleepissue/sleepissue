package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripPersistenceControllerTest {
    private class Repository : TripRepository {
        var stored: TripSnapshot? = null
        var failSave = false
        val saved = mutableListOf<TripSnapshot>()
        override suspend fun load() = stored?.let { TripLoadResult.Loaded(it) } ?: TripLoadResult.Empty
        override suspend fun save(snapshot: TripSnapshot): RepositoryResult {
            if (failSave) return RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE)
            stored = snapshot; saved += snapshot
            return RepositoryResult.Succeeded
        }
        override suspend fun delete(): RepositoryResult { stored = null; return RepositoryResult.Succeeded }
    }

    private suspend fun TestScope.open(repository: Repository, clock: FakeClock = FakeClock()): DebugHarnessController =
        DebugHarnessController(backgroundScope, repository, clock).also { it.initialize(); runCurrent() }

    private fun DebugHarnessController.check(context: CheckContext): CheckRequest = state.value.requests
        .map { it.request }.filterIsInstance<CheckRequest>().last { it.context == context }

    @Test fun savedTwoMinuteDriveAndThreeMinuteStopContinueWithExactStopEvidence() = runTest {
        val repository = Repository(); val actualClock = FakeClock(wallMs = 1_000_000, elapsedMs = 500_000)
        val first = open(repository, actualClock)
        assertEquals(TripRestoreStatus.EMPTY, first.state.value.persistence.restore)
        first.setGear(Gear.D); first.advanceTime(120_000)
        first.setGear(Gear.P); first.advanceTime(180_000); first.saveNow()
        val saved = requireNotNull(repository.stored)
        val tripId = saved.state.tripId
        assertEquals(300_000L, saved.savedAtScenarioMs)
        assertEquals(120_000L, saved.preciseState?.continuousBeforeStopMs)
        assertEquals(120_000L, saved.preciseState?.stopStartedAtMs)
        assertEquals(120L, saved.state.last24hDriveSec)
        first.close()
        actualClock.wallMs += 3_600_000; actualClock.elapsedMs += 3_600_000
        val restored = open(repository, actualClock)
        assertEquals(TripRestoreStatus.RESTORED, restored.state.value.persistence.restore)
        assertEquals(300_000L, restored.state.value.timeMs)
        assertEquals(saved.state, restored.state.value.trip)
        assertEquals(300L, restored.state.value.trip.continuousDriveSec)
        assertTrue(restored.state.value.requests.isEmpty())
        restored.advanceTime(120_000)
        assertEquals(120L, restored.state.value.trip.continuousDriveSec)
        restored.advanceTime(600_000)
        assertEquals(0L, restored.state.value.trip.continuousDriveSec)
        assertEquals(120L, restored.state.value.trip.last24hDriveSec)
        assertEquals(tripId, restored.state.value.trip.tripId)
    }

    @Test fun processExitIsNotOffAndRealDowntimeIsNotDriving() = runTest {
        val repository = Repository(); val clock = FakeClock()
        val first = open(repository, clock)
        first.setGear(Gear.D); first.advanceTime(120_000); first.saveNow(); first.close()
        clock.elapsedMs = 8_000_000; clock.wallMs = 8_000_000
        val second = open(repository, clock)
        assertEquals(PowerState.ON, second.state.value.power)
        assertEquals(DriveState.DRIVING, second.state.value.trip.driveState)
        assertEquals(120L, second.state.value.trip.continuousDriveSec)
        second.advanceTime(30_000)
        assertEquals(150L, second.state.value.trip.continuousDriveSec)
        assertNull(repository.stored?.preciseState?.offStartedAtMs)
    }

    @Test fun slowStreakAndRemainingRetryWaitSurviveWithoutReplayingOldQuestion() = runTest {
        val repository = Repository()
        val first = open(repository)
        first.applyAssessment(64f, true); first.advanceTime(30_000); runCurrent()
        val old = first.check(CheckContext.DRIVER_ALERTNESS)
        first.completeCheck(old.requestId, CheckOutcome.SLOW); first.saveNow(); first.close()
        val second = open(repository)
        assertEquals(InterventionStage.CHECK, second.state.value.intervention.stage)
        assertTrue(second.state.value.requests.isEmpty())
        assertFalse(second.state.value.canResumeChecks)
        second.resumeInterruptedChecks(); runCurrent()
        assertTrue(second.state.value.requests.isEmpty())
        second.advanceTime(29_999); assertFalse(second.state.value.canResumeChecks)
        second.advanceTime(1); assertTrue(second.state.value.canResumeChecks)
        second.resumeInterruptedChecks(); runCurrent()
        val resumed = second.check(CheckContext.DRIVER_ALERTNESS)
        assertNotEquals(old.requestId, resumed.requestId)
        second.completeCheck(old.requestId, CheckOutcome.NO_RESPONSE)
        assertEquals(InterventionStage.CHECK, second.state.value.intervention.stage)
        second.completeCheck(resumed.requestId, CheckOutcome.SLOW); runCurrent()
        assertEquals(InterventionStage.REST_GUIDANCE, second.state.value.intervention.stage)
        assertEquals(TransitionReason.CHECK_RESULT, second.state.value.intervention.reason)
        assertEquals(SpeechCue.REST_RECOMMENDED,
            second.state.value.requests.map { it.request }.filterIsInstance<SpeechRequest>().single().cue)
    }

    @Test fun stageThreeStillRequiresCertificationAndExplicitResumedNormalRecovery() = runTest {
        val repository = Repository()
        val first = open(repository)
        first.applyAssessment(90f, true); first.advanceTime(60_000); first.saveNow(); first.close()
        val second = open(repository)
        assertEquals(InterventionStage.REST_GUIDANCE, second.state.value.trip.stage)
        assertEquals(TransitionReason.SCORE, second.state.value.intervention.reason)
        assertTrue(second.state.value.requests.isEmpty())
        assertFalse(second.state.value.canResumeChecks)
        second.completeCheck(RequestId("old-unproven-recovery"), CheckOutcome.NORMAL)
        assertEquals(InterventionStage.REST_GUIDANCE, second.state.value.intervention.stage)
        second.certifyRest(); runCurrent()
        val oldRecovery = second.check(CheckContext.RECOVERY)
        second.saveNow(); second.close()
        val third = open(repository)
        assertEquals(InterventionStage.REST_GUIDANCE, third.state.value.intervention.stage)
        assertTrue(third.state.value.requests.isEmpty())
        assertTrue(third.state.value.canResumeChecks)
        third.resumeInterruptedChecks(); runCurrent()
        val recovery = third.check(CheckContext.RECOVERY)
        assertNotEquals(oldRecovery.requestId, recovery.requestId)
        third.completeCheck(recovery.requestId, CheckOutcome.NORMAL); runCurrent()
        assertEquals(InterventionStage.NORMAL, third.state.value.intervention.stage)
        assertEquals(InterventionStage.NORMAL, third.state.value.trip.stage)
        assertEquals(TransitionReason.REST_COMPLETED, third.state.value.intervention.reason)
        assertNull(third.state.value.assessmentScore)
        third.saveNow(); third.close()
        val fourth = open(repository)
        assertEquals(InterventionStage.NORMAL, fourth.state.value.intervention.stage)
        assertFalse(fourth.state.value.canResumeChecks)
        assertTrue(fourth.state.value.requests.isEmpty())
    }

    @Test fun resetPreservesDatabaseAndPausesWritesUntilAnExplicitSaveOrResume() = runTest {
        val repository = Repository(); val first = open(repository)
        first.setGear(Gear.D); first.advanceTime(120_000); first.saveNow()
        val saved = repository.stored
        first.reset(); first.advanceTime(60_000)
        assertFalse(first.state.value.persistence.autoSaveEnabled)
        assertEquals(saved, repository.stored)
        first.close()
        val second = open(repository)
        assertEquals(120_000L, second.state.value.timeMs)
        assertEquals(120L, second.state.value.trip.continuousDriveSec)
    }

    @Test fun databaseDeletionPreservesMemoryAndCannotImmediatelyRecreateSavedData() = runTest {
        val repository = Repository(); val first = open(repository)
        first.setGear(Gear.D); first.advanceTime(120_000); first.saveNow()
        first.deleteSavedData()
        assertEquals(120L, first.state.value.trip.continuousDriveSec)
        assertNull(repository.stored)
        first.advanceTime(30_000)
        assertNull(repository.stored)
        first.close()
        val second = open(repository)
        assertEquals(TripRestoreStatus.EMPTY, second.state.value.persistence.restore)
        assertEquals(0L, second.state.value.timeMs)
    }

    @Test fun failedSaveDoesNotClaimSuccessAndCorruptRestoreCannotOverwriteTheDatabase() = runTest {
        val repository = Repository(); val first = open(repository)
        first.setGear(Gear.D); first.advanceTime(30_000); first.saveNow()
        val original = requireNotNull(repository.stored)
        repository.failSave = true
        first.advanceTime(30_000); first.saveNow()
        assertEquals(TripStorageOperation.SAVE_FAILED, first.state.value.persistence.operation)
        assertEquals(30_000L, first.state.value.persistence.lastSavedScenarioMs)
        assertEquals(original, repository.stored)
        first.close(); repository.failSave = false
        val invalid = original.copy(state = original.state.copy(continuousDriveSec = 999))
        repository.stored = invalid
        val second = open(repository)
        assertEquals(TripRestoreStatus.FAILED, second.state.value.persistence.restore)
        assertEquals(RepositoryFailure.INVALID_DATA, second.state.value.persistence.failure)
        assertFalse(second.state.value.persistence.autoSaveEnabled)
        second.advanceTime(30_000)
        assertEquals(invalid, repository.stored)
    }

    @Test fun oldRealInputAndElapsedTimelineAreExplicitlyRejected() = runTest {
        val repository = Repository(); val first = open(repository)
        first.saveNow(); first.close()
        val valid = requireNotNull(repository.stored)
        repository.stored = valid.copy(source = Source.REAL)
        val real = open(repository)
        assertEquals(RepositoryFailure.STALE_INPUT, real.state.value.persistence.failure)
        assertFalse(real.state.value.persistence.autoSaveEnabled)
        real.close()
        repository.stored = valid.copy(timeBasis = TripTimeBasis.ELAPSED_REALTIME)
        val elapsed = open(repository)
        assertEquals(RepositoryFailure.UNSUPPORTED_TIME_BASIS, elapsed.state.value.persistence.failure)
        assertFalse(elapsed.state.value.persistence.autoSaveEnabled)
    }
}
