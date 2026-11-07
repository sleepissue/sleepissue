package com.sleepissue.awakedrive.engine.config

import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.model.VulnerableTimeRange

/** 현재기능 기획서 6-1의 단계 점수다. 이 자료형은 단계 전환을 실행하지 않는다. */
data class StageThresholds(val caution: Int, val check: Int, val restGuidance: Int) {
    init {
        require(caution in 0..100 && check in 0..100 && restGuidance in 0..100) {
            "단계 점수는 0~100 범위여야 합니다."
        }
        require(caution < check && check < restGuidance) { "단계 점수는 오름차순이어야 합니다." }
    }
}

/**
 * 공통 계약과 운행·개입 규칙의 수치를 모은다.
 * 정차 보정은 호출자가 실행 계획의 정책을 명시적으로 선택할 때만 적용한다.
 * 미확정 최신성·혼합 출처 유지시간에는 임의 기본값을 두지 않는다.
 */
data class EngineConfig(
    val signalFrameIntervalMs: Long = 100,
    val stateSaveIntervalMs: Long = 10_000,
    val normalStageThresholds: StageThresholds = StageThresholds(40, 60, 80),
    val sensitiveStageThresholds: StageThresholds = StageThresholds(30, 50, 70),
    // 실행 계획 v0.3 9-2: P단·전원 OFF가 이어진 정차 구간의 경계다.
    val tripStopPauseMs: Long = 300_000,
    val tripRestResetMs: Long = 900_000,
    // 현재기능 기획서 7-2: 정확히 2시간은 같은 운행, 초과하면 새 운행이다.
    val newTripAfterOffMs: Long = 7_200_000,
    // 실행 계획 v0.3 9-3: 날짜가 아닌 최근 구간을 합산한다.
    val rollingDriveWindowMs: Long = 86_400_000,
    val interventionStepMs: Long = 30_000,
    val interventionLowScoreHoldMs: Long = 600_000,
    val normalDriveCautionSec: Long = 7_200,
    val sensitiveDriveCautionSec: Long = 3_600,
    val slowCheckCountForRest: Int = 2,
    val slowCheckRetryMs: Long = 30_000,
    val trendCheckRetryMs: Long = 600_000,
    val stageNoticeCooldownMs: Long = 600_000,
    val contextCautionReminderMs: Long = 1_800_000,
    val restGuidanceReminderMs: Long = 300_000,
    val waypointRefusalRetryMs: Long = 300_000,
    val waypointAutoAddGraceMs: Long = 10_000,
    val assistedCheckIntervalMs: Long = 1_200_000,
    val assistedRiskCheckIntervalMs: Long = 600_000,
    val assistedOperationGapSec: Long = 900, // [가설] 조작 공백은 2번이 계산해 전달한다.
    val restDistanceReminderM: Float = 2_000f,
    // 기존 위치 인자 계약을 보존하도록 새 설정은 뒤에 추가한다.
    val naviStateIntervalMs: Long = 1_000, // SRS Data Interface MSG-14의 출력 주기다.
    val steeringRateWindowMs: Long = 10_000,
    val minimumSteeringRateHz: Float = 5f, // [제안값] SRS SIG_009, 실제 SDK 실측 후 확정한다.
    val headingWindowMs: Long = 60_000, // SRS MSG-14: 최근 1분 위치로 진행 방향 계산.

    // 수치 출처: SRS v0.2 합격기준 + 기획서 v4 현재기능 4-5·4-7장. 위치 인자 호환을 위해 맨 뒤에 둔다.

    // ── 취약 시간대 (예보 REQ_FCS_005, 맥락 가중 K도 같은 값을 씀) ──────────

    // 기획서 4-5: 새벽 2~7시·오후 2~5시 (Horne & Reyner 1995, 도로교통공단)
    val vulnerableBaseRanges: List<VulnerableTimeRange> = listOf(
        VulnerableTimeRange.hours(2, 7),
        VulnerableTimeRange.hours(14, 17),
    ),

    // SRS REQ_FAT_015: 연령대 설정 시 추가되는 시간대
    val vulnerableAgeRanges: Map<AgeBand, List<VulnerableTimeRange>> = mapOf(
        AgeBand.TWENTIES to listOf(VulnerableTimeRange.hours(4, 8)),
        AgeBand.THIRTIES_FORTIES to listOf(VulnerableTimeRange.hours(22, 2)),
        AgeBand.FIFTIES_PLUS to listOf(VulnerableTimeRange.hours(14, 18)),
    ),

    // ── 예보 (ForecastEngine) ─────────────────────────────

    // REQ_FCS_002: 운행 기록이 3회 쌓이기 전에 쓰는 개인 졸음 시점 기본값 (연속 운전 X분째)
    val forecastDefaultOnsetMin: Int = 120,

    // REQ_FCS_002: 개인 졸음 시점을 중앙값으로 바꾸기 시작하는 최소 운행 횟수 (0~2회 → 기본값, 3회부터 중앙값)
    val forecastMinTripsForMedian: Int = 3,

    // REQ_FCS_002: 중앙값 계산에 쓰는 최근 운행 수 (CLAUDE.md 5장 "최근 10회")
    val forecastOnsetHistorySize: Int = 10,

    // REQ_FCS_003: 최근 24시간 누적 운전이 이 값 이상이면 예상 시점을 앞당긴다 (239분 → 그대로, 240분 → 앞당김)
    val forecastCumulativeThresholdMin: Int = 240,

    // REQ_FCS_003: 위 조건일 때 앞당기는 시간 (120 → 90분)
    val forecastCumulativeAdvanceMin: Int = 30,

    // REQ_FCS_004: '졸려요' 선택 또는 출발 피로 감지 시 예상 시점에 곱하는 비율 (120 → 60분)
    val forecastDrowsyFactor: Double = 0.5,

    // REQ_FCS_004: 위 절반 적용 후 하한 (50 → 30분)
    val forecastDrowsyFloorMin: Int = 30,

    // REQ_FCS_005: 출발 시점에 이미 취약 시간대이면 예상 시점의 하한
    val forecastVulnerableFloorMin: Int = 30, // [가설] SRS 합격기준에 [제안]으로 표시됨

    // REQ_FCS_006: 반올림한 남은 시간이 이 값 미만이면 "n분쯤 뒤", 이상이면 "밤 11시쯤"처럼 시각으로 말한다
    val forecastSpeakAsClockFromMin: Int = 60,

    // REQ_FCS_006: "n분쯤 뒤" 반올림 단위 (54분 → "50분쯤 뒤")
    val forecastRoundMinutesUnit: Int = 10, // [가설] SRS Q110 제안값

    // REQ_FCS_006: 시각 표현 반올림 단위 (22:50 → "밤 11시쯤", 22:20 → "밤 10시 반쯤")
    val forecastRoundClockUnitMin: Int = 30, // [가설] SRS Q110 제안값

    // REQ_FCS_006: 시각 앞에 붙는 말. (시작 시, 말) — 새벽 0~6, 오전 6~12, 오후 12~18, 저녁 18~21, 밤 21~24
    val forecastDayPeriods: List<Pair<Int, String>> = listOf( // [가설] SRS Q110 제안값
        0 to "새벽",
        6 to "오전",
        12 to "오후",
        18 to "저녁",
        21 to "밤",
    ),

    // ── 3번 음성 확인·실내 (CheckManager·VoiceAgent·SpeechAgent·CabinMonitor) ──
    // 단계 전환 문턱값(단계 점수·30초 승급·10분 재알림·10분 유지)은 위의 1번 값을 쓴다.
    val voiceSlowRatio: Double = 1.5,
    val voiceSlowAbsoluteMs: Long = 2_000L,
    val voiceResponseTimeoutMs: Long = 5_000L,
    val sttFinalizationGraceMs: Long = 500L, // [가설] ON_DEVICE 최종 결과 수신 대기
    val voiceRecheckDelayMs: Long = 30_000L,
    val defaultVoiceBaselineMs: Long = 1_000L, // [가설] 팀 측정값 확정 전 기본값
    val minimumTurnDistanceForCheckM: Float = 300f,
    val maximumCheckDeferralMs: Long = 20_000L,
    val fallbackDistanceStepM: Int = 5_000,
    val fallbackSoonThresholdM: Int = 2_500,
    val voicePreRollSilenceMs: Long = 180L,
    val voicePromptSettleMs: Long = 2_000L, // [가설] Pleos 에뮬레이터 TTS 지연 잔향 실측 제외
    val cabinSpeechSampleMs: Long = 10_000L,
    val cabinSpeechRmsThreshold: Double = 1_250.0, // [가설] Pleos 실측: 잔향 안정 후 무음 1,216 / 일반 발화 1,290~1,362 RMS
    val cabinMinimumSpeechFrames: Int = 3, // [가설] 짧은 잡음 제외용
    val voiceMinimumConsecutiveSpeechFrames: Int = 2, // [가설] '네' 발화 실측: 200 ms 연속, 단발 잡음 제외
    val voiceNoiseCalibrationFrames: Int = 3, // [가설] 신호음 후 150 ms 현재 소음 측정
    val voiceMinimumSpeechRms: Double = 300.0, // [가설] 저소음 환경 최소 문턱
    val voiceSpeechToNoiseRatio: Double = 1.08, // [가설] 배경 소음 대비 8% 상승
    val voiceMinimumRmsAboveNoise: Double = 100.0, // [가설] 배경 대비 최소 RMS 증가분
    val sttPcmBytesPerSecond: Long = 32_000L, // [가설] 공식 예시 16 kHz·16-bit·mono
    val rearPassengerMaximumWaitMs: Long = 180_000L,
    val conversationSilenceForDriverCheckMs: Long = 30_000L,
    val passengerTrendRecheckMs: Long = 600_000L,

    // ── 4번 앞쪽 쉼터 고르기 (RestAreaSelector, 기획서 10장) ──
    // REQ_RST_002: 현재 위치에서 이 거리 안의 쉼터만 후보 (39.9 km → 후보, 40.1 km → 제외). 단위 m (REQ_SIG_010)
    val restAreaSearchRadiusM: Float = 40_000f,
    // REQ_RST_002: 최근 1분 이동 방향과 쉼터 방위각의 차 허용치 (44.9° → 후보, 45.1° → 제외)
    val restAreaHeadingToleranceDeg: Float = 45f,
    // REQ_RST_003: 쉼터 차로 방위각과 차 진행 방향의 차가 이 값 안이면 같은 방향 차로
    // [가설] 근거: 전처리본에서 2 km 안 반대편 쌍 163개의 차로 방위각 차가 최소 149°·중앙 180° (2026-11-04)
    val restAreaCarriagewayToleranceDeg: Float = 90f,
    // REQ_RST_003: 차로 방향을 모르는 쉼터(순환선 등 전처리본 10곳)를 후보에 넣을지
    val restAreaIncludeUnknownDirection: Boolean = false, // [가설] 반대편 쉼터 안내를 막는 쪽

    // ── 4번 경유지 추가 (NaviBridge, REQ_RST_004·006) ──
    // addWaypoint는 결과를 돌려주지 않으므로 onWayPointInfo에 쉼터가 보일 때까지 기다리는 한도.
    // RST_006(실패 시 2 s 안 거리 안내)보다 짧아야 한다.
    val waypointConfirmTimeoutMs: Long = 1_500L, // [가설] 에뮬레이터 TC_RST_004에서 실측 후 조정
    // 내비가 돌려준 경유지 좌표가 쉼터에서 이 거리 안이면 같은 곳으로 본다 (poiId가 안 맞을 때 대비)
    val waypointMatchRadiusM: Float = 200f, // [가설]
    // 경유지 확인 중 getWaypointInfo를 다시 묻는 간격
    val waypointPollIntervalMs: Long = 250L, // [가설]

    // ── 4번 운전석 통풍 (ClimateController, 기획서 6장 통풍 규칙) ──
    // 켤 때 목표 단계. 차가 알려 준 최대 단계보다 크면 최대 단계로 줄인다
    val ventilationTargetLevel: Int = 3, // [가설] 에뮬레이터 범위 확인 후 조정
    // setHvacSeatVentilation 뒤 다시 읽어 반영을 기다리는 한도
    val ventilationVerifyTimeoutMs: Long = 2_000L, // [가설]
    // "통풍을 켜 보세요" 안내 뒤 운전자가 켰는지 기다리는 한도
    val ventilationManualWaitMs: Long = 30_000L, // [가설]
    // 반영 확인 중 상태를 다시 읽는 간격
    val ventilationPollIntervalMs: Long = 250L, // [가설]

    // ── 4번 휴식 인증 (RestCertifier, 기획서 7-1·7-3) ──
    // 쉼터·휴게소 반경. 이 안에서 P단(또는 시동 꺼짐)이어야 휴식으로 센다
    val restCertifyRadiusM: Float = 150f,
    // 휴식 인증에 필요한 연속 시간 (15분)
    val restCertifyMinMs: Long = 900_000L,

    // ── 4번 파워냅 (NapSession, 기획서 7-1) ──
    val napDurationMs: Long = 900_000L,
    // 알람 소리가 가장 작은 단계에서 최대까지 커지는 데 걸리는 시간
    val napAlarmRampMs: Long = 30_000L, // [가설]
    // 아무도 끄지 않을 때 알람을 멈추는 한도
    val napAlarmMaxMs: Long = 120_000L, // [가설]

    // ── 4번 보상 (RewardRules, 기획서 7-3) ──
    // 보상 대상 ①: 직전 휴식(또는 출발) 뒤 이만큼 운전한 뒤의 휴식
    val rewardMinDriveSec: Long = 7_200L,
    // 보상 대상 ②: 이 단계 이상 알림을 받은 뒤의 휴식 (2 = 확인 단계)
    val rewardMinStageLevel: Int = 2,
    // 악용 방지: 하루 최대 횟수
    val rewardDailyMax: Int = 4,
    // 악용 방지: 같은 쉼터 재인정 금지 시간 (2시간)
    val rewardSameAreaCooldownMs: Long = 7_200_000L,
    // 악용 방지: 직전 인정 쉼터에서 이만큼 떨어져야 함. [가정] 주행 거리 대신 쉼터 사이 직선 거리로 잰다(더 엄격한 쪽)
    val rewardMinMoveM: Float = 30_000f,
) {
    init {
        require(signalFrameIntervalMs > 0) { "신호 발행 간격은 양수여야 합니다." }
        require(naviStateIntervalMs >= signalFrameIntervalMs) { "내비 발행 간격은 차량 프레임 간격 이상이어야 합니다." }
        require(steeringRateWindowMs > 0 && minimumSteeringRateHz.isFinite() && minimumSteeringRateHz > 0) {
            "원천 주기 창과 기준 Hz는 양수여야 합니다."
        }
        require(headingWindowMs > 0)
        require(stateSaveIntervalMs > 0) { "상태 저장 간격은 양수여야 합니다." }
        require(tripStopPauseMs > 0 && tripStopPauseMs < tripRestResetMs) {
            "정차 일시 중단 경계는 양수이며 연속 시간 초기화 경계보다 작아야 합니다."
        }
        require(newTripAfterOffMs >= tripRestResetMs) {
            "새 운행의 OFF 경계는 연속 시간 초기화 경계 이상이어야 합니다."
        }
        require(rollingDriveWindowMs > 0) { "누적 운전 시간 창은 양수여야 합니다." }
        require(rollingDriveWindowMs >= tripStopPauseMs) {
            "잠정 정차 구간을 보관할 수 있도록 누적 창은 짧은 정차 경계 이상이어야 합니다."
        }
        require(listOf(interventionStepMs, interventionLowScoreHoldMs, normalDriveCautionSec,
            sensitiveDriveCautionSec, slowCheckRetryMs, trendCheckRetryMs, stageNoticeCooldownMs,
            contextCautionReminderMs, restGuidanceReminderMs, waypointRefusalRetryMs,
            waypointAutoAddGraceMs, assistedCheckIntervalMs, assistedRiskCheckIntervalMs,
            assistedOperationGapSec).all { it > 0 }) { "개입의 시간 경계는 양수여야 합니다." }
        require(slowCheckCountForRest > 0) { "느림 확인 횟수는 양수여야 합니다." }
        require(restDistanceReminderM.isFinite() && restDistanceReminderM > 0) { "쉼터 재안내 거리는 양수여야 합니다." }
        require(restAreaSearchRadiusM.isFinite() && restAreaSearchRadiusM > 0) { "쉼터 검색 반경은 양수여야 합니다." }
        require(restAreaHeadingToleranceDeg.isFinite() && restAreaHeadingToleranceDeg in 0f..180f) {
            "쉼터 방위각 허용치는 0~180도여야 합니다."
        }
        require(restAreaCarriagewayToleranceDeg.isFinite() && restAreaCarriagewayToleranceDeg in 0f..180f) {
            "차로 방향 허용치는 0~180도여야 합니다."
        }
        require(waypointConfirmTimeoutMs > 0) { "경유지 확인 대기 시간은 양수여야 합니다." }
        require(waypointMatchRadiusM.isFinite() && waypointMatchRadiusM > 0) { "경유지 일치 반경은 양수여야 합니다." }
        require(waypointPollIntervalMs in 1 until waypointConfirmTimeoutMs) { "경유지 재조회 간격은 확인 한도보다 짧아야 합니다." }
        require(ventilationTargetLevel > 0) { "통풍 목표 단계는 양수여야 합니다." }
        require(listOf(ventilationVerifyTimeoutMs, ventilationManualWaitMs, ventilationPollIntervalMs).all { it > 0 }) {
            "통풍 확인 시간은 양수여야 합니다."
        }
        require(restCertifyRadiusM.isFinite() && restCertifyRadiusM > 0) { "휴식 인증 반경은 양수여야 합니다." }
        require(restCertifyMinMs > 0) { "휴식 인증 시간은 양수여야 합니다." }
        require(napDurationMs > 0 && napAlarmRampMs > 0 && napAlarmMaxMs >= napAlarmRampMs) {
            "파워냅 시간은 양수이고 알람 한도는 커지는 시간 이상이어야 합니다."
        }
        require(rewardMinDriveSec > 0 && rewardMinStageLevel in 1..3 && rewardDailyMax > 0 &&
            rewardSameAreaCooldownMs > 0 && rewardMinMoveM.isFinite() && rewardMinMoveM >= 0) { "보상 조건 값이 잘못됐습니다." }
    }
}
