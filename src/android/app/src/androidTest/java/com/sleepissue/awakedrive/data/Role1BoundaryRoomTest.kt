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

/** 장시간 경계는 주입 Clock으로 검증한다. 실제 시간 대기·시동 OFF 시험이 아니며 사용자 DB를 열지 않는다. */
@RunWith(AndroidJUnit4::class)
class Role1BoundaryRoomTest {
    private val config = EngineConfig()
    private val policy = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)
    private val epoch = 1_800_000_000_000L
    private class TestClock(var at: Long) : Clock {
        override fun scenarioTimeMs() = at
        override fun elapsedRealtimeMs() = at - 1_800_000_000_000L
        override fun wallTimeMs() = at
    }
    private fun roundTrip(state: TripRuleState): TripSnapshot = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TripDatabase::class.java).build()
        try {
            val repo = RoomTripRepository(db.checkpointDao())
            val saved = RealTripPersistence.capture(state, policy, TestClock(state.lastObservation!!.tMs))
            assertEquals(RepositoryResult.Succeeded, repo.save(saved))
            val loaded = (repo.load() as TripLoadResult.Loaded).snapshot
            assertEquals(saved, loaded)
            loaded
        } finally { db.close() }
    }
    private fun rules() = TripRules(config, policy) { "next-trip" }
    private fun started(rules: TripRules): TripRuleState =
        rules.reduce(TripRuleState(), TripObservation(epoch, Gear.D, null)).state

    @Test fun fiveAndFifteenMinuteBoundariesPersistExactlyAndUnknownDoesNotCompleteRest() {
        for (duration in listOf(299_999L, 300_000L, 899_999L, 900_000L)) {
            val r = rules()
            var state = started(r)
            val stop = epoch + 120_345
            state = r.reduce(state, TripObservation(stop, Gear.P, null)).state
            val result = r.reduce(state, TripObservation(stop + duration, Gear.P, null))
            state = result.state
            val expected = if (duration >= config.tripRestResetMs) 0L
                else if (duration >= config.tripStopPauseMs) 120_345L else 120_345L + duration
            assertEquals(expected, state.continuousDriveMs)
            assertEquals(duration >= config.tripRestResetMs, result.events.any { it is TripRuleEvent.RestResetReached })
            val loaded = roundTrip(state)
            val restored = RealTripPersistence.restore(loaded, stop + duration + config.tripRestResetMs)
            assertEquals(expected, restored.continuousDriveMs)
            assertEquals(DriveState.UNKNOWN, restored.trip.driveState)
            assertNull(restored.stopStartedAtMs)
            assertEquals(120L, restored.trip.last24hDriveSec)
            val fresh = r.reduce(restored, TripObservation(restored.lastObservation!!.tMs, Gear.P, null))
            assertTrue(fresh.events.isEmpty())
            assertEquals(state, loaded.preciseState)
        }
    }

    @Test fun exactAndOverTwoHourOffEvidenceSurvivesRoomButUnknownGapCannotExtendIt() {
        for (extra in listOf(0L, 1L)) {
            var id = 0
            val r = TripRules(config, policy) { "off-trip-${++id}" }
            var state = started(r)
            val off = epoch + 60_000
            state = r.reduce(state, TripObservation(off, Gear.P, PowerState.OFF)).state
            val savedAt = off + config.newTripAfterOffMs + extra
            state = r.reduce(state, TripObservation(savedAt, Gear.P, PowerState.OFF)).state
            val loaded = roundTrip(state)
            val restored = RealTripPersistence.restore(loaded, savedAt + config.newTripAfterOffMs + 1)
            assertEquals(extra > 0, restored.pendingNewTrip)
            assertNull(restored.offStartedAtMs)
            val next = r.reduce(restored, TripObservation(restored.lastObservation!!.tMs, Gear.D, null))
            assertEquals(extra > 0, next.events.any { it is TripRuleEvent.NewTrip })
            assertEquals(60L, next.state.trip.last24hDriveSec)
        }
    }

    @Test fun recent24HourWindowClipsRestoredMillisecondsWithoutAddingUnknownTime() {
        val r = rules()
        var state = started(r)
        val end = epoch + 7_200_345
        state = r.reduce(state, TripObservation(end, Gear.P, null)).state
        val loaded = roundTrip(state)
        val restored = RealTripPersistence.restore(loaded, end + config.rollingDriveWindowMs - 1_345)
        assertEquals(1L, restored.trip.last24hDriveSec)
        assertEquals(listOf(DriveSegment(end - 1_345, end)), restored.driveSegments)
        assertEquals(7_200_345L, restored.continuousDriveMs)
        assertEquals(state.trip.tripId, restored.trip.tripId)
        assertEquals(state, loaded.preciseState)
    }
}
