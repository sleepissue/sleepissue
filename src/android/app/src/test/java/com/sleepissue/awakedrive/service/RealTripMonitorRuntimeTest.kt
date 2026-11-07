package com.sleepissue.awakedrive.service

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.FakeClock
import com.sleepissue.awakedrive.signal.PlaygroundTripSession
import com.sleepissue.awakedrive.signal.TripRepository
import com.sleepissue.awakedrive.signal.TripLoadResult
import com.sleepissue.awakedrive.signal.RepositoryResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealTripMonitorRuntimeTest {
    private class MemoryRepository : TripRepository {
        var snapshot: TripSnapshot? = null
        var loads = 0
        val writes = mutableListOf<TripSnapshot>()
        override suspend fun load(): TripLoadResult {
            loads++
            return snapshot?.let(TripLoadResult::Loaded) ?: TripLoadResult.Empty
        }
        override suspend fun save(snapshot: TripSnapshot): RepositoryResult {
            this.snapshot = snapshot
            writes += snapshot
            return RepositoryResult.Succeeded
        }
        override suspend fun delete() = error("기존 데이터를 삭제하면 안 됩니다.")
    }

    private class Input(private val startFailure: Boolean = false) : RealTripMonitorInput {
        val frames = Channel<SignalFrame>(Channel.UNLIMITED)
        var starts = 0
        var closes = 0
        var collectors = 0
        var failClose = false
        override val samples = flow {
            collectors++
            try { emitAll(frames.receiveAsFlow()) }
            finally { collectors-- }
        }
        override suspend fun start(scope: CoroutineScope) {
            starts++
            if (startFailure) error("SDK 시작 실패")
        }
        override fun close() {
            closes++
            if (failClose) error("SDK 해제 실패")
        }
    }

    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        val clock = FakeClock(scenarioMs = 1_800_000_000_000, wallMs = 1_800_000_000_000)
        val repository = MemoryRepository()
        val session = PlaygroundTripSession(repository, clock, newTripId = { "service-trip" })
        val inputs = mutableListOf<Input>()
        var failNextStart = false
        val runtime = RealTripMonitorRuntime(session, scope, {
            Input(failNextStart).also { inputs += it; failNextStart = false }
        }, dispatcher)
        fun elapse(ms: Long) {
            clock.scenarioMs += ms
            clock.elapsedMs += ms
            clock.wallMs += ms
        }
        fun frame(gear: Gear?, availability: SignalAvailability = SignalAvailability.AVAILABLE) = SignalFrame(
            tMs = clock.scenarioMs, gear = gear,
            quality = mapOf(SignalField.GEAR to SignalQuality(availability, Source.REAL)),
        )
    }

    @Test fun repeatedAndConcurrentStartsKeepOneInputCollectorAndSaveTimer() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.runtime.initialize()
        f.runtime.initialize()
        repeat(20) { backgroundScope.launch { assertTrue(f.runtime.start()) } }
        runCurrent()
        assertEquals(1, f.repository.loads)
        assertEquals(1, f.inputs.size)
        assertEquals(1, f.inputs.single().starts)
        assertEquals(1, f.inputs.single().collectors)
        assertEquals(1, f.runtime.starts)
        assertEquals(1, f.runtime.activeTimers)

        f.inputs.single().frames.send(f.frame(Gear.D)); runCurrent()
        val immediateWrites = f.repository.writes.size
        assertEquals(1, immediateWrites)
        f.elapse(9_999); advanceTimeBy(9_999); runCurrent()
        assertEquals(immediateWrites, f.repository.writes.size)
        f.elapse(1); advanceTimeBy(1); runCurrent()
        assertEquals(immediateWrites + 1, f.repository.writes.size)
        f.runtime.stop()
    }

    @Test fun explicitStopSavesLastObservationAndRejectsLateInputAndTimers() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.runtime.initialize(); f.runtime.start()
        val input = f.inputs.single()
        input.frames.send(f.frame(Gear.D)); runCurrent()
        f.elapse(1_250); input.frames.send(f.frame(Gear.D)); runCurrent()
        val lastObserved = f.clock.scenarioMs
        assertTrue(f.runtime.stop())
        val checkpoint = requireNotNull(f.repository.snapshot)
        assertEquals(1_250L, checkpoint.preciseState!!.continuousDriveMs)
        assertEquals(lastObserved, checkpoint.savedAtScenarioMs)
        assertEquals(Gear.D, checkpoint.preciseState!!.lastObservation?.gear)
        assertNull(checkpoint.preciseState!!.lastObservation?.power)
        assertEquals(DriveState.UNKNOWN, f.session.trip.value.driveState)
        assertFalse(f.session.connected)
        assertFalse(f.runtime.running)
        assertEquals(0, f.runtime.activeTimers)
        assertEquals(0, input.collectors)
        assertEquals(1, input.closes)

        val stoppedWrites = f.repository.writes.size
        f.elapse(60_000); input.frames.send(f.frame(Gear.P))
        advanceTimeBy(60_000); runCurrent()
        assertEquals(stoppedWrites, f.repository.writes.size)
        assertEquals(1_250L, f.session.preciseState().continuousDriveMs)
        assertFalse(f.runtime.stop())
        assertEquals(1, input.closes)
    }

    @Test fun restartCreatesNewGenerationWithoutAddingStoppedGapOrOldFrames() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.runtime.initialize(); f.runtime.start()
        val oldInput = f.inputs.single()
        val oldGeneration = f.session.generation
        oldInput.frames.send(f.frame(Gear.D)); runCurrent()
        f.elapse(1_000); oldInput.frames.send(f.frame(Gear.D)); runCurrent()
        f.runtime.stop()
        f.elapse(7_200_001)
        assertTrue(f.runtime.start())
        assertTrue(f.session.generation > oldGeneration)
        assertEquals(DriveState.UNKNOWN, f.session.trip.value.driveState)
        assertEquals("service-trip", f.session.trip.value.tripId)
        oldInput.frames.send(f.frame(Gear.P)); runCurrent()
        assertEquals(DriveState.UNKNOWN, f.session.trip.value.driveState)
        val next = f.inputs.last()
        next.frames.send(f.frame(Gear.D)); runCurrent()
        assertEquals(1_000L, f.session.preciseState().continuousDriveMs)
        f.elapse(1_000); next.frames.send(f.frame(Gear.D)); runCurrent()
        assertEquals(2_000L, f.session.preciseState().continuousDriveMs)
        assertEquals(2, f.runtime.starts)
        assertEquals(1, f.runtime.activeTimers)
        assertEquals(0, oldInput.collectors)
        assertEquals(1, next.collectors)
        f.runtime.stop()
    }

    @Test fun upstreamFailureEndsCollectionAndPreservesLastValidCheckpointAsUnknown() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.runtime.initialize(); f.runtime.start()
        val input = f.inputs.single()
        input.frames.send(f.frame(Gear.D)); runCurrent()
        f.elapse(1_000); input.frames.send(f.frame(Gear.D)); runCurrent()
        input.frames.close(IllegalStateException("SDK 연결 해제")); runCurrent()
        assertFalse(f.runtime.running)
        assertFalse(f.session.connected)
        assertEquals(0, f.runtime.activeTimers)
        assertEquals(0, input.collectors)
        assertEquals(1, input.closes)
        assertEquals(1_000L, f.repository.snapshot!!.preciseState!!.continuousDriveMs)
        assertEquals(DriveState.UNKNOWN, f.session.trip.value.driveState)
        assertTrue(f.runtime.status.value.contains("입력 오류"))
        assertTrue(requireNotNull(f.runtime.failure.value).contains("입력 오류"))
    }

    @Test fun normallyCompletedInputAlsoStopsInsteadOfKeepingPreviousGearAlive() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.runtime.initialize(); f.runtime.start()
        val input = f.inputs.single()
        input.frames.send(f.frame(Gear.D)); runCurrent()
        input.frames.close(); runCurrent()
        assertFalse(f.runtime.running)
        assertEquals(DriveState.UNKNOWN, f.session.trip.value.driveState)
        assertEquals(1, input.closes)
        assertEquals(0, f.runtime.activeTimers)
        assertTrue(f.runtime.status.value.contains("입력 종료"))
    }

    @Test fun failedInputStartCleansCollectorAndCanBeRetriedWithNewInput() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.runtime.initialize(); f.failNextStart = true
        assertFalse(f.runtime.start())
        val failed = f.inputs.single()
        assertEquals(1, failed.starts)
        assertEquals(1, failed.closes)
        assertEquals(0, failed.collectors)
        assertEquals(0, f.runtime.starts)
        assertEquals(0, f.runtime.activeTimers)
        assertFalse(f.runtime.running)
        assertFalse(f.session.connected)
        assertEquals(0, f.repository.writes.size)
        assertTrue(f.runtime.start())
        assertEquals(2, f.inputs.size)
        assertEquals(1, f.inputs.last().collectors)
        assertEquals(1, f.runtime.activeTimers)
        f.runtime.stop()
    }

    @Test fun factoryFailureDoesNotBeginInputOrAllocateTimer() = runTest {
        val clock = FakeClock(scenarioMs = 1_800_000_000_000, wallMs = 1_800_000_000_000)
        val repository = MemoryRepository()
        val session = PlaygroundTripSession(repository, clock)
        val runtime = RealTripMonitorRuntime(session, backgroundScope,
            { error("SDK 소유권 획득 실패") }, StandardTestDispatcher(testScheduler))
        runtime.initialize()
        assertFalse(runtime.start())
        assertFalse(runtime.running)
        assertFalse(session.connected)
        assertEquals(0L, session.generation)
        assertEquals(0, runtime.activeTimers)
        assertEquals(0, repository.writes.size)
        assertTrue(runtime.status.value.contains("SDK 소유권 획득 실패"))
    }

    @Test fun closeFailureDoesNotKeepCollectorOrTimerAliveAndStillSavesLastState() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.runtime.initialize(); f.runtime.start()
        val input = f.inputs.single()
        input.frames.send(f.frame(Gear.D)); runCurrent()
        f.elapse(1_000); input.frames.send(f.frame(Gear.D)); runCurrent()
        input.failClose = true
        assertTrue(f.runtime.stop())
        assertEquals(1_000L, f.repository.snapshot!!.preciseState!!.continuousDriveMs)
        assertEquals(DriveState.UNKNOWN, f.session.trip.value.driveState)
        assertFalse(f.runtime.running)
        assertFalse(f.session.connected)
        assertEquals(0, input.collectors)
        assertEquals(0, f.runtime.activeTimers)
        assertTrue(f.runtime.status.value.contains("해제 오류"))
        assertTrue(f.runtime.start())
        assertEquals(1, f.runtime.activeTimers)
        f.runtime.stop()
    }

    @Test fun failedStartupAlsoCleansUpWhenCloseThrows() = runTest {
        val clock = FakeClock(scenarioMs = 1_800_000_000_000, wallMs = 1_800_000_000_000)
        val session = PlaygroundTripSession(MemoryRepository(), clock)
        val input = Input(startFailure = true).apply { failClose = true }
        val runtime = RealTripMonitorRuntime(session, backgroundScope, { input }, StandardTestDispatcher(testScheduler))
        runtime.initialize()
        assertFalse(runtime.start())
        assertFalse(runtime.running)
        assertFalse(session.connected)
        assertEquals(0, input.collectors)
        assertEquals(1, input.closes)
        assertEquals(0, runtime.activeTimers)
    }

    @Test fun unavailableGearNeverAccumulatesTimeUntilNewValidObservation() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.runtime.initialize(); f.runtime.start()
        val input = f.inputs.single()
        input.frames.send(f.frame(Gear.D)); runCurrent()
        f.elapse(1_000); input.frames.send(f.frame(Gear.D)); runCurrent()
        f.elapse(1_000); input.frames.send(f.frame(null, SignalAvailability.PERMISSION_DENIED)); runCurrent()
        assertEquals(DriveState.UNKNOWN, f.session.trip.value.driveState)
        assertEquals(1_000L, f.session.preciseState().continuousDriveMs)
        f.elapse(60_000); input.frames.send(f.frame(null, SignalAvailability.ERROR)); runCurrent()
        assertEquals(1_000L, f.session.preciseState().continuousDriveMs)
        input.frames.send(f.frame(Gear.D)); runCurrent()
        assertEquals(1_000L, f.session.preciseState().continuousDriveMs)
        f.elapse(1_000); input.frames.send(f.frame(Gear.D)); runCurrent()
        assertEquals(2_000L, f.session.preciseState().continuousDriveMs)
        f.runtime.stop()
    }
}
