package com.sleepissue.awakedrive.ui

import com.sleepissue.awakedrive.engine.forecast.ForecastResult

/**
 * 팀원 4번의 졸음 예보 결과를
 * AwakeDrive 홈 화면에 표시할 데이터로 변환한다.
 */
data class ForecastUiState(
    val expectedCautionTime: String = "예측 전",
    val drowsinessForecast: String = "정보 없음"
)

/**
 * 예보 결과가 없으면 기본 상태를 반환한다.
 *
 * ForecastResult에는 낮음/보통/높음 등급이 없으므로
 * UI에서 임의의 위험 등급을 계산하지 않는다.
 */
fun ForecastResult?.toForecastUiState(): ForecastUiState {

    if (this == null) {
        return ForecastUiState()
    }

    return ForecastUiState(
        expectedCautionTime = spokenTime.ifBlank { "예측 전" },
        drowsinessForecast = "등급 미제공"
    )
}