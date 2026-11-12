package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.time.DemoClock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProductDemoSpeedTest {
    @Test fun originalSelectionKeepsCustomSegmentsAndEveryOtherSelectionChangesOnlyThePlaybackCopy() {
        val original = demoTestScenario(20_000, listOf(DemoSpeedSegment(0, 10_000, 1), DemoSpeedSegment(10_000, 20_000, 10)))
        assertSame(original, original.withPlaybackSpeed(ProductDemoSpeed.ORIGINAL))
        for (speed in ProductDemoSpeed.entries.filter { it.factor != null }) {
            val playback = original.withPlaybackSpeed(speed)
            assertEquals(listOf(DemoSpeedSegment(0, original.durationMs, requireNotNull(speed.factor))), playback.speedSegments)
            assertSame(original.samples, playback.samples)
            assertEquals(original.startEpochMs, playback.startEpochMs)
            assertEquals(original.tripPreset, playback.tripPreset)
        }
        assertEquals(listOf(1, 10), original.speedSegments.map { it.factor })
    }

    @Test fun sixProductOriginalsStayUnchangedAndTenTimesMakesThirtyMinutesThreeWithoutQuestions() {
        for (id in listOf("normal", "distraction", "drowsy", "talk", "assist", "departure")) {
            val file = File("../../../data/scenarios/$id.json")
            val before = file.readBytes()
            val original = (DemoJsonLoader().loadFile(file) as DemoLoadResult.Loaded).scenario
            val originalSegments = original.speedSegments.toList()
            val playback = original.withPlaybackSpeed(ProductDemoSpeed.TEN)
            assertSame(original.samples, playback.samples)
            assertEquals(original.tripPreset, playback.tripPreset)
            val real = DemoTestClock()
            val clock = DemoClock(real).apply { configure(playback.durationMs, playback.speedSegments); start() }
            real.realMs = 180_000
            assertEquals(1_800_000L, clock.scenarioTimeMs())
            assertEquals(0, clock.snapshot().speedMultiplier)
            assertEquals(originalSegments, original.speedSegments)
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun eachTerminalVoiceOutcomeRestoresTheSelectedSpeedAndMeasuresAnswerInActualTime() {
        for (speed in listOf(ProductDemoSpeed.TEN, ProductDemoSpeed.SIXTY)) {
            for (terminal in listOf(ExecutionStatus.SUCCEEDED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED)) {
                val scenario = demoTestScenario(100_000).withPlaybackSpeed(speed)
                val real = DemoTestClock()
                val clock = DemoClock(real).apply { configure(scenario.durationMs, scenario.speedSegments); start() }
                val gate = DemoVoiceSpeedGate(clock) { _, _ -> }
                real.realMs = 100
                val before = clock.scenarioTimeMs()
                val id = RequestId("voice")
                gate.onLifecycle(id, ExecutionStatus.STARTED)
                real.realMs += 2_300
                assertEquals(before + 2_300, clock.scenarioTimeMs())
                assertEquals(1, clock.snapshot().speedMultiplier)
                gate.onLifecycle(id, terminal)
                assertEquals(speed.factor, clock.snapshot().speedMultiplier)
                real.realMs += 100
                assertEquals(before + 2_300 + 100 * requireNotNull(speed.factor), clock.scenarioTimeMs())
            }
        }
    }

    @Test fun tenTimesPauseDoesNotAdvanceScenarioOrCountWaitingAsDrivenTime() {
        val scenario = demoTestScenario(100_000).withPlaybackSpeed(ProductDemoSpeed.TEN)
        val real = DemoTestClock()
        val clock = DemoClock(real).apply { configure(scenario.durationMs, scenario.speedSegments); start() }
        real.realMs = 100
        clock.pause()
        real.realMs = 10_100
        assertEquals(1_000L, clock.scenarioTimeMs())
        assertEquals(10_100L, clock.snapshot().realElapsedMs)
        clock.resume()
        real.realMs += 100
        assertEquals(2_000L, clock.scenarioTimeMs())
    }
}
