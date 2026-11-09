package com.sleepissue.awakedrive.ui

import com.sleepissue.awakedrive.signal.RestAreaCandidate
import java.util.Locale

/**
 * 휴게소 정보를 AwakeDrive UI에 표시하기 위한 변환 함수.
 *
 * 실제 휴게소 검색은 수행하지 않는다.
 * 팀원이 검색한 결과를 화면용 문자열로 변환한다.
 */
fun formatRestAreaDistance(
    candidate: RestAreaCandidate?
): String {

    if (candidate == null) {
        return "정보 없음"
    }

    val distanceM = candidate.distanceM

    if (!distanceM.isFinite() || distanceM < 0f) {
        return "정보 없음"
    }

    return if (distanceM < 1000f) {
        "${distanceM.toInt()}m"
    } else {
        String.format(
            Locale.KOREA,
            "%.1fkm",
            distanceM / 1000f
        )
    }
}