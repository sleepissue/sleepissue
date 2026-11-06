package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.TripSnapshot
import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TripRestoreStatus { NOT_REQUESTED, LOADING, EMPTY, RESTORED, FAILED }
enum class TripStorageOperation { NOT_CONNECTED, IDLE, SAVING, SAVED, SAVE_FAILED, DELETED, DELETE_FAILED }

data class TripPersistenceState(
    val restore: TripRestoreStatus = TripRestoreStatus.NOT_REQUESTED,
    val operation: TripStorageOperation = TripStorageOperation.NOT_CONNECTED,
    val failure: RepositoryFailure? = null,
    val lastSavedScenarioMs: Long? = null,
    val lastSavedWallMs: Long? = null,
    val lastSavedElapsedMs: Long? = null,
    val timerBasis: SaveTimerBasis = SaveTimerBasis.SCENARIO,
    val autoSaveEnabled: Boolean = false,
    val successfulSaves: Long = 0,
)

/** 저장 타이머만 담당한다. 시동 OFF·운전 시간 증가·프로세스 종료를 추론하지 않는다. */
class TripSaveCoordinator(
    private val repository: TripRepository,
    private val clock: Clock,
    private val config: EngineConfig = EngineConfig(),
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TripPersistenceState(operation = TripStorageOperation.IDLE))
    val state: StateFlow<TripPersistenceState> = mutableState.asStateFlow()
    private var lastAttemptMs = 0L

    suspend fun load(): TripLoadResult = mutex.withLock {
        mutableState.value = state.value.copy(restore = TripRestoreStatus.LOADING, failure = null)
        val result = try { repository.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { TripLoadResult.Failed(RepositoryFailure.UNAVAILABLE) }
        when (result) {
            TripLoadResult.Empty -> mutableState.value = state.value.copy(restore = TripRestoreStatus.EMPTY)
            is TripLoadResult.Failed -> restoredFailure(result.reason)
            is TripLoadResult.Loaded -> Unit // Tracker·개입 복원이 끝난 다음에만 성공으로 표시한다.
        }
        result
    }

    fun restored(snapshot: TripSnapshot) {
        mutableState.value = state.value.copy(restore = TripRestoreStatus.RESTORED,
            lastSavedScenarioMs = snapshot.savedAtScenarioMs, lastSavedWallMs = snapshot.savedAtWallMs,
            lastSavedElapsedMs = snapshot.savedAtElapsedMs, failure = null)
    }

    fun restoredFailure(reason: RepositoryFailure) {
        mutableState.value = state.value.copy(restore = TripRestoreStatus.FAILED, failure = reason, autoSaveEnabled = false)
    }

    fun memoryReset() {
        mutableState.value = state.value.copy(restore = TripRestoreStatus.NOT_REQUESTED, failure = null, autoSaveEnabled = false)
    }

    fun configure(basis: SaveTimerBasis, scenarioMs: Long, enabled: Boolean) {
        lastAttemptMs = if (basis == SaveTimerBasis.SCENARIO) scenarioMs else clock.elapsedRealtimeMs()
        mutableState.value = state.value.copy(timerBasis = basis, autoSaveEnabled = enabled)
    }

    suspend fun maybeSave(scenarioMs: Long, capture: suspend () -> TripSnapshot): Boolean = mutex.withLock {
        if (!state.value.autoSaveEnabled) return@withLock false
        val now = timerNow(scenarioMs)
        if (now < lastAttemptMs) {
            restoredFailure(RepositoryFailure.UNSUPPORTED_TIME_BASIS)
            return@withLock false
        }
        if (now - lastAttemptMs < config.stateSaveIntervalMs) return@withLock false
        lastAttemptMs = now
        save(capture)
    }

    suspend fun saveNow(scenarioMs: Long, capture: suspend () -> TripSnapshot): Boolean = mutex.withLock {
        lastAttemptMs = timerNow(scenarioMs)
        mutableState.value = state.value.copy(autoSaveEnabled = true)
        save(capture)
    }

    suspend fun delete(): Boolean = mutex.withLock {
        // 삭제한 직후 주기 작업이 DB를 다시 만들지 않도록 명시적으로 재개할 때까지 멈춘다.
        mutableState.value = state.value.copy(autoSaveEnabled = false)
        val result = try { repository.delete() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE) }
        if (result == RepositoryResult.Succeeded) {
            mutableState.value = state.value.copy(operation = TripStorageOperation.DELETED, failure = null,
                lastSavedScenarioMs = null, lastSavedWallMs = null, lastSavedElapsedMs = null)
            true
        } else {
            mutableState.value = state.value.copy(operation = TripStorageOperation.DELETE_FAILED,
                failure = (result as RepositoryResult.Failed).reason)
            false
        }
    }

    private suspend fun save(capture: suspend () -> TripSnapshot): Boolean {
        mutableState.value = state.value.copy(operation = TripStorageOperation.SAVING, failure = null)
        val snapshot = try { capture() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.value = state.value.copy(operation = TripStorageOperation.SAVE_FAILED, failure = RepositoryFailure.INVALID_DATA)
            return false
        }
        val result = try { repository.save(snapshot) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE) }
        if (result == RepositoryResult.Succeeded) {
            val saved = snapshot
            mutableState.value = state.value.copy(operation = TripStorageOperation.SAVED, failure = null,
                lastSavedScenarioMs = saved.savedAtScenarioMs, lastSavedWallMs = saved.savedAtWallMs,
                lastSavedElapsedMs = saved.savedAtElapsedMs, successfulSaves = state.value.successfulSaves + 1)
            return true
        }
        mutableState.value = state.value.copy(operation = TripStorageOperation.SAVE_FAILED,
            failure = (result as RepositoryResult.Failed).reason)
        return false
    }

    private fun timerNow(scenarioMs: Long) = when (state.value.timerBasis) {
        SaveTimerBasis.SCENARIO -> scenarioMs
        SaveTimerBasis.ELAPSED_REALTIME -> clock.elapsedRealtimeMs()
    }
}
