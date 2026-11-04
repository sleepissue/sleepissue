package com.sleepissue.awakedrive.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TripModelsTest {
    @Test
    fun snapshotPreservesRollingTotalAndPreStopAnchor() {
        val state = TripState(
            tripId = "trip-1",
            continuousDriveSec = 6_600,
            last24hDriveSec = 9_000,
            runStartMs = 1_000,
            stopStartMs = 6_601_000,
            driveState = DriveState.PAUSED,
            continuousDriveSecBeforeStop = 6_600,
            provisionalStopSec = 180,
        )
        val snapshot = TripSnapshot(
            state = state,
            savedAtWallMs = 1_800_000_000_000,
            driveSegments = listOf(DriveSegment(1_000, 6_601_000)),
            savedAtElapsedMs = 123_000,
        )

        assertEquals(9_000L, snapshot.state.last24hDriveSec)
        assertEquals(6_600L, snapshot.state.continuousDriveSecBeforeStop)
        assertEquals(180L, snapshot.state.provisionalStopSec)
        assertEquals(1_800_000_000_000L, snapshot.savedAtWallMs)
        assertEquals(123_000L, snapshot.savedAtElapsedMs)
    }

    @Test
    fun reversedDriveSegmentsAndNegativeTimesAreRejected() {
        DriveSegment(0, 0)
        assertThrows(IllegalArgumentException::class.java) { DriveSegment(100, 99) }
        assertThrows(IllegalArgumentException::class.java) { DriveSegment(-1, 100) }
        assertThrows(IllegalArgumentException::class.java) { TripState(continuousDriveSec = -1) }
        assertThrows(IllegalArgumentException::class.java) { TripState(last24hDriveSec = -1) }
        assertThrows(IllegalArgumentException::class.java) { TripSnapshot(TripState(), savedAtWallMs = -1) }
    }
}
