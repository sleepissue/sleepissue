package com.sleepissue.awakedrive.engine.config

import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.model.VulnerableTimeRange

// 기획서의 모든 숫자 문턱값을 모아 두는 곳 (CLAUDE.md 4장: 하드코딩 금지).
// 기획서상 초깃값이며 시연 데이터로 조정하므로, 값을 바꿀 땐 여기만 고친다.
// object가 아니라 기본값 있는 data class인 이유: 엔진에 생성자로 주입해서
// 테스트·시연 튜닝 때 EngineConfig(forecastDefaultOnsetMin = 90)처럼 일부만 바꿔 쓰기 위해.
//
// 지금은 예보(FCS)와 취약 시간대 값만 들어 있다. fatigue/pattern/assist/rest 등 다른 모듈 문턱값은
// 각 모듈 작업 때 아래에 섹션을 추가한다.
//
// 수치 출처: SRS v0.2 합격기준 + 기획서 v4 현재기능 4-5·4-7장.
data class EngineConfig(

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
)
