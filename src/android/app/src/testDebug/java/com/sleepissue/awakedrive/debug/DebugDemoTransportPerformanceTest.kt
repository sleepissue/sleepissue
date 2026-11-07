package com.sleepissue.awakedrive.debug

import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.demo.DemoDeliveryAuditSnapshot
import com.sleepissue.awakedrive.demo.DemoPhase
import com.sleepissue.awakedrive.demo.DemoTransportMode
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.performance.WorkProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

/**
 * 같은 원본·시계에서 세 Flow와 완전 관측 한 번 인계를 비교한다. 기존 성능 시험은 수정하지 않는다.
 * first/repeat는 같은 Controller의 첫 재생/다음 재생이다. JVM 자체의 cold start 표시는 아니다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugDemoTransportPerformanceTest {
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

    @Test fun sameAssetComparesSeparateAndCompleteTransportAcrossFirstAndRepeatRuns() = runTest(timeout = 5.minutes) {
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
        val asset = performanceAsset()
        val json = asset.readText()
        val jsonHash = MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
        val output = File("build/reports/demo-transport-20261106").also { it.mkdirs() }
        val runs = mutableListOf<Map<String, Any?>>()
        for (mode in listOf(DemoTransportMode.SEPARATE_FLOWS, DemoTransportMode.COMPLETE_OBSERVATION)) {
            for (withCancellation in listOf(false, true)) {
                val pairJob = SupervisorJob(backgroundScope.coroutineContext[Job])
                val pairScope = CoroutineScope(backgroundScope.coroutineContext + pairJob)
                val clock = BenchmarkClock()
                val probe = WorkProbe(System::nanoTime, ::allocatedNow)
                val demo = DebugDemoController(pairScope, clock, automaticTicks = false, probe = probe, transportMode = mode)
                try {
                    demo.initialize()
                    for (repetition in listOf("first", "repeat")) {
                        val setupStagesBefore = probe.snapshot()
                        val setupBefore = counters()
                        val setupStarted = System.nanoTime()
                        demo.loadJson(json)
                        val setupDurationNs = System.nanoTime() - setupStarted
                        val setupAfter = counters()
                        val setupStagesAfter = probe.snapshot()
                        assertEquals(DemoPhase.READY, demo.state.value.playback.phase)
                        val stagesBefore = probe.snapshot()
                        val before = counters()
                        val startedElapsed = clock.elapsedMs
                        val startedNs = System.nanoTime()
                        demo.start()
                        var questionStarted = false
                        var questionCancelled = false
                        var ticks = 0
                        while (demo.state.value.playback.phase == DemoPhase.PLAYING) {
                            clock.elapsedMs++
                            demo.tick()
                            ticks++
                            val elapsed = clock.elapsedMs - startedElapsed
                            if (withCancellation && !questionStarted && elapsed == 5_000L) {
                                demo.beginQuestion()
                                assertEquals(1, demo.state.value.playback.speedMultiplier)
                                questionStarted = true
                            }
                            if (withCancellation && !questionCancelled && elapsed == 6_000L) {
                                demo.cancelQuestion()
                                assertEquals(60, demo.state.value.playback.speedMultiplier)
                                assertEquals(DebugDemoQuestionPhase.CANCELLED, demo.state.value.questionPhase)
                                questionCancelled = true
                            }
                            check(ticks <= 32_000) { "동일 원본은 정해진 가상 시간 안에 종료해야 합니다." }
                        }
                        val durationNs = System.nanoTime() - startedNs
                        val after = counters()
                        val stagesAfter = probe.snapshot()
                        val terminal = demo.state.value
                        assertEquals(DemoPhase.COMPLETED, terminal.playback.phase)
                        assertEquals(18_000L, terminal.playback.deliveredSamples)
                        assertEquals(1_799_900L, terminal.playback.lastOriginalSampleMs)
                        assertEquals(withCancellation, questionCancelled)
                        val audit = requireNotNull(terminal.deliveryAudit)
                        assertCompleteAudit(audit)
                        val records = demo.exportTrace()
                        val summary = records.first { it["type"] == "summary" }
                        @Suppress("UNCHECKED_CAST")
                        val session = (summary["sessionSummaries"] as List<Map<String, Any?>>).last {
                            (it["sessionId"] as Number).toLong() == terminal.playback.sessionId
                        }
                        val condition = if (withCancellation) "question-cancel" else "normal"
                        val label = "${mode.name.lowercase()}-$condition-$repetition"
                        val traceFile = File(output, "$label.jsonl")
                        DebugDemoTrace.write(traceFile, records)
                        val emitted = terminal.diagnostics.emittedFrames
                        val skipped = terminal.diagnostics.skippedDeadlines
                        runs += linkedMapOf(
                            "transportMode" to mode.name,
                            "condition" to condition,
                            "repetition" to repetition,
                            "scopePair" to "${mode.name}/$condition",
                            "scenarioId" to terminal.playback.scenarioId,
                            "scenarioJsonSha256" to jsonHash,
                            "originalSamples" to 18_000,
                            "scenarioDurationMs" to 1_800_000,
                            "tickAdvanceRealMs" to 1,
                            "ticks" to ticks,
                            "virtualElapsedMs" to clock.elapsedMs - startedElapsed,
                            "wallDurationNs" to durationNs,
                            "setup" to linkedMapOf("wallDurationNs" to setupDurationNs,
                                "currentThreadAllocationDeltaBytes" to allocationDelta(setupBefore, setupAfter),
                                "processThreadsAllocationDeltaBytes" to setupBefore.processThreadsAllocatedBytes?.let { start ->
                                    setupAfter.processThreadsAllocatedBytes?.let { it - start }
                                },
                                "usedHeapBeforeBytes" to setupBefore.usedHeapBytes,
                                "usedHeapAfterBytes" to setupAfter.usedHeapBytes,
                                "heapDeltaBytes" to setupAfter.usedHeapBytes - setupBefore.usedHeapBytes,
                                "gcCountDelta" to setupAfter.gcCount - setupBefore.gcCount,
                                "gcTimeDeltaMs" to setupAfter.gcTimeMs - setupBefore.gcTimeMs,
                                "stageDeltas" to stageDeltas(setupStagesBefore, setupStagesAfter),
                                "heapMeasurementScope" to "GC를 강제하지 않은 load 전후 JVM 사용 heap 차이. 파싱 garbage와 기존 세션 해제도 포함하므로 유지 객체의 정확한 크기가 아님."),
                            "before" to before,
                            "after" to after,
                            "currentThreadAllocationDeltaBytes" to allocationDelta(before, after),
                            "processThreadsAllocationDeltaBytes" to before.processThreadsAllocatedBytes?.let { start ->
                                after.processThreadsAllocatedBytes?.let { it - start }
                            },
                            "gcCountDelta" to after.gcCount - before.gcCount,
                            "gcTimeDeltaMs" to after.gcTimeMs - before.gcTimeMs,
                            "heapDeltaBytes" to after.usedHeapBytes - before.usedHeapBytes,
                            "stageDeltas" to stageDeltas(stagesBefore, stagesAfter),
                            "stageCumulativeAtEnd" to stagesAfter,
                            "deliveryAudit" to audit,
                            "sessionSummary" to session,
                            "hubPublication" to linkedMapOf("emittedFrames" to emitted, "skippedDeadlines" to skipped,
                                "emittedPlusSkippedDeadlines" to emitted + skipped,
                                "nominalDeadlinesForScenarioDuration" to 18_000,
                                "unpublishedTailDeadlinesAtCompletion" to 18_000L - emitted - skipped),
                            "tracePath" to traceFile.path,
                        )
                    }
                } finally {
                    demo.close()
                    pairJob.cancelAndJoin()
                }
            }
        }
        val report = linkedMapOf(
            "measurement" to "JVM_DEMO_TRANSPORT_FIXED_VIRTUAL_TICKS",
            "scenarioAssetPath" to asset.path,
            "scenarioJsonSha256" to jsonHash,
            "javaVersion" to System.getProperty("java.version"),
            "allocationSupported" to (bean?.isThreadAllocatedMemorySupported == true),
            "matrixRuns" to 8,
            "notes" to listOf(
                "실제 AVD와 같은 JSON. 가상1ms tick으로 운행하며 처리 시간·할당은 실제 JVM 계측이다. AVD deadline 성능을 대신하지 않는다.",
                "mode×normal/cancel마다 fresh scope와 Controller를 만들고 같은 Controller에서 first/repeat를 재생한다. first는 JVM cold start 표시가 아니다.",
                "각 run의 stageDeltas는 load 이후 start 직전부터 종료까지 count/total/할당 차분이다. setup의 JSON 파싱·Hub 재연결 비용은 별도다.",
                "p95/max는 누계 차분으로 복원할 수 없어 stageCumulativeAtEnd에만 보존한다. 중첩 stage를 단순 합산하지 않는다.",
                "setup은 load의 stage·할당·heap/GC를 replay와 분리한다. 강제GC 없이 잰 heap 차이로 유지 객체량을 확정하지 않는다.",
                "현재 스레드 할당은 단일 테스트 스레드의 차이이며 processThreads 합은 스레드 종료 영향이 있을 수 있다. JSON 직렬화·trace 파일 쓰기는 재생 계측 뒤에 수행한다.",
            ),
            "runs" to runs,
        )
        File(output, "jvm-transport-matrix.json").writeText(
            GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report) + "\n")
        assertEquals(8, runs.size)
    }

    private fun allocationDelta(before: RuntimeCounters, after: RuntimeCounters): Long? =
        before.currentThreadAllocatedBytes?.let { start -> after.currentThreadAllocatedBytes?.let { it - start } }

    private fun stageDeltas(before: Map<String, Map<String, Number>>, after: Map<String, Map<String, Number>>): Map<String, Map<String, Long?>> =
        after.mapValues { (stage, values) ->
            val start = before.getValue(stage)
            val calls = values.getValue("calls").toLong() - start.getValue("calls").toLong()
            val total = values.getValue("totalNs").toLong() - start.getValue("totalNs").toLong()
            linkedMapOf("calls" to calls, "totalNs" to total, "meanNs" to if (calls > 0) total / calls else null,
                "allocatedBytes" to values.getValue("allocatedBytes").toLong() - start.getValue("allocatedBytes").toLong(),
                "allocationCalls" to values.getValue("allocationCalls").toLong() - start.getValue("allocationCalls").toLong())
        }

    private fun assertCompleteAudit(audit: DemoDeliveryAuditSnapshot) {
        assertEquals(18_000, audit.expectedTotal)
        assertEquals(3, audit.channels.size)
        audit.channels.values.forEach { channel ->
            assertEquals(18_000L, channel.originalCount)
            assertEquals(18_000L, channel.uniqueExpectedCount)
            assertEquals(0L, channel.missingSamples)
            assertEquals(0L, channel.remainingExpectedSamples)
            assertEquals(0L, channel.duplicates)
            assertEquals(0L, channel.outOfOrder)
            assertEquals(0L, channel.unknownTimestamps)
            assertEquals(1L, channel.invalidationCount)
            assertEquals(0L, channel.firstOriginalMs)
            assertEquals(1_799_900L, channel.lastOriginalMs)
            assertEquals(audit.expectedHash, channel.hash)
        }
    }

    private fun performanceAsset(): File = listOf(
        File("src/debug/assets/demo_perf_validation.json"), File("app/src/debug/assets/demo_perf_validation.json"),
    ).first { it.isFile }
}
