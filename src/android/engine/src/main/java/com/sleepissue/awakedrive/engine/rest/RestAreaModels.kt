package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.GeoPoint

// [4번] 쉼터 데이터 모델 — REQ_RST_001(내장 표 한 줄)·RST_002~003(선택 입력).
// 원본: data/rest_areas/ 공공데이터 CSV 2종 → src/tools-py/preprocess_rest_areas.py →
//       data/rest_areas/processed/rest_areas.csv (앱 assets) → RestAreaCsv.parse → 이 모델.

/** REQ_RST_001: 졸음쉼터와 휴게소를 표 하나에 담고 종류 열로 나눈다. */
enum class RestAreaKind { DROWSY_SHELTER, SERVICE_AREA }

/**
 * @param directionLabel 원본 방향 표기 그대로 (예: "서울기점 + 부산종점", "상행"). 화면·로그용
 * @param towardCity 이 차로가 향하는 도시 (예: "부산"). 양방향이면 null
 * @param bothDirections 양방향 휴게소 — 어느 방향 차로에서든 들어갈 수 있음
 * @param carriagewayBearingDeg 이 쉼터가 붙은 차로의 진행 방위각(°, 북 0·시계 방향).
 *   전처리에서 방향 도시 좌표로 계산한 근사값이다. null = 방향 모름(순환선·도시 너무 가까움 등)
 */
data class RestArea(
    val id: String,
    val name: String,
    val kind: RestAreaKind,
    val location: GeoPoint,
    val routeName: String,
    val directionLabel: String,
    val towardCity: String? = null,
    val bothDirections: Boolean = false,
    val carriagewayBearingDeg: Float? = null,
    val hasToilet: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "쉼터 id는 비어 있으면 안 됩니다." }
        require(name.isNotBlank()) { "쉼터 이름은 비어 있으면 안 됩니다." }
        require(routeName.isNotBlank()) { "노선 이름은 비어 있으면 안 됩니다." }
        require(directionLabel.isNotBlank()) { "방향 표기는 비어 있으면 안 됩니다." }
        require(carriagewayBearingDeg == null || carriagewayBearingDeg.isFinite() &&
            carriagewayBearingDeg >= 0f && carriagewayBearingDeg < 360f) { "차로 방위각은 0 이상 360 미만이어야 합니다." }
    }
}

/** 쉼터를 못 고른 이유. 어느 경우든 1번 InterventionManager는 SpeechCue.NO_REST_AREA로 안내한다(REQ_RST_005). */
enum class NoRestAreaReason {
    NO_POSITION,   // 내비 위치를 못 받음 (NaviState.lat/lon null)
    NO_HEADING,    // 진행 방향을 못 구함 (정차 중이거나 최근 1분 위치 변화 없음)
    NONE_IN_RANGE, // 40 km·±45°·차로 방향 조건을 모두 만족하는 쉼터가 없음
}

sealed interface RestAreaSelection {
    /** @param distanceM 현재 위치에서 쉼터까지 직선(대권) 거리 m. 도로 거리가 아니다. */
    data class Selected(
        val area: RestArea,
        val distanceM: Float,
        val bearingDiffDeg: Float,
    ) : RestAreaSelection

    data class NotFound(val reason: NoRestAreaReason) : RestAreaSelection
}
