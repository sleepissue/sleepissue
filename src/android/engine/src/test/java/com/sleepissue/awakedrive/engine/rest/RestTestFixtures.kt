package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.GeoPoint

// 4번 휴식·보상·경유지 테스트의 공통 쉼터. 좌표 자체는 의미 없다 [가정].
internal object RestTestFixtures {
    private const val METERS_PER_DEG_LAT = 111_195.0 // 평균 반지름 6,371,008.8 m 기준 위도 1°

    val origin = GeoPoint(36.5, 127.4)

    /** origin에서 북쪽으로 northM 미터 떨어진 점. */
    fun north(northM: Double, from: GeoPoint = origin) = GeoPoint(from.lat + northM / METERS_PER_DEG_LAT, from.lon)

    fun area(id: String, at: GeoPoint) = RestArea(
        id = id,
        name = "${id}졸음쉼터",
        kind = RestAreaKind.DROWSY_SHELTER,
        location = at,
        routeName = "경부선",
        directionLabel = "부산방향",
        carriagewayBearingDeg = 180f,
    )
}
