package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.GeoPoint

/**
 * 내비가 돌려준 경유지 한 개 (SDK `WaypointInfo`에서 필요한 값만 옮긴 것).
 * @param location 좌표를 못 받았거나 범위 밖이면 null
 */
data class NaviWaypoint(val poiId: String?, val location: GeoPoint?)

/**
 * [4번] REQ_RST_004: "경유지에 실제로 들어갔는가"를 판정한다.
 *
 * `addWaypoint`는 결과를 돌려주지 않으므로, 내비 경유지 목록(`onWayPointInfo`)에 이 쉼터가 보이면 성공으로 본다.
 * 넣을 때 poiId에 쉼터 id를 실어 보내지만 내비가 자기 POI id로 바꿀 수 있으므로 [가정],
 * id가 다르면 좌표가 [EngineConfig.waypointMatchRadiusM] 안인지로 한 번 더 본다.
 */
class WaypointMatcher(private val config: EngineConfig = EngineConfig()) {

    fun contains(waypoints: List<NaviWaypoint>, area: RestArea): Boolean = waypoints.any { matches(it, area) }

    fun matches(waypoint: NaviWaypoint, area: RestArea): Boolean {
        if (waypoint.poiId != null && waypoint.poiId == area.id) return true
        val at = waypoint.location ?: return false
        return GeoMath.distanceM(at, area.location) <= config.waypointMatchRadiusM
    }
}
