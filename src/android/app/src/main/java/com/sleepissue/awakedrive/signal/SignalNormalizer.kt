package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.Clock

/** 기존 단위·null 정규화를 관측 전달과 주기 발행에서 공유한다. */
internal class SignalNormalizer(private val clock: Clock) {
    fun vehicle(input: VehicleSignalInput): SignalFrame {
        val quality = mutableMapOf<SignalField, SignalQuality>()
        val receivedAtMs = clock.elapsedRealtimeMs()
        fun <T> read(field: SignalField, value: T?, isValid: (T) -> Boolean = { true }): T? {
            val supplied = input.quality[field] ?: SignalQuality(
                availability = if (value == null) SignalAvailability.UNAVAILABLE else SignalAvailability.AVAILABLE,
                source = input.source,
            )
            val availability = when {
                supplied.availability != SignalAvailability.AVAILABLE -> supplied.availability
                value == null -> SignalAvailability.UNAVAILABLE
                !isValid(value) -> SignalAvailability.ERROR
                else -> SignalAvailability.AVAILABLE
            }
            quality[field] = supplied.copy(
                availability = availability,
                source = supplied.source ?: input.source,
                receivedAtElapsedMs = receivedAtMs,
            )
            return value.takeIf { availability == SignalAvailability.AVAILABLE }
        }
        val speedKph = input.speed?.let { UnitConverter.speedKph(it, input.speedUnit) }
        return SignalFrame(
            tMs = input.tMs,
            steeringDeg = read(SignalField.STEERING_DEG, input.steeringDeg) { it.isFinite() },
            speedKph = read(SignalField.SPEED_KPH, speedKph) { it.isFinite() && it >= 0f },
            gear = read(SignalField.GEAR, input.gear),
            turnSignal = read(SignalField.TURN_SIGNAL, input.turnSignal),
            brakeOn = read(SignalField.BRAKE_ON, input.brakeOn),
            cruiseActive = read(SignalField.CRUISE_ACTIVE, input.cruiseActive),
            cruiseTargetKph = read(SignalField.CRUISE_TARGET_KPH, input.cruiseTargetKph) { it.isFinite() && it >= 0f },
            seats = read(SignalField.SEATS, input.seats),
            ventilationLevel = read(SignalField.VENTILATION_LEVEL, input.ventilationLevel) { it >= 0 },
            cabinTempC = read(SignalField.CABIN_TEMP_C, input.cabinTempC) { it.isFinite() },
            quality = quality.toMap(),
        )
    }

    fun navi(input: NaviState): NaviState {
        val quality = mutableMapOf<NaviField, SignalQuality>()
        val receivedAtMs = clock.elapsedRealtimeMs()
        fun <T> read(field: NaviField, value: T?): T? {
            val supplied = input.quality[field] ?: SignalQuality()
            val availability = if (supplied.availability == SignalAvailability.AVAILABLE && value == null) {
                SignalAvailability.UNAVAILABLE
            } else supplied.availability
            quality[field] = supplied.copy(availability = availability, receivedAtElapsedMs = receivedAtMs)
            return value.takeIf { availability == SignalAvailability.AVAILABLE }
        }
        return input.copy(
            lat = read(NaviField.LAT, input.lat),
            lon = read(NaviField.LON, input.lon),
            headingDeg = read(NaviField.HEADING_DEG, input.headingDeg),
            remainTimeSec = read(NaviField.REMAIN_TIME_SEC, input.remainTimeSec),
            remainDistM = read(NaviField.REMAIN_DIST_M, input.remainDistM),
            distToNextTurnM = read(NaviField.DIST_TO_NEXT_TURN_M, input.distToNextTurnM),
            quality = quality.toMap(),
        )
    }

}
