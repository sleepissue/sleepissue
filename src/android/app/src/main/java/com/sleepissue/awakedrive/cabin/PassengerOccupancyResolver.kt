package com.sleepissue.awakedrive.cabin

import com.sleepissue.awakedrive.engine.check.OccupancyContext
import com.sleepissue.awakedrive.engine.model.SeatOccupancy
import com.sleepissue.awakedrive.signal.SeatBeltState

/**
 * 좌석 착석과 안전벨트 체결을 합쳐 음성 확인 대상을 고른다.
 * 한 신호라도 true면 동승자가 있다고 보되 false는 다른 신호의 true를 덮지 않는다.
 * 두 신호가 모두 null이면 '없음'이 아니라 '확인 불가'이므로 보수적으로 운전자 확인으로 간다.
 */
object PassengerOccupancyResolver {
    fun resolve(
        seats: SeatOccupancy?,
        belts: SeatBeltState?,
        speechDetected: Boolean,
        microphoneAvailable: Boolean,
    ): OccupancyContext = OccupancyContext(
        frontPassengerPresent = present(seats?.frontPassenger, belts?.frontPassenger),
        rearPassengerPresent = listOf(
            present(seats?.rearLeft, belts?.rearLeft),
            present(seats?.rearCenter, belts?.rearCenter),
            present(seats?.rearRight, belts?.rearRight),
        ).any { it },
        speechDetected = speechDetected,
        microphoneAvailable = microphoneAvailable,
    )

    private fun present(occupancy: Boolean?, buckled: Boolean?): Boolean =
        occupancy == true || buckled == true
}
