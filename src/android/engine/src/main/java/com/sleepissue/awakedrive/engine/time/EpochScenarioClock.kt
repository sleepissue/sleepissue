package com.sleepissue.awakedrive.engine.time

/** 예보에만 쓰는 epoch ↔ JSON 상대 ms 변환이다. Hub/운행의 상대 시계는 바꾸지 않는다. */
class ScenarioEpochMapping(val startEpochMs: Long) {
    init { require(startEpochMs >= 0) }
    fun toEpochMs(relativeMs: Long): Long {
        require(relativeMs >= 0)
        return Math.addExact(startEpochMs, relativeMs)
    }
    fun toRelativeMs(epochMs: Long): Long {
        require(epochMs >= startEpochMs) { "시나리오 시작 전 epoch 시각입니다." }
        return Math.subtractExact(epochMs, startEpochMs)
    }
}

/** ForecastEngine이 Instant로 해석하는 scenarioTimeMs만 epoch로 투영한다. */
class EpochScenarioClock(private val relativeClock: Clock, val mapping: ScenarioEpochMapping) : Clock {
    override fun scenarioTimeMs() = mapping.toEpochMs(relativeClock.scenarioTimeMs())
    override fun elapsedRealtimeMs() = relativeClock.elapsedRealtimeMs()
    override fun wallTimeMs() = relativeClock.wallTimeMs()
}
