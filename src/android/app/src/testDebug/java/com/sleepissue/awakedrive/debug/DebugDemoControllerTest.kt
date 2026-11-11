package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.demo.DemoPhase
import com.sleepissue.awakedrive.demo.DemoDeliveryAuditSnapshot
import com.sleepissue.awakedrive.demo.DemoDeliveryChannel
import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DebugDemoControllerTest {
    @Test fun bundledExampleTraversesAllThreeFactorsAndClosesItsHubAtEnd() = runTest {
        val real = FakeClock()
        val demo = DebugDemoController(backgroundScope, real, automaticTicks = false)
        demo.initialize()
        demo.loadJson(validationAsset().readText())
        assertEquals(DemoPhase.READY, demo.state.value.playback.phase)
        demo.start()
        real.elapsedMs = 2_999; demo.tick()
        assertEquals(2_999L, demo.state.value.playback.scenarioTimeMs)
        assertEquals(1, demo.state.value.playback.speedMultiplier)
        real.elapsedMs = 3_000; demo.tick()
        assertEquals(10, demo.state.value.playback.speedMultiplier)
        real.elapsedMs = 9_000; demo.tick()
        assertEquals(63_000L, demo.state.value.playback.scenarioTimeMs)
        assertEquals(60, demo.state.value.playback.speedMultiplier)
        real.elapsedMs = 14_000; demo.tick()
        assertEquals(DemoPhase.COMPLETED, demo.state.value.playback.phase)
        assertFalse(demo.state.value.diagnostics.running)
        assertNull(demo.state.value.frame)
        demo.close()
    }

    @Test fun fakeQuestionReusesCheckManagerAndNeverScalesTheMeasuredResponse() = runTest {
        val real = FakeClock()
        val demo = DebugDemoController(backgroundScope, real, automaticTicks = false)
        demo.initialize(); demo.loadJson(scenarioJson()); demo.start()
        real.elapsedMs += 100; demo.tick()
        assertEquals(6_000L, demo.state.value.playback.scenarioTimeMs)
        demo.beginQuestion(); demo.markBeepEnded()
        real.elapsedMs += 2_300; demo.tick(); demo.markVoiceStarted()
        assertEquals(8_300L, demo.state.value.playback.scenarioTimeMs)
        assertEquals(1, demo.state.value.playback.speedMultiplier)
        real.elapsedMs += 700; demo.tick(); demo.finishQuestion()
        assertEquals(2_300, demo.state.value.response!!.latencyMs)
        assertEquals(CheckVerdict.SLOW, demo.state.value.response!!.verdict)
        assertEquals(60, demo.state.value.playback.speedMultiplier)
        assertEquals(DebugDemoQuestionPhase.FINISHED, demo.state.value.questionPhase)
        val atFinish = demo.state.value.playback.scenarioTimeMs
        real.elapsedMs += 100; demo.tick()
        assertEquals(atFinish + 6_000, demo.state.value.playback.scenarioTimeMs)
        demo.close()
    }

    @Test fun pauseFakeRealSelectionCoordinatesFailureCancellationAndLateResultsAreOperable() = runTest {
        val real = FakeClock()
        val demo = DebugDemoController(backgroundScope, real, automaticTicks = false)
        demo.initialize(); demo.loadJson(scenarioJson()); demo.start()
        real.elapsedMs += 10; demo.tick()
        assertEquals(40f, demo.state.value.frame!!.speedKph!!, 0f)
        demo.setFakeReal(true)
        real.elapsedMs += 10; demo.tick()
        val selected = demo.state.value
        assertEquals(80f, selected.frame!!.speedKph!!, 0f)
        assertEquals(SignalSelectionReason.REAL_FRESH, selected.frame.qualityOf(SignalField.SPEED_KPH).selectionReason)
        assertEquals(35.5, selected.navi!!.lat!!, 0.0)
        assertEquals(129.0, selected.navi.lon!!, 0.0)
        assertEquals(SignalSelectionReason.DEMO_LOCATION, selected.navi.qualityOf(NaviField.LAT).selectionReason)
        demo.pause()
        val pausedAt = demo.state.value.playback.scenarioTimeMs
        real.elapsedMs += 2_000; demo.tick()
        assertEquals(pausedAt, demo.state.value.playback.scenarioTimeMs)
        assertEquals(0, demo.state.value.playback.speedMultiplier)
        demo.resume(); demo.beginQuestion(); demo.repeatQuestionStart()
        val id = demo.state.value.playback.questionRequestId
        assertNotNull(id)
        assertEquals(1, demo.state.value.playback.speedMultiplier)
        demo.failQuestion()
        assertEquals(DebugDemoQuestionPhase.FAILED, demo.state.value.questionPhase)
        assertEquals(60, demo.state.value.playback.speedMultiplier)
        demo.beginQuestion()
        val next = demo.state.value.playback.questionRequestId
        demo.repeatPreviousQuestionResult()
        assertNotEquals(id, next)
        assertEquals(next, demo.state.value.playback.questionRequestId)
        assertEquals(1, demo.state.value.playback.speedMultiplier)
        demo.stop()
        assertNull(demo.state.value.frame)
        assertFalse(demo.state.value.diagnostics.running)
        demo.reset(); demo.repeatPreviousQuestionResult()
        assertEquals(DemoPhase.NOT_LOADED, demo.state.value.playback.phase)
        assertEquals(0L, demo.state.value.playback.scenarioTimeMs)
        assertNull(demo.state.value.playback.questionRequestId)
        demo.loadJson(scenarioJson()); demo.start(); demo.beginQuestion(); demo.cancelQuestion()
        assertEquals(DebugDemoQuestionPhase.CANCELLED, demo.state.value.questionPhase)
        assertEquals(60, demo.state.value.playback.speedMultiplier)
        demo.close()
    }

    @Test fun noResponseEndsOnTheRealFiveSecondTimeoutAndRestoresTheSegmentFactor() = runTest {
        val real = object : com.sleepissue.awakedrive.engine.time.Clock {
            override fun scenarioTimeMs() = testScheduler.currentTime
            override fun elapsedRealtimeMs() = testScheduler.currentTime
            override fun wallTimeMs() = 1793862000000L + testScheduler.currentTime
        }
        val demo = DebugDemoController(backgroundScope, real, automaticTicks = false)
        demo.initialize(); demo.loadJson(scenarioJson()); demo.start()
        demo.beginQuestion(); demo.markBeepEnded()
        advanceTimeBy(4_999); demo.tick()
        assertEquals(1, demo.state.value.playback.speedMultiplier)
        assertNull(demo.state.value.response)
        advanceTimeBy(1); runCurrent()
        assertEquals(CheckVerdict.NO_RESPONSE, demo.state.value.response!!.verdict)
        assertEquals(60, demo.state.value.playback.speedMultiplier)
        assertNull(demo.state.value.playback.questionRequestId)
        demo.close()
    }

    @Test fun reloadWhilePlayingStartsAFreshHubAndDoesNotTouchManualPersistence() = runTest {
        val repository = object : TripRepository {
            var saved: TripSnapshot? = null
            override suspend fun save(snapshot: TripSnapshot): RepositoryResult { saved = snapshot; return RepositoryResult.Succeeded }
            override suspend fun load() = saved?.let { TripLoadResult.Loaded(it) } ?: TripLoadResult.Empty
            override suspend fun delete(): RepositoryResult { saved = null; return RepositoryResult.Succeeded }
        }
        val manual = DebugHarnessController(backgroundScope, repository, FakeClock())
        manual.initialize(); manual.setGear(Gear.D); manual.advanceTime(120_000); manual.setGear(Gear.P)
        manual.advanceTime(180_000); manual.saveNow()
        val before = manual.state.value
        val saved = repository.saved
        val real = FakeClock()
        val demo = DebugDemoController(backgroundScope, real, automaticTicks = false)
        demo.initialize(); demo.loadJson(scenarioJson()); demo.start()
        real.elapsedMs += 100; demo.tick()
        val oldSession = demo.state.value.playback.sessionId
        demo.loadJson(scenarioJson())
        assertEquals(DemoPhase.READY, demo.state.value.playback.phase)
        assertNotEquals(oldSession, demo.state.value.playback.sessionId)
        assertFalse(demo.state.value.diagnostics.clockFailure)
        assertNull(demo.state.value.frame)
        demo.start(); real.elapsedMs += 100; demo.tick(); demo.close(); runCurrent()
        assertEquals(before, manual.state.value)
        assertEquals(saved, repository.saved)
        manual.close()
    }

    @Test fun completedTraceRetainsBeforeAndAfterHubStopAndAuditBeyondDetailRetention() = runTest {
        val real = FakeClock()
        val demo = DebugDemoController(backgroundScope, real, automaticTicks = false)
        demo.initialize(); demo.loadJson(scenarioJson()); demo.start()
        repeat(2_000) { real.elapsedMs++; demo.tick() }
        assertEquals(DemoPhase.COMPLETED, demo.state.value.playback.phase)
        val terminal = checkNotNull(demo.state.value.terminalTrace)
        val captured = terminal.records()
        val completedRows = captured.drop(1).filter { it["phase"] == DemoPhase.COMPLETED.name }
        assertEquals(listOf(true, false), completedRows.map { it["hubRunning"] })
        val lastAudit = completedRows.last()["deliveryAudit"] as DemoDeliveryAuditSnapshot
        assertCompleteAudit(lastAudit)
        assertFalse(demo.state.value.diagnostics.running)
        assertNull(demo.state.value.frame)
        real.elapsedMs += 120_001
        demo.tick()
        assertSame(terminal, demo.captureTrace())
        assertEquals(captured, demo.captureTrace().records())
        assertCompleteAudit((demo.captureTrace().records().last()["deliveryAudit"] as DemoDeliveryAuditSnapshot))
        demo.close()
    }

    @Test fun normalSixtyAndQuestionCancellationDeliverEveryOriginalToAllThreeHubChannels() = runTest {
        for (cancelQuestion in listOf(false, true)) {
            val real = FakeClock()
            val demo = DebugDemoController(backgroundScope, real, automaticTicks = false)
            demo.initialize(); demo.loadJson(scenarioJson()); demo.start()
            var ticks = 0
            while (demo.state.value.playback.phase == DemoPhase.PLAYING) {
                real.elapsedMs++; demo.tick(); ticks++
                if (cancelQuestion && ticks == 200) {
                    demo.beginQuestion()
                    assertEquals(1, demo.state.value.playback.speedMultiplier)
                }
                if (cancelQuestion && ticks == 300) {
                    demo.cancelQuestion()
                    assertEquals(60, demo.state.value.playback.speedMultiplier)
                    assertEquals(DebugDemoQuestionPhase.CANCELLED, demo.state.value.questionPhase)
                }
                assertTrue("가상120초 시나리오는 유한한 tick 안에 완료돼야 합니다.", ticks <= 2_200)
            }
            assertEquals(DemoPhase.COMPLETED, demo.state.value.playback.phase)
            assertEquals(1_200L, demo.state.value.playback.deliveredSamples)
            assertCompleteAudit(checkNotNull(demo.state.value.deliveryAudit))
            val header = demo.captureTrace().records().first()
            val session = (header["sessionSummaries"] as List<*>).single() as Map<*, *>
            assertEquals(0L, session["unexpectedNullPublishedFrames"])
            assertEquals(0L, session["allSignalNullPublishedFrames"])
            demo.close()
        }
    }

    private fun assertCompleteAudit(audit: DemoDeliveryAuditSnapshot) {
        assertEquals(1_200, audit.expectedTotal)
        assertEquals(DemoDeliveryChannel.entries.toSet(), audit.channels.keys)
        audit.channels.forEach { (channel, received) ->
            assertEquals("$channel 원본 수", 1_200L, received.originalCount)
            assertEquals("$channel 고유 원본 수", 1_200L, received.uniqueExpectedCount)
            assertEquals("$channel 누락", 0L, received.missingSamples)
            assertEquals("$channel 미수신", 0L, received.remainingExpectedSamples)
            assertEquals("$channel 중복", 0L, received.duplicates)
            assertEquals("$channel 순서 역행", 0L, received.outOfOrder)
            assertEquals("$channel 예상 밖 시각", 0L, received.unknownTimestamps)
            assertEquals("$channel 종료 무효화", 1L, received.invalidationCount)
            assertEquals("$channel 첫 원본", 0L, received.firstOriginalMs)
            assertEquals("$channel 마지막 원본", 119_900L, received.lastOriginalMs)
            assertEquals("$channel 원본 시각 해시", audit.expectedHash, received.hash)
        }
    }

    private fun validationAsset(): File = listOf(
        File("src/debug/assets/demo_validation.json"), File("app/src/debug/assets/demo_validation.json"),
    ).first { it.isFile }

    private fun scenarioJson(): String {
        val samples = (0 until 1_200).joinToString(",") { index ->
            """{"t":${index / 10.0},"steeringDeg":8,"speedKph":40,"gear":"D","turnSignal":"OFF","brake":false,"seats":null,"cruise":null,"lat":35.5,"lon":129.0,"power":"ON"}"""
        }
        return """{"schemaVersion":1,"id":"controller-test","startEpochMs":1793862000000,"speedSegments":[{"fromSec":0,"toSec":120,"factor":60}],"samples":[$samples]}"""
    }
    @Test fun completionIsQueuedEvenWhenTheNextLoadArrivesBeforeTheScreenPolls() = runTest {
        val real = FakeClock()
        val demo = DebugDemoController(backgroundScope, real, automaticTicks = false)
        demo.initialize(); demo.loadJson(scenarioJson()); demo.start()
        real.elapsedMs = 2_000; demo.tick()
        val terminal = checkNotNull(demo.state.value.terminalTrace)
        demo.loadJson(scenarioJson())
        assertNull(demo.state.value.terminalTrace)
        assertSame(terminal, demo.completedTraces.first())
        demo.start(); demo.stop()
        val stopped = demo.completedTraces.first()
        val details = stopped.records().filter { it["type"] == "sample" }
        assertTrue(details.any { it["phase"] == "STOPPED" && it["hubRunning"] == true })
        assertTrue(details.any { it["phase"] == "STOPPED" && it["hubRunning"] == false })
        demo.close()
    }

}
