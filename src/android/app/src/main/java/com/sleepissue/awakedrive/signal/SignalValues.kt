package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.*

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
