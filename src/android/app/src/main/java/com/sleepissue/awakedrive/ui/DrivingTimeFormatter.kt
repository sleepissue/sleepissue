package com.sleepissue.awakedrive.ui

/**
 * 초 단위 주행 시간을
 * 사용자에게 보여줄 문자열로 변환한다.
 */
fun formatDrivingTime(totalSeconds: Long): String {

    val safeSeconds = totalSeconds.coerceAtLeast(0L)

    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60

    return when {
        hours > 0 -> "${hours}시간 ${minutes}분"
        minutes > 0 -> "${minutes}분"
        else -> "0분"
    }
}