package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.check.CheckResult as VoiceCheckResult
import com.sleepissue.awakedrive.engine.check.CheckType
import com.sleepissue.awakedrive.engine.check.OccupancyContext
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.CheckOutcome
import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.ResponseChannel
import com.sleepissue.awakedrive.engine.model.ResponseIntent
import com.sleepissue.awakedrive.signal.CheckPort
import com.sleepissue.awakedrive.signal.CheckRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 버튼 대체가 필요할 때 UI에 전달하는 최소 정보다. */
data class VoiceButtonRequest(
    val requestId: RequestId,
    val context: CheckContext,
    val passengerButton: Boolean,
)

/**
 * 1번 [CheckRequest]를 기존 TTS·STT·동승자 확인 흐름에 연결한다.
 * ROS로 비유하면 개입 요청 토픽을 음성 노드에 맞게 변환하고 결과를 다시 발행하는 어댑터다.
 */
class VoiceCheckPort(
    private val passengerCheckFlow: PassengerCheckFlow,
    private val voiceAgent: VoiceAgent,
    private val coordinator: VoiceCheckCoordinator,
    private val occupancyProvider: () -> OccupancyContext,
    private val baselineMsProvider: () -> Long,
) : CheckPort {
    private val mutableExecutionResults = MutableSharedFlow<ExecutionResult>(extraBufferCapacity = 16)
    override val executionResults: Flow<ExecutionResult> = mutableExecutionResults.asSharedFlow()

    private val mutableResults = MutableSharedFlow<CheckResult>(extraBufferCapacity = 16)
    override val results: Flow<CheckResult> = mutableResults.asSharedFlow()

    private val mutableButtonRequests = MutableSharedFlow<VoiceButtonRequest>(extraBufferCapacity = 4)
    val buttonRequests: Flow<VoiceButtonRequest> = mutableButtonRequests.asSharedFlow()

    private val lock = Any()
    private var activeRequest: CheckRequest? = null
    private var awaitingButton = false

    override suspend fun start(request: CheckRequest): ExecutionResult {
        synchronized(lock) {
            val active = activeRequest
            if (active != null) {
                return if (active.requestId == request.requestId) {
                    execution(request.requestId, ExecutionStatus.DEFERRED)
                } else {
                    execution(request.requestId, ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)
                }
            }
            activeRequest = request
            awaitingButton = false
        }

        val requested = execution(request.requestId, ExecutionStatus.REQUESTED)
        when (request.context) {
            CheckContext.DRIVER_ALERTNESS,
            CheckContext.PASSENGER_ALERTNESS,
            -> startAlertness(request)

            CheckContext.WAYPOINT_CONSENT,
            CheckContext.RECOVERY,
            CheckContext.PRE_DEPARTURE_CONDITION,
            -> startDirect(request)
        }
        return requested
    }

    override suspend fun cancel(requestId: RequestId): ExecutionResult {
        val canCancel = synchronized(lock) {
            if (activeRequest?.requestId != requestId) false
            else {
                clearActive()
                true
            }
        }
        if (!canCancel) return execution(requestId, ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)
        passengerCheckFlow.cancel()
        coordinator.cancel()
        return execution(requestId, ExecutionStatus.CANCELLED).also { mutableExecutionResults.emit(it) }
    }

    /** STT를 쓸 수 없을 때 화면의 운전자·동승자 버튼 결과를 같은 요청에 연결한다. */
    suspend fun completeWithButton(
        requestId: RequestId,
        intent: ResponseIntent,
        passengerButton: Boolean,
    ): ExecutionResult {
        val request = synchronized(lock) {
            activeRequest?.takeIf { it.requestId == requestId && awaitingButton }
        } ?: return execution(requestId, ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)

        mutableResults.emit(CheckResult(
            requestId = requestId,
            context = request.context,
            outcome = CheckOutcome.NORMAL,
            intent = intent,
            channel = if (passengerButton) ResponseChannel.PASSENGER_BUTTON else ResponseChannel.DRIVER_BUTTON,
        ))
        synchronized(lock) { clearActive() }
        return execution(requestId, ExecutionStatus.SUCCEEDED).also { mutableExecutionResults.emit(it) }
    }

    /** 동승자 보조 버튼은 진행 중인 각성 확인에만 답한다. 경유지 동의·회복 확인을 대신하지 않는다. */
    suspend fun completeWithPassengerButton(rest: Boolean): ExecutionResult {
        val request = synchronized(lock) {
            activeRequest?.takeIf { it.context == CheckContext.DRIVER_ALERTNESS || it.context == CheckContext.PASSENGER_ALERTNESS }
                ?.also { clearActive() }
        } ?: return execution(RequestId("passenger-no-active-check"), ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)
        passengerCheckFlow.cancel()
        coordinator.cancel()
        mutableResults.emit(CheckResult(request.requestId, request.context, CheckOutcome.NORMAL,
            intent = if (rest) ResponseIntent.REST else ResponseIntent.OK, channel = ResponseChannel.PASSENGER_BUTTON))
        return execution(request.requestId, ExecutionStatus.SUCCEEDED).also { mutableExecutionResults.emit(it) }
    }

    private fun startAlertness(request: CheckRequest) {
        passengerCheckFlow.start(
            context = occupancyProvider(),
            baselineMs = baselineMsProvider(),
            onPromptStarted = { emitStarted(request.requestId) },
            onResult = { result, _ -> completeVoice(request, result) },
            onButtonRequired = { requireButton(request, passengerButton = false) },
        )
    }

    private fun startDirect(request: CheckRequest) {
        val (prompt, questionType) = when (request.context) {
            CheckContext.WAYPOINT_CONSENT -> {
                val area = request.restArea
                val distanceKm = area?.distanceM?.div(1_000f)?.toInt()?.coerceAtLeast(1) ?: 1
                VoiceScripts.stage3(area?.name ?: "앞쪽 쉼터", distanceKm) to QuestionType.WAYPOINT_CONFIRMATION
            }
            CheckContext.RECOVERY -> VoiceScripts.RECOVERY_CHECK to QuestionType.DRIVER_CHECK
            CheckContext.PRE_DEPARTURE_CONDITION ->
                VoiceScripts.PRE_DRIVE_CONDITION to QuestionType.PRE_DRIVE_CONDITION
            else -> error("직접 확인으로 처리할 수 없는 문맥: ${request.context}")
        }
        voiceAgent.speak(
            text = prompt,
            fallback = FallbackCue.CHECK_PROMPT,
            onComplete = {
                coordinator.start(
                    baselineMs = baselineMsProvider(),
                    questionType = questionType,
                    onResult = { result, _ -> completeVoice(request, result) },
                    onSttFallbackRequired = { requireButton(request, passengerButton = false) },
                )
            },
            onStarted = { emitStarted(request.requestId) },
        )
    }

    private fun completeVoice(request: CheckRequest, result: VoiceCheckResult) {
        val shouldEmit = synchronized(lock) {
            activeRequest?.requestId == request.requestId && !awaitingButton
        }
        if (!shouldEmit) return
        mutableResults.tryEmit(result.toInterventionResult(request.requestId, request.context))
        synchronized(lock) { clearActive() }
        mutableExecutionResults.tryEmit(execution(request.requestId, ExecutionStatus.SUCCEEDED))
    }

    private fun requireButton(request: CheckRequest, passengerButton: Boolean) {
        val accepted = synchronized(lock) {
            if (activeRequest?.requestId != request.requestId) false
            else {
                awaitingButton = true
                true
            }
        }
        if (accepted) mutableButtonRequests.tryEmit(
            VoiceButtonRequest(request.requestId, request.context, passengerButton),
        )
    }

    private fun emitStarted(requestId: RequestId) {
        mutableExecutionResults.tryEmit(execution(requestId, ExecutionStatus.STARTED))
    }

    private fun clearActive() {
        activeRequest = null
        awaitingButton = false
    }

    private fun execution(
        requestId: RequestId,
        status: ExecutionStatus,
        failure: ExecutionFailure? = null,
    ) = ExecutionResult(requestId, status, failure)
}
