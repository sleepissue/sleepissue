package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.GeoPoint

// [4번] 쉼터 데이터 모델 — REQ_RST_001(내장 표 한 줄)·RST_002~003(선택 입력).
// CSV를 읽는 쪽(app/navi/RestAreaRepository)이 이 모양으로 바꿔 엔진에 넘긴다.

/** REQ_RST_001: 졸음쉼터와 휴게소를 표 하나에 담고 종류 열로 나눈다. */
enum class RestAreaKind { DROWSY_SHELTER, SERVICE_AREA }

/**
 * 노선 방향. 차가 이 쉼터를 쓸 수 있는지(반대편 차로가 아닌지)를 가린다.
 *
 * [가정] 원본 CSV의 '도로노선방향' 열 값 형식은 아직 확인 전이다(SRS API Map 29 ⚠).
 * 전처리 스크립트가 원본 값을 이 네 값 중 하나로 바꿔 넣는다고 가정한다.
 * 실제 값이 "서울방향"처럼 도시 이름이면 CSV 확인 뒤 이 enum을 다시 정한다.
 */
enum class RouteDirection {
    UP,      // 상행
    DOWN,    // 하행
    BOTH,    // 양방향에서 들어갈 수 있음
    UNKNOWN, // 원본 값이 비었거나 해석 못 함
}

data class RestArea(
    val id: String,
    val name: String,
    val kind: RestAreaKind,
    val location: GeoPoint,
    val routeName: String,
    val direction: RouteDirection,
) {
    init {
        require(id.isNotBlank()) { "쉼터 id는 비어 있으면 안 됩니다." }
        require(name.isNotBlank()) { "쉼터 이름은 비어 있으면 안 됩니다." }
    }
}

/** 쉼터를 못 고른 이유. 어느 경우든 1번 InterventionManager는 SpeechCue.NO_REST_AREA로 안내한다(REQ_RST_005). */
enum class NoRestAreaReason {
    NO_POSITION,   // 내비 위치를 못 받음 (NaviState.lat/lon null)
    NO_HEADING,    // 진행 방향을 못 구함 (정차 중이거나 최근 1분 위치 변화 없음)
    NONE_IN_RANGE, // 40 km·±45°·노선 방향 조건을 모두 만족하는 쉼터가 없음
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
