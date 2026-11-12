package com.sleepissue.awakedrive.engine.demo

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*

/** 데모에만 쓰는 명시적 과거 운행 가정. 카운터와 구간 근거를 함께 만든다. */
data class DemoTripPreset(val continuousDriveSec: Long, val last24hDriveSec: Long) {
    init { require(continuousDriveSec in 0..last24hDriveSec && last24hDriveSec <= 86_400) }

    fun seed(atMs: Long, tripId: String): TripRuleState {
        require(atMs >= last24hDriveSec * 1000)
        val start = atMs - continuousDriveSec * 1000
        val segments = if (last24hDriveSec == 0L) emptyList() else listOf(DriveSegment(atMs - last24hDriveSec * 1000, atMs))
        val trip = TripState(tripId = tripId, continuousDriveSec = continuousDriveSec,
            last24hDriveSec = last24hDriveSec, runStartMs = start, lastGear = Gear.D, driveState = DriveState.DRIVING)
        return TripRuleState(trip, driveSegments = segments, lastObservation = TripObservation(atMs, Gear.D, PowerState.ON))
    }
}
