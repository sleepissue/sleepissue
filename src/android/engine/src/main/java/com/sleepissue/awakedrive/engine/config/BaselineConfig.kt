package com.sleepissue.awakedrive.engine.config

import com.sleepissue.awakedrive.engine.model.SteeringBaseline

/**
 * BaselineStore 문턱값: 기본값, 오늘의 나, 출발 피로. (REQ_BSL_001~007)
 */
data class BaselineConfig(
    /**
     * 운행 기록이 3회 쌓이기 전에 '평소의 나' 대신 쓰는 미세 수정 분당 횟수 (REQ_BSL_003).
     * [가정] 시연 데이터로 확정할 값. 지금은 SRS REQ_FAT_009 합격기준의 예시 기준값(20)을 임시로 쓴다.
     */
    val defaultMicroPerMin: Float = 20f,
    /**
     * 같은 조건의 큰 수정 분당 횟수 (REQ_BSL_003).
     * [가정] SRS REQ_FAT_010 합격기준의 예시 기준값(4)을 임시로 쓴다.
     */
    val defaultLargePerMin: Float = 4f,

    /** 운행이 이 횟수 이상 쌓여야 '평소의 나'를 씀. 0·1·2회 → 기본값 (REQ_BSL_003) */
    val minTripsForUsual: Int = 3,
    /** '평소의 나'를 계산할 때 쓰는 최근 운행 수 (REQ_BSL_001, 2차) */
    val usualRecentTrips: Int = 10,
    /** '깨어 있던 구간' 조건: 운전 시작 뒤 이 시간 이내 (REQ_BSL_002, 2차) */
    val awakeMaxDriveMs: Long = 2 * 3_600_000L,

    /** 게이트 통과 시간이 누적 이만큼 되면 '오늘의 나'를 확정. 14 min 59 s → 미확정, 15 min → 확정 (REQ_BSL_004) */
    val todayBaselineGatedMs: Long = 15 * 60_000L,

    /** 출발 피로: 미세 수정이 기준보다 이 비율 이상 줄면 1개 충족. −30.0% → 충족, −29.9% → 아님 (REQ_BSL_005) */
    val departureMicroDropRatio: Float = 0.30f,
    /** 출발 피로: 큰 수정이 기준의 이 배수 이상이면 1개 충족 (REQ_BSL_005) */
    val departureLargeRiseRatio: Float = 1.5f,
    /** 출발 피로: 급조향이 이 횟수 이상이면 1개 충족 (REQ_BSL_005) */
    val departureSuddenMinCount: Int = 1,
    /** 위 세 조건 가운데 이 개수 이상이면 출발 피로 (REQ_BSL_005) */
    val departureMinCriteria: Int = 2,

    /** 출발 피로·'졸려요'로 민감 모드가 되면 이 시간 뒤 첫 음성 확인 (REQ_BSL_006) */
    val sensitiveFirstCheckDelayMs: Long = 15 * 60_000L,

    /**
     * 처음 15분 동안 게이트를 통과한 프레임 가운데 조향 원천이 5 Hz 미만이었던 비율이 이 값 이상이면
     * 그날은 미세 수정을 믿지 않는다: 출발 피로에서 미세 조건을 빼고, 오늘의 나를 기준으로 쓰지 않음 [제안값] (REQ_SIG_009)
     */
    val todayMaxSlowSteeringRatio: Float = 0.10f,
) {
    /** 운행 기록이 3회 쌓이기 전에 쓰는 기준선 (REQ_BSL_003) */
    val defaultBaseline: SteeringBaseline
        get() = SteeringBaseline(defaultMicroPerMin, defaultLargePerMin)
}
