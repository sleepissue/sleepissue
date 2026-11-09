package com.sleepissue.awakedrive.ui

/**
 * AwakeDrive 주행 리포트 화면에 표시할 데이터.
 *
 * 실제 주행 기록을 계산하거나 저장하지 않는다.
 * 팀원들의 기록 데이터를 UI에서 사용할 수 있도록 표현한다.
 */
data class ReportUiState(

    // 이번 운행의 총 운전 시간
    // null이면 아직 확인되지 않은 상태
    val totalDrivingTime: String? = null,

    // 경고 발생 횟수
    // null이면 아직 집계되지 않은 상태
    val warningCount: Int? = null,

    // 인증된 휴식 횟수
    val restCount: Int? = null,

    // 경고 발생 이력
    val warningEvents: List<ReportWarningEvent> = emptyList(),

    // 휴식 이력
    val restEvents: List<ReportRestEvent> = emptyList()
)

/**
 * 경고 발생 기록.
 */
data class ReportWarningEvent(
    val time: String,
    val stage: String,
    val description: String
)

/**
 * 휴식 기록.
 */
data class ReportRestEvent(
    val time: String,
    val duration: String? = null,
    val description: String
)