package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.*

/** 좌석별 안전벨트 체결 상태. null은 미지원·미수신·오래된 값 등으로 현재 상태를 모른다는 뜻이다. */
data class SeatBeltState(
    val driver: Boolean? = null,
    val frontPassenger: Boolean? = null,
    val rearLeft: Boolean? = null,
    val rearCenter: Boolean? = null,
    val rearRight: Boolean? = null,
)

/** enum과 자료형의 대응을 한 곳에 두어 정규화·중재에서 같은 필드를 읽는다. */
internal fun SignalFrame.valueOf(field: SignalField): Any? = when (field) {
    SignalField.STEERING_DEG -> steeringDeg
    SignalField.SPEED_KPH -> speedKph
    SignalField.GEAR -> gear
    SignalField.TURN_SIGNAL -> turnSignal
    SignalField.BRAKE_ON -> brakeOn
    SignalField.CRUISE_ACTIVE -> cruiseActive
    SignalField.CRUISE_TARGET_KPH -> cruiseTargetKph
    SignalField.SEATS -> seats
    SignalField.VENTILATION_LEVEL -> ventilationLevel
    SignalField.CABIN_TEMP_C -> cabinTempC
}

internal fun NaviState.valueOf(field: NaviField): Any? = when (field) {
    NaviField.LAT -> lat
    NaviField.LON -> lon
    NaviField.HEADING_DEG -> headingDeg
    NaviField.REMAIN_TIME_SEC -> remainTimeSec
    NaviField.REMAIN_DIST_M -> remainDistM
    NaviField.DIST_TO_NEXT_TURN_M -> distToNextTurnM
}
