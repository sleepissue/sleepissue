package com.sleepissue.awakedrive.ui

import com.sleepissue.awakedrive.engine.model.InvalidReason
import com.sleepissue.awakedrive.engine.model.PatternType
import com.sleepissue.awakedrive.fatigue.FatigueSnapshot

/**
 * 2번 엔진의 FatigueSnapshot을 5번 UI의 FatigueUiState로 변환한다.
 *
 * 위험도가 유효하지 않을 때는 0점으로 표시하지 않는다.
 */
fun FatigueSnapshot.toFatigueUiState(): FatigueUiState {

    val fatigueState = fatigue

    val measurementStatus = when {
        fatigueState == null ->
            MeasurementStatus.WAITING

        fatigueState.valid ->
            MeasurementStatus.VALID

        fatigueState.invalidReason == InvalidReason.ASSIST ->
            MeasurementStatus.ASSIST

        else ->
            MeasurementStatus.GATE_WAIT
    }

    val patternText = when (pattern.patternType) {
        PatternType.NONE -> "특이 패턴 없음"
        PatternType.TREND -> "졸음 추세형"
        PatternType.SPIKE -> "일시적 이상형"
        PatternType.AMBIGUOUS -> "추가 확인 필요"
    }

    return FatigueUiMapper.fromEngineValues(
        currentRisk = fatigueState?.riskR,
        smoothedRisk = fatigueState?.riskSmoothed,
        measurementStatus = measurementStatus,
        drivingPattern = patternText,
        assistMode = assist.assistMode,
        handoverAbnormal = assist.handoverAbnormal,
        departureFatigue = baseline.departureFatigue,
        baselineReady = baseline.today != null,
        vulnerableTime = vulnerableTime,
        microEnabled = fatigueState?.microEnabled
    )
}
