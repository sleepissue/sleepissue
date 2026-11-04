package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.GeoPoint
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// 위경도 두 점 사이의 거리·방위각. 입력 단위 변환이 아니라 구면 기하 계산이다(REQ_SIG_010과 무관).
// 40 km 범위에서는 지구를 구로 봐도 오차가 0.5% 안쪽이라 haversine으로 충분하다.
internal object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_008.8 // 평균 지구 반지름(IUGG)

    /** 대권 거리(m). */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** a에서 b를 바라보는 처음 방위각(°, 북쪽 0·시계 방향, 0 이상 360 미만). */
    fun initialBearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** 두 방위각의 차(°, 0~180). 350°와 10°의 차는 20°. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
