package com.sleepissue.awakedrive.ui

/**
 * 엔진 측정 상태를 사용자에게 보여줄 문구로 변환한다.
 *
 * 실제 엔진에서 전달받지 않은 값은 만들어 내지 않는다.
 */
object FatigueUiFormatter {

    fun statusText(state: FatigueUiState): String {
        return when (state.measurementStatus) {
            MeasurementStatus.WAITING -> "판정 대기 중"
            MeasurementStatus.GATE_WAIT -> "측정 대기 중"
            MeasurementStatus.ASSIST -> "보조 주행 중"
            MeasurementStatus.VALID -> "측정 중"
        }
    }

    fun riskText(state: FatigueUiState): String {
        val risk = state.currentRisk

        if (
            state.measurementStatus != MeasurementStatus.VALID ||
            risk == null
        ) {
            return "측정 전"
        }

        return "${risk.coerceIn(0f, 100f).toInt()}점"
    }

    fun smoothedRiskText(state: FatigueUiState): String {
        val risk = state.smoothedRisk

        if (
            state.measurementStatus != MeasurementStatus.VALID ||
            risk == null
        ) {
            return "측정 전"
        }

        return "${risk.coerceIn(0f, 100f).toInt()}점"
    }

    fun patternText(state: FatigueUiState): String {
        return if (state.measurementStatus == MeasurementStatus.WAITING) {
            "분석 전"
        } else {
            state.drivingPattern
        }
    }

    fun assistText(state: FatigueUiState): String {
        return when (state.assistMode) {
            true -> "보조 주행 중"
            false -> "보조 주행 꺼짐"
            null -> "정보 수신 대기 중"
        }
    }

    fun baselineText(state: FatigueUiState): String {
        return when (state.baselineReady) {
            true -> "운전 기준선 확정"
            false -> "운전 기준선 분석 중"
            null -> "분석 전"
        }
    }
}