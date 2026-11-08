package com.sleepissue.awakedrive.engine.model

/** 지금 f미세·f큰 계산에 쓰는 기준선의 출처. (REQ_BSL_003·004·006) */
enum class BaselineSource {
    /** 운행 기록이 3회 쌓이기 전의 기본값 (REQ_BSL_003) */
    DEFAULT,

    /** 평소의 나: 깨어 있던 최근 운행 10회의 중앙값 (REQ_BSL_001·003) */
    USUAL,

    /** 오늘의 나: 이번 운행 유효 구간(게이트 통과)의 처음 15분 (REQ_BSL_004) */
    TODAY,
}

/**
 * 기준선과 민감 모드 상태. BaselineStore가 값이 바뀔 때만 새로 만든다. (REQ_BSL_003~007)
 *
 * 받는 쪽
 * - 1번 InterventionManager: [sensitive] → 단계 문턱 30/50/70·1단계 1시간 (REQ_INT_012), [firstCheckDueMs] → 첫 음성 확인
 * - 4번 ForecastEngine: [departureFatigue] → startFatigueDetected, false→true가 되는 순간 예보 다시 계산 (REQ_FCS_001)
 * - 5번 화면: [sensitive] → 주행 화면 '민감 모드' 표시 (REQ_UI_002)
 *
 * SRS Data Interface에는 아직 없는 메시지다(MSG-16으로 추가 제안).
 * 시각이 들어간 필드도 상태가 바뀔 때만 달라지므로, StateFlow에 그대로 넣으면 바뀔 때만 알림이 간다.
 */
data class BaselineState(
    /** 지금 쓰는 기준선의 출처 */
    val source: BaselineSource,
    /** 지금 f미세·f큰 계산에 쓰는 기준선 */
    val reference: SteeringBaseline,
    /** 확정된 오늘의 나. 유효 구간이 15분 쌓이기 전이면 null (REQ_BSL_004) */
    val today: SteeringBaseline? = null,
    /** 출발 피로 판정 결과. 오늘의 나를 확정할 때 한 번 정하고 운행이 끝날 때까지 바꾸지 않는다 (REQ_BSL_005) */
    val departureFatigue: Boolean = false,
    /** 출발 전 컨디션으로 '졸려요'를 골랐는지 (REQ_BSL_007) */
    val reportedSleepy: Boolean = false,
    /** 민감 모드가 켜진 시각(ms). 꺼져 있으면 null */
    val sensitiveSinceMs: Long? = null,
    /**
     * 민감 모드 첫 음성 확인 예정 시각(ms) = max(민감 모드 시작, 출발) + 15분 (REQ_BSL_006) [해석].
     * 출발 전에 '졸려요'를 골랐으면 출발(D단)부터 15분이다. 아직 출발 전이거나 민감 모드가 아니면 null.
     */
    val firstCheckDueMs: Long? = null,
) {
    /** 민감 모드: 출발 피로이거나 '졸려요' (기획서 5-1). 회복 확인 '느림' 뒤 민감 모드(REQ_RES_006)는 4번이 따로 낸다. */
    val sensitive: Boolean get() = departureFatigue || reportedSleepy
}
