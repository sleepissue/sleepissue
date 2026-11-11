package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.demo.DemoDeliveryAuditSnapshot
import com.sleepissue.awakedrive.demo.DemoPhase
import com.sleepissue.awakedrive.demo.DemoTransportMode
import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 표본 개수 예산과 사용자 명령의 양보를 검증한다. 실제 AVD의 지연 상한 시험은 아니다.
 * 시계 읽기 훅은 tick이 소유 잠금을 잡은 순간에 명령이 들어온 상황만 재현한다.
 * 훅은 시각을 바꾸지 않고, 단일 Flow emit이 장시간 대기하는 경우의 강제 중단을 뜻하지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugDemoSchedulingTest {
    @Test fun boundedBacklogTickPublishesOnceAndNeverFabricatesFramesAtTheSameClock() = runTest {
        val json = scenarioJson()
        for (mode in DemoTransportMode.entries) {
            val clock = CommandArrivalClock()
            val demo = DebugDemoController(backgroundScope, clock, automaticTicks = false,
                transportMode = mode, maxSamplesPerTick = 4)
            demo.initialize(); demo.loadJson(json); demo.start()
            val before = demo.state.value.playback.deliveredSamples
            clock.elapsedMs = 10_000
            demo.tick()
            val firstTick = demo.state.value
            assertEquals("$mode 표본 경계에서 반환", before + 4, firstTick.playback.deliveredSamples)
            assertEquals("$mode 현재 시각의 발행 기회", 600_000L, firstTick.frame!!.tMs)
            assertEquals("$mode 한 번의 현재 프레임", 1L, firstTick.diagnostics.emittedFrames)
            assertEquals("$mode 과거 deadline은 별도 계수", 5_999L, firstTick.diagnostics.skippedDeadlines)
            demo.tick()
            val secondTick = demo.state.value
            assertEquals(before + 8, secondTick.playback.deliveredSamples)
            assertEquals("$mode 고정된 시각에서 중복 발행 없음", firstTick.diagnostics.emittedFrames,
                secondTick.diagnostics.emittedFrames)
            assertEquals(firstTick.diagnostics.skippedDeadlines, secondTick.diagnostics.skippedDeadlines)
            demo.stop()
            assertPrefixAudit(checkNotNull(demo.state.value.deliveryAudit), secondTick.playback.deliveredSamples)
            demo.close()
        }
    }

    @Test fun queuedQuestionCancellationAndStopRunInOrderBeforeTheBacklogCanDrain() = runTest {
        val json = scenarioJson()
        for (mode in DemoTransportMode.entries) {
            val clock = CommandArrivalClock()
            val demo = DebugDemoController(backgroundScope, clock, automaticTicks = false,
                transportMode = mode, maxSamplesPerTick = 4)
            demo.initialize(); demo.loadJson(json); demo.start(); demo.beginQuestion()
            assertEquals(1, demo.state.value.playback.speedMultiplier)
            val before = demo.state.value.playback.deliveredSamples
            // 질문 중에는 실제1,000초가 시나리오1,000초다. 원본10,000개 이상을 밀리게 한다.
            clock.elapsedMs = 1_000_000
            val completed = mutableListOf<String>()
            var cancellation: Job? = null
            var stop: Job? = null
            clock.onNextElapsedRead = {
                cancellation = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    demo.cancelQuestion()
                    assertEquals("$mode 취소 후 구간60배 복귀", 60, demo.state.value.playback.speedMultiplier)
                    completed += "cancel"
                }
                stop = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    demo.stop()
                    completed += "stop"
                }
            }
            demo.tick()
            runCurrent()
            assertTrue("$mode 취소 명령 완료", cancellation?.isCompleted == true)
            assertTrue("$mode 중지 명령 완료", stop?.isCompleted == true)
            assertEquals(listOf("cancel", "stop"), completed)
            val stopped = demo.state.value
            assertEquals(DemoPhase.STOPPED, stopped.playback.phase)
            assertTrue("$mode 명령 대기 시 첫 완전 관측 경계에서 양보", stopped.playback.deliveredSamples <= before + 1)
            assertFalse(stopped.diagnostics.running)
            assertNull(stopped.frame)
            assertPrefixAudit(checkNotNull(stopped.deliveryAudit), stopped.playback.deliveredSamples)
            demo.close()
        }
    }

    @Test fun automaticTickerYieldsToQueuedCommandsWhileBothTransportModesHaveBacklog() = runTest {
        val json = scenarioJson()
        for (mode in DemoTransportMode.entries) {
            val clock = CommandArrivalClock()
            val demo = DebugDemoController(backgroundScope, clock, automaticTicks = true,
                transportMode = mode, maxSamplesPerTick = 4)
            demo.initialize(); demo.loadJson(json); demo.start(); demo.beginQuestion()
            val before = demo.state.value.playback.deliveredSamples
            clock.elapsedMs = 1_000_000
            val completed = mutableListOf<String>()
            var cancellation: Job? = null
            var stop: Job? = null
            clock.onNextElapsedRead = {
                cancellation = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    demo.cancelQuestion()
                    assertEquals(60, demo.state.value.playback.speedMultiplier)
                    completed += "cancel"
                }
                stop = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    demo.stop()
                    completed += "stop"
                }
            }
            advanceTimeBy(1)
            runCurrent()
            assertTrue("$mode 자동tick 중 취소가 밀리지 않음", cancellation?.isCompleted == true)
            assertTrue("$mode 자동tick 중 중지가 밀리지 않음", stop?.isCompleted == true)
            assertEquals(listOf("cancel", "stop"), completed)
            assertEquals(DemoPhase.STOPPED, demo.state.value.playback.phase)
            assertTrue("$mode 추가tick이 대기 명령 앞에 들어가지 않음",
                demo.state.value.playback.deliveredSamples <= before + 1)
            assertFalse(demo.state.value.diagnostics.running)
            val audit = checkNotNull(demo.state.value.deliveryAudit)
            assertPrefixAudit(audit, demo.state.value.playback.deliveredSamples)
            demo.close()
        }
    }

    @Test fun adaptiveDeadlineWaitIsBoundedAndDoesNotEmitFutureOriginalsWithAFrozenClock() = runTest {
        val json = scenarioJson()
        for (mode in DemoTransportMode.entries) {
            val clock = CommandArrivalClock()
            val demo = DebugDemoController(backgroundScope, clock, automaticTicks = true,
                transportMode = mode, adaptiveTicks = true)
            demo.initialize(); demo.loadJson(json); demo.start()
            clock.elapsedMs = 1 // 시나리오60ms: 다음 원본100ms와 발행시한은 아직 오지 않았다.
            advanceTimeBy(1); runCurrent() // bounded yield 이후 delay하므로 정지 시계에서도 반환해야 한다.
            val before = demo.captureTrace().records().last { it["type"] == "sample" }
            assertEquals(1L, before["deliveredSamples"])
            assertEquals(0L, before["hubEmittedFrames"])
            clock.elapsedMs = 2 // 시나리오120ms: 원본100ms 한 개만 새로 받을 수 있다.
            advanceTimeBy(1); runCurrent()
            val after = demo.captureTrace().records().last { it["type"] == "sample" }
            assertEquals(2L, after["deliveredSamples"])
            assertEquals(1L, after["hubEmittedFrames"])
            demo.close()
        }
    }

    @Test fun adaptiveWaitPreservesHubDeadlinesWhenOriginalTimesAreNotAligned() = runTest {
        val json = """{"schemaVersion":1,"id":"offset-originals","startEpochMs":1793862000000,
            "samples":[{"t":0,"steeringDeg":1,"speedKph":40,"gear":"D","turnSignal":"OFF","brake":false,"seats":null,"cruise":null,"lat":35.5,"lon":129.0,"power":"ON"},
            {"t":0.101,"steeringDeg":2,"speedKph":40,"gear":"D","turnSignal":"OFF","brake":false,"seats":null,"cruise":null,"lat":35.5,"lon":129.0,"power":"ON"}]}"""
        for (mode in DemoTransportMode.entries) {
            val clock = CommandArrivalClock()
            val demo = DebugDemoController(backgroundScope, clock, automaticTicks = true,
                transportMode = mode, adaptiveTicks = true)
            demo.initialize(); demo.loadJson(json); demo.start()
            clock.elapsedMs = 100
            advanceTimeBy(1); runCurrent()
            val first = demo.captureTrace().records().last { it["type"] == "sample" }
            assertEquals(1L, first["deliveredSamples"])
            assertEquals(1L, first["hubEmittedFrames"])
            assertEquals(100L, first["hubFrameTimeMs"])
            clock.elapsedMs = 101
            advanceTimeBy(1); runCurrent()
            clock.elapsedMs = 200
            advanceTimeBy(1); runCurrent()
            val second = demo.captureTrace().records().last { it["type"] == "sample" }
            assertEquals(2L, second["deliveredSamples"])
            assertEquals(2L, second["hubEmittedFrames"])
            assertEquals(0L, second["hubSkippedDeadlines"])
            assertEquals(200L, second["hubFrameTimeMs"])
            demo.close()
        }
    }

    @Test fun automaticDeadlinePollingKeepsThePlayerClockFailureCleanup() = runTest {
        val json = scenarioJson()
        for (mode in DemoTransportMode.entries) {
            val clock = CommandArrivalClock()
            val demo = DebugDemoController(backgroundScope, clock, automaticTicks = true,
                transportMode = mode, adaptiveTicks = true)
            demo.initialize(); demo.loadJson(json); demo.start()
            clock.elapsedMs = 100
            advanceTimeBy(1); runCurrent()
            clock.elapsedMs = 99
            advanceTimeBy(1); runCurrent()
            assertEquals(DemoPhase.FAILED, demo.state.value.playback.phase)
            assertFalse(demo.state.value.diagnostics.running)
            assertNull(demo.state.value.frame)
            demo.close()
        }
    }

    private class CommandArrivalClock : Clock {
        var elapsedMs = 0L
        var onNextElapsedRead: (() -> Unit)? = null
        override fun scenarioTimeMs() = elapsedMs
        override fun elapsedRealtimeMs(): Long {
            // 재진입 시 같은 명령을 다시 넣지 않는다. 시각은 모든 읽기에서 그대로다.
            val arrival = onNextElapsedRead
            onNextElapsedRead = null
            arrival?.invoke()
            return elapsedMs
        }
        override fun wallTimeMs() = 1_793_862_000_000L + elapsedMs
    }

    private fun assertPrefixAudit(audit: DemoDeliveryAuditSnapshot, delivered: Long) {
        assertEquals(30_000, audit.expectedTotal)
        assertEquals(3, audit.channels.size)
        audit.channels.forEach { (channel, count) ->
            assertEquals("$channel 수신 수", delivered, count.originalCount)
            assertEquals("$channel 고유 수신 수", delivered, count.uniqueExpectedCount)
            assertEquals("$channel 이미 지난 원본 누락", 0L, count.missingSamples)
            assertEquals("$channel 미래를 포함한 잔여 원본", 30_000L - delivered, count.remainingExpectedSamples)
            assertEquals("$channel 중복", 0L, count.duplicates)
            assertEquals("$channel 순서 역행", 0L, count.outOfOrder)
            assertEquals("$channel 원본 밖 시각", 0L, count.unknownTimestamps)
            assertEquals("$channel 중지 무효화", 1L, count.invalidationCount)
            assertEquals("$channel 첫 원본", 0L, count.firstOriginalMs)
            assertEquals("$channel 마지막 원본", (delivered - 1) * 100, count.lastOriginalMs)
        }
        assertEquals("세 채널에 같은 원본 시각 순서", 1, audit.channels.values.map { it.hash }.distinct().size)
    }

    private fun scenarioJson(): String {
        val samples = (0 until 30_000).joinToString(",") { index ->
            """{"t":${index / 10.0},"steeringDeg":8,"speedKph":40,"gear":"D","turnSignal":"OFF","brake":false,"seats":null,"cruise":null,"lat":35.5,"lon":129.0,"power":"ON"}"""
        }
        return """{"schemaVersion":1,"id":"scheduling-backlog","startEpochMs":1793862000000,"speedSegments":[{"fromSec":0,"toSec":3000,"factor":60}],"samples":[$samples]}"""
    }
}
