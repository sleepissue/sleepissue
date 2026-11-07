package com.sleepissue.awakedrive.engine.model

data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0) { "위도는 -90~90도여야 합니다." }
        require(lon.isFinite() && lon in -180.0..180.0) { "경도는 -180~180도여야 합니다." }
    }
}

enum class NaviField {
    LAT, LON, HEADING_DEG, REMAIN_TIME_SEC, REMAIN_DIST_M, DIST_TO_NEXT_TURN_M,
}

/** 내비가 없거나 목적지를 정하지 않았다면 해당 값만 null로 전달한다. */
data class NaviState(
    val tMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val headingDeg: Float? = null,
    val remainTimeSec: Long? = null,
    val remainDistM: Float? = null,
    val distToNextTurnM: Float? = null,
    val quality: Map<NaviField, SignalQuality> = emptyMap(),
) {
    init {
        require(tMs >= 0) { "내비 논리 시각은 음수일 수 없습니다." }
        require(lat == null || lat.isFinite() && lat in -90.0..90.0) { "위도는 -90~90도여야 합니다." }
        require(lon == null || lon.isFinite() && lon in -180.0..180.0) { "경도는 -180~180도여야 합니다." }
        require(headingDeg == null || headingDeg.isFinite() && headingDeg >= 0f && headingDeg < 360f) {
            "방향은 0 이상 360도 미만으로 정규화해야 합니다."
        }
        require(remainTimeSec == null || remainTimeSec >= 0) { "남은 시간은 음수일 수 없습니다." }
        require(remainDistM == null || remainDistM.isFinite() && remainDistM >= 0f) {
            "남은 거리는 유한한 0 이상의 값이어야 합니다."
        }
        require(distToNextTurnM == null || distToNextTurnM.isFinite() && distToNextTurnM >= 0f) {
            "다음 회전 거리는 유한한 0 이상의 값이어야 합니다."
        }
    }

    fun qualityOf(field: NaviField): SignalQuality = quality[field] ?: SignalQuality()
}
