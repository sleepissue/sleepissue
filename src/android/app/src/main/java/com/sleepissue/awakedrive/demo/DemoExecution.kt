package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.rest.NapState
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.*
import com.sleepissue.awakedrive.ui.DriveSessionUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** 실행 부품만 주입하며 데모 보상은 메모리에 보관한다. */
data class DemoExecutionPorts(
    val check: CheckPort,
    val speech: SpeechPort,
    val rest: RestPort,
    val buttonResponses: Flow<CheckRequest> = emptyFlow(),
    val respond: suspend (RequestId, ResponseIntent) -> ExecutionResult = { id, _ ->
        ExecutionResult(id, ExecutionStatus.NOT_CONNECTED, ExecutionFailure.NOT_CONNECTED)
    },
    val alarm: (Boolean) -> Unit = {},
    val close: suspend () -> Unit = {},
    val waypointFailure: suspend (RestAreaCandidate?) -> Unit = {},
    val napStarted: (Boolean) -> Unit = {},
    val respondPassenger: suspend (Boolean) -> ExecutionResult = {
        ExecutionResult(RequestId("demo-passenger-unavailable"), ExecutionStatus.NOT_CONNECTED, ExecutionFailure.NOT_CONNECTED)
    },
)

fun interface DemoExecutionFactory {
    suspend fun create(scope: CoroutineScope, current: () -> DriveSessionUiState): DemoExecutionPorts
}

sealed interface DemoExecutionEvent {
    data class Question(val id: RequestId, val active: Boolean, val outcome: DemoQuestionOutcome = DemoQuestionOutcome.FINISHED) : DemoExecutionEvent
    data class Button(val context: CheckContext?) : DemoExecutionEvent
    data class Checked(val result: CheckResult) : DemoExecutionEvent
    data class Message(val text: String, val waypoint: Boolean = false) : DemoExecutionEvent
    data class ResetAfterRecovery(val completed: CompletableDeferred<Unit>) : DemoExecutionEvent
    data object Refresh : DemoExecutionEvent
}

/** 단계·재확인·경유지 유예 규칙은 실제 서비스와 같은 매니저가 소유한다. */
class DemoExecution(
    parent: CoroutineScope,
    clock: Clock,
    private val ports: DemoExecutionPorts,
    private val event: suspend (DemoExecutionEvent) -> Unit,
    private val config: EngineConfig = EngineConfig(),
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val sequence = AtomicLong()
    private fun id() = RequestId("demo-execution-${sequence.incrementAndGet()}")
    val manager = PolicyInterventionManager(InterventionPolicy(config), config, clock, Source.DEMO,
        RequestTimerBasis.ELAPSED_REALTIME, ::id)
    private var button: RequestId? = null
    private var alarming = false
    private var napStartedAtMs: Long? = null
    private var closed = false
    private var candidate: RestAreaCandidate? = null
    private val restActions = ConcurrentHashMap<RequestId, RestAction>()
    private val trackedRest = object : RestPort by ports.rest {
        override suspend fun start(request: RestRequest): ExecutionResult {
            restActions[request.requestId] = request.action
            return ports.rest.start(request)
        }
    }
    private val router = InterventionRouter(manager, ports.check, ports.speech, trackedRest,
        recoveryReset = {
            val completed = CompletableDeferred<Unit>()
            event(DemoExecutionEvent.ResetAfterRecovery(completed))
            completed.await()
            ExecutionResult(it.requestId, ExecutionStatus.SUCCEEDED)
        }, newInputId = ::id)

    suspend fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ports.check.executionResults.collect { value ->
                when (value.status) {
                    ExecutionStatus.STARTED -> event(DemoExecutionEvent.Question(value.requestId, true))
                    ExecutionStatus.FAILED, ExecutionStatus.NOT_CONNECTED, ExecutionStatus.CANCELLED -> {
                        if (button == value.requestId) { button = null; event(DemoExecutionEvent.Button(null)) }
                        event(DemoExecutionEvent.Question(value.requestId, false,
                            if (value.status == ExecutionStatus.CANCELLED) DemoQuestionOutcome.CANCELLED else DemoQuestionOutcome.FAILED))
                    }
                    else -> Unit
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ports.check.results.collect {
                if (button == it.requestId) { button = null; event(DemoExecutionEvent.Button(null)) }
                event(DemoExecutionEvent.Question(it.requestId, false))
                event(DemoExecutionEvent.Checked(it))
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ports.buttonResponses.collect { request ->
                button = request.requestId
                event(DemoExecutionEvent.Button(request.context))
                event(DemoExecutionEvent.Message("음성 인식을 사용할 수 없습니다. 운전자 응답 버튼으로 답해 주세요"))
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            ports.rest.results.collect { result ->
                val action = restActions[result.execution.requestId]
                if (action == RestAction.FIND_AHEAD && result.execution.status == ExecutionStatus.SUCCEEDED) candidate = result.candidate
                if (action == RestAction.ADD_WAYPOINT) {
                    val text = when (result.execution.status) {
                        ExecutionStatus.STARTED, ExecutionStatus.REQUESTED -> "경유지 추가: 내비게이션에 요청하고 있습니다"
                        ExecutionStatus.SUCCEEDED -> "경유지 추가: 내비게이션에서 반영을 확인했습니다"
                        ExecutionStatus.CANCELLED -> "경유지 추가: 요청을 취소했습니다"
                        else -> "경유지 추가: 반영을 확인하지 못했습니다. 내비게이션에서 쉼터를 확인해 주세요"
                    }
                    event(DemoExecutionEvent.Message(text, waypoint = true))
                    if (result.execution.status in setOf(ExecutionStatus.FAILED, ExecutionStatus.NOT_CONNECTED)) ports.waypointFailure(candidate)
                }
                if (result.execution.status in setOf(ExecutionStatus.SUCCEEDED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED, ExecutionStatus.NOT_CONNECTED))
                    restActions.remove(result.execution.requestId)
            }
        }
        router.start(scope)
        yield()
        manager.start(id())
        scope.launch {
            while (isActive) {
                delay(config.naviStateIntervalMs)
                manager.submit(id(), InterventionInput.Tick)
                event(DemoExecutionEvent.Refresh)
            }
        }
    }

    suspend fun submit(inputs: List<InterventionInput>) {
        inputs.forEach { manager.submit(id(), it) }
        manager.submit(id(), InterventionInput.Tick)
    }

    suspend fun cancelWaypoint() {
        val pending = manager.state.value.pendingRequestId
        val result = if (manager.state.value.stage == InterventionStage.REST_GUIDANCE && pending != null)
            manager.cancel(pending) else null
        event(DemoExecutionEvent.Message(when (result?.status) {
            ExecutionStatus.CANCELLED -> "경유지 제안·자동 추가 대기를 취소했습니다. 위험 단계는 유지됩니다"
            else -> "취소할 대기 요청이 없습니다. 이미 추가된 경유지는 제거하지 않습니다"
        }, waypoint = true))
    }

    suspend fun respond(intent: ResponseIntent) {
        val request = button
        if (request == null) event(DemoExecutionEvent.Message("버튼 응답을 기다리는 음성 확인이 없습니다"))
        else ports.respond(request, intent)
    }

    suspend fun respondPassenger(rest: Boolean) {
        val result = ports.respondPassenger(rest)
        event(DemoExecutionEvent.Message(if (result.status == ExecutionStatus.SUCCEEDED)
            "동승자 응답을 전달했습니다" else "현재 확인 요청에 동승자 응답을 전달할 수 없습니다"))
    }

    fun updateAlarm(state: NapState, atRestArea: Boolean = true) {
        if (state is NapState.Running && state.startedAtMs != napStartedAtMs) {
            napStartedAtMs = state.startedAtMs
            ports.napStarted(atRestArea)
        }
        if (state is NapState.Idle) napStartedAtMs = null
        val next = state is NapState.Alarming
        if (next != alarming) { alarming = next; ports.alarm(next) }
    }

    suspend fun close() {
        if (closed) return
        closed = true
        manager.stop(id())
        job.cancelAndJoin()
        ports.alarm(false)
        ports.close()
    }
}
