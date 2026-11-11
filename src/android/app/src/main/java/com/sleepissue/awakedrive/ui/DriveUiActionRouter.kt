package com.sleepissue.awakedrive.ui

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.signal.InterventionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 실제 화면 버튼을 기존 실행 소유자에게 전달한다. 판단·SDK 호출·DB 삭제 규칙을 화면에 복제하지 않는다.
 * 각 suspend 함수에는 NapSession, RewardLedger/TripSaveCoordinator, VoiceCheckPort의 실행을 연결한다.
 */
class DriveUiActionRouter(
    private val manager: InterventionManager,
    private val current: () -> DriveSessionUiState,
    private val condition: suspend (DrivingCondition) -> Unit,
    private val preferences: suspend (DriverUiSettings) -> Unit,
    private val passenger: suspend (Boolean) -> ExecutionResult,
    private val startNap: suspend () -> Unit,
    private val wake: suspend () -> Unit,
    private val recovery: suspend () -> Unit,
    private val deleteRecords: suspend () -> Boolean,
    private val feedback: (String) -> Unit,
    private val newId: () -> RequestId,
) {
    private val lock = Mutex()

    suspend fun dispatch(action: DriveUiAction) = lock.withLock {
        when (action) {
            is DriveUiAction.Preferences -> {
                manager.submit(newId(), InterventionInput.VentilationConsentChanged(action.value.ventilationConsent))
                manager.submit(newId(), InterventionInput.CheckFrequencyChanged(action.value.frequentChecks))
                preferences(action.value)
            }
            is DriveUiAction.Condition -> ifParked { condition(action.value) }
            is DriveUiAction.Passenger -> {
                val result = passenger(action.rest)
                feedback(if (result.status == ExecutionStatus.SUCCEEDED) "동승자 응답을 전달했습니다" else "현재 확인 요청에 동승자 응답을 전달할 수 없습니다")
            }
            DriveUiAction.CancelWaypoint -> {
                val pending = manager.state.value.pendingRequestId
                if (manager.state.value.stage != InterventionStage.REST_GUIDANCE || pending == null) {
                    feedback("취소할 대기 요청이 없습니다. 이미 추가된 경유지 제거는 지원하지 않습니다")
                } else {
                    val result = manager.cancel(pending)
                    feedback(when {
                        result.status == ExecutionStatus.CANCELLED -> "경유지 제안·자동 추가 대기를 취소했습니다. 위험 단계는 유지됩니다"
                        result.failure == ExecutionFailure.NOT_SUPPORTED -> "이미 추가된 경유지 제거는 지원하지 않습니다"
                        else -> "취소 결과를 확인할 수 없습니다"
                    })
                }
            }
            DriveUiAction.StartNap -> ifParked(startNap)
            DriveUiAction.WakeNow -> ifParked(wake)
            DriveUiAction.RequestRecovery -> ifParked(recovery)
            DriveUiAction.DeleteRecords -> ifParked {
                feedback(if (deleteRecords()) "연결된 운행·휴식 기록을 삭제했습니다" else "기록 삭제가 완료되지 않았습니다")
            }
        }
    }

    private suspend fun ifParked(action: suspend () -> Unit) {
        if (current().parked) action() else feedback("차속 0·P단을 확인한 뒤 사용할 수 있습니다")
    }
}
