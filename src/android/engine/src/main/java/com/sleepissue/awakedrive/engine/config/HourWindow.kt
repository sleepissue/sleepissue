package com.sleepissue.awakedrive.engine.config

/**
 * 하루 중 시간대. 시작 시각은 포함하고 끝 시각은 뺀다. [해석] (REQ_FAT_015 비고)
 *
 * 예: HourWindow(2, 7)은 02:00~06:59. 시작이 끝보다 크면 자정을 넘는 구간이다(예: HourWindow(22, 2)는 22:00~01:59).
 */
data class HourWindow(val startHour: Int, val endHour: Int) {
    init {
        require(startHour in 0..23 && endHour in 0..23) { "시각은 0~23: $startHour, $endHour" }
        require(startHour != endHour) { "시작과 끝이 같으면 구간이 없음: $startHour" }
    }

    /** [hourOfDay](0~23)가 이 구간에 들어가는지. */
    fun contains(hourOfDay: Int): Boolean =
        if (startHour < endHour) {
            hourOfDay in startHour until endHour
        } else {
            hourOfDay >= startHour || hourOfDay < endHour
        }
}
