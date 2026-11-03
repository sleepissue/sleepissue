package com.sleepissue.awakedrive.engine.config

import com.sleepissue.awakedrive.engine.model.AgeGroup
import kotlin.math.exp

/**
 * FatigueEngine 문턱값: 게이트, 조향 지표 3개, 위험 점수, 3분 지수평균. (REQ_FAT_001~020)
 *
 * 단위는 필드 이름 끝에 붙인다 (REQ_SIG_010): Kph = km/h, Deg = °, M = m, Ms = ms, C = ℃.
 */
data class FatigueConfig(
    // ── 게이트: 조향 지표를 계산해도 되는 프레임 (REQ_FAT_001~004) ──

    /** 이 속도 이상이어야 계산. 59.9 → 제외, 60.0 → 포함 (REQ_FAT_001) */
    val gateMinSpeedKph: Float = 60f,
    /** 방향지시등 작동 구간의 앞뒤로 이만큼 뺌 (REQ_FAT_002) */
    val turnSignalMarginMs: Long = 10_000,
    /** 브레이크 작동 구간의 앞뒤로 이만큼 뺌 (REQ_FAT_003) */
    val brakeMarginMs: Long = 5_000,
    /** 회전 지점 거리를 알 때, 이보다 가까우면 뺌. 299.9 → 제외, 300.0 → 포함 (REQ_FAT_004) */
    val minDistToNextTurnM: Float = 300f,
    /** 회전 지점 거리를 모를 때(null), 게이트 속도 이상을 이만큼 연속 유지한 뒤부터 계산 [제안값] (REQ_FAT_004) */
    val unknownTurnHoldMs: Long = 5 * 60_000L,
    /** 위 유지 중 게이트 속도 미만이 이 시간을 넘게 이어지면 유지 시간을 처음부터 다시 셈 [제안값] (REQ_FAT_004) */
    val unknownTurnLowSpeedGraceMs: Long = 30_000,
    /**
     * 프레임 사이가 이보다 벌어지면 신호가 끊긴 것으로 보고 위 '5분 유지'를 처음부터 다시 센다 [제안값].
     * 신호는 100 ms마다 오므로(REQ_SIG_001) 1초 넘게 안 오면 그 사이 속도를 알 수 없다.
     */
    val gateMaxFrameGapMs: Long = 1_000,

    // ── 창과 갱신 (REQ_FAT_005) ──

    /** 지표를 세는 창 길이 */
    val windowMs: Long = 60_000,
    /** 지표·점수를 다시 계산하는 간격 */
    val updateIntervalMs: Long = 30_000,
    /** 창 안에서 게이트를 통과한 시간이 이보다 짧으면 이번 갱신을 건너뜀 [제안값 — 팀 확정 필요] (REQ_FAT_005) */
    val minGatedMsPerWindow: Long = 30_000,

    // ── 조향 지표 3개 (REQ_FAT_006~008) ──

    /** 미세 수정: 방향이 바뀌는 조향의 극값 사이 변화량이 이 값 이상 (경계 포함 [해석]) (REQ_FAT_006) */
    val microMinDeg: Float = 0.5f,
    /** 미세 수정: 같은 변화량이 이 값 이하 (경계 포함 [해석]) (REQ_FAT_006) */
    val microMaxDeg: Float = 3.0f,
    /** 큰 수정: 극값 사이 변화량이 이 값 이상. 3.0 초과~5.0 미만은 어느 쪽에도 세지 않음 (REQ_FAT_007) */
    val largeMinDeg: Float = 5.0f,
    /** 멈춤 뒤 급조향: 이 시간을 '넘게' 변화가 작아야 함. 4.0 s → 안 셈, 4.1 s → 셈 (REQ_FAT_008) */
    val suddenStillMs: Long = 4_000,
    /** 멈춤으로 보는 변화량 상한('미만') (REQ_FAT_008) */
    val suddenStillMaxDeltaDeg: Float = 0.5f,
    /** 멈춤이 끝난 뒤 이 시간 안에 꺾여야 함 (REQ_FAT_008) */
    val suddenTurnWithinMs: Long = 1_000,
    /** 그때 꺾인 각도가 이 값 이상 (REQ_FAT_008) */
    val suddenTurnMinDeg: Float = 6.0f,
    /** f급조향을 셀 때 보는 기간 (REQ_FAT_011) */
    val suddenCountWindowMs: Long = 5 * 60_000L,

    // ── 위험 점수 (REQ_FAT_009~012) ──

    /** f미세 = (1 − 현재÷기준) ÷ 이 값. 기준의 50% 이하로 줄면 1 (REQ_FAT_009) */
    val fMicroDropForFull: Float = 0.5f,
    /** f큰 = (현재÷기준 − 1) ÷ 이 값. 기준의 2배 이상이면 1 (REQ_FAT_010) */
    val fLargeRiseForFull: Float = 1.0f,
    /** f급조향 = 최근 5분 급조향 횟수 ÷ 이 값. 2회 이상이면 1 (REQ_FAT_011) */
    val fSuddenCountForFull: Int = 2,
    /** B = 100 × (가중치 미세·f미세 + 가중치 큰·f큰 + 가중치 급조향·f급조향) (REQ_FAT_012) */
    val weightMicro: Float = 0.35f,
    val weightLarge: Float = 0.25f,
    val weightSudden: Float = 0.40f,
    /** 점수 상한. R = min(이 값, B × K) (REQ_FAT_012) */
    val riskMax: Float = 100f,

    // ── 3분 지수평균 (REQ_FAT_020) ──

    /**
     * 지수평균의 시정수. '3분 지수평균'을 시정수 180 s로 읽었다 [해석 — 팀 확정 필요].
     * 0 → 100 계단 입력에서 180 s 뒤 평활값이 63.2가 되는 값이다.
     */
    val smoothingTimeConstantMs: Long = 180_000,

    // ── 맥락 가중 K (REQ_FAT_013~018) ──

    val context: ContextWeightConfig = ContextWeightConfig(),
) {
    /**
     * 갱신 한 번마다 새 R을 섞는 비율 α = 1 − e^(−갱신 간격 ÷ 시정수).
     * 기본값(30 s, 180 s)이면 0.1535. (REQ_FAT_020 합격기준)
     */
    val smoothingAlpha: Double
        get() = 1.0 - exp(-updateIntervalMs.toDouble() / smoothingTimeConstantMs.toDouble())
}

/**
 * 맥락 가중 K. 1.00에 해당하는 가중을 더하고 [kMax]로 자른다. (REQ_FAT_013~018)
 *
 * 맥락은 졸음 판정이 아니라 민감도만 바꾼다(기획서 4-1). 조향 신호가 없어 B = 0이면 K와 상관없이 R = 0이다(REQ_FAT_019).
 */
data class ContextWeightConfig(
    val kBase: Float = 1.00f,
    /** K 상한. 가중 5개를 모두 더하면 정확히 이 값 (REQ_FAT_013) */
    val kMax: Float = 1.35f,

    /** 연속 운전 시간(앱 타이머)이 이 이상이면 [longDriveWeight]. 119 min → 0, 120 min → +0.10 (REQ_FAT_014) */
    val longDriveMs: Long = 2 * 3_600_000L,
    val longDriveWeight: Float = 0.10f,

    /** 취약 시간대면 [vulnerableTimeWeight] (REQ_FAT_015) */
    val vulnerableTimeWeight: Float = 0.10f,
    /** 기본 취약 시간대: 새벽 2~7시, 오후 2~5시 */
    val vulnerableWindows: List<HourWindow> = listOf(HourWindow(2, 7), HourWindow(14, 17)),
    /** 연령대를 설정하면 기본 시간대에 더하는 시간대 (도로교통공단 자료, 기획서 1장) */
    val vulnerableWindowsByAge: Map<AgeGroup, List<HourWindow>> = mapOf(
        AgeGroup.FIFTIES_PLUS to listOf(HourWindow(14, 18)),
        AgeGroup.THIRTIES_FORTIES to listOf(HourWindow(22, 2)),
        AgeGroup.TWENTIES to listOf(HourWindow(4, 8)),
    ),

    /** 혼자 운전(조수석·뒷좌석 착석 없음)이면. 착석 값 null이면 더하지 않음 (REQ_FAT_016) */
    val soloDriveWeight: Float = 0.05f,

    /** 실내 온도가 이 이상이면 [warmCabinWeight]. 24.9 → 0, 25.0 → +0.05, null → 0 (REQ_FAT_017) */
    val warmCabinMinC: Float = 25f,
    val warmCabinWeight: Float = 0.05f,

    /** 최근 24시간 누적 운전이 이 이상이면 [longDayDriveWeight]. 239 min → 0, 240 min → +0.05 (REQ_FAT_018) */
    val longDayDriveMs: Long = 4 * 3_600_000L,
    val longDayDriveWeight: Float = 0.05f,
)
