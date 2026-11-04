package com.sleepissue.awakedrive.engine.forecast

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.engine.model.VulnerableTimeRange
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

// [4번] 예보 엔진 (FCS) — 간트차트 id=40, 2026-11-03 ~ 11-04
// 출발 전·운행 중에 "언제쯤 졸릴지"를 연속 운전 시간 기준으로 계산해 TTS 문구로 돌려준다 (기획서 3장 A, 4-7).
// 순수 Kotlin(:engine) 모듈이므로 Android·Pleos SDK를 import하지 않는다.
// 내비 목적지·TTS 호출은 app 쪽(NaviBridge·VoiceAgent)이 하고, 여기엔 정규화된 숫자만 들어온다.
// ROS로 치면 입력 토픽을 받아 결과 하나를 내는 순수 함수 노드 — 상태를 들고 있지 않아 몇 번 불러도 결과가 같다.
//
// SRS 범위: REQ_FCS_001 ~ 006, 008
// REQ_FCS_007(예상 시점 10~20분 전 쉼터를 휴식 후보로 지정)은 2026-11-02 D-1 결정으로 삭제.
//  → 예보는 "시각"만 알리고, 쉼터는 1단계·3단계 진입 때 그 위치 기준으로 실시간 안내한다
//    (REQ_INT_014·REQ_INT_010, RestAreaRepository/NaviBridge 담당). 그래서 ForecastResult에 쉼터 필드가 없다.

// REQ_FCS_001·004: 출발 전 컨디션 입력 (선택 사항, 기획서 5-1 [개운해요][보통][졸려요])
enum class StartCondition {
    NONE,   // 입력 안 함
    FRESH,  // '개운해요'
    NORMAL, // '보통'
    DROWSY, // '졸려요' → REQ_FCS_004 절반 규칙 적용
}

// REQ_FCS_001: 예보를 다시 계산해야 하는 6가지 시점. 호출 주체는 TripStateStore(1번).
// 계산 자체는 시점과 무관하고, 결과에 그대로 실어 로그·화면에서 "왜 다시 계산했는지" 보이게 한다.
enum class ForecastRecalcTrigger {
    DESTINATION_SET,        // 목적지 설정
    TRIP_START,             // 운행 시작(D단)
    CONDITION_INPUT,        // 컨디션 입력
    START_FATIGUE_DETECTED, // 출발 피로 판정 (2번 FatigueEngine)
    REST_VERIFIED,          // 휴식 인증 뒤 (RestSession) — 연속 운전 시간이 0으로 초기화된 상태로 들어옴
    DRIVING_RESUMED,        // 연속 운전 시간이 멈췄다가 다시 갈 때
}

// 기본 시점에서 어떤 규칙이 적용됐는지. 테스트·디버그 화면에서 계산 과정을 확인하는 용도.
enum class ForecastAdjustment {
    CUMULATIVE_ADVANCE, // REQ_FCS_003 최근 24시간 누적 4시간 이상 → 30분 앞당김
    DROWSY_HALVED,      // REQ_FCS_004 '졸려요'·출발 피로 → 절반
    DROWSY_FLOOR,       // REQ_FCS_004 절반 결과가 30분 미만 → 30분
    VULNERABLE_EARLIER, // REQ_FCS_005 취약 시간대 진입 시각이 더 이름 → 그 시각 사용
    VULNERABLE_FLOOR,   // REQ_FCS_005 이미 취약 시간대 안 → 최소 30분
}

data class ForecastInput(
    // 현재 연속 운전 시간(분). 출발 전이면 0. 휴식 인증 뒤엔 0부터 다시 센다 (TripStateStore 제공).
    val continuousDrivingMin: Int,

    // 목적지까지 남은 시간(분) (NaviHelper.getDestinationInfo → NaviBridge가 변환).
    // null = 목적지 없음 또는 내비 앱 없음 → REQ_FCS_008 대체 경로. 예보 계산 자체는 이 값 없이도 된다.
    val remainingTimeToDestinationMin: Int?,

    // 최근 24시간 누적 운전 시간(분) → REQ_FCS_003
    val last24hCumulativeDrivingMin: Int,

    // 지난 운행들에서 "운전 시작 후 몇 분째에 2단계가 처음 왔는지" 기록. 최근 운행이 앞에 온다 (Room 저장값).
    // 2단계가 한 번도 안 온 운행은 넣지 않는다 → REQ_FCS_002
    val onsetHistoryMin: List<Int>,

    // 출발 컨디션 → REQ_FCS_004
    val startCondition: StartCondition = StartCondition.NONE,

    // 출발 피로 판정 결과 (2번 FatigueEngine, 기획서 5-1) → REQ_FCS_004
    val startFatigueDetected: Boolean = false,

    // 설정의 연령대. null이면 기본 취약 시간대만 쓴다 → REQ_FCS_005
    val ageBand: AgeBand? = null,
)

data class ForecastResult(
    // 예상 졸음 시각 (epoch ms)
    val expectedDrowsyAtMs: Long,

    // 지금부터 예상 시각까지 남은 분
    val remainingMin: Int,

    // 취약 시간대 비교 전, 규칙을 적용한 개인 졸음 시점 X (연속 운전 X분째)
    val onsetMin: Int,

    // REQ_FCS_006 말투로 만든 시점 표현. 예: "40분쯤 뒤", "밤 11시쯤"
    val spokenTime: String,

    // TTS로 읽을 문장. 예: "밤 11시쯤 졸릴 수 있어요."
    // 실제 발화는 VoiceAgent(3번)가 ON_DEVICE 모드로 한다 (CLAUDE.md 6장).
    val ttsMessage: String,

    val adjustments: Set<ForecastAdjustment>,

    // 목적지 정보 없이 계산했는지 (REQ_FCS_008)
    val usedFallbackWithoutDestination: Boolean,

    // 예상 시각보다 목적지에 먼저 도착하는지. 목적지가 없으면 null.
    // [해석] SRS에 사용처가 없어 판단만 넘긴다. 예보 안내를 생략할지는 호출하는 쪽에서 정한다.
    val arrivesBeforeExpected: Boolean?,

    val trigger: ForecastRecalcTrigger,
)

class ForecastEngine(
    private val clock: Clock,
    private val config: EngineConfig = EngineConfig(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {

    // REQ_FCS_001: 예상 졸음 시점을 계산한다. [ForecastRecalcTrigger] 6가지 시점마다 다시 호출된다.
    // 적용 순서: 개인 졸음 시점 → −30분(누적) → 절반(졸려요·출발 피로) → 취약 시간대 (SRS Q108 [해석])
    fun calculate(input: ForecastInput, trigger: ForecastRecalcTrigger): ForecastResult {
        val nowMs = clock.scenarioTimeMs()
        val adjustments = mutableSetOf<ForecastAdjustment>()

        // 1) REQ_FCS_002
        var onset = personalOnsetMin(input.onsetHistoryMin)

        // 2) REQ_FCS_003
        if (input.last24hCumulativeDrivingMin >= config.forecastCumulativeThresholdMin) {
            onset -= config.forecastCumulativeAdvanceMin
            adjustments += ForecastAdjustment.CUMULATIVE_ADVANCE
        }

        // 3) REQ_FCS_004
        if (input.startCondition == StartCondition.DROWSY || input.startFatigueDetected) {
            val halved = (onset * config.forecastDrowsyFactor).toInt() // 내림 — 더 이른 쪽으로
            adjustments += ForecastAdjustment.DROWSY_HALVED
            // [해석] 원래 값이 이미 30분보다 작으면 하한 때문에 오히려 늦어지지 않게 원래 값까지만 올린다.
            val floor = min(config.forecastDrowsyFloorMin, onset)
            if (halved < floor) adjustments += ForecastAdjustment.DROWSY_FLOOR
            onset = max(halved, floor)
        }
        onset = max(onset, 0)

        // 4) 남은 시간 = X − 현재 연속 운전 시간. 이미 지났으면 0(곧).
        val baseRemaining = max(onset - input.continuousDrivingMin, 0)
        var remaining = baseRemaining

        // 5) REQ_FCS_005: '지금 + 남은 시간' 안에 취약 시간대로 들어가면 더 이른 쪽
        val entryMs = firstVulnerableEntryMs(nowMs, nowMs + baseRemaining * MS_PER_MIN, rangesFor(input.ageBand))
        if (entryMs != null) {
            val entryRemaining = ((entryMs - nowMs) / MS_PER_MIN).toInt()
            val alreadyInside = entryMs == nowMs
            val candidate = if (alreadyInside) max(entryRemaining, config.forecastVulnerableFloorMin) else entryRemaining
            if (candidate < remaining) {
                remaining = candidate
                adjustments += ForecastAdjustment.VULNERABLE_EARLIER
                if (alreadyInside) adjustments += ForecastAdjustment.VULNERABLE_FLOOR
            }
        }

        val expectedAtMs = nowMs + remaining * MS_PER_MIN
        val spoken = spokenTime(remaining, expectedAtMs)

        // 6) REQ_FCS_008: 목적지가 없어도 위 계산은 그대로 끝난다. 도착 비교만 건너뛴다.
        val destination = input.remainingTimeToDestinationMin
        return ForecastResult(
            expectedDrowsyAtMs = expectedAtMs,
            remainingMin = remaining,
            onsetMin = onset,
            spokenTime = spoken,
            ttsMessage = "$spoken 졸릴 수 있어요.",
            adjustments = adjustments,
            usedFallbackWithoutDestination = destination == null,
            arrivesBeforeExpected = destination?.let { it < remaining },
            trigger = trigger,
        )
    }

    // REQ_FCS_002: 최근 10회 기록의 중앙값. 기록이 3회 미만이면 기본 120분.
    // 개수가 짝수면 가운데 두 값의 평균(내림 — 더 이른 쪽으로 틀리도록).
    // TODO(2번 협의): 이 기록을 BaselineStore(2번)가 관리할지, 4번이 Room에서 직접 읽을지 경계 확정
    internal fun personalOnsetMin(onsetHistoryMin: List<Int>): Int {
        val recent = onsetHistoryMin.take(config.forecastOnsetHistorySize)
        if (recent.size < config.forecastMinTripsForMedian) return config.forecastDefaultOnsetMin
        val sorted = recent.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun rangesFor(ageBand: AgeBand?): List<VulnerableTimeRange> =
        config.vulnerableBaseRanges + (ageBand?.let { config.vulnerableAgeRanges[it] } ?: emptyList())

    // [nowMs, untilMs] 안에서 취약 시간대에 들어가는 가장 이른 시각. 이미 안이면 nowMs, 없으면 null.
    private fun firstVulnerableEntryMs(nowMs: Long, untilMs: Long, ranges: List<VulnerableTimeRange>): Long? {
        if (ranges.isEmpty()) return null
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        if (ranges.any { it.contains(now.hour * 60 + now.minute) }) return nowMs

        val today = now.toLocalDate()
        val days = ((untilMs - nowMs) / MS_PER_DAY).toInt() + 1
        return (0..days).flatMap { d ->
            ranges.map { r ->
                today.plusDays(d.toLong()).atStartOfDay(zone).plusMinutes(r.startMinuteOfDay.toLong())
                    .toInstant().toEpochMilli()
            }
        }.filter { it in (nowMs + 1)..untilMs }.minOrNull()
    }

    // REQ_FCS_006: 남은 시간을 10분 단위로 반올림해 60분 미만이면 "n분쯤 뒤", 아니면 시각으로 말한다.
    // (59분 → 60분으로 반올림되므로 시각 표현, SRS Q110 [해석])
    private fun spokenTime(remainingMin: Int, expectedAtMs: Long): String {
        val rounded = roundHalfUp(remainingMin, config.forecastRoundMinutesUnit)
        if (rounded >= config.forecastSpeakAsClockFromMin) return clockPhrase(expectedAtMs)
        if (rounded == 0) return "곧" // [해석] "0분쯤 뒤" 대신
        return "${rounded}분쯤 뒤"
    }

    // 22:50 → "밤 11시쯤", 22:20 → "밤 10시 반쯤", 14:00 → "오후 2시쯤"
    private fun clockPhrase(atMs: Long): String {
        val t = Instant.ofEpochMilli(atMs).atZone(zone)
        val minuteOfDay = roundHalfUp(t.hour * 60 + t.minute, config.forecastRoundClockUnitMin) %
            VulnerableTimeRange.MINUTES_PER_DAY
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60

        // [해석] 자정(0시)은 '새벽 12시'보다 자연스러운 '밤 12시'로 말한다.
        val period = if (hour == 0) "밤" else config.forecastDayPeriods.last { it.first <= hour }.second
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        val minutePart = when (minute) {
            0 -> ""
            30 -> " 반"
            else -> " ${minute}분"
        }
        return "$period ${hour12}시${minutePart}쯤"
    }

    private fun roundHalfUp(value: Int, unit: Int): Int = (value + unit / 2) / unit * unit

    private companion object {
        const val MS_PER_MIN = 60_000L
        const val MS_PER_DAY = 24 * 60 * MS_PER_MIN
    }
}
