package com.sleepissue.awakedrive.engine.model

// 하루 중 시간 구간. 분(0~1439)으로 표현하고 끝은 포함하지 않는다 (07:00 → 취약 아님, SRS F60).
// 자정을 넘는 구간(예: 22:00~02:00)은 start > end로 표현한다.
// 예보(4번)와 맥락 가중 K(2번)가 같은 정의를 쓰도록 model에 둔다.
data class VulnerableTimeRange(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
) {
    init {
        require(startMinuteOfDay in 0 until MINUTES_PER_DAY && endMinuteOfDay in 0 until MINUTES_PER_DAY)
    }

    fun contains(minuteOfDay: Int): Boolean =
        if (startMinuteOfDay <= endMinuteOfDay) minuteOfDay in startMinuteOfDay until endMinuteOfDay
        else minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        fun hours(startHour: Int, endHour: Int) =
            VulnerableTimeRange(startHour * 60 % MINUTES_PER_DAY, endHour * 60 % MINUTES_PER_DAY)
    }
}

// 설정 화면의 연령대. 설정하면 연령대별 취약 시간대가 기본 시간대에 더해진다 (기획서 4-5 맥락 가중 표, REQ_FAT_015).
enum class AgeBand { TWENTIES, THIRTIES_FORTIES, FIFTIES_PLUS }
