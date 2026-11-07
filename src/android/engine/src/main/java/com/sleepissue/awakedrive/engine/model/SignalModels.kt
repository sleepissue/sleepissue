package com.sleepissue.awakedrive.engine.model

import com.sleepissue.awakedrive.engine.signal.SourceRate

/** REAL은 Playground SDK·Virtual Console 입력, DEMO는 앱의 JSON 재생 입력이다. */
enum class Source { REAL, DEMO }

enum class Gear { P, R, N, D }

enum class TurnSignal { OFF, LEFT, RIGHT, HAZARD }

enum class SignalAvailability {
    AVAILABLE, UNSUPPORTED, PERMISSION_DENIED, UNAVAILABLE, ERROR, STALE,
}

enum class ObservationTimeBasis { UNKNOWN, SCENARIO, ELAPSED_REALTIME, WALL_TIME }

enum class SignalSelectionReason { REAL_FRESH, DEMO_FALLBACK, DEMO_LOCATION, REAL_LOCATION, REAL_LOSS_HOLD, NO_VALID_INPUT }

/** 발행 순번은 센서 관측 번호가 아니다. 느린 소비자는 같은 세션의 순번 차이로 누락을 안다. */
data class FramePublication(val sessionId: Long, val sequence: Long, val publishedAtElapsedMs: Long)

/** 값이 같아도 새 관측일 수 있다. 원천 시각과 실제 수신 경과 시각을 따로 보존한다. */
data class SignalQuality(
    val availability: SignalAvailability = SignalAvailability.UNAVAILABLE,
    val source: Source? = null,
    val observedAtMs: Long? = null,
    val receivedAtElapsedMs: Long? = null,
    val sourceRateHz: Float? = null,
    // UNKNOWN인 원천 시각은 실제 센서 Hz를 계산하는 근거로 해석하지 않는다.
    val observedTimeBasis: ObservationTimeBasis = ObservationTimeBasis.UNKNOWN,
    val observationSequence: Long? = null,
    val selectionReason: SignalSelectionReason? = null,
    // 선택된 조향의 측정 상태·5Hz 미만 여부를 발행 프레임과 같은 세대로 전달한다.
    val sourceRateInfo: SourceRate? = null,
    // ON_CHANGE 값의 재조회 확인이다. 원천 관측/수신 시각이나 센서 관측 순번을 대신하지 않는다.
    val stateConfirmedAtElapsedMs: Long? = null,
    val stateLeaseUntilElapsedMs: Long? = null,
) {
    init {
        require(observedAtMs == null || observedAtMs >= 0) { "원천 관측 시각은 음수일 수 없습니다." }
        require(receivedAtElapsedMs == null || receivedAtElapsedMs >= 0) { "수신 경과 시각은 음수일 수 없습니다." }
        require(sourceRateHz == null || sourceRateHz.isFinite() && sourceRateHz >= 0f) {
            "원천 갱신률은 유한한 0 이상의 값이어야 합니다."
        }
        require((stateConfirmedAtElapsedMs == null) == (stateLeaseUntilElapsedMs == null))
        require(stateConfirmedAtElapsedMs == null || stateConfirmedAtElapsedMs >= 0 &&
            requireNotNull(stateLeaseUntilElapsedMs) >= stateConfirmedAtElapsedMs)
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
    val publication: FramePublication? = null,
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
