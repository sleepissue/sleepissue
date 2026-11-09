package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionRequest
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 개입 매니저의 요청을 담당 포트로 나눠 주고, 포트 결과를 매니저로 되돌린다.
 *
 * ROS로 치면 매니저의 `requests` 토픽 하나를 구독해 액션 서버 4개(Check·Speech·Rest·Climate)로 보내는 중계 노드다.
 * 매니저 `requests`는 한 곳에서만 소비할 수 있으므로(PolicyInterventionManager 계약) 각 담당이 따로 구독하지 않고 여기 한 곳에서 받는다.
 *
 * | 요청 | 포트 | 결과를 돌려주는 길 |
 * |---|---|---|
 * | CheckRequest | [CheckPort] | executionResults → onResult, results → submit(CheckCompleted) |
 * | SpeechRequest | [SpeechPort] | results → onResult |
 * | RestRequest FIND_AHEAD | [RestPort] | results → submit(RestAreaResolved) — 쉼터 후보를 매니저가 받아야 하므로 |
 * | RestRequest ADD_WAYPOINT·REMOVE | [RestPort] | results → onResult |
 * | ClimateRequest | [ClimatePort] | results → onResult |
 * | RestRecoveryResetRequest | [recoveryReset] | 반환값 → onResult |
 * | CancelExecutionRequest | 원래 요청을 받은 포트의 cancel | (포트가 CANCELLED를 보내도 매니저가 이미 취소로 처리함) |
 *
 * 포트가 null이면(담당 구현 전) 매니저에 NOT_CONNECTED를 돌려준다. 성공으로 꾸미지 않는다.
 * `start()`가 바로 FAILED를 돌려주면 그것도 매니저에 전달한다.
 *
 * @param newInputId submit용 ID. 매니저의 요청 ID 생성기와 겹치지 않는 접두어를 쓴다
 */
class InterventionRouter(
    private val manager: InterventionManager,
    private val check: CheckPort? = null,
    private val speech: SpeechPort? = null,
    private val rest: RestPort? = null,
    private val climate: ClimatePort? = null,
    private val recoveryReset: (suspend (RestRecoveryResetRequest) -> ExecutionResult)? = null,
    private val newInputId: () -> RequestId,
) {
    private enum class Owner { CHECK, SPEECH, REST_FIND, REST_OTHER, CLIMATE, RESET }

    private val owners = ConcurrentHashMap<RequestId, Owner>()

    // 다른 담당(4번 RestWiring 등)이 매니저와 같은 결과를 보고 다음 행동을 정할 수 있게 다시 내보낸다.
    private val mutableCheckResults = MutableSharedFlow<CheckResult>(extraBufferCapacity = 16)
    /** 사람의 확인 결과(대답 뜻 포함). "잠깐 잘게"·"통풍 꺼 줘" 같은 후속 동작에 쓴다. */
    val checkResults: SharedFlow<CheckResult> = mutableCheckResults.asSharedFlow()

    private val mutableFoundAreas = MutableSharedFlow<RestResult>(extraBufferCapacity = 16)
    /** FIND_AHEAD의 최종 결과. 쉼터까지 거리 추적에 쓴다. */
    val foundAreas: SharedFlow<RestResult> = mutableFoundAreas.asSharedFlow()

    /**
     * 수집을 시작한다. 포트 결과를 먼저 듣고 나서 매니저 요청을 받기 시작해야 빠른 결과를 놓치지 않는다.
     * 반환된 Job을 취소하면 모든 수집이 멈춘다.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        check?.let { port ->
            listen(port.executionResults) { manager.onResult(it) }
            listen(port.results) {
                manager.submit(newInputId(), InterventionInput.CheckCompleted(it))
                mutableCheckResults.emit(it)
            }
        }
        speech?.let { port -> listen(port.results) { manager.onResult(it) } }
        rest?.let { port ->
            listen(port.results) { result ->
                when (owners[result.execution.requestId]) {
                    Owner.REST_FIND -> {
                        manager.submit(newInputId(), InterventionInput.RestAreaResolved(result))
                        if (result.execution.status in FINAL) mutableFoundAreas.emit(result)
                    }
                    else -> manager.onResult(result.execution)
                }
            }
        }
        climate?.let { port -> listen(port.results) { manager.onResult(it) } }
        listen(manager.requests) { route(it) }
    }

    private fun <T> CoroutineScope.listen(flow: Flow<T>, action: suspend (T) -> Unit) {
        // UNDISPATCHED: 바로 구독을 시작해 다음 줄 이전에 나온 값도 받는다.
        launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { action(it) } }
    }

    private suspend fun route(request: ExecutionRequest) {
        val id = request.requestId
        val immediate: ExecutionResult = when (request) {
            is CancelExecutionRequest -> {
                cancel(id)
                return
            }
            is CheckRequest -> check?.let { owners[id] = Owner.CHECK; it.start(request) }
            is SpeechRequest -> speech?.let { owners[id] = Owner.SPEECH; it.start(request) }
            is RestRequest -> rest?.let {
                owners[id] = if (request.action == RestAction.FIND_AHEAD) Owner.REST_FIND else Owner.REST_OTHER
                it.start(request)
            }
            is ClimateRequest -> climate?.let { owners[id] = Owner.CLIMATE; it.start(request) }
            is RestRecoveryResetRequest -> recoveryReset?.let { owners[id] = Owner.RESET; it(request) }
            else -> null
        } ?: notConnected(id)
        // REQUESTED는 매니저가 이미 아는 상태라 무시된다. 즉시 실패·완료만 의미가 있다.
        if (immediate.status != ExecutionStatus.REQUESTED) manager.onResult(immediate)
    }

    private suspend fun cancel(id: RequestId) {
        when (owners[id]) {
            Owner.CHECK -> check?.cancel(id)
            Owner.SPEECH -> speech?.cancel(id)
            Owner.REST_FIND, Owner.REST_OTHER -> rest?.cancel(id)
            Owner.CLIMATE -> climate?.cancel(id)
            Owner.RESET, null -> Unit
        }
    }

    private fun notConnected(id: RequestId) = ExecutionResult(id, ExecutionStatus.NOT_CONNECTED, ExecutionFailure.NOT_CONNECTED)

    private companion object {
        val FINAL = setOf(ExecutionStatus.SUCCEEDED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED, ExecutionStatus.NOT_CONNECTED)
    }
}
