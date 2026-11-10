package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.signal.HubTimingPoint

/** SDK 1배속 원인 분석용 고정 상한 메모리다. 입력/emit 스레드에서 파일을 쓰지 않는다. */
internal class DebugHubTimingRecorder(private val capacity: Int = 60_000) {
    private val points = ArrayList<HubTimingPoint>()
    private var dropped = 0L
    @Synchronized fun record(point: HubTimingPoint) {
        if (points.size < capacity) points.add(point.copy(nativeThreadId = android.os.Process.myTid())) else dropped++
    }
    @Synchronized fun snapshot(includePoints: Boolean): Map<String, Any> = linkedMapOf<String, Any>(
        "pointsRecorded" to points.size, "pointsDropped" to dropped,
    ).apply { if (includePoints) put("points", points.toList()) }
}
