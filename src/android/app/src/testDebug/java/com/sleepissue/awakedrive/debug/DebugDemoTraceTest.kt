package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.demo.DemoPhase
import com.sleepissue.awakedrive.demo.DemoPlaybackState
import com.sleepissue.awakedrive.demo.DemoScenarioSample
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.HubDiagnostics
import com.sleepissue.awakedrive.signal.VehicleSignalInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DebugDemoTraceTest {
    @Test fun storedRowsCaptureMissingStagesWithoutSamplingSkippedTicksOrCopyingExistingMetrics() {
        val capturedStages = mapOf("PUMP" to mapOf<String, Number>("calls" to 7L))
        val providedStages = mapOf("PUMP" to mapOf<String, Number>("calls" to 11L))
        var captures = 0
        val trace = DebugDemoTrace(performanceSnapshot = { captures++; capturedStages })
        val value = snapshot(4, DemoPhase.PLAYING)
        trace.record(value, 0, 100, original())
        assertEquals(1, captures)
        // 같은 100ms 창의 관측에서는 모든 단계의 계측 스냅샷을 다시 계산하지 않는다.
        repeat(99) { trace.record(value, it + 1L, 100, original()) }
        assertEquals(1, captures)
        trace.record(value.copy(performanceStages = providedStages), 100, 100, original())
        assertEquals(1, captures)
        val details = trace.capture(4).records().drop(1)
        assertEquals(2, details.size)
        assertSame(capturedStages, details[0]["performanceStages"])
        assertSame(providedStages, details[1]["performanceStages"])
    }

    @Test fun repeatedObservationTicksCountOneNullPublicationAndPreserveFieldNames() {
        val trace = DebugDemoTrace()
        val nullFrame = snapshot(session = 4, phase = DemoPhase.PLAYING)
        repeat(3) { trace.record(nullFrame, it.toLong(), 100, original()) }
        val records = trace.capture(4).records()
        val summary = records.first()
        assertEquals(3L, summary["unexpectedNullTicks"])
        assertEquals(3L, summary["allSignalNullTicks"])
        val session = (summary["sessionSummaries"] as List<*>).single() as Map<*, *>
        assertEquals(1L, session["unexpectedNullPublishedFrames"])
        assertEquals(1L, session["allSignalNullPublishedFrames"])
        assertEquals(listOf("SPEED_KPH", "STEERING_DEG", "GEAR", "LAT", "LON"), records[1]["unexpectedNullFields"])
    }

    @Test fun terminalCaptureKeepsOnlyRequestedSessionAndSurvivesLaterRingEviction() {
        val trace = DebugDemoTrace()
        trace.record(snapshot(3, DemoPhase.COMPLETED), 0, 100, original())
        trace.record(snapshot(4, DemoPhase.PLAYING), 1, 100, original())
        trace.record(snapshot(4, DemoPhase.COMPLETED, running = true), 2, 100, original())
        trace.record(snapshot(4, DemoPhase.COMPLETED, running = false), 3, 100, original())
        val capture = trace.capture(4)
        val frozen = capture.records()
        assertEquals(4L, capture.sessionId)
        assertEquals(3L, capture.capturedElapsedMs)
        assertEquals(3, frozen.drop(1).size)
        assertTrue(frozen.drop(1).all { it["sessionId"] == 4L })
        assertEquals(listOf(true, false), frozen.drop(2).map { it["hubRunning"] })
        trace.record(snapshot(5, DemoPhase.READY), 130_000, 0, null)
        assertEquals(frozen, capture.records())
        assertEquals(1, trace.capture(4).records().size)
    }

    @Test fun newSessionAndHubStopAreCapturedInsideTheDetailInterval() {
        val trace = DebugDemoTrace()
        trace.record(snapshot(3, DemoPhase.READY), 0, 0, null)
        trace.record(snapshot(4, DemoPhase.READY), 1, 0, null)
        trace.record(snapshot(4, DemoPhase.COMPLETED, running = true), 2, 100, original())
        trace.record(snapshot(4, DemoPhase.COMPLETED, running = false), 3, 100, original())
        assertEquals(5, trace.capture().records().size)
        assertEquals(0L, trace.capture().records().first()["unexpectedNullTicks"])
    }

    @Test fun incompleteExpectedSignalSetNeverCountsAllFiveSignalsNull() {
        val trace = DebugDemoTrace()
        val original = original().let { it.copy(navi = NaviState(0)) }
        trace.record(snapshot(4, DemoPhase.PLAYING), 1, 100, original)
        val records = trace.capture(4).records()
        assertEquals(1L, records.first()["unexpectedNullTicks"])
        assertEquals(0L, records.first()["allSignalNullTicks"])
        assertEquals(listOf("SPEED_KPH", "STEERING_DEG", "GEAR"), records[1]["unexpectedNullFields"])
    }

    @Test fun serializationFailurePreservesPreviouslySavedTraceAndCleansPartialFile() {
        val directory = java.nio.file.Files.createTempDirectory("demo-trace-save-test").toFile()
        val target = File(directory, "trace.jsonl")
        try {
            target.writeText("previous completed trace\n")
            try {
                DebugDemoTrace.write(target, listOf(mapOf("invalidNumber" to Double.NaN)))
                fail("비유한 JSON 값은 저장에 실패해야 합니다.")
            } catch (_: IllegalArgumentException) { }
            assertEquals("previous completed trace\n", target.readText())
            assertEquals(listOf("trace.jsonl"), directory.listFiles()!!.map { it.name })
            DebugDemoTrace.write(target, listOf(mapOf("type" to "summary", "totalTicks" to 1)))
            assertTrue(target.readText().contains("\"totalTicks\":1"))
        } finally { directory.deleteRecursively() }
    }

    private fun original() = DemoScenarioSample(0,
        VehicleSignalInput(0, Source.DEMO, speed = 40f, steeringDeg = 1f, gear = Gear.D),
        NaviState(0, 35.5, 129.0), PowerEvent(0, PowerState.ON))

    private fun snapshot(session: Long, phase: DemoPhase, running: Boolean = true) = DebugDemoSnapshot(
        playback = DemoPlaybackState(phase = phase, sessionId = session, scenarioId = "trace-test", speedMultiplier = 60),
        frame = SignalFrame(100, publication = FramePublication(session, 1, 0)),
        navi = NaviState(100),
        diagnostics = HubDiagnostics(running = running, emittedFrames = 1),
    )
}
