package com.sleepissue.awakedrive.pleos

import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.RouteStateInfo

import com.sleepissue.awakedrive.engine.model.NaviField
import com.sleepissue.awakedrive.engine.model.SignalAvailability

/** NaviHelper는 조회 응답과 자발 알림을 같은 listener로 준다. 모두 상태 보고로 취급하며 센서 관측을 만들지 않는다. */
internal class SdkNaviDelivery(
    private val store: SdkSignalStore,
    private val route: SdkRouteGate,
    private val emit: () -> Unit,
) {
    /** 새 활성 경로일 때만 재조회를 요청할 수 있도록 전환 여부를 돌려준다. 반복 상태 보고는 제외한다. */
    fun route(state: RouteStateInfo, receipt: Long): Boolean {
        // 공식 NaviMode의 명시적 안내 상태만 경로 값의 근거로 쓴다.
        // 현재 provider는 ROUTE_GUIDING 중에도 isRouting=false를 보고한다(2026-11-09 기기 재현).
        // 요청 반환·탐색 중 Boolean만으로 안내 시작을 추정하거나 SAFE_GUIDING의 기본 0을 살리지 않는다.
        val active = state.naviMode == NaviMode.ROUTE_GUIDING || state.naviMode == NaviMode.SIMULATION_GUIDING
        val becameActive = active && !route.acceptsRouteValues()
        route.update(active, receipt)
        if (!active) clearRoute("${state.naviMode} · 활성 안내 없음 · 기본 0 미사용")
        return becameActive
    }

    fun location(lat: Double, lon: Double, rawHeading: Double?, receipt: Long) {
        val valid = lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0
        val a = store.receive("LAT", lat, lat.takeIf { valid }, SdkDelivery.REQUERY, receivedMs = receipt)
        val b = store.receive("LON", lon, lon.takeIf { valid }, SdkDelivery.REQUERY, receivedMs = receipt)
        if (!a || !b) return
        if (!valid) store.clearLocationHistory()
        store.receive("HEADING_DEG", rawHeading,
            if (valid) store.locationHeading(lat, lon, receipt) else null, SdkDelivery.REQUERY,
            note = "조회/자발 알림 구분 불가 · SDK heading 단위 미확인 · 최근 1분 유효 위치로 방위각 계산", receivedMs = receipt)
        emit()
    }

    fun destination(seconds: Int, meters: Int, receipt: Long) {
        val active = route.acceptsRouteValues()
        store.receive("REMAIN_TIME_SEC", seconds, seconds.toLong().takeIf { active && it >= 0 },
            SdkDelivery.REQUERY, receivedMs = receipt, note = "초 · 현재 경로 상태 보고")
        store.receive("REMAIN_DIST_M", meters, meters.toFloat().takeIf { active && it >= 0 },
            SdkDelivery.REQUERY, receivedMs = receipt, note = "m · 현재 경로 상태 보고")
        emit()
    }

    fun turns(distances: List<Double>, receipt: Long) {
        val distance = distances.filter { it.isFinite() && it >= 0 }.minOrNull()
        store.receive("DIST_TO_NEXT_TURN_M", distance,
            distance?.takeIf { route.acceptsRouteValues() }?.toFloat(), SdkDelivery.REQUERY,
            receivedMs = receipt, note = "TBT upcoming turn distance m 중 가장 가까운 비음수 거리 · 상태 보고")
        emit()
    }

    fun clearRoute(note: String) {
        for (field in listOf("REMAIN_TIME_SEC", "REMAIN_DIST_M", "DIST_TO_NEXT_TURN_M"))
            store.failed(field, SignalAvailability.UNAVAILABLE, note)
        emit()
    }

    fun cancel(note: String) { route.clear(); clearRoute(note) }

    fun failed(error: Exception) {
        route.clear(); store.clearLocationHistory()
        NaviField.entries.forEach { store.failed(it.name,
            if (error is SecurityException) SignalAvailability.PERMISSION_DENIED else SignalAvailability.ERROR,
            "${error.javaClass.simpleName} · 내비 상태 재확인 필요") }
        emit()
    }
}
