package com.sleepissue.awakedrive.engine.model

enum class Source { REAL, DEMO }

enum class Gear { P, R, N, D }

enum class TurnSignal { OFF, LEFT, RIGHT, HAZARD }

enum class SignalAvailability {
    AVAILABLE, UNSUPPORTED, PERMISSION_DENIED, UNAVAILABLE, ERROR, STALE,
}

enum class ObservationTimeBasis { UNKNOWN, SCENARIO, ELAPSED_REALTIME, WALL_TIME }

/** 값이 같아도 새 관측일 수 있다. 원천 시각과 실제 수신 경과 시각을 따로 보존한다. */
data class SignalQuality(
    val availability: SignalAvailability = SignalAvailability.UNAVAILABLE,
    val source: Source? = null,
    val observedAtMs: Long? = null,
    val receivedAtElapsedMs: Long? = null,
    val sourceRateHz: Float? = null,
    // UNKNOWN인 원천 시각은 실제 센서 Hz를 계산하는 근거로 해석하지 않는다.
    val observedTimeBasis: ObservationTimeBasis = ObservationTimeBasis.UNKNOWN,
) {
    init {
        require(observedAtMs == null || observedAtMs >= 0) { "원천 관측 시각은 음수일 수 없습니다." }
        require(receivedAtElapsedMs == null || receivedAtElapsedMs >= 0) { "수신 경과 시각은 음수일 수 없습니다." }
        require(sourceRateHz == null || sourceRateHz.isFinite() && sourceRateHz >= 0f) {
            "원천 갱신률은 유한한 0 이상의 값이어야 합니다."
        }
    }
}

/** 좌석별 null은 빈 좌석이 아니라 현재 착석 여부를 모른다는 뜻이다. */
data class SeatOccupancy(
    val driver: Boolean? = null,
    val frontPassenger: Boolean? = null,
    val rearLeft: Boolean? = null,
    val rearCenter: Boolean? = null,
    val rearRight: Boolean? = null,
)

enum class SignalField {
    STEERING_DEG, SPEED_KPH, GEAR, TURN_SIGNAL, BRAKE_ON, CRUISE_ACTIVE,
    CRUISE_TARGET_KPH, SEATS, VENTILATION_LEVEL, CABIN_TEMP_C,
}

/**
 * Hub에서 한 번 정규화한 신호다. tMs는 운행 논리 시각이며 차속 단위는 km/h다.
 * null을 0이나 false로 바꾸지 않는다. 프레임 일부만 실제 입력일 수 있어 출처는 필드별로 둔다.
 */
data class SignalFrame(
    val tMs: Long,
    val steeringDeg: Float? = null,
    val speedKph: Float? = null,
    val gear: Gear? = null,
    val turnSignal: TurnSignal? = null,
    val brakeOn: Boolean? = null,
    val cruiseActive: Boolean? = null,
    val cruiseTargetKph: Float? = null,
    val seats: SeatOccupancy? = null,
    val ventilationLevel: Int? = null,
    val cabinTempC: Float? = null,
    val quality: Map<SignalField, SignalQuality> = emptyMap(),
) {
    init {
        require(tMs >= 0) { "프레임 논리 시각은 음수일 수 없습니다." }
        require(steeringDeg == null || steeringDeg.isFinite()) { "조향각은 유한해야 합니다." }
        require(speedKph == null || speedKph.isFinite() && speedKph >= 0f) { "차속은 유한한 0 이상의 값이어야 합니다." }
        require(cruiseTargetKph == null || cruiseTargetKph.isFinite() && cruiseTargetKph >= 0f) {
            "크루즈 설정 차속은 유한한 0 이상의 값이어야 합니다."
        }
        require(ventilationLevel == null || ventilationLevel >= 0) { "통풍 단계는 음수일 수 없습니다." }
        require(cabinTempC == null || cabinTempC.isFinite()) { "실내 온도는 유한해야 합니다." }
    }

    fun qualityOf(field: SignalField): SignalQuality = quality[field] ?: SignalQuality()
}

enum class PowerState { ON, OFF, RESUME, UNKNOWN }

/** 전원 관측 결과만 표현하며 실제 전원 제어 기능을 뜻하지 않는다. */
data class PowerEvent(
    val tMs: Long,
    val state: PowerState,
    val quality: SignalQuality = SignalQuality(),
) {
    init {
        require(tMs >= 0) { "전원 이벤트 논리 시각은 음수일 수 없습니다." }
    }
}
