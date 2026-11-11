package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.demo.DemoPhase
import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 목표 도달로 끝난 Clock 경과와 원본 처리가 끝난 실제 경과를 혼동하지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugDemoProcessingElapsedTest {
    @Test fun actualCompletionIncludesBacklogDrainAfterTheRequestedTargetReachedItsEnd() = runTest {
        val clock = MutableRealClock()
        val demo = DebugDemoController(backgroundScope, clock, automaticTicks = false, maxSamplesPerTick = 4)
        demo.initialize(); demo.loadJson(shortAsset()); demo.start()
        clock.now = 5_000
        demo.tick()
        assertEquals(DemoPhase.PLAYING, demo.state.value.playback.phase)
        assertEquals(1_000L, demo.state.value.playback.realElapsedMs)
        assertNull(demo.state.value.processingCompletedAtElapsedMs)
        repeat(200) { if (demo.state.value.playback.phase == DemoPhase.PLAYING) demo.tick() }
        val completed = demo.state.value
        assertEquals(DemoPhase.COMPLETED, completed.playback.phase)
        assertEquals(600L, completed.playback.deliveredSamples)
        assertEquals(0L, completed.replayStartedAtElapsedMs)
        assertEquals(5_000L, completed.processingCompletedAtElapsedMs)
        assertEquals(1_000L, completed.playback.realElapsedMs)
        assertEquals(12.0, 60_000.0 / (completed.processingCompletedAtElapsedMs!! - completed.replayStartedAtElapsedMs!!), 0.0)
        demo.close()
    }

    @Test fun reloadingStartsANewActualProcessingMeasurementWithoutChangingTheScenario() = runTest {
        val clock = MutableRealClock()
        val demo = DebugDemoController(backgroundScope, clock, automaticTicks = false)
        demo.initialize(); demo.loadJson(shortAsset()); demo.start()
        clock.now = 2_000; demo.tick()
        assertEquals(2_000L, demo.state.value.processingCompletedAtElapsedMs)
        demo.loadJson(shortAsset())
        assertNull(demo.state.value.replayStartedAtElapsedMs)
        assertNull(demo.state.value.processingCompletedAtElapsedMs)
        clock.now = 3_000; demo.start()
        clock.now = 3_500; demo.start() // 거절된 중복 시작은 실제 시작 계측을 덮어쓰지 않는다.
        clock.now = 4_000; demo.tick()
        assertEquals(3_000L, demo.state.value.replayStartedAtElapsedMs)
        assertEquals(4_000L, demo.state.value.processingCompletedAtElapsedMs)
        assertEquals(60_000L, demo.state.value.playback.scenarioTimeMs)
        demo.reset()
        assertNull(demo.state.value.replayStartedAtElapsedMs)
        assertNull(demo.state.value.processingCompletedAtElapsedMs)
        demo.close()
    }

    @Test fun remainingOriginalsDrainWithPublicationTimeFollowingTheSourceBoundary() = runTest {
        for (factor in listOf(1, 10, 60)) {
            val clock = MutableRealClock()
            val samples = (0..7).joinToString(",") { index ->
                """{"t":${index / 10.0},"steeringDeg":1,"speedKph":40,"gear":"D","turnSignal":"OFF","brake":false,"seats":null,"cruise":null}"""
            }
            val json = """{"schemaVersion":1,"id":"ended-target","startEpochMs":1793862000000,
                "speedSegments":[{"fromSec":0,"toSec":0.8,"factor":$factor}],"samples":[$samples]}"""
            val demo = DebugDemoController(backgroundScope, clock, automaticTicks = false, maxSamplesPerTick = 2)
            demo.initialize(); demo.loadJson(json); demo.start()
            clock.now = 2_000; demo.tick()
            val first = demo.state.value
            assertEquals(DemoPhase.PLAYING, first.playback.phase)
            assertEquals(800L, first.playback.scenarioTimeMs)
            assertEquals(first.playback.lastOriginalSampleMs!! + 100, first.frame!!.tMs)
            assertEquals(1L, first.diagnostics.emittedFrames)
            assertEquals(first.frame.tMs / 100 - 1, first.diagnostics.skippedDeadlines)
            demo.tick()
            val next = demo.state.value
            assertTrue(next.playback.deliveredSamples > first.playback.deliveredSamples)
            assertTrue(next.frame!!.tMs > first.frame.tMs)
            assertEquals(first.diagnostics.emittedFrames + 1, next.diagnostics.emittedFrames)
            assertEquals(40f, next.frame.speedKph!!, 0f)
            repeat(4) { if (demo.state.value.playback.phase == DemoPhase.PLAYING) demo.tick() }
            assertEquals(DemoPhase.COMPLETED, demo.state.value.playback.phase)
            assertEquals(8L, demo.state.value.playback.deliveredSamples)
            assertEquals(2_000L, demo.state.value.processingCompletedAtElapsedMs)
            assertEquals(4L, demo.state.value.diagnostics.emittedFrames)
            demo.close()
        }
    }

    private fun shortAsset(): String = File("src/debug/assets/demo_timeaxis_60s_60.json").readText()
    private class MutableRealClock : Clock {
        var now = 0L
        override fun scenarioTimeMs() = now
        override fun elapsedRealtimeMs() = now
        override fun wallTimeMs() = 1_793_862_000_000L + now
    }
}
