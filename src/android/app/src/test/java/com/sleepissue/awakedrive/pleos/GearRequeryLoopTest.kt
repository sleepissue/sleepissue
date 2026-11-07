package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GearRequeryLoopTest {
    private fun clock(scheduler: TestCoroutineScheduler) = object : Clock {
        override fun scenarioTimeMs() = scheduler.currentTime
        override fun wallTimeMs() = scheduler.currentTime
        override fun elapsedRealtimeMs() = scheduler.currentTime
    }

    @Test fun ioTimerKeepsSchedulingWhileOwnerMainDispatcherCannotRun() {
        val main = TestCoroutineScheduler(); val io = TestCoroutineScheduler()
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(main))
        val time = clock(io); val audit = GearCollectionAudit(time, "IO_FIXED_DELAY")
        val queries = mutableListOf<Long>()
        val job = launchGearRequeryLoop(owner, time, 1_000, 1, audit, { true }, StandardTestDispatcher(io)) { _, at -> queries += at }
        io.advanceTimeBy(3_001); io.runCurrent()
        assertEquals(listOf(1_000L, 2_000L, 3_000L), queries)
        assertEquals(0L, main.currentTime)
        job.cancel(); owner.cancel(); io.runCurrent()
    }

    @Test fun legacyMainThenIoPathCannotStartItsDueReadUntilMainResumes() {
        val main = TestCoroutineScheduler(); val io = TestCoroutineScheduler()
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(main))
        val queries = mutableListOf<Long>()
        val job = owner.launch {
            delay(1_000)
            withContext(StandardTestDispatcher(io)) { queries += io.currentTime }
        }
        main.runCurrent()
        io.advanceTimeBy(2_315); io.runCurrent()
        assertTrue(queries.isEmpty())
        main.advanceTimeBy(1_000); main.runCurrent(); io.runCurrent()
        assertEquals(listOf(2_315L), queries)
        job.cancel(); owner.cancel(); main.runCurrent(); io.runCurrent()
    }

    @Test fun cancellationAndConnectionGenerationLossStopFutureReads() {
        val io = TestCoroutineScheduler()
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(io))
        val time = clock(io); var current = true; var reads = 0
        val job = launchGearRequeryLoop(owner, time, 1_000, 1, GearCollectionAudit(time), { current },
            StandardTestDispatcher(io)) { _, _ -> reads++ }
        io.advanceTimeBy(1_001); io.runCurrent(); assertEquals(1, reads)
        current = false
        io.advanceTimeBy(5_000); io.runCurrent(); assertEquals(1, reads); assertTrue(job.isCompleted)
        val next = launchGearRequeryLoop(owner, time, 1_000, 2, GearCollectionAudit(time), { true },
            StandardTestDispatcher(io)) { _, _ -> reads++ }
        io.runCurrent(); next.cancel()
        io.advanceTimeBy(5_000); io.runCurrent(); assertEquals(1, reads)
        owner.cancel(); io.runCurrent()
    }

    @Test fun slowReadDoesNotCreateOverlappingQueriesOrCatchUpBursts() {
        val io = TestCoroutineScheduler()
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(io))
        val time = clock(io); var active = 0; var maximum = 0
        val times = mutableListOf<Long>()
        val job = launchGearRequeryLoop(owner, time, 1_000, 1, GearCollectionAudit(time), { true },
            StandardTestDispatcher(io)) { _, at ->
            active++; maximum = maxOf(maximum, active); times += at
            try { delay(2_500) } finally { active-- }
        }
        io.advanceTimeBy(8_001); io.runCurrent()
        assertEquals(listOf(1_000L, 4_500L, 8_000L), times)
        assertEquals(1, maximum)
        job.cancel(); owner.cancel(); io.runCurrent(); assertEquals(0, active)
    }
}
