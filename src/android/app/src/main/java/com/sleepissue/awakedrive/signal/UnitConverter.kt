package com.sleepissue.awakedrive.signal

enum class DistanceUnit { METERS, KILOMETERS }

/** 외부 단위 변환의 경계다. 내부 SignalFrame/NaviState를 소비하는 쪽은 다시 변환하지 않는다. */
object UnitConverter {
    fun speedKph(value: Float, unit: SpeedUnit): Float = when (unit) {
        SpeedUnit.METERS_PER_SECOND -> value * 3.6f
        SpeedUnit.KILOMETERS_PER_HOUR -> value
    }
    fun distanceMeters(value: Float, unit: DistanceUnit): Float = when (unit) {
        DistanceUnit.METERS -> value
        DistanceUnit.KILOMETERS -> value * 1_000f
    }
}
