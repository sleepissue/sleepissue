package com.sleepissue.awakedrive.engine.time

/** 실제 대기 없이 논리 시각만 별도로 이동할 수 있는 시험용 시계다. */
class FakeClock(
    var scenarioMs: Long = 0,
    var elapsedMs: Long = 0,
    var wallMs: Long = 0,
) : Clock {
    override fun scenarioTimeMs(): Long = scenarioMs
    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun wallTimeMs(): Long = wallMs
}
