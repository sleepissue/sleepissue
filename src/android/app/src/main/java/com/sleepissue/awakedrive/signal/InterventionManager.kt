package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionRequest
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.InterventionState
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.SafetyEvent
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.engine.model.NaviState
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
    /**
     * 판단 엔진(2번)이 지금 주의 확인을 요청한다. 민감 모드 첫 음성 확인(출발 피로·'졸려요' 15분 뒤, REQ_BSL_006)에 쓴다.
     * 급조향 1회처럼 2단계 확인으로 처리한다(이미 2단계면 확인만 다시, 3단계면 그대로).
     */
    data class CheckDue(val tMs: Long, val source: Source) : InterventionInput
    data class RestCertified(val value: RestCertification) : InterventionInput
    data class SensitiveModeChanged(val enabled: Boolean) : InterventionInput
    /** 예정 시각은 2번이 계산한다. 1번은 같은 운행의 실행 예약만 소유한다. null은 예약 해제다. */
    data class FirstCheckScheduleChanged(val tripId: String, val dueAtMs: Long?) : InterventionInput
    data class VulnerableTimeChanged(val enabled: Boolean) : InterventionInput
    data class VentilationConsentChanged(val enabled: Boolean) : InterventionInput
    data class AlertnessTargetChanged(val context: com.sleepissue.awakedrive.engine.model.CheckContext) : InterventionInput
    data class NavigationChanged(val value: NaviState?) : InterventionInput
    data class RestAreaResolved(val value: RestResult) : InterventionInput
    data class RestAreaDistance(val candidateId: String, val distanceM: Float?) : InterventionInput
    data object Tick : InterventionInput
}

/** 단계·실행 요청의 공통 계약이다. PolicyInterventionManager 또는 명시적 미연결 구현을 선택한다. */
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
