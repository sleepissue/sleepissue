package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionRequest
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.InterventionState
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.SafetyEvent
import com.sleepissue.awakedrive.engine.model.TripState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** 주기 판단 외에 즉시 안전 이벤트와 확인·휴식 결과도 받는 계약이다. */
sealed interface InterventionInput {
    data class Assessment(val value: DrivingAssessment) : InterventionInput
    data class TripChanged(val value: TripState) : InterventionInput
    data class Forecast(val value: ForecastAssessment) : InterventionInput
    data class CheckCompleted(val value: CheckResult) : InterventionInput
    data class Safety(val value: SafetyEvent) : InterventionInput
    data class RestCertified(val value: RestCertification) : InterventionInput
    data class SensitiveModeChanged(val enabled: Boolean) : InterventionInput
}

/** 단계 규칙·타이머·중복 요청 방지는 B에서 구현한다. */
interface InterventionManager {
    val state: StateFlow<InterventionState>
    val requests: Flow<ExecutionRequest>
    suspend fun start(requestId: RequestId): ExecutionResult
    suspend fun submit(requestId: RequestId, input: InterventionInput): ExecutionResult
    suspend fun onResult(result: ExecutionResult): ExecutionResult
    suspend fun cancel(requestId: RequestId): ExecutionResult
    suspend fun stop(requestId: RequestId): ExecutionResult
}

/** 연결 전 상태를 명시한다. 호출을 받아도 단계나 실제 실행 성공을 만들어 내지 않는다. */
class UnconnectedInterventionManager : InterventionManager {
    override val state: StateFlow<InterventionState> =
        MutableStateFlow(InterventionState()).asStateFlow()
    override val requests: Flow<ExecutionRequest> = emptyFlow()

    override suspend fun start(requestId: RequestId) = notConnected(requestId)

    override suspend fun submit(requestId: RequestId, input: InterventionInput) =
        notConnected(requestId)

    override suspend fun onResult(result: ExecutionResult) = notConnected(result.requestId)

    override suspend fun cancel(requestId: RequestId) = notConnected(requestId)

    override suspend fun stop(requestId: RequestId) = notConnected(requestId)

    private fun notConnected(requestId: RequestId) = ExecutionResult(
        requestId = requestId,
        status = ExecutionStatus.NOT_CONNECTED,
        failure = ExecutionFailure.NOT_CONNECTED,
    )
}
