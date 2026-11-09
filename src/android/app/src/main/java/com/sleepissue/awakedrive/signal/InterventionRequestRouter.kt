package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.ExecutionRequest
import com.sleepissue.awakedrive.engine.model.RequestId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 개입 관리자가 만든 요청을 각 실행 포트로 한 곳에서 분배하고 결과를 다시 관리자에 돌려준다.
 * ROS로 비유하면 단계 전환 노드와 음성·내비·공조 액션 서버 사이의 라우터다.
 */
class InterventionRequestRouter(
    private val manager: InterventionManager,
    private val checkPort: CheckPort,
    private val speechPort: SpeechPort,
    private val restPort: RestPort,
    private val climatePort: ClimatePort,
    private val onPreDepartureResult: suspend (com.sleepissue.awakedrive.engine.model.CheckResult) -> Unit = {},
    private val onRestRecoveryReset: suspend (String) -> Unit = {},
) {
    private val requestTypes = ConcurrentHashMap<RequestId, RequestKind>()

    /** 앱 수명주기 scope에서 한 번만 실행한다. */
    suspend fun run() = coroutineScope {
        launch { checkPort.executionResults.collect(manager::onResult) }
        launch {
            checkPort.results.collect { result ->
                if (result.context == CheckContext.PRE_DEPARTURE_CONDITION) onPreDepartureResult(result)
                manager.submit(
                    RequestId("router-check-${result.requestId.value}"),
                    InterventionInput.CheckCompleted(result),
                )
            }
        }
        launch { speechPort.results.collect(manager::onResult) }
        launch {
            restPort.results.collect { result ->
                val kind = requestTypes[result.execution.requestId]
                if (kind == RequestKind.REST_FIND) {
                    manager.submit(
                        RequestId("router-rest-${result.execution.requestId.value}"),
                        InterventionInput.RestAreaResolved(result),
                    )
                } else manager.onResult(result.execution)
            }
        }
        launch { climatePort.results.collect(manager::onResult) }
        manager.requests.collect(::dispatch)
    }

    private suspend fun dispatch(request: ExecutionRequest) {
        when (request) {
            is CheckRequest -> {
                requestTypes[request.requestId] = RequestKind.CHECK
                manager.onResult(checkPort.start(request))
            }
            is SpeechRequest -> {
                requestTypes[request.requestId] = RequestKind.SPEECH
                manager.onResult(speechPort.start(request))
            }
            is RestRequest -> {
                requestTypes[request.requestId] = if (request.action == RestAction.FIND_AHEAD) {
                    RequestKind.REST_FIND
                } else RequestKind.REST_ACTION
                manager.onResult(restPort.start(request))
            }
            is ClimateRequest -> {
                requestTypes[request.requestId] = RequestKind.CLIMATE
                manager.onResult(climatePort.start(request))
            }
            is CancelExecutionRequest -> cancel(request.requestId)
            is RestRecoveryResetRequest -> {
                onRestRecoveryReset(request.restSessionId)
                manager.onResult(com.sleepissue.awakedrive.engine.model.ExecutionResult(
                    request.requestId,
                    com.sleepissue.awakedrive.engine.model.ExecutionStatus.SUCCEEDED,
                ))
            }
        }
    }

    private suspend fun cancel(requestId: RequestId) {
        when (requestTypes.remove(requestId)) {
            RequestKind.CHECK -> checkPort.cancel(requestId)
            RequestKind.SPEECH -> speechPort.cancel(requestId)
            RequestKind.REST_FIND, RequestKind.REST_ACTION -> restPort.cancel(requestId)
            RequestKind.CLIMATE -> climatePort.cancel(requestId)
            null -> return
        }
    }

    private enum class RequestKind { CHECK, SPEECH, REST_FIND, REST_ACTION, CLIMATE }
}
