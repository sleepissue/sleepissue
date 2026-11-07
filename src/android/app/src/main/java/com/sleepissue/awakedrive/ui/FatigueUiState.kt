package com.sleepissue.awakedrive.ui

/**
 * AwakeDrive 화면에 표시할 졸음 판단 정보.
 *
 * 실제 데이터는 FatigueAssessmentRunner에서 전달받는다.
 * 아직 연결되지 않았다면 null로 유지한다.
 */
data class FatigueUiState(

    // 순간 졸음 위험 점수
    val currentRisk: Float? = null,

    // 최근 3분 평활 위험 점수
    val smoothedRisk: Float? = null,

    // 운전 행동 패턴
    val drivingPattern: String = "분석 전",

    // 보조 주행 여부
    val assistMode: Boolean = false,

    // 보조 주행 해제 후 인계 이상 여부
    val handoverAbnormal: Boolean = false,

    // 출발 피로 감지 여부
    val departureFatigue: Boolean? = null,

    // 오늘의 운전 기준선 확정 여부
    val baselineReady: Boolean = false,

    // 취약 시간대 여부
    val vulnerableTime: Boolean? = null
)