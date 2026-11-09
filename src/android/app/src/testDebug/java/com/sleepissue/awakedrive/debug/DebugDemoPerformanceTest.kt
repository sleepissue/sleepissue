package com.sleepissue.awakedrive.debug

import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.demo.DemoPhase
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.performance.WorkProbe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.minutes

/**
 * 동일 JSON·동일 1ms tick으로 수집 경로의 처리 시간과 할당을 비교한다.
 * 가상 시계는 운행에만 쓰고 처리 시간은 nanoTime으로 잰다. AVD deadline 성능을 대신하지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugDemoPerformanceTest {
    private class BenchmarkClock : Clock {
        var elapsedMs = 0L
        override fun scenarioTimeMs() = elapsedMs
        override fun elapsedRealtimeMs() = elapsedMs
        override fun wallTimeMs() = 1_793_862_000_000L + elapsedMs
    }

    private data class RuntimeCounters(
        val currentThreadAllocatedBytes: Long?,
        val processThreadsAllocatedBytes: Long?,
        val gcCount: Long,
        val gcTimeMs: Long,
        val usedHeapBytes: Long,
    )

    @Test fun sameEighteenThousandOriginalsExposeTimeAllocationAndDiagnosticWork() = runTest(timeout = 5.minutes) {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (bean?.isThreadAllocatedMemorySupported == true && !bean.isThreadAllocatedMemoryEnabled) {
            bean.isThreadAllocatedMemoryEnabled = true
        }
        fun allocatedNow(): Long = bean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1L
        fun counters(): RuntimeCounters {
            val processBytes = bean?.let { threads ->
                threads.getThreadAllocatedBytes(threads.allThreadIds).filter { it >= 0 }.sum()
            }
            val gc = ManagementFactory.getGarbageCollectorMXBeans()
            val runtime = Runtime.getRuntime()
            return RuntimeCounters(allocatedNow().takeIf { it >= 0 }, processBytes,
                gc.sumOf { it.collectionCount.coerceAtLeast(0) },
                gc.sumOf { it.collectionTime.coerceAtLeast(0) }, runtime.totalMemory() - runtime.freeMemory())
        }
        val json = scenarioJson()
        val runs = mutableListOf<Map<String, Any?>>()
        for (withCancellation in listOf(false, true)) {
            val clock = BenchmarkClock()
            val probe = WorkProbe(System::nanoTime, ::allocatedNow)
            val demo = DebugDemoController(backgroundScope, clock, automaticTicks = false, probe = probe)
            val before = counters()
            val startedNs = System.nanoTime()
            demo.initialize()
            demo.loadJson(json)
            assertEquals(DemoPhase.READY, demo.state.value.playback.phase)
            demo.start()
            var questionStarted = false
            var questionCancelled = false
            var ticks = 0
            while (demo.state.value.playback.phase == DemoPhase.PLAYING) {
                clock.elapsedMs++
                demo.tick()
                ticks++
                if (withCancellation && !questionStarted && clock.elapsedMs == 5_000L) {
                    demo.beginQuestion()
                    assertEquals(1, demo.state.value.playback.speedMultiplier)
                    questionStarted = true
                }
                if (withCancellation && !questionCancelled && clock.elapsedMs == 6_000L) {
                    demo.cancelQuestion()
                    assertEquals(60, demo.state.value.playback.speedMultiplier)
                    assertEquals(DebugDemoQuestionPhase.CANCELLED, demo.state.value.questionPhase)
                    questionCancelled = true
                }
                check(ticks <= 32_000) { "같은 JSON이 정해진 시간 안에 끝나야 합니다." }
            }
            val durationNs = System.nanoTime() - startedNs
            val after = counters()
            assertEquals(DemoPhase.COMPLETED, demo.state.value.playback.phase)
            assertEquals(18_000L, demo.state.value.playback.deliveredSamples)
            assertEquals(1_799_900L, demo.state.value.playback.lastOriginalSampleMs)
            assertEquals(withCancellation, questionCancelled)
            val records = demo.exportTrace()
            val outputRoot = reportDirectory()
            val label = if (withCancellation) "question-cancel" else "normal"
            DebugDemoTrace.write(File(outputRoot, "jvm-$label-trace.jsonl"), records)
            runs += linkedMapOf(
                "condition" to label,
                "originalSamples" to 18_000,
                "configuredMultiplier" to 60,
                "tickAdvanceRealMs" to 1,
                "ticks" to ticks,
                "virtualElapsedMs" to clock.elapsedMs,
                "wallDurationNs" to durationNs,
                "before" to before,
                "after" to after,
                "currentThreadAllocationDeltaBytes" to before.currentThreadAllocatedBytes?.let { start ->
                    after.currentThreadAllocatedBytes?.let { it - start }
                },
                "processThreadsAllocationDeltaBytes" to before.processThreadsAllocatedBytes?.let { start ->
                    after.processThreadsAllocatedBytes?.let { it - start }
                },
                "gcCountDelta" to after.gcCount - before.gcCount,
                "gcTimeDeltaMs" to after.gcTimeMs - before.gcTimeMs,
                "heapDeltaBytes" to after.usedHeapBytes - before.usedHeapBytes,
                "stages" to probe.snapshot(),
                "traceSummary" to records.first { it["type"] == "summary" },
            )
            demo.close()
        }
        val report = linkedMapOf(
            "measurement" to "JVM_FIXED_VIRTUAL_TICKS",
            "note" to "동일 18,000개 JSON·가상 1ms tick 비교. nanoTime 처리 시간·thread allocation은 실제 JVM 계측이며 AVD 발행 시한 성능을 의미하지 않음.",
            "javaVersion" to System.getProperty("java.version"),
            "allocationSupported" to (bean?.isThreadAllocatedMemorySupported == true),
            "runs" to runs,
        )
        File(reportDirectory(), "jvm-current.json").writeText(
            GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report) + "\n")
    }

    private fun reportDirectory(): File = File("build/reports/demo-performance-20261106").also { it.mkdirs() }

    private fun scenarioJson(): String {
        val samples = (0 until 18_000).joinToString(",") { index ->
            """{"t":${index / 10.0},"steeringDeg":8,"speedKph":40,"gear":"D","turnSignal":"OFF","brake":false,"seats":null,"cruise":null,"lat":35.5,"lon":129.0,"power":"ON"}"""
        }
        return """{"schemaVersion":1,"id":"performance-18000","startEpochMs":1793862000000,"speedSegments":[{"fromSec":0,"toSec":1800,"factor":60}],"samples":[$samples]}"""
    }
}
