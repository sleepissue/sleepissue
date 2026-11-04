package com.sleepissue.awakedrive.data

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicyState
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.*
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 실기기 SQLite/Room에서 원자 저장·재개 근거·오류·삭제를 검증한다. SDK 입력은 사용하지 않는다. */
@RunWith(AndroidJUnit4::class)
class RoomTripRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: TripDatabase
    private lateinit var repository: RoomTripRepository
    private val policy = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)

    @Before fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, TripDatabase::class.java).build()
        repository = RoomTripRepository(database.checkpointDao())
    }
    @After fun close() { database.close() }

    private fun snapshot(): TripSnapshot {
        val rules = TripRules(EngineConfig(), policy) { "trip-room" }
        var state = rules.reduce(TripRuleState(), TripObservation(0, Gear.D, PowerState.ON)).state
        state = rules.reduce(state, TripObservation(120_000, Gear.P, PowerState.ON)).state
        state = rules.reduce(state, TripObservation(300_000, Gear.P, PowerState.ON)).state
        state = state.copy(trip = state.trip.copy(stage = InterventionStage.CHECK,
            baselineRef = "baseline-room", lastLocation = GeoPoint(37.5, 127.5)))
        val checkpoint = InterventionCheckpoint(
            policy = InterventionPolicyState(InterventionState(InterventionStage.CHECK,
                EvaluationStatus.EVALUATED, TransitionReason.SCORE), lastEvaluatedMs = 300_000,
                firstEvaluatedMs = 0, lastTransitionMs = 30_000, consecutiveSlow = 1),
            assessment = DrivingAssessment(300_000, EvaluationStatus.EVALUATED, 64f, DrivingPattern.TRENDING),
            tripContextAvailable = true, forecast = null, sensitive = false, vulnerable = false,
            ventilationConsent = false, alertnessContext = CheckContext.DRIVER_ALERTNESS,
            restSessionId = null, seenRestSessions = emptySet(), latestRestCertificationMs = null,
            recoveryCompletedAtMs = null, routeAdded = false,
            interruptedChecks = mapOf(CheckContext.DRIVER_ALERTNESS to 300_000L),
            enteredAt = mapOf(InterventionStage.CAUTION to 0L, InterventionStage.CHECK to 30_000L), assistedAnchor = null,
        )
        return TripSnapshot(state.trip, 1_000_000, state.driveSegments, 500_000, state,
            300_000, policy, Source.DEMO, TripTimeBasis.MANUAL_SCENARIO,
            TripSessionCodec.encode(TripSessionOwnerState(Gear.P, PowerState.ON, SaveTimerBasis.SCENARIO, checkpoint)))
    }

    @Test fun emptyAndTenFieldsPreciseSegmentsAndInterventionRoundTrip() = runBlocking {
        assertEquals(TripLoadResult.Empty, repository.load())
        val saved = snapshot()
        assertEquals(RepositoryResult.Succeeded, repository.save(saved))
        val loaded = (repository.load() as TripLoadResult.Loaded).snapshot
        assertEquals(saved, loaded)
        val row = requireNotNull(database.checkpointDao().checkpoint())
        assertEquals("trip-room", row.tripId)
        assertEquals(300L, row.continuousDriveSec)
        assertEquals("baseline-room", row.baselineRef)
        assertEquals("CHECK", row.stage)
        assertEquals(37.5, requireNotNull(row.lastLat), 0.0)
        assertEquals(127.5, requireNotNull(row.lastLon), 0.0)
        assertEquals("P", row.lastGear)
        assertEquals(0L, row.runStartMs)
        assertEquals(120_000L, row.stopStartMs)
        assertEquals("PAUSED", row.driveState)
        assertEquals(120L, loaded.state.last24hDriveSec)
        assertEquals(120_000L, loaded.preciseState?.continuousBeforeStopMs)
        val rules = TripRules(EngineConfig(), policy) { "unexpected-new-trip" }
        val continued = rules.reduce(requireNotNull(loaded.preciseState), TripObservation(420_000, Gear.P, PowerState.ON)).state
        assertEquals(120L, continued.trip.continuousDriveSec)
        assertEquals(120L, continued.trip.last24hDriveSec)
    }

    @Test fun fileDatabaseCloseAndReopenKeepsTheExactResumeState() = runBlocking {
        val name = "trip-reopen-${UUID.randomUUID()}.db"
        try {
            val first = TripDatabase.open(context, name)
            val saved = snapshot()
            assertEquals(RepositoryResult.Succeeded, RoomTripRepository(first.checkpointDao()).save(saved))
            first.close()
            val second = TripDatabase.open(context, name)
            try { assertEquals(saved, (RoomTripRepository(second.checkpointDao()).load() as TripLoadResult.Loaded).snapshot) }
            finally { second.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun offStartPendingNewTripAndSubsecondPrecisionResumeAfterRoomRoundTrip() = runBlocking {
        val config = EngineConfig()
        val rules = TripRules(config, policy) { "trip-after-off" }
        val initial = snapshot()
        val offAt = 300_777L
        val off = rules.reduce(requireNotNull(initial.preciseState), TripObservation(offAt, Gear.P, PowerState.OFF)).state
        val now = offAt + config.newTripAfterOffMs + 1
        val pending = rules.reduce(off, TripObservation(now, Gear.P, PowerState.OFF)).state
        val owner = TripSessionCodec.decode(requireNotNull(initial.ownerState))
        val checkpoint = owner.intervention.copy(policy = owner.intervention.policy.copy(lastEvaluatedMs = now))
        val saved = initial.copy(state = pending.trip, preciseState = pending, driveSegments = pending.driveSegments,
            savedAtScenarioMs = now, ownerState = TripSessionCodec.encode(owner.copy(power = PowerState.OFF, intervention = checkpoint)))
        assertEquals(RepositoryResult.Succeeded, repository.save(saved))
        val loaded = (repository.load() as TripLoadResult.Loaded).snapshot
        assertEquals(saved, loaded)
        assertEquals(offAt, loaded.preciseState?.offStartedAtMs)
        assertTrue(requireNotNull(loaded.preciseState).pendingNewTrip)
        val resumed = rules.reduce(requireNotNull(loaded.preciseState), TripObservation(now, Gear.D, PowerState.ON)).state
        assertEquals("trip-after-off", resumed.trip.tripId)
        assertFalse(resumed.pendingNewTrip)
        assertEquals(0L, resumed.continuousDriveMs)
        assertEquals(120L, resumed.trip.last24hDriveSec)
    }

    @Test fun failedReplacementRollsBackStateAndSegmentsTogether() = runBlocking {
        val saved = snapshot()
        repository.save(saved)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_checkpoint BEFORE INSERT ON trip_checkpoint BEGIN SELECT RAISE(ABORT, '시험용 저장 실패'); END")
        val result = repository.save(saved.copy(savedAtWallMs = 2_000_000))
        assertEquals(RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE), result)
        assertEquals(saved, (repository.load() as TripLoadResult.Loaded).snapshot)
        assertEquals(saved.driveSegments.size, database.checkpointDao().segments().size)
    }

    @Test fun corruptedPreciseDataUnsupportedVersionAndMissingSegmentsAreNotEmptyOrSuccess() = runBlocking {
        repository.save(snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE trip_checkpoint SET continuousDriveMs = continuousDriveMs + 1")
        assertEquals(TripLoadResult.Failed(RepositoryFailure.INVALID_DATA), repository.load())
        repository.save(snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE trip_checkpoint SET formatVersion = 99")
        assertEquals(TripLoadResult.Failed(RepositoryFailure.UNSUPPORTED_VERSION), repository.load())
        repository.save(snapshot())
        database.openHelper.writableDatabase.execSQL("DELETE FROM drive_segment")
        assertEquals(TripLoadResult.Failed(RepositoryFailure.INVALID_DATA), repository.load())
    }

    @Test fun staleObservationAndMalformedOwnerAreExplicitFailures() = runBlocking {
        repository.save(snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE trip_checkpoint SET observationMs = 290000")
        assertEquals(TripLoadResult.Failed(RepositoryFailure.STALE_INPUT), repository.load())
        repository.save(snapshot())
        database.openHelper.writableDatabase.execSQL("UPDATE trip_checkpoint SET ownerState = 'invalid'")
        assertEquals(TripLoadResult.Failed(RepositoryFailure.INVALID_DATA), repository.load())
        val saved = snapshot()
        repository.save(saved)
        val owner = TripSessionCodec.decode(requireNotNull(saved.ownerState))
        database.openHelper.writableDatabase.execSQL("UPDATE trip_checkpoint SET ownerState = ?",
            arrayOf(TripSessionCodec.encode(owner.copy(gear = Gear.D))))
        assertEquals(TripLoadResult.Failed(RepositoryFailure.INVALID_DATA), repository.load())
    }

    @Test fun legacyCountersAreRejectedDeletionIsDurableAndClosedDatabaseIsAFailure() = runBlocking {
        assertEquals(RepositoryResult.Failed(RepositoryFailure.INVALID_DATA),
            repository.save(TripSnapshot(TripState(continuousDriveSec = 120), 0)))
        repository.save(snapshot())
        assertEquals(RepositoryResult.Succeeded, repository.delete())
        assertEquals(TripLoadResult.Empty, repository.load())
        assertTrue(database.checkpointDao().segments().isEmpty())
        database.close()
        assertEquals(TripLoadResult.Failed(RepositoryFailure.UNAVAILABLE), repository.load())
        assertEquals(RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE), repository.save(snapshot()))
    }

    @Test fun actualElapsedTimerCommitsRoomAtTenSecondIntervalsWithoutMovingScenario() = runBlocking {
        val wall = java.time.Clock.systemUTC()
        val clock = object : Clock {
            override fun scenarioTimeMs() = 300_000L
            override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
            override fun wallTimeMs() = wall.millis()
        }
        val coordinator = TripSaveCoordinator(repository, clock)
        val anchor = clock.elapsedRealtimeMs()
        val commits = mutableListOf<Long>()
        coordinator.configure(SaveTimerBasis.ELAPSED_REALTIME, 300_000, true)
        repeat(21) {
            delay(1_000)
            if (coordinator.maybeSave(300_000) { snapshot().copy(savedAtWallMs = clock.wallTimeMs(), savedAtElapsedMs = clock.elapsedRealtimeMs()) }) {
                val saved = (repository.load() as TripLoadResult.Loaded).snapshot
                assertEquals(300_000L, saved.savedAtScenarioMs)
                commits += clock.elapsedRealtimeMs()
            }
        }
        assertEquals(2, commits.size)
        assertTrue("첫 저장: ${commits.first() - anchor} ms", commits.first() - anchor in 9_000..11_000)
        assertTrue("저장 간격: ${commits[1] - commits[0]} ms", commits[1] - commits[0] in 9_000..11_000)
        println("Room 실제 저장 간격 ms: ${commits.first() - anchor}, ${commits[1] - commits[0]}")
    }
}
