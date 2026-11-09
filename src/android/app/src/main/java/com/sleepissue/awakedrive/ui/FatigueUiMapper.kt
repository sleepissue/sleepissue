package com.sleepissue.awakedrive.ui

/**
 * 엔진에서 전달받을 측정 결과를 UI 모델로 변환한다.
 *
 * 현재는 엔진 코드가 별도 브랜치에 있으므로
 * UI에 필요한 값만 인자로 받는다.
 */
object FatigueUiMapper {

    fun fromEngineValues(
        currentRisk: Float?,
        smoothedRisk: Float?,
        measurementStatus: MeasurementStatus,
        drivingPattern: String = "분석 전",
        assistMode: Boolean? = null,
        handoverAbnormal: Boolean? = null,
        departureFatigue: Boolean? = null,
        baselineReady: Boolean? = null,
        vulnerableTime: Boolean? = null,
        microEnabled: Boolean? = null
    ): FatigueUiState {

        val valid = measurementStatus == MeasurementStatus.VALID

        return FatigueUiState(
            currentRisk = if (valid) currentRisk else null,
            smoothedRisk = if (valid) smoothedRisk else null,
            measurementStatus = measurementStatus,
            drivingPattern = drivingPattern,
            assistMode = assistMode,
            handoverAbnormal = handoverAbnormal,
            departureFatigue = departureFatigue,
            baselineReady = baselineReady,
            vulnerableTime = vulnerableTime,
            microEnabled = microEnabled
        )
    }
}