package com.sleepissue.awakedrive.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 기존 DB에 접근하지 않는 메모리 Room 시험. 앱 삭제·데이터 초기화 없이 직접 runner로 실행한다. */
@RunWith(AndroidJUnit4::class)
class RealTripRoomTest {
    private val policy = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)
    private fun snapshot(): TripSnapshot {
        val rules = TripRules(EngineConfig(), policy) { "real-room" }
        var precise = rules.reduce(TripRuleState(), TripObservation(1_800_000_000_000, Gear.D, null)).state
        precise = rules.reduce(precise, TripObservation(1_800_000_120_345, Gear.P, null)).state
        precise = rules.reduce(precise, TripObservation(1_800_000_300_345, Gear.P, null)).state
        val clock = object : Clock {
            override fun scenarioTimeMs() = 1_800_000_300_345L
            override fun elapsedRealtimeMs() = 300_345L
            override fun wallTimeMs() = 1_800_000_300_345L
        }
        return RealTripPersistence.capture(precise, policy, clock)
    }

    @Test fun realNullablePowerAndPreciseStopRoundTripThenResumeUnknown() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, TripDatabase::class.java).build()
        try {
            val repo = RoomTripRepository(db.checkpointDao()); val saved = snapshot()
            assertEquals(RepositoryResult.Succeeded, repo.save(saved))
            val loaded = (repo.load() as TripLoadResult.Loaded).snapshot
            assertEquals(saved, loaded)
            val resumed = RealTripPersistence.restore(loaded, 1_800_001_000_000)
            assertEquals(DriveState.UNKNOWN, resumed.trip.driveState)
            assertEquals(loaded.state.tripId, resumed.trip.tripId)
            assertEquals(300_345L, resumed.continuousDriveMs)
            assertEquals(120L, resumed.trip.last24hDriveSec)
            assertNull(resumed.lastObservation?.power)
            assertEquals(120_345L, loaded.preciseState?.continuousBeforeStopMs)
            assertEquals(saved, (repo.load() as TripLoadResult.Loaded).snapshot)
        } finally { db.close() }
    }

    @Test fun failedRealReplacementPreservesAllSegmentsAndPreciseStop() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, TripDatabase::class.java).build()
        try {
            val repo = RoomTripRepository(db.checkpointDao()); val saved = snapshot()
            assertEquals(RepositoryResult.Succeeded, repo.save(saved))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_sdk BEFORE INSERT ON trip_checkpoint BEGIN SELECT RAISE(ABORT, '검증용 실패'); END")
            assertEquals(RepositoryResult.Failed(RepositoryFailure.UNAVAILABLE), repo.save(saved.copy(savedAtWallMs = saved.savedAtWallMs + 10_000)))
            assertEquals(saved, (repo.load() as TripLoadResult.Loaded).snapshot)
        } finally { db.close() }
    }
}
