package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.model.DriveFrame
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.SeatOccupancy
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.engine.model.TurnSignal
import com.sleepissue.awakedrive.engine.signal.RateStatus

/*
 * 1번 신호·운행 상태 → 2번 엔진 입력 변환. 단위는 1번 Hub가 이미 내부 표준으로 바꿔 주므로(REQ_SIG_010)
 * 여기서는 이름만 맞추고 단위는 바꾸지 않는다. null은 그대로 null로 넘긴다(모르는 값을 0·false로 바꾸지 않음).
 */

/** 신호 묶음(MSG-01)과 최신 내비 상태(MSG-14) → [DriveFrame]. 10 Hz 프레임마다 부른다. */
fun SignalFrame.toDriveFrame(navi: NaviState?): DriveFrame = DriveFrame(
    tMs = tMs,
    steeringDeg = steeringDeg,
    speedKph = speedKph,
    inDrive = gear?.let { it == Gear.D },
    // 비상등도 켜짐으로 본다. 비상등을 켠 주행은 평소 조향이 아니다 [해석]
    turnSignalOn = turnSignal?.let { it != TurnSignal.OFF },
    brakeOn = brakeOn,
    distToNextTurnM = navi?.distToNextTurnM,
    cruiseActive = cruiseActive,
    cruiseTargetKph = cruiseTargetKph,
    ventilationLevel = ventilationLevel,
    steeringRateOk = steeringRateOk(),
)

/**
 * 조향각 원천이 5 Hz 이상으로 '측정된' 때만 true (REQ_SIG_009).
 * 측정 전(WARMING_UP)·측정 불가·정보 없음은 false — 1번 요청대로 '모름'을 '충분함'으로 보지 않는다.
 */
fun SignalFrame.steeringRateOk(): Boolean {
    val rate = qualityOf(SignalField.STEERING_DEG).sourceRateInfo ?: return false
    return rate.status == RateStatus.MEASURED && rate.belowMinimum == false
}

/** 조수석·뒷좌석에 앉은 사람 수. 하나라도 모르면(null) null → 혼자 운전 가중을 더하지 않음 (REQ_FAT_016) */
fun SeatOccupancy.passengerCount(): Int? {
    val seats = listOf(frontPassenger, rearLeft, rearCenter, rearRight)
    if (seats.any { it == null }) return null
    return seats.count { it == true }
}

/**
 * 운행 상태(MSG-08)·신호 묶음 → 맥락 가중 입력(REQ_FAT_013~018).
 * 시각(자정부터 센 분)과 연령대(MSG-12)는 앱이 넣는다: 시간대 변환과 설정은 엔진 밖에 있다.
 */
fun contextInputOf(trip: TripState?, frame: SignalFrame?, minuteOfDay: Int?, ageBand: AgeBand?): ContextInput =
    ContextInput(
        continuousDriveSec = trip?.continuousDriveSec,
        last24hDriveSec = trip?.last24hDriveSec,
        minuteOfDay = minuteOfDay,
        ageBand = ageBand,
        passengerCount = frame?.seats?.passengerCount(),
        cabinTempC = frame?.cabinTempC,
    )
