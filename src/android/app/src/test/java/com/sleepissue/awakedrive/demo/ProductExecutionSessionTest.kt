package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.rest.RestAreaCsv
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProductExecutionSessionTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .first { File(it, "data/scenarios/normal.json").exists() }
    private val areas = RestAreaCsv.parse(File(root, "data/rest_areas/processed/rest_areas.csv").readText())
    private class Ports : DemoExecutionFactory {
        val questions = mutableListOf<CheckRequest>()
        val routes = mutableListOf<RestRequest>()
        var closes = 0
        var pending: CheckRequest? = null
        val passengerResults = mutableListOf<CheckResult>()
        override suspend fun create(scope: kotlinx.coroutines.CoroutineScope, current: () -> com.sleepissue.awakedrive.ui.DriveSessionUiState): DemoExecutionPorts {
            val check = object : CheckPort {
                override val executionResults = MutableSharedFlow<ExecutionResult>(extraBufferCapacity = 16)
                override val results = MutableSharedFlow<CheckResult>(extraBufferCapacity = 16)
                override suspend fun start(request: CheckRequest): ExecutionResult {
                    questions += request
                    pending = request
                    executionResults.emit(ExecutionResult(request.requestId, ExecutionStatus.STARTED))
                    return ExecutionResult(request.requestId, ExecutionStatus.REQUESTED)
                }
                override suspend fun cancel(requestId: RequestId) = ExecutionResult(requestId, ExecutionStatus.CANCELLED).also { executionResults.emit(it) }
            }
            val rest = object : RestPort {
                override val results = MutableSharedFlow<RestResult>(extraBufferCapacity = 16)
                override suspend fun start(request: RestRequest): ExecutionResult {
                    routes += request
                    if (request.action == RestAction.FIND_AHEAD) results.emit(RestResult(
                        ExecutionResult(request.requestId, ExecutionStatus.SUCCEEDED),
                        RestAreaCandidate("test-area", "시험 쉼터", GeoPoint(36.0, 128.0), 5000f)))
                    return ExecutionResult(request.requestId, ExecutionStatus.REQUESTED)
                }
                override suspend fun cancel(requestId: RequestId) = ExecutionResult(requestId, ExecutionStatus.CANCELLED)
            }
            val speech = object : SpeechPort {
                override val results = MutableSharedFlow<ExecutionResult>()
                override suspend fun start(request: SpeechRequest) = ExecutionResult(request.requestId, ExecutionStatus.REQUESTED)
                override suspend fun cancel(requestId: RequestId) = ExecutionResult(requestId, ExecutionStatus.CANCELLED)
            }
            return DemoExecutionPorts(check, speech, rest, close = { closes++ }, respondPassenger = { restRequested ->
                val active = pending?.takeIf { it.context in setOf(CheckContext.DRIVER_ALERTNESS, CheckContext.PASSENGER_ALERTNESS) }
                if (active == null) ExecutionResult(RequestId("missing"), ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)
                else {
                    pending = null
                    val result = CheckResult(active.requestId, active.context, CheckOutcome.NORMAL,
                        intent = if (restRequested) ResponseIntent.REST else ResponseIntent.OK, channel = ResponseChannel.PASSENGER_BUTTON)
                    passengerResults += result
                    check.results.emit(result)
                    ExecutionResult(active.requestId, ExecutionStatus.SUCCEEDED)
                }
            })
        }
    }

    @Test fun `실행 매니저를 연결해도 정상 원본은 끝까지 0단계이고 재로드는 실행 부품을 닫는다`() = runTest {
        val clock = DemoTestClock()
        val ports = Ports()
        val session = ProductDemoSession(backgroundScope, clock, areas, ports)
        val json = File(root, "data/scenarios/normal.json").readText()
        try {
            session.load(json, 60); session.start()
            var maxStage = 0
            repeat(12_000) {
                if (session.playback.value.phase == DemoPhase.PLAYING) {
                    clock.realMs += 10
                    advanceTimeBy(10); runCurrent()
                    maxStage = maxOf(maxStage, session.state.value.intervention?.stage?.level ?: 0)
                }
            }
            assertEquals(DemoPhase.COMPLETED, session.playback.value.phase)
            assertEquals(0, maxStage)
            assertTrue(ports.questions.none { it.context == CheckContext.DRIVER_ALERTNESS })
            assertTrue(session.state.value.restCertified)
            session.load(json, 60)
            assertEquals(1, ports.closes)
            assertNull(session.playback.value.questionRequestId)
            assertEquals(0, session.state.value.stampCount)
        } finally { session.close() }
        assertEquals(2, ports.closes)
    }

    @Test fun `제품 Hub를 거친 졸음 확인이 시작되면 60배가 질문 동안 1배가 된다`() = runTest {
        val clock = DemoTestClock()
        val ports = Ports()
        val session = ProductDemoSession(backgroundScope, clock, areas, ports)
        try {
            session.load(File(root, "data/scenarios/drowsy.json").readText(), 60); session.start()
            repeat(6000) {
                if (session.playback.value.questionRequestId == null && session.playback.value.phase == DemoPhase.PLAYING) {
                    clock.realMs += 10
                    advanceTimeBy(10); runCurrent()
                }
            }
            assertNotNull(ports.questions.firstOrNull { it.context == CheckContext.DRIVER_ALERTNESS })
            assertNotNull(session.playback.value.questionRequestId)
            assertEquals(1, session.playback.value.speedMultiplier)
            val before = session.state.value.tMs
            clock.realMs += 1000
            advanceTimeBy(1000); runCurrent()
            assertTrue("질문 중 시나리오가 배속으로 진행됨", session.state.value.tMs - before <= 1100)
        } finally { session.close() }
    }
    @Test fun `제품 데모 동승자 버튼은 설정 해제와 중복을 거절하고 기존 확인에 답한다`() = runTest {
        for (restRequested in listOf(false, true)) {
            val clock = DemoTestClock()
            val ports = Ports()
            val session = ProductDemoSession(backgroundScope, clock, areas, ports)
            try {
                session.load(File(root, "data/scenarios/drowsy.json").readText(), 60); session.start()
                session.action(com.sleepissue.awakedrive.ui.DriveUiAction.Passenger(true))
                assertTrue(ports.passengerResults.isEmpty())
                repeat(6000) {
                    if (session.playback.value.questionRequestId == null && session.playback.value.phase == DemoPhase.PLAYING) {
                        clock.realMs += 10; advanceTimeBy(10); runCurrent()
                    }
                }
                val request = ports.questions.first { it.context == CheckContext.DRIVER_ALERTNESS }
                session.action(com.sleepissue.awakedrive.ui.DriveUiAction.Preferences(
                    com.sleepissue.awakedrive.ui.DriverUiSettings(passengerChecks = false)))
                session.action(com.sleepissue.awakedrive.ui.DriveUiAction.Passenger(restRequested))
                assertTrue(ports.passengerResults.isEmpty())
                session.action(com.sleepissue.awakedrive.ui.DriveUiAction.Preferences(
                    com.sleepissue.awakedrive.ui.DriverUiSettings(passengerChecks = true)))
                session.action(com.sleepissue.awakedrive.ui.DriveUiAction.Passenger(restRequested))
                runCurrent()
                assertEquals(request.requestId, ports.passengerResults.single().requestId)
                assertEquals(ResponseChannel.PASSENGER_BUTTON, ports.passengerResults.single().channel)
                assertEquals(if (restRequested) InterventionStage.REST_GUIDANCE else InterventionStage.CHECK,
                    session.state.value.intervention?.stage)
                session.action(com.sleepissue.awakedrive.ui.DriveUiAction.Passenger(restRequested))
                runCurrent()
                assertEquals(1, ports.passengerResults.size)
            } finally { session.close() }
        }
    }

    @Test fun `내비 화면 전환 동안 일시정지는 실행 연결과 세션을 보존하고 복귀 후 이어진다`() = runTest {
        val clock = DemoTestClock()
        val ports = Ports()
        val session = ProductDemoSession(backgroundScope, clock, areas, ports)
        try {
            session.load(File(root, "data/scenarios/normal.json").readText(), 60)
            session.start()
            clock.realMs += 100
            advanceTimeBy(100); runCurrent()
            session.pause()
            val sessionId = session.playback.value.sessionId
            val pausedAt = session.state.value.tMs
            clock.realMs += 5000
            advanceTimeBy(5000); runCurrent()
            assertEquals(DemoPhase.PAUSED, session.playback.value.phase)
            assertEquals(pausedAt, session.state.value.tMs)
            assertEquals(0, ports.closes)
            session.resume()
            clock.realMs += 100
            advanceTimeBy(100); runCurrent()
            assertEquals(DemoPhase.PLAYING, session.playback.value.phase)
            assertEquals(sessionId, session.playback.value.sessionId)
            assertTrue(session.state.value.tMs > pausedAt)
        } finally { session.close() }
        assertEquals(1, ports.closes)
    }

}
