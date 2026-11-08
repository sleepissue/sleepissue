package com.sleepissue.awakedrive.ui

/**
 * AwakeDrive UI에 표시할 졸음 판단 상태.
 *
 * 실제 엔진 연결 전에는 모든 측정값을 미수신 상태로 유지한다.
 */
data class FatigueUiState(

    // 순간 졸음 위험도 (0~100)
    val currentRisk: Float? = null,

    // 최근 3분 평활 위험도 (0~100)
    val smoothedRisk: Float? = null,

    // 측정 상태
    val measurementStatus: MeasurementStatus = MeasurementStatus.WAITING,

    // 운전 행동 패턴
    val drivingPattern: String = "분석 전",

    // 보조 주행 여부
    val assistMode: Boolean? = null,

    // 보조 주행 해제 후 인계 이상 여부
    val handoverAbnormal: Boolean? = null,

    // 출발 피로 감지 여부
    val departureFatigue: Boolean? = null,

    // 기준선 확정 여부
    val baselineReady: Boolean? = null,

    // 취약 시간대 여부
    val vulnerableTime: Boolean? = null,

    // 미세 조향 지표 사용 여부
    val microEnabled: Boolean? = null
)

/**
 * 엔진의 FatigueState 상태를 UI에서 표현하는 방식.
 */
enum class MeasurementStatus {

    // 아직 엔진 결과를 받지 못함
    WAITING,

    // 게이트를 통과할 데이터가 부족함
    GATE_WAIT,

    // 보조 주행 중으로 조향 위험도 판단 제외
    ASSIST,

    // 유효한 위험도 측정값
    VALID
}