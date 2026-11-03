package com.sleepissue.awakedrive.engine.model

/**
 * FatigueEngine이 10 Hz로 받는 주행 신호 한 프레임. 2번이 임시로 쓰는 입력이다.
 *
 * 1번의 SignalFrame(MSG-01)·NaviState(MSG-14)가 main에 들어오면 거기서 채우는 변환 함수를 하나 둔다.
 * null은 "지금 이 신호를 모른다"는 뜻이다.
 */
data class DriveFrame(
    /** 프레임 시각(ms). 주입된 Clock 기준, 데모 중에는 시나리오 시계 */
    val tMs: Long,
    /** 조향각(°). null이면 보조 주행으로 간주한다 (REQ_AST_003) */
    val steeringDeg: Float?,
    /** 차속(km/h) */
    val speedKph: Float?,
    /** 기어가 D단인지 */
    val inDrive: Boolean?,
    /** 방향지시등이 켜져 있는지 (비상등을 켜짐으로 볼지는 변환 함수에서 정함) */
    val turnSignalOn: Boolean?,
    /** 브레이크를 밟고 있는지 */
    val brakeOn: Boolean?,
    /** 다음 회전 지점까지 거리(m). 목적지가 없거나 내비가 없으면 null (REQ_FAT_004) */
    val distToNextTurnM: Float?,
    /** 크루즈 작동 중인지 (DrivingMode). null이면 보조 주행 아님으로 본다 (REQ_AST_008 [제안]) */
    val cruiseActive: Boolean? = null,
    /** 크루즈 설정 속도(km/h). 바뀌면 조작으로 센다 (REQ_AST_006) */
    val cruiseTargetKph: Float? = null,
    /** 운전석 통풍 단계. 바뀌면 조작으로 센다 (REQ_AST_006) */
    val ventilationLevel: Int? = null,
)
