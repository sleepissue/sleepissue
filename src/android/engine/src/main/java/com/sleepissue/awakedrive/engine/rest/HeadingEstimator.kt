package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.NaviState

/**
 * [4번] 위치 이력으로 "최근 1분 이동 방향"을 추정한다 (REQ_RST_002).
 *
 * 내비(getCurrentLocationInfo)나 데모 시나리오가 위치만 주고 진행 방향(headingDeg)을 주지 않으면
 * [RestAreaSelector]가 NO_HEADING으로 쉼터를 못 고른다. 그래서 쉼터 고르기 직전에 방향을 채운다.
 *
 * 방법: 지금 위치와 [EngineConfig.restHeadingWindowMs](1분) 전쯤 위치 사이의 방위각.
 * 그 사이 [EngineConfig.restHeadingMinDisplacementM]보다 덜 움직였으면(정차·위치 고정 에뮬레이터) 방향을 정하지 않는다.
 * 내비가 방향을 이미 주면 그 값을 그대로 쓴다.
 *
 * ROS로 치면 내비 토픽을 구독해 1분짜리 위치 버퍼를 들고 있는 노드다. 한 스레드에서만 부른다.
 */
class HeadingEstimator(private val config: EngineConfig = EngineConfig()) {
    private data class Fix(val tMs: Long, val point: GeoPoint)

    private val fixes = ArrayDeque<Fix>()

    /** 내비 관측을 넣는다. 시간이 거꾸로 가는 값과 위치 없는 값은 버린다. */
    fun observe(navi: NaviState) {
        val lat = navi.lat ?: return
        val lon = navi.lon ?: return
        val last = fixes.lastOrNull()
        if (last != null && navi.tMs <= last.tMs) return
        fixes.addLast(Fix(navi.tMs, GeoPoint(lat, lon)))
        // 창보다 오래된 점은 하나만 남긴다(창 경계 바로 바깥 점이 "1분 전" 기준점이 된다).
        while (fixes.size >= 2 && navi.tMs - fixes[1].tMs >= config.restHeadingWindowMs) fixes.removeFirst()
    }

    /** 추정한 진행 방향(0 이상 360 미만). 정할 수 없으면 null. */
    fun headingDeg(): Float? {
        if (fixes.size < 2) return null
        val now = fixes.last()
        val origin = fixes.first()
        if (GeoMath.distanceM(origin.point, now.point) < config.restHeadingMinDisplacementM) return null
        val bearing = GeoMath.initialBearingDeg(origin.point, now.point).toFloat()
        return normalize(bearing)
    }

    /** 내비에 방향이 없으면 추정값으로 채운 복사본. 추정도 못 하면 그대로 돌려준다. */
    fun fill(navi: NaviState): NaviState {
        if (navi.headingDeg != null) return navi
        val heading = headingDeg() ?: return navi
        return navi.copy(headingDeg = heading)
    }

    /** 새 운행·입력 재연결 때 이전 궤적을 버린다. */
    fun reset() = fixes.clear()

    private fun normalize(deg: Float): Float {
        val n = ((deg % 360f) + 360f) % 360f
        return if (n >= 360f) 0f else n
    }
}
