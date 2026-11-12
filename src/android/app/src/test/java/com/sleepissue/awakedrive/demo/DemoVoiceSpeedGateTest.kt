package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.*
import org.junit.Assert.*
import org.junit.Test

class DemoVoiceSpeedGateTest {
    private class Real : Clock {
        var now = 0L
        override fun scenarioTimeMs() = now
        override fun elapsedRealtimeMs() = now
        override fun wallTimeMs() = now
    }
    @Test fun actualStartLocksAtOneAndEachTerminalResultRestoresSegment() {
        for (terminal in listOf(ExecutionStatus.SUCCEEDED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED)) {
            val real = Real()
            val clock = DemoClock(real).apply { configure(100_000, listOf(DemoSpeedSegment(0, 100_000, 60))); start() }
            val events = mutableListOf<ExecutionStatus>()
            val gate = DemoVoiceSpeedGate(clock) { _, status -> events += status }
            real.now += 100
            assertEquals(6000L, clock.scenarioTimeMs())
            val id = RequestId("question")
            gate.onLifecycle(id, ExecutionStatus.STARTED)
            real.now += 2_300
            assertEquals(8300L, clock.scenarioTimeMs())
            assertEquals(1, clock.snapshot().speedMultiplier)
            gate.onLifecycle(id, terminal)
            assertEquals(60, clock.snapshot().speedMultiplier)
            assertEquals(listOf(ExecutionStatus.STARTED, terminal), events)
        }
    }
    @Test fun oldTerminalEventCannotReleaseANewQuestionAndStopReleasesIt() {
        val clock = DemoClock(Real()).apply { configure(1000, listOf(DemoSpeedSegment(0, 1000, 10))); start() }
        val gate = DemoVoiceSpeedGate(clock) { _, _ -> }
        val old = RequestId("old"); val next = RequestId("next")
        gate.onLifecycle(old, ExecutionStatus.STARTED); gate.onLifecycle(old, ExecutionStatus.SUCCEEDED)
        gate.onLifecycle(next, ExecutionStatus.STARTED); gate.onLifecycle(old, ExecutionStatus.CANCELLED)
        assertEquals(1, clock.snapshot().speedMultiplier)
        gate.cancel()
        assertEquals(10, clock.snapshot().speedMultiplier)
    }
}
