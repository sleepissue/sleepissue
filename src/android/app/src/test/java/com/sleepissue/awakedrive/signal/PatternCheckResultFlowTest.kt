package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.CheckOutcome
import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.EvaluationStatus
import com.sleepissue.awakedrive.engine.model.ExecutionRequest
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2번 패턴(추세형·튐형)과 사람의 확인 결과를 합친 단계 처리 (TC_PAT_004 단계 하강·TC_PAT_006).
 * 판단은 1번 PolicyInterventionManager·InterventionPolicy가 한다. 1번 코드는 바꾸지 않고, 2번 TC 증거로 흐름만 확인한다.
 */
class PatternCheckResultFlowTest {

    private class Harness {
        val clock = FakeClock()
        val output = mutableListOf<ExecutionRequest>()
        private var nextAction = 0
        private var nextCommand = 0
        val manager = PolicyInterventionManager(
            InterventionPolicy(EngineConfig()), EngineConfig(), clock,
            Source.REAL, RequestTimerBasis.ELAPSED_REALTIME,
        ) { RequestId("action-${++nextAction}") }

        val stage get() = manager.state.value.stage
        val checks get() = output.filterIsInstance<CheckRequest>().filter { it.context == CheckContext.DRIVER_ALERTNESS }

        fun at(ms: Long) { clock.scenarioMs = ms; clock.elapsedMs = ms }
        suspend fun send(input: InterventionInput) =
            manager.submit(RequestId("command-${++nextCommand}"), input).also { yield() }
        suspend fun score(value: Float, pattern: DrivingPattern) = send(InterventionInput.Assessment(
            DrivingAssessment(clock.scenarioMs, EvaluationStatus.EVALUATED, value, pattern, assistedDriving = false)))
        suspend fun answer(outcome: CheckOutcome) {
            val request = checks.last()
            manager.onResult(ExecutionResult(request.requestId, ExecutionStatus.STARTED)); yield()
            send(InterventionInput.CheckCompleted(CheckResult(request.requestId, request.context, outcome, responseTimeMs = 800L)))
        }
    }

    private fun withHarness(block: suspend Harness.() -> Unit) = runBlocking {
        val h = Harness()
        val collector = launch(Dispatchers.Unconfined) { h.manager.requests.collect { h.output += it } }
        h.manager.start(RequestId("start"))
        try { h.block() } finally { collector.cancel() }
    }

    @Test
    fun normalAnswerWithTrendKeepsStageTwoAndAsksAgainTenMinutesLater() = withHarness {
        // (정상, 추세형) → 2단계 유지, 600 s 뒤 다시 확인 (TC_PAT_006)
        score(65f, DrivingPattern.TRENDING); at(30_000L); score(65f, DrivingPattern.TRENDING)
        assertEquals(InterventionStage.CHECK, stage)
        answer(CheckOutcome.NORMAL)
        at(629_999L); score(65f, DrivingPattern.TRENDING)
        assertEquals(InterventionStage.CHECK, stage)
        assertEquals(1, checks.size)
        at(630_000L); send(InterventionInput.Tick)
        assertEquals(InterventionStage.CHECK, stage)
        assertEquals(2, checks.size)
    }

    @Test
    fun normalAnswerWithSpikeComesDownAfterTenLowMinutes() = withHarness {
        // (정상, 튐형) → 단계 하강: 점수 60 미만이 10분 이어지면 1단계 (TC_PAT_004 뒷부분, REQ_INT_007)
        score(65f, DrivingPattern.UNCERTAIN); at(30_000L); score(65f, DrivingPattern.UNCERTAIN)
        assertEquals(InterventionStage.CHECK, stage)
        answer(CheckOutcome.NORMAL)
        at(60_000L); score(30f, DrivingPattern.TRANSIENT)
        at(659_999L); score(30f, DrivingPattern.TRANSIENT)
        assertEquals(InterventionStage.CHECK, stage)
        at(660_000L); score(30f, DrivingPattern.TRANSIENT)
        assertEquals(InterventionStage.CAUTION, stage)
        assertEquals("다시 묻지 않는다", 1, checks.size)
    }

    @Test
    fun normalAnswerWithTrendDoesNotComeDownEvenWhenScoreDrops() = withHarness {
        // 추세형이 이어지면 점수가 내려가도 2단계 유지 (TC_PAT_006, 기획서 5-2)
        score(65f, DrivingPattern.TRENDING); at(30_000L); score(65f, DrivingPattern.TRENDING)
        answer(CheckOutcome.NORMAL)
        at(60_000L); score(30f, DrivingPattern.TRENDING)
        at(660_000L); score(30f, DrivingPattern.TRENDING)
        assertEquals(InterventionStage.CHECK, stage)
    }
}
