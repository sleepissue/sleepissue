package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.NaviState

// [4번] 앞쪽 쉼터 고르기 — 간트 id=42 "쉼터 선택·경유지 (RST_002~006)" 중 엔진 부분.
// 기획서 10장: 아래 조건을 모두 만족하는 쉼터 중 가장 가까운 곳을 고른다.
//   1) 현재 위치에서 40 km 안            (REQ_RST_002)
//   2) 최근 1분 이동 방향 ±45° 안         (REQ_RST_002) — 차에서 쉼터를 바라본 방위각
//   3) 노선 방향 필드가 진행 방향과 맞음   (REQ_RST_003) — 쉼터 차로의 방위각
// 못 고르면 NotFound를 돌려주고 예외를 던지지 않는다 (REQ_RST_005).
//
// 3)의 구현: 원본 방향 필드("서울기점 + 부산종점", "횡성(인천)")를 전처리에서 차로 방위각으로 바꿔 두고,
// 차 진행 방향과의 차가 restAreaCarriagewayToleranceDeg 안이면 같은 방향 차로로 본다.
// 그래서 지금 달리는 노선이 상행인지 하행인지 따로 알 필요가 없다.
//
// ROS로 치면 NaviState 토픽 + 쉼터 표를 받아 결과 하나를 내는 순수 함수 노드다. 상태가 없다.
// 호출 주체: 1번 PolicyInterventionManager가 RestPort(FIND_AHEAD)로 요청 → app 쪽 RestPort 구현이
// RestAreaRepository의 표와 함께 이 함수를 부르고, Selected를 RestAreaCandidate로 바꿔 돌려준다.
// "가까운 곳에서 쉬어 가요" 문구는 3번 VoiceAgent가 SpeechCue.NO_REST_AREA를 받아 말한다.
class RestAreaSelector(private val config: EngineConfig = EngineConfig()) {

    fun select(navi: NaviState, areas: List<RestArea>): RestAreaSelection {
        val lat = navi.lat
        val lon = navi.lon
        if (lat == null || lon == null) return RestAreaSelection.NotFound(NoRestAreaReason.NO_POSITION)
        val heading = navi.headingDeg ?: return RestAreaSelection.NotFound(NoRestAreaReason.NO_HEADING)

        val best = candidates(GeoPoint(lat, lon), heading, areas)
            .filter { sameCarriageway(it.area, heading) }
            // 거리가 같으면 id 순으로 골라 결과가 항상 같게 한다.
            .minWithOrNull(compareBy<RestAreaSelection.Selected> { it.distanceM }.thenBy { it.area.id })

        return best ?: RestAreaSelection.NotFound(NoRestAreaReason.NONE_IN_RANGE)
    }

    /** REQ_RST_002만 적용한 후보(거리·방위각). 차로 방향은 보지 않는다. */
    internal fun candidates(
        position: GeoPoint,
        headingDeg: Float,
        areas: List<RestArea>,
    ): List<RestAreaSelection.Selected> = areas.mapNotNull { area ->
        val distanceM = GeoMath.distanceM(position, area.location)
        // [해석] 경계값(정확히 40 km, 정확히 45°)은 후보에 넣는다. SRS 합격기준은 39.9/40.1, 44.9/45.1만 정함.
        if (distanceM > config.restAreaSearchRadiusM) return@mapNotNull null
        val bearingDiff = GeoMath.angleDiffDeg(GeoMath.initialBearingDeg(position, area.location), headingDeg.toDouble())
        if (bearingDiff > config.restAreaHeadingToleranceDeg) return@mapNotNull null
        RestAreaSelection.Selected(area, distanceM.toFloat(), bearingDiff.toFloat())
    }

    // REQ_RST_003: 반대편 차로 쉼터는 갈 수 없으므로 뺀다.
    internal fun sameCarriageway(area: RestArea, headingDeg: Float): Boolean {
        if (area.bothDirections) return true
        val bearing = area.carriagewayBearingDeg ?: return config.restAreaIncludeUnknownDirection
        return GeoMath.angleDiffDeg(bearing.toDouble(), headingDeg.toDouble()) <= config.restAreaCarriagewayToleranceDeg
    }
}
