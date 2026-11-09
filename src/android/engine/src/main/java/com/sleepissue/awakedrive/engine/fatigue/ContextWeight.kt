package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.ContextWeightConfig
import com.sleepissue.awakedrive.engine.model.AgeBand

/**
 * 맥락 가중 K를 정하는 데 필요한 값만 모은 입력. (REQ_FAT_013~018)
 *
 * 1번의 TripState(연속·24시간 누적 운전)와 SignalFrame(착석·실내 온도), 설정(연령대)에서 만든다.
 * 변환 함수는 SignalAdapters.kt의 `contextInputOf`.
 * null은 "지금 이 값을 모른다"는 뜻이고, 모르는 항목은 가중을 더하지 않는다 (REQ_FAT_016·017 "null → +0").
 */
data class ContextInput(
    /** 앱 타이머 기준 연속 운전 시간(초). 1번 TripState.continuousDriveSec 그대로. 정차·시동 꺼짐 규칙은 1번이 적용한 값 (REQ_TRP_010) */
    val continuousDriveSec: Long? = null,
    /** 최근 24시간 누적 운전 시간(초). 1번 TripState.last24hDriveSec 그대로 (REQ_TRP_011) */
    val last24hDriveSec: Long? = null,
    /** 현지 시각을 자정부터 센 분(0~1439). 23:10이면 1390. 시간대(타임존) 변환은 앱 쪽에서 한다 */
    val minuteOfDay: Int? = null,
    /** 설정의 연령대 (MSG-12). null이면 설정 안 함 → 기본 취약 시간대만 씀. 4번 예보와 같은 [AgeBand] */
    val ageBand: AgeBand? = null,
    /** 조수석·뒷좌석에 앉은 사람 수. 착석 값을 하나라도 모르면 null (REQ_FAT_016) */
    val passengerCount: Int? = null,
    /** 실내 온도(℃) (REQ_FAT_017) */
    val cabinTempC: Float? = null,
) {
    init {
        require(continuousDriveSec == null || continuousDriveSec >= 0) { "연속 운전 시간은 0 이상: $continuousDriveSec" }
        require(last24hDriveSec == null || last24hDriveSec >= 0) { "24시간 누적 운전 시간은 0 이상: $last24hDriveSec" }
        require(minuteOfDay == null || minuteOfDay in 0 until MINUTES_PER_DAY) { "minuteOfDay는 0~1439: $minuteOfDay" }
        require(passengerCount == null || passengerCount >= 0) { "동승자 수는 0 이상: $passengerCount" }
    }
}

/**
 * 지금 켜진 맥락 가중 항목. (REQ_FAT_014~018)
 *
 * 1번이 1단계 안내 조건 중 '취약 시간대 진입'(REQ_INT_001)을 볼 때도 [vulnerableTime]을 쓰면
 * 취약 시간대 정의가 한 곳에만 있게 된다.
 */
data class ContextFactors(
    /** 연속 운전 2시간 이상 (REQ_FAT_014) */
    val longDrive: Boolean = false,
    /** 취약 시간대 (REQ_FAT_015) */
    val vulnerableTime: Boolean = false,
    /** 혼자 운전: 동승자 0명 (REQ_FAT_016) */
    val soloDrive: Boolean = false,
    /** 실내 25℃ 이상 (REQ_FAT_017) */
    val warmCabin: Boolean = false,
    /** 최근 24시간 누적 운전 4시간 이상 (REQ_FAT_018) */
    val longDayDrive: Boolean = false,
)

/**
 * 맥락 가중 K = 1.00 + 켜진 항목의 가중 합, 최대 1.35. (REQ_FAT_013)
 *
 * 맥락은 졸음 판정이 아니라 민감도만 바꾼다(기획서 4-1). K는 행동 점수 B에 곱하기만 하므로
 * B = 0이면 K가 아무리 커도 R = 0이다(REQ_FAT_019).
 */
object ContextWeight {

    /** 입력에서 켜진 항목을 고른다. 경계값은 모두 '이상'이라 경계에서 켜진다 (119 min → 꺼짐, 120 min → 켜짐). */
    fun factorsOf(input: ContextInput, config: ContextWeightConfig = ContextWeightConfig()): ContextFactors {
        val continuous = input.continuousDriveSec
        val minute = input.minuteOfDay
        val cabin = input.cabinTempC
        val last24h = input.last24hDriveSec
        return ContextFactors(
            longDrive = continuous != null && continuous >= config.longDriveSec,
            vulnerableTime = minute != null && isVulnerableTime(minute, input.ageBand, config),
            soloDrive = input.passengerCount == 0,
            warmCabin = cabin != null && cabin >= config.warmCabinMinC,
            longDayDrive = last24h != null && last24h >= config.longDayDriveSec,
        )
    }

    /** 켜진 항목의 가중을 1.00에 더하고 1.35로 자른다. */
    fun kOf(factors: ContextFactors, config: ContextWeightConfig = ContextWeightConfig()): Float {
        // Float를 여러 번 더하면 오차가 쌓이므로 Double로 더한 뒤 마지막에 바꾼다
        var k = config.kBase.toDouble()
        if (factors.longDrive) k += config.longDriveWeight
        if (factors.vulnerableTime) k += config.vulnerableTimeWeight
        if (factors.soloDrive) k += config.soloDriveWeight
        if (factors.warmCabin) k += config.warmCabinWeight
        if (factors.longDayDrive) k += config.longDayDriveWeight
        return minOf(k, config.kMax.toDouble()).toFloat()
    }

    /** 입력에서 바로 K를 구한다. */
    fun kOf(input: ContextInput, config: ContextWeightConfig = ContextWeightConfig()): Float =
        kOf(factorsOf(input, config), config)

    /**
     * 취약 시간대인지 (REQ_FAT_015). 기본 시간대(02~07시, 14~17시)에 설정한 연령대의 시간대를 더한다.
     * 시작 포함·끝 제외 [해석]: 01:59 → 아님, 02:00 → 맞음, 06:59 → 맞음, 07:00 → 아님.
     * 4번 예보(ForecastEngine)와 같은 [com.sleepissue.awakedrive.engine.model.VulnerableTimeRange] 정의를 쓴다.
     */
    fun isVulnerableTime(
        minuteOfDay: Int,
        ageBand: AgeBand?,
        config: ContextWeightConfig = ContextWeightConfig(),
    ): Boolean {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minuteOfDay는 0~1439: $minuteOfDay" }
        val ranges = config.vulnerableBaseRanges + (ageBand?.let { config.vulnerableAgeRanges[it] }.orEmpty())
        return ranges.any { it.contains(minuteOfDay) }
    }
}

private const val MINUTES_PER_DAY = 24 * 60
