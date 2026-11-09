package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.time.Clock

/** 운행 날짜 축은 세션 시작 날짜에 단조 경과를 더한다. 실행 중 날짜 설정 변경으로 운전 시간이 뛰지 않는다. */
class AnchoredTripClock(private val device: Clock) : Clock {
    private val epochAnchor = device.wallTimeMs()
    private val elapsedAnchor = device.elapsedRealtimeMs()
    override fun scenarioTimeMs(): Long = Math.addExact(epochAnchor, Math.subtractExact(device.elapsedRealtimeMs(), elapsedAnchor))
    override fun elapsedRealtimeMs() = device.elapsedRealtimeMs()
    override fun wallTimeMs() = device.wallTimeMs()
}
