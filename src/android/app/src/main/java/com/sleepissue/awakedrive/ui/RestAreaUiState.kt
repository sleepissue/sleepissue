package com.sleepissue.awakedrive.ui

import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.signal.RestAreaCandidate
import com.sleepissue.awakedrive.signal.RestResult

/**
 * AwakeDrive 화면에서 휴게소 검색 상태를 표현한다.
 */
sealed interface RestAreaUiState {

    data object NotSearched : RestAreaUiState

    data object Loading : RestAreaUiState

    data class Found(
        val name: String,
        val distance: String
    ) : RestAreaUiState

    data object NotFound : RestAreaUiState

    data object Error : RestAreaUiState
}

/**
 * 휴게소 후보 데이터를 UI 상태로 변환한다.
 */
fun RestAreaCandidate.toRestAreaUiState(): RestAreaUiState {

    return RestAreaUiState.Found(
        name = name,
        distance = formatRestAreaDistance(this)
    )
}

/**
 * 휴게소 검색 결과를 UI 상태로 변환한다.
 *
 * 주의: FIND_AHEAD 검색 결과에만 사용해야 한다.
 * ADD_WAYPOINT 결과에는 실제 거리 정보가 없을 수 있다.
 */
fun RestResult.toRestAreaUiState(): RestAreaUiState {

    return when (execution.status) {

        ExecutionStatus.REQUESTED,
        ExecutionStatus.STARTED,
        ExecutionStatus.DEFERRED -> {
            RestAreaUiState.Loading
        }

        ExecutionStatus.SUCCEEDED -> {
            candidate?.toRestAreaUiState()
                ?: RestAreaUiState.NotFound
        }

        ExecutionStatus.FAILED,
        ExecutionStatus.NOT_CONNECTED -> {
            RestAreaUiState.Error
        }

        ExecutionStatus.CANCELLED -> {
            RestAreaUiState.NotSearched
        }
    }
}