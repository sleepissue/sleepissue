package com.sleepissue.awakedrive.ui

import com.sleepissue.awakedrive.engine.forecast.ForecastResult
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.rest.NapState
import com.sleepissue.awakedrive.fatigue.FatigueSnapshot

/** 화면에는 관측값만 전달한다. null을 정상·0·정차로 꾸미지 않는다. */
data class DriveSessionUiState(
    val sessionId: Long = 0,
    val tMs: Long = 0,
    val frame: SignalFrame? = null,
    val trip: TripState = TripState(),
    val currentTripDriveSec: Long? = null,
    val fatigue: FatigueSnapshot? = null,
    val intervention: InterventionState? = null,
    val forecast: ForecastResult? = null,
    val nextRestArea: RestAreaUiState = RestAreaUiState.NotSearched,
    val restAreaName: String? = null,
    val restRemainingMs: Long? = null,
    val restCertified: Boolean = false,
    val stampCount: Int = 0,
    val nap: NapState = NapState.Idle,
    val napRemainingMs: Long? = null,
    val voiceButtonContext: CheckContext? = null,
    val recovery: String = "회복 확인 전",
    val waypointMessage: String = "경유지 요청 없음",
    val message: String = "신호 연결 대기",
    val demoPlaying: Boolean = false,
    val demoSession: Boolean = false,
    val actionsConnected: Boolean = false,
    val warnings: List<ReportWarningEvent> = emptyList(),
    val rests: List<ReportRestEvent> = emptyList(),
) {
    val parked: Boolean get() = frame?.gear == Gear.P && frame.speedKph == 0f
    val ventilationText: String get() = when (val level = frame?.ventilationLevel) {
        null -> "운전석 통풍 상태 미수신"
        0 -> "운전석 통풍 꺼짐"
        else -> "운전석 통풍 켜짐 · ${level}단계"
    }
}

fun DriveSessionUiState.toReportUiState() = ReportUiState(
    totalDrivingTime = currentTripDriveSec?.let { "%d시간 %02d분".format(it / 3600, it % 3600 / 60) },
    warningCount = warnings.size.takeIf { frame != null },
    restCount = rests.size.takeIf { frame != null },
    warningEvents = warnings, restEvents = rests,
)

/** 버튼은 상태를 직접 고치지 않고 세션 소유자에게 요청한다. ROS 서비스 요청과 같은 역할이다. */
sealed interface DriveUiAction {
    data class VoiceResponse(val intent: ResponseIntent) : DriveUiAction
    data class Condition(val value: DrivingCondition) : DriveUiAction
    data class Preferences(val value: DriverUiSettings) : DriveUiAction
    data class Passenger(val rest: Boolean) : DriveUiAction
    data object CancelWaypoint : DriveUiAction
    data object StartNap : DriveUiAction
    data object WakeNow : DriveUiAction
    data object RequestRecovery : DriveUiAction
    data object DeleteRecords : DriveUiAction
}
