package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser
import com.sleepissue.awakedrive.signal.VehicleSignalInput
import com.sleepissue.awakedrive.voice.CheckManager
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 실제 경과 시계는 데모 운행 시계와 독립적으로만 전진시킨다. */
internal class DemoTestClock(var realMs: Long = 0L) : Clock {
    override fun scenarioTimeMs() = realMs
    override fun elapsedRealtimeMs() = realMs
    override fun wallTimeMs() = 1_800_000_000_000L + realMs
}

/** 실제 JSON 검증을 거친 원본 관측을 player와 Hub 시험에서 함께 사용한다. */
internal fun demoTestScenario(
    durationMs: Long = 20_000L,
    segments: List<DemoSpeedSegment> = listOf(DemoSpeedSegment(0L, durationMs, 1)),
): DemoScenario {
    fun seconds(ms: Long) = BigDecimal.valueOf(ms).movePointLeft(3).toPlainString()
    val speeds = segments.joinToString(",") {
        """{"fromSec":${seconds(it.fromMs)},"toSec":${seconds(it.toMs)},"factor":${it.factor}}"""
    }
    val samples = (0L until durationMs step 100L).joinToString(",") {
        """{"t":${seconds(it)},"steeringDeg":8,"speedKph":40,"gear":"D","turnSignal":"OFF","brake":false,"seats":null,"cruise":null,"lat":35.5,"lon":129.0,"power":"ON","source":"DEMO"}"""
    }
    val json = """{"schemaVersion":1,"id":"test-demo","startEpochMs":1800000000000,"source":"DEMO","speedSegments":[$speeds],"samples":[$samples]}"""
    val loaded = DemoJsonLoader().parse(json)
    check(loaded is DemoLoadResult.Loaded) { "시험용 JSON이 유효하지 않습니다: $loaded" }
    return loaded.scenario
}

internal fun demoTestRepository(scenario: DemoScenario) = DemoScenarioRepository { id ->
    if (id == scenario.id) DemoLoadResult.Loaded(scenario) else DemoLoadResult.Failed(
        DemoLoadError(DemoLoadErrorCode.FILE_NOT_FOUND, id, "시험 시나리오가 없습니다."),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioDemoPlayerTest {
    private fun id(value: String) = RequestId(value)

    @Test
    fun loadStartPauseResumeStopAndResetHaveDistinctEffects() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(20_000L, listOf(DemoSpeedSegment(0, 20_000, 10)))
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        val samples = mutableListOf<VehicleSignalInput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { samples += it } }

        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.Start(id("before-load"))).status)
        assertEquals(DemoPhase.NOT_LOADED, player.playbackState.value.phase)
        assertEquals(ExecutionStatus.SUCCEEDED, player.execute(DemoCommand.Load(id("load"), scenario.id)).status)
        assertEquals(DemoPhase.READY, player.playbackState.value.phase)
        assertEquals(0, player.playbackState.value.speedMultiplier)
        player.execute(DemoCommand.Start(id("start")))
        assertEquals(listOf(0L), samples.map { it.tMs })

        real.realMs = 500L
        player.execute(DemoCommand.Pause(id("pause")))
        assertEquals(DemoPhase.PAUSED, player.playbackState.value.phase)
        assertEquals(5_000L, player.playbackState.value.scenarioTimeMs)
        assertEquals(5_000L, samples.last().tMs)
        val pausedCount = samples.size
        real.realMs = 1_500L
        player.pump()
        assertEquals(5_000L, player.playbackState.value.scenarioTimeMs)
        assertEquals(1_500L, player.playbackState.value.realElapsedMs)
        assertEquals(0, player.playbackState.value.speedMultiplier)
        assertEquals(pausedCount, samples.size)

        player.execute(DemoCommand.Resume(id("resume")))
        real.realMs = 1_600L
        player.pump()
        assertEquals(6_000L, player.playbackState.value.scenarioTimeMs)
        player.execute(DemoCommand.Stop(id("stop")))
        assertEquals(DemoPhase.STOPPED, player.playbackState.value.phase)
        assertEquals(6_000L, samples.last().tMs)
        assertNull(samples.last().speed)
        assertTrue(samples.last().quality.values.all { it.availability == SignalAvailability.UNAVAILABLE })
        val stoppedCount = samples.size
        real.realMs = 6_000L
        player.pump()
        assertEquals(stoppedCount, samples.size)
        assertEquals(6_000L, player.clock.scenarioTimeMs())
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.Resume(id("resume-stopped"))).status)

        val oldSession = player.playbackState.value.sessionId
        player.execute(DemoCommand.Reset(id("reset")))
        assertEquals(DemoPhase.NOT_LOADED, player.playbackState.value.phase)
        assertNull(player.playbackState.value.scenarioId)
        assertEquals(0L, player.clock.scenarioTimeMs())
        assertTrue(player.playbackState.value.sessionId > oldSession)
    }

    @Test
    fun sixtyTimesPlaybackPreservesEveryOriginalObservationAndItsScenarioTimestamp() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(10_000L, listOf(DemoSpeedSegment(0, 10_000, 60)))
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        val samples = mutableListOf<VehicleSignalInput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { samples += it } }
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        real.realMs = 100L
        player.pump()

        assertEquals((0L..6_000L step 100L).toList(), samples.map { it.tMs })
        assertEquals(6_000L, player.playbackState.value.scenarioTimeMs)
        assertEquals(100L, player.playbackState.value.realElapsedMs)
        assertTrue(samples.all { it.source == Source.DEMO })
        samples.forEach { sample ->
            assertTrue(sample.quality.values.all {
                it.source == Source.DEMO && it.observedAtMs == sample.tMs && it.receivedAtElapsedMs == null
            })
        }
    }

    @Test
    fun pauseFreezesAtCommandReceiptEvenWhenDrainingSamplesTakesRealTime() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(1_000L)
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        var drainingPause = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.samples.collect { sample ->
                if (drainingPause && sample.tMs == 100L) {
                    real.realMs = 1_000L // 마지막 관측 전달을 기다리는 동안 실제 시간만 더 흐른다.
                }
            }
        }
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        real.realMs = 900L
        drainingPause = true
        player.execute(DemoCommand.Pause(id("pause")))
        assertEquals(DemoPhase.PAUSED, player.playbackState.value.phase)
        assertEquals(900L, player.playbackState.value.scenarioTimeMs)
        assertEquals(ExecutionStatus.SUCCEEDED, player.execute(DemoCommand.Resume(id("resume"))).status)
        assertEquals(900L, player.playbackState.value.scenarioTimeMs)
        real.realMs = 1_100L
        player.pump()
        assertEquals(DemoPhase.COMPLETED, player.playbackState.value.phase)
    }

    @Test
    fun completionEmitsRemainingSamplesThenInvalidatesWithoutAllowingImplicitRestart() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(1_000L)
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        val samples = mutableListOf<VehicleSignalInput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { samples += it } }
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        real.realMs = 2_000L
        player.pump()

        assertEquals((0L..900L step 100L).toList(), samples.filter { it.speed != null }.map { it.tMs })
        assertEquals(1_000L, samples.last().tMs)
        assertNull(samples.last().speed)
        assertEquals(DemoPhase.COMPLETED, player.playbackState.value.phase)
        assertEquals(1_000L, player.playbackState.value.realElapsedMs)
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.Start(id("restart"))).status)
        real.realMs = 3_000L
        player.pump()
        assertEquals(11, samples.size)
    }

    @Test
    fun questionUsesOneTimesAcrossSegmentBoundaryAndRestoresTheCurrentSegmentImmediately() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(20_000L, listOf(
            DemoSpeedSegment(0, 1_000, 10), DemoSpeedSegment(1_000, 20_000, 60),
        ))
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        real.realMs = 50L
        player.pump()
        assertEquals(500L, player.playbackState.value.scenarioTimeMs)
        val session = player.playbackState.value.sessionId
        val question = id("question")
        player.execute(DemoCommand.QuestionStarted(question, session))
        assertEquals(1, player.playbackState.value.speedMultiplier)
        real.realMs = 2_050L
        player.pump()
        assertEquals(2_500L, player.playbackState.value.scenarioTimeMs)
        assertEquals(60, player.playbackState.value.segmentMultiplier)
        assertEquals(1, player.playbackState.value.speedMultiplier)
        player.execute(DemoCommand.QuestionFinished(question, session))
        assertEquals(60, player.playbackState.value.speedMultiplier)
        real.realMs = 2_060L
        player.pump()
        assertEquals(3_100L, player.playbackState.value.scenarioTimeMs)
    }

    @Test
    fun questionDuplicateOverlapFailureCancellationAndPauseFollowTheSameSessionToken() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(20_000L, listOf(DemoSpeedSegment(0, 20_000, 60)))
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        val session = player.playbackState.value.sessionId
        val first = id("first")
        val second = id("second")

        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.QuestionStarted(first)).status)
        assertEquals(ExecutionStatus.SUCCEEDED, player.execute(DemoCommand.QuestionStarted(first, session)).status)
        assertEquals(ExecutionStatus.SUCCEEDED, player.execute(DemoCommand.QuestionStarted(first, session)).status)
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.QuestionStarted(second, session)).status)
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.QuestionFinished(first, session - 1)).status)
        assertEquals(first, player.playbackState.value.questionRequestId)
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.QuestionFinished(second, session)).status)
        player.execute(DemoCommand.QuestionFinished(first, session, DemoQuestionOutcome.FAILED))
        assertEquals(60, player.playbackState.value.speedMultiplier)
        assertEquals(DemoQuestionOutcome.FAILED, player.playbackState.value.questionOutcome)
        assertEquals(ExecutionStatus.SUCCEEDED, player.execute(DemoCommand.QuestionFinished(first, session)).status)
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.QuestionStarted(first, session)).status)

        player.execute(DemoCommand.QuestionStarted(second, session))
        player.execute(DemoCommand.Pause(id("pause")))
        real.realMs = 1_000L
        player.pump()
        assertEquals(0L, player.playbackState.value.scenarioTimeMs)
        assertEquals(0, player.playbackState.value.speedMultiplier)
        player.execute(DemoCommand.Resume(id("resume")))
        assertEquals(1, player.playbackState.value.speedMultiplier)
        real.realMs = 1_100L
        player.pump()
        assertEquals(100L, player.playbackState.value.scenarioTimeMs)
        player.execute(DemoCommand.QuestionFinished(second, session, DemoQuestionOutcome.CANCELLED))
        assertEquals(60, player.playbackState.value.speedMultiplier)
        assertNull(player.playbackState.value.questionRequestId)
        assertEquals(DemoQuestionOutcome.CANCELLED, player.playbackState.value.questionOutcome)
    }

    @Test
    fun stoppedAndResetSessionsRejectLateQuestionResultsWithoutUnlockingANewQuestion() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario()
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        val oldSession = player.playbackState.value.sessionId
        val oldQuestion = id("old-question")
        player.execute(DemoCommand.QuestionStarted(oldQuestion, oldSession))
        player.execute(DemoCommand.Stop(id("stop")))
        assertNull(player.playbackState.value.questionRequestId)
        assertEquals(DemoQuestionOutcome.CANCELLED, player.playbackState.value.questionOutcome)
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.QuestionFinished(oldQuestion, oldSession)).status)
        player.execute(DemoCommand.Reset(id("reset")))
        player.execute(DemoCommand.Load(id("reload"), scenario.id))
        player.execute(DemoCommand.Start(id("restart")))
        val newQuestion = id("new-question")
        player.execute(DemoCommand.QuestionStarted(newQuestion, player.playbackState.value.sessionId))
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.QuestionFinished(oldQuestion, oldSession)).status)
        assertEquals(newQuestion, player.playbackState.value.questionRequestId)
        assertEquals(1, player.playbackState.value.speedMultiplier)
    }

    @Test
    fun actualCheckManagerMeasuresRealVoiceLatencyEvenWithAcceleratedTripTimeAndLateStt() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(20_000L, listOf(DemoSpeedSegment(0, 20_000, 60)))
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        real.realMs = 100L
        player.pump()
        assertEquals(6_000L, player.playbackState.value.scenarioTimeMs)
        val question = id("real-time-question")
        val session = player.playbackState.value.sessionId
        player.execute(DemoCommand.QuestionStarted(question, session))
        val manager = CheckManager(player.clock, CheckPolicy(), SpeechIntentParser())
        manager.onBeepEnded()
        real.realMs = 2_400L
        manager.onVoiceStarted()
        real.realMs = 3_400L // STT 처리 시간은 응답 시간에 더하지 않는다.
        val result = manager.finish(900L, QuestionType.DRIVER_CHECK, "네")
        assertEquals(2_300, result.latencyMs)
        assertEquals(CheckVerdict.SLOW, result.verdict)
        assertEquals(3_400L, result.ts)
        assertEquals(9_300L, player.clock.scenarioTimeMs())
        player.execute(DemoCommand.QuestionFinished(question, session))
        assertEquals(60, player.playbackState.value.speedMultiplier)
    }

    @Test
    fun failedLoadDoesNotKeepAnOldScenarioAndCanBeFollowedByAValidLoad() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario()
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.Load(id("missing"), "missing")).status)
        assertEquals(DemoPhase.FAILED, player.playbackState.value.phase)
        assertEquals(DemoLoadErrorCode.FILE_NOT_FOUND, player.playbackState.value.error?.code)
        assertNull(player.playbackState.value.scenarioId)
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.Start(id("start-failed"))).status)
        assertEquals(ExecutionStatus.SUCCEEDED, player.execute(DemoCommand.Load(id("load"), scenario.id)).status)
        assertEquals(DemoPhase.READY, player.playbackState.value.phase)
        assertNull(player.playbackState.value.error)
    }

    @Test
    fun reversedRealClockFailsPlaybackClearsTheQuestionAndInvalidatesAtTheLastSafeTime() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(20_000L, listOf(DemoSpeedSegment(0, 20_000, 60)))
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        val samples = mutableListOf<VehicleSignalInput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { samples += it } }
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        real.realMs = 100L
        player.pump()
        player.execute(DemoCommand.QuestionStarted(id("question"), player.playbackState.value.sessionId))
        real.realMs = 90L
        player.pump()
        assertEquals(DemoPhase.FAILED, player.playbackState.value.phase)
        assertEquals(0, player.playbackState.value.speedMultiplier)
        assertNull(player.playbackState.value.questionRequestId)
        assertEquals(DemoQuestionOutcome.FAILED, player.playbackState.value.questionOutcome)
        assertEquals(6_000L, samples.last().tMs)
        assertNull(samples.last().speed)
        val afterFailure = samples.size
        real.realMs = 200L
        player.pump()
        assertEquals(afterFailure, samples.size)
        assertEquals(6_000L, player.clock.scenarioTimeMs())
    }

    @Test
    fun lateFinalizerOfAnOldTickerCannotStopAReloadedSession() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario()
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope)
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        runCurrent()
        player.execute(DemoCommand.Stop(id("stop")))
        player.execute(DemoCommand.Load(id("reload"), scenario.id))
        player.execute(DemoCommand.Start(id("restart")))
        runCurrent() // 이전 ticker의 finally와 새 ticker의 시작을 같은 scheduler에서 처리한다.
        assertEquals(DemoPhase.PLAYING, player.playbackState.value.phase)
        assertEquals(1, player.playbackState.value.speedMultiplier)
        player.execute(DemoCommand.Stop(id("cleanup")))
        runCurrent()
    }

    @Test
    fun ownerCancellationStopsTheClockAndClearsTheActiveQuestion() = runTest {
        val real = DemoTestClock()
        val owner = Job(backgroundScope.coroutineContext[Job])
        val scenario = demoTestScenario()
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario),
            CoroutineScope(backgroundScope.coroutineContext + owner))
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        player.execute(DemoCommand.QuestionStarted(id("question"), player.playbackState.value.sessionId))
        runCurrent()
        owner.cancelAndJoin()
        assertEquals(DemoPhase.STOPPED, player.playbackState.value.phase)
        assertNull(player.playbackState.value.questionRequestId)
        assertEquals(DemoQuestionOutcome.CANCELLED, player.playbackState.value.questionOutcome)
        real.realMs = 1_000L
        assertEquals(0L, player.clock.scenarioTimeMs())
    }

    @Test
    fun aCancelledOwnerCannotStartANewPlaybackOrEmitItsFirstSample() = runTest {
        val real = DemoTestClock()
        val owner = Job(backgroundScope.coroutineContext[Job])
        val scenario = demoTestScenario()
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario),
            CoroutineScope(backgroundScope.coroutineContext + owner))
        val samples = mutableListOf<VehicleSignalInput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { samples += it } }
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        owner.cancelAndJoin()
        assertEquals(ExecutionStatus.FAILED, player.execute(DemoCommand.Start(id("start"))).status)
        assertEquals(DemoPhase.READY, player.playbackState.value.phase)
        assertTrue(samples.isEmpty())
        real.realMs = 1_000L
        assertEquals(0L, player.clock.scenarioTimeMs())
        assertEquals(0, player.playbackState.value.speedMultiplier)
    }
}
