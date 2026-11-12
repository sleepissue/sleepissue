package com.sleepissue.awakedrive.pleos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SdkNaviRefreshLoopTest {
    @Test fun routeEventDoesNotWaitForNextPeriodicQuery() = runTest {
        val loop = SdkNaviRefreshLoop(1_000) { currentTime }
        val times = mutableListOf<Long>()
        val job = launch { loop.run { times += currentTime } }
        runCurrent()
        advanceTimeBy(73)
        loop.requestRefresh(); runCurrent()
        assertEquals(listOf(0L, 73L), times)
        advanceTimeBy(926); runCurrent()
        assertEquals(2, times.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(0L, 73L, 1_000L), times)
        job.cancelAndJoin()
    }

    @Test fun burstIsConflatedAndCancellationStopsQueries() = runTest {
        val loop = SdkNaviRefreshLoop(1_000) { currentTime }
        var calls = 0
        val job = launch { loop.run { calls++ } }
        runCurrent()
        repeat(100) { loop.requestRefresh() }; runCurrent()
        assertEquals(2, calls)
        job.cancelAndJoin()
        loop.requestRefresh(); advanceTimeBy(10_000); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun eventDuringQueryGetsOneFollowUpRatherThanBeingLost() = runTest {
        val loop = SdkNaviRefreshLoop(1_000) { currentTime }
        var calls = 0
        val job = launch { loop.run {
            calls++
            if (calls == 1) repeat(20) { loop.requestRefresh() }
        } }
        runCurrent()
        assertEquals(2, calls)
        job.cancelAndJoin()
    }

    @Test fun slowQueryDoesNotReplayPastDeadlines() = runTest {
        var wall = 0L
        val loop = SdkNaviRefreshLoop(1_000) { wall }
        var calls = 0
        val job = launch { loop.run {
            calls++
            if (calls == 1) wall = 3_100
        } }
        runCurrent()
        advanceTimeBy(1); runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(899); runCurrent()
        assertEquals(2, calls)
        job.cancelAndJoin()
    }
}
