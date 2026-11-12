package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.rest.NapState
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemoExecutionTest {
    private class TestClock : Clock {
        var logical = 0L
        var real = 0L
        override fun scenarioTimeMs() = logical
        override fun elapsedRealtimeMs() = real
        override fun wallTimeMs() = logical
    }
    private class Check : CheckPort {
        override val executionResults = MutableSharedFlow<ExecutionResult>(extraBufferCapacity = 16)
        override val results = MutableSharedFlow<CheckResult>(extraBufferCapacity = 16)
        val requests = mutableListOf<CheckRequest>()
        override suspend fun start(request: CheckRequest): ExecutionResult {
            requests += request
            executionResults.emit(ExecutionResult(request.requestId, ExecutionStatus.STARTED))
            return ExecutionResult(request.requestId, ExecutionStatus.REQUESTED)
        }
        override suspend fun cancel(requestId: RequestId) = ExecutionResult(requestId, ExecutionStatus.CANCELLED).also { executionResults.emit(it) }
    }
    private class Rest : RestPort {
        override val results = MutableSharedFlow<RestResult>(extraBufferCapacity = 16)
        val requests = mutableListOf<RestRequest>()
        override suspend fun start(request: RestRequest): ExecutionResult {
            requests += request
            if (request.action == RestAction.FIND_AHEAD) results.emit(RestResult(
                ExecutionResult(request.requestId, ExecutionStatus.SUCCEEDED),
                RestAreaCandidate("test-area", "시험 쉼터", GeoPoint(36.0, 128.0), 5000f)))
            return ExecutionResult(request.requestId, ExecutionStatus.REQUESTED)
        }
        override suspend fun cancel(requestId: RequestId) = ExecutionResult(requestId, ExecutionStatus.CANCELLED)
    }
    private class Speech : SpeechPort {
        override val results = MutableSharedFlow<ExecutionResult>()
        override suspend fun start(request: SpeechRequest) = ExecutionResult(request.requestId, ExecutionStatus.REQUESTED)
        override suspend fun cancel(requestId: RequestId) = ExecutionResult(requestId, ExecutionStatus.CANCELLED)
    }
    private class Harness(scope: TestScope) {
        val clock = TestClock()
        val check = Check()
        val rest = Rest()
        val events = mutableListOf<DemoExecutionEvent>()
        var closed = 0
        val execution = DemoExecution(scope.backgroundScope, clock,
            DemoExecutionPorts(check, Speech(), rest, close = { closed++ }), { events += it })
        suspend fun restGuidance() {
            execution.start()
            execution.submit(listOf(InterventionInput.Safety(
                SafetyEvent(clock.logical, SafetyEventType.REPEATED_RAPID_STEERING, Source.DEMO))))
        }
    }

    @Test fun `질문 진행과 응답 결과가 같은 요청으로 매니저와 배속 이벤트에 전달된다`() = runTest {
        val h = Harness(this)
        try {
            h.restGuidance(); runCurrent()
            val question = h.check.requests.single { it.context == CheckContext.WAYPOINT_CONSENT }
            assertTrue(h.events.contains(DemoExecutionEvent.Question(question.requestId, true)))
            h.check.results.emit(CheckResult(question.requestId, question.context, CheckOutcome.NORMAL,
                intent = ResponseIntent.YES, channel = ResponseChannel.VOICE))
            runCurrent()
            assertTrue(h.events.contains(DemoExecutionEvent.Question(question.requestId, false)))
            assertEquals("test-area", h.rest.requests.single { it.action == RestAction.ADD_WAYPOINT }.candidateId)
        } finally { h.execution.close() }
        assertEquals(1, h.closed)
    }

    @Test fun `시나리오가 60배 진행해도 무응답 자동 추가의 유예는 실제 10초다`() = runTest {
        val h = Harness(this)
        try {
            h.restGuidance(); runCurrent()
            val question = h.check.requests.single()
            h.check.results.emit(CheckResult(question.requestId, question.context, CheckOutcome.NO_RESPONSE))
            runCurrent()
            h.clock.logical = 600_000
            h.clock.real = 9_999
            h.execution.submit(emptyList()); runCurrent()
            assertFalse(h.rest.requests.any { it.action == RestAction.ADD_WAYPOINT })
            h.clock.real = 10_000
            h.execution.submit(emptyList()); runCurrent()
            assertEquals(1, h.rest.requests.count { it.action == RestAction.ADD_WAYPOINT })
        } finally { h.execution.close() }
    }

    @Test fun `취소하면 자동 추가 대기가 사라지고 위험 단계는 유지된다`() = runTest {
        val h = Harness(this)
        try {
            h.restGuidance(); runCurrent()
            val question = h.check.requests.single()
            h.check.results.emit(CheckResult(question.requestId, question.context, CheckOutcome.NO_RESPONSE))
            runCurrent()
            h.execution.cancelWaypoint(); runCurrent()
            h.clock.real = 30_000
            h.clock.logical = 30_000
            h.execution.submit(emptyList()); runCurrent()
            assertFalse(h.rest.requests.any { it.action == RestAction.ADD_WAYPOINT })
            assertEquals(InterventionStage.REST_GUIDANCE, h.execution.manager.state.value.stage)
            assertTrue(h.events.filterIsInstance<DemoExecutionEvent.Message>().any { it.text.contains("취소") })
        } finally { h.execution.close() }
    }

    @Test fun `장치 실패는 사람의 무응답으로 바뀌거나 경유지를 자동 추가하지 않는다`() = runTest {
        val h = Harness(this)
        try {
            h.restGuidance(); runCurrent()
            val question = h.check.requests.single()
            h.check.executionResults.emit(ExecutionResult(question.requestId, ExecutionStatus.FAILED, ExecutionFailure.PERMISSION_DENIED))
            runCurrent()
            h.clock.real = 30_000
            h.clock.logical = 30_000
            h.execution.submit(emptyList()); runCurrent()
            assertFalse(h.rest.requests.any { it.action == RestAction.ADD_WAYPOINT })
            assertTrue(h.events.contains(DemoExecutionEvent.Question(question.requestId, false, DemoQuestionOutcome.FAILED)))
        } finally { h.execution.close() }
    }

    @Test fun `닫힌 세션의 늦은 결과는 후속 질문이나 이벤트를 만들지 않는다`() = runTest {
        val h = Harness(this)
        h.restGuidance(); runCurrent()
        val question = h.check.requests.single()
        h.execution.close()
        val count = h.events.size
        h.check.results.emit(CheckResult(question.requestId, question.context, CheckOutcome.NORMAL, intent = ResponseIntent.YES))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(count, h.events.size)
        assertFalse(h.rest.requests.any { it.action == RestAction.ADD_WAYPOINT })
    }
}
