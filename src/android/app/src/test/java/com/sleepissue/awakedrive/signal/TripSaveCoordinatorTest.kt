package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TripSaveCoordinatorTest {
    private class Repository : TripRepository {
        val saved = mutableListOf<TripSnapshot>()
        var saveFailure = false
        var deleteFailure = false
        override suspend fun load() = saved.lastOrNull()?.let { TripLoadResult.Loaded(it) } ?: TripLoadResult.Empty
        override suspend fun save(snapshot: TripSnapshot): RepositoryResult =
            if (saveFailure) RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE)
            else { saved += snapshot; RepositoryResult.Succeeded }
        override suspend fun delete(): RepositoryResult = if (deleteFailure) RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE)
            else { saved.clear(); RepositoryResult.Succeeded }
    }

    @Test fun scenarioSavesEveryTenSecondsAndExplicitSaveResetsTheAnchor() = runTest {
        val repository = Repository(); val clock = FakeClock()
        val coordinator = TripSaveCoordinator(repository, clock)
        var scenario = 0L
        val capture = suspend { TripSnapshot(TripState(), clock.wallMs, savedAtScenarioMs = scenario) }
        coordinator.configure(SaveTimerBasis.SCENARIO, 0, true)
        for (second in 1L..120) { scenario = second * 1_000; coordinator.maybeSave(scenario, capture) }
        assertEquals((10L..120 step 10).map { it * 1_000 }, repository.saved.map { it.savedAtScenarioMs })
        assertEquals(0L, clock.elapsedMs)
        scenario = 125_000; assertTrue(coordinator.saveNow(scenario, capture))
        scenario = 134_999; assertFalse(coordinator.maybeSave(scenario, capture))
        scenario = 135_000; assertTrue(coordinator.maybeSave(scenario, capture))
    }

    @Test fun elapsedTimerDoesNotDependOnScenarioAcceleration() = runTest {
        val repository = Repository(); val clock = FakeClock()
        val coordinator = TripSaveCoordinator(repository, clock)
        val capture = suspend { TripSnapshot(TripState(), 0, savedAtScenarioMs = 5_000) }
        coordinator.configure(SaveTimerBasis.ELAPSED_REALTIME, 0, true)
        assertFalse(coordinator.maybeSave(900_000, capture))
        clock.elapsedMs = 9_999; assertFalse(coordinator.maybeSave(900_000, capture))
        clock.elapsedMs = 10_000; assertTrue(coordinator.maybeSave(900_000, capture))
        assertEquals(5_000L, repository.saved.single().savedAtScenarioMs)
    }

    @Test fun failedSavePreservesLastSuccessAndRetriesOnTheNextPeriod() = runTest {
        val repository = Repository(); val clock = FakeClock()
        val coordinator = TripSaveCoordinator(repository, clock)
        var scenario = 0L
        val capture = suspend { TripSnapshot(TripState(), 0, savedAtScenarioMs = scenario) }
        coordinator.configure(SaveTimerBasis.SCENARIO, 0, true)
        assertTrue(coordinator.saveNow(scenario, capture))
        repository.saveFailure = true; scenario = 10_000
        assertFalse(coordinator.maybeSave(scenario, capture))
        assertEquals(TripStorageOperation.SAVE_FAILED, coordinator.state.value.operation)
        assertEquals(0L, coordinator.state.value.lastSavedScenarioMs)
        assertEquals(1L, coordinator.state.value.successfulSaves)
        repository.saveFailure = false
        scenario = 19_999; assertFalse(coordinator.maybeSave(scenario, capture))
        scenario = 20_000; assertTrue(coordinator.maybeSave(scenario, capture))
    }

    @Test fun deleteAndMemoryResetPauseAutoSaveWithoutPretendingDeletionSucceeded() = runTest {
        val repository = Repository(); val coordinator = TripSaveCoordinator(repository, FakeClock())
        val capture = suspend { TripSnapshot(TripState(), 0) }
        coordinator.saveNow(0, capture)
        repository.deleteFailure = true
        assertFalse(coordinator.delete())
        assertEquals(TripStorageOperation.DELETE_FAILED, coordinator.state.value.operation)
        assertEquals(1, repository.saved.size)
        repository.deleteFailure = false
        assertTrue(coordinator.delete())
        assertFalse(coordinator.maybeSave(1_000_000, capture))
        assertTrue(repository.saved.isEmpty())
        coordinator.saveNow(0, capture); coordinator.memoryReset()
        assertEquals(1, repository.saved.size)
        assertFalse(coordinator.state.value.autoSaveEnabled)
    }

    @Test fun elapsedClockReversalIsAnExplicitUnsupportedRestoreCondition() = runTest {
        val clock = FakeClock(elapsedMs = 100_000)
        val coordinator = TripSaveCoordinator(Repository(), clock)
        coordinator.configure(SaveTimerBasis.ELAPSED_REALTIME, 0, true)
        clock.elapsedMs = 10
        assertFalse(coordinator.maybeSave(0) { TripSnapshot(TripState(), 0) })
        assertEquals(RepositoryFailure.UNSUPPORTED_TIME_BASIS, coordinator.state.value.failure)
        assertFalse(coordinator.state.value.autoSaveEnabled)
    }
}
