package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionRequest
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.SafetyEvent
import com.sleepissue.awakedrive.engine.model.SafetyEventType
import com.sleepissue.awakedrive.engine.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2번 엔진이 보내는 `CheckDue`(민감 모드 첫 음성 확인, REQ_BSL_006)를 1번 PolicyInterventionManager가 처리하는 시험.
 * 급조향 1회와 같은 길: 30초에 한 칸씩 2단계로 올라가 운전자 확인을 한 번 요청한다 (REQ_INT_004).
 */
class CheckDueInputTest {

    private class Harness {
        val clock = FakeClock()
        val output = mutableListOf<ExecutionRequest>()
        private var nextAction = 0
        private var nextCommand = 0
        val manager = PolicyInterventionManager(
            InterventionPolicy(EngineConfig()), EngineConfig(), clock,
            Source.REAL, RequestTimerBasis.ELAPSED_REALTIME,
        ) { RequestId("action-${++nextAction}") }

        fun at(ms: Long) { clock.scenarioMs = ms; clock.elapsedMs = ms }
        suspend fun send(input: InterventionInput) =
            manager.submit(RequestId("command-${++nextCommand}"), input).also { yield() }
        val checks get() = output.filterIsInstance<CheckRequest>().filter { it.context == CheckContext.DRIVER_ALERTNESS }
    }

    private fun withHarness(block: suspend Harness.() -> Unit) = runBlocking {
        val h = Harness()
        val collector = launch(Dispatchers.Unconfined) { h.manager.requests.collect { h.output += it } }
        h.manager.start(RequestId("start"))
        try { h.block() } finally { collector.cancel() }
    }

    @Test
    fun firstCheckDueGoesToStageTwoAndAsksTheDriverOnce() = withHarness {
        at(900_000L)
        assertEquals(ExecutionStatus.REQUESTED, send(InterventionInput.CheckDue(900_000L, Source.REAL)).status)
        assertEquals(InterventionStage.CAUTION, manager.state.value.stage)
        assertTrue("1단계에서는 묻지 않는다", checks.isEmpty())
        at(929_000L); send(InterventionInput.Tick)
        assertEquals(InterventionStage.CAUTION, manager.state.value.stage)
        at(930_000L); send(InterventionInput.Tick)
        assertEquals(InterventionStage.CHECK, manager.state.value.stage)
        assertEquals(1, checks.size)
        at(960_000L); send(InterventionInput.Tick)
        assertEquals("다시 묻지 않는다", 1, checks.size)
    }

    @Test
    fun checkDueFromAnotherSessionOrTheFutureIsRejected() = withHarness {
        at(10_000L)
        val foreign = send(InterventionInput.CheckDue(10_000L, Source.DEMO))
        assertEquals(ExecutionStatus.FAILED, foreign.status)
        assertEquals(ExecutionFailure.UNAVAILABLE, foreign.failure)
        assertEquals(InterventionStage.NORMAL, manager.state.value.stage)
        val future = runCatching { send(InterventionInput.CheckDue(20_000L, Source.REAL)) }
        assertTrue(future.isFailure)
        assertEquals(InterventionStage.NORMAL, manager.state.value.stage)
    }

    @Test
    fun checkDueAtStageThreeChangesNothing() = withHarness {
        at(5_000L)
        send(InterventionInput.Safety(SafetyEvent(5_000L, SafetyEventType.REPEATED_RAPID_STEERING, Source.REAL)))
        assertEquals(InterventionStage.REST_GUIDANCE, manager.state.value.stage)
        val before = output.size
        at(6_000L); send(InterventionInput.CheckDue(6_000L, Source.REAL))
        assertEquals(InterventionStage.REST_GUIDANCE, manager.state.value.stage)
        assertTrue(checks.isEmpty())
        assertEquals(before, output.size)
    }
}
