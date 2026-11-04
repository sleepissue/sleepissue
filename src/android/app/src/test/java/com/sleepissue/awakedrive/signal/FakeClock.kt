package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.time.Clock

/** 세 시간축을 독립적으로 움직인다. 실제 대기나 시스템 시각을 읽지 않는다. */
class FakeClock(
    var scenarioMs: Long = 0L,
    var elapsedMs: Long = 0L,
    var wallMs: Long = 0L,
) : Clock {
    override fun scenarioTimeMs(): Long = scenarioMs
    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun wallTimeMs(): Long = wallMs
}
