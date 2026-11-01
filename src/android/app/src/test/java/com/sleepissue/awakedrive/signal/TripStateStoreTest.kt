package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.DriveState
import com.sleepissue.awakedrive.engine.model.TripState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripStateStoreTest {
    @Test
    fun initialUnknownStateDoesNotInventATrip() {
        val state = TripStateStore().state.value
        assertNull(state.tripId)
        assertNull(state.runStartMs)
        assertEquals(DriveState.UNKNOWN, state.driveState)
    }

    @Test
    fun replacementAndPartialUpdateKeepUnrelatedFields() {
        val store = TripStateStore()
        val original = TripState(
            tripId = "trip-1",
            continuousDriveSec = 120,
            last24hDriveSec = 600,
            baselineRef = "baseline-3",
        )
        store.replace(original)

        val updated = store.update { it.copy(continuousDriveSec = 180) }

        assertEquals(original.copy(continuousDriveSec = 180), updated)
        assertEquals(updated, store.state.value)
    }

    @Test
    fun concurrentUpdatesDoNotLoseAcceptedChanges() = runTest {
        val store = TripStateStore()
        List(4) {
            launch(Dispatchers.Default) {
                repeat(500) {
                    store.update { state ->
                        state.copy(continuousDriveSec = state.continuousDriveSec + 1)
                    }
                }
            }
        }.joinAll()

        assertEquals(2000L, store.state.value.continuousDriveSec)
    }
}
