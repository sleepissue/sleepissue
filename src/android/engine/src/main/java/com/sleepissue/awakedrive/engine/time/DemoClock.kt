package com.sleepissue.awakedrive.engine.time

import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import kotlin.math.floor

data class DemoClockSnapshot(
    val scenarioTimeMs: Long,
    val realElapsedMs: Long,
    val segmentMultiplier: Int,
    val speedMultiplier: Int,
)

/**
 * 이 인스턴스의 운행 시각만 배속한다. 실제 경과·기기 날짜는 주입 시계에 그대로 위임한다.
 * 상대 시각을 되돌리는 load/start/reset 전에는 소비 Hub를 중지하고 새 세션으로 시작해야 한다.
 * 구간 사이에 남은 실제 시간을 다음 구간의 배속으로 적분하므로 늦게 깬 tick도 경계를 넘기지 않는다.
 */
class DemoClock(private val realClock: Clock) : Clock {
    companion object {
        /** 정수 ms 정밀도와 산술 범위를 보호하는 기술적 상한이다. */
        const val MAX_DURATION_MS: Long = 1L shl 50
    }
    private var durationMs = 0L
    private var segments = emptyList<DemoSpeedSegment>()
    private var positionMs = 0.0
    private var lastElapsedMs = realClock.elapsedRealtimeMs()
    private var startedAtMs: Long? = null
    private var endedElapsedMs: Long? = null
    private var running = false
    private var questionActive = false

    @Synchronized
    fun configure(durationMs: Long, segments: List<DemoSpeedSegment>) {
        // Double 적분의 정수 ms 정밀도가 유지되는 범위다. 실제 시나리오는 수 시간 규모다.
        require(durationMs in 1..MAX_DURATION_MS) { "지원하지 않는 시나리오 길이입니다." }
        require(segments.isNotEmpty() && segments.first().fromMs == 0L && segments.last().toMs == durationMs)
        require(segments.zipWithNext().all { (a, b) -> a.toMs == b.fromMs }) { "배속 구간은 빈틈 없이 이어져야 합니다." }
        this.durationMs = durationMs
        this.segments = segments.toList()
        resetPosition()
    }

    @Synchronized
    fun start() {
        check(segments.isNotEmpty()) { "시나리오를 먼저 구성해야 합니다." }
        resetPosition()
        startedAtMs = lastElapsedMs
        running = true
    }

    @Synchronized
    fun pause() { synchronize(); running = false }

    @Synchronized
    fun resume() {
        check(startedAtMs != null && endedElapsedMs == null && positionMs < durationMs)
        synchronize()
        running = true
    }

    @Synchronized
    fun stop() {
        synchronize()
        running = false
        questionActive = false
        if (startedAtMs != null && endedElapsedMs == null) endedElapsedMs = lastElapsedMs
    }

    /** 단조 시계 자체가 실패했을 때 마지막 안전 위치에서 정지한다. 새 시각을 읽지 않는다. */
    @Synchronized
    fun halt() {
        running = false
        questionActive = false
        if (startedAtMs != null && endedElapsedMs == null) endedElapsedMs = lastElapsedMs
    }

    @Synchronized
    fun reset() {
        durationMs = 0
        segments = emptyList()
        resetPosition()
    }

    @Synchronized
    fun setQuestionActive(active: Boolean) {
        synchronize()
        questionActive = active
    }

    @Synchronized
    fun snapshot(): DemoClockSnapshot {
        synchronize()
        val position = floor(positionMs + 0.000001).toLong().coerceAtMost(durationMs)
        val factor = segmentAt(positionMs)?.factor ?: segments.lastOrNull()?.factor ?: 1
        val real = startedAtMs?.let { (endedElapsedMs ?: lastElapsedMs) - it } ?: 0L
        return DemoClockSnapshot(position, real, factor, if (running && position < durationMs) {
            if (questionActive) 1 else factor
        } else 0)
    }

    override fun scenarioTimeMs(): Long = snapshot().scenarioTimeMs
    override fun elapsedRealtimeMs(): Long = realClock.elapsedRealtimeMs()
    override fun wallTimeMs(): Long = realClock.wallTimeMs()

    private fun resetPosition() {
        positionMs = 0.0
        lastElapsedMs = realClock.elapsedRealtimeMs()
        require(lastElapsedMs >= 0)
        startedAtMs = null
        endedElapsedMs = null
        running = false
        questionActive = false
    }

    private fun segmentAt(position: Double) = segments.firstOrNull { position < it.toMs }

    private fun synchronize() {
        val now = realClock.elapsedRealtimeMs()
        check(now >= lastElapsedMs) { "실제 단조 경과 시계가 역행했습니다." }
        var remainingMs = (now - lastElapsedMs).toDouble()
        lastElapsedMs = now
        if (!running) return
        while (remainingMs > 0 && positionMs < durationMs) {
            val segment = requireNotNull(segmentAt(positionMs))
            val factor = if (questionActive) 1 else segment.factor
            val untilBoundaryMs = (segment.toMs - positionMs) / factor
            if (remainingMs >= untilBoundaryMs) {
                positionMs = segment.toMs.toDouble()
                remainingMs -= untilBoundaryMs
            } else {
                positionMs += remainingMs * factor
                remainingMs = 0.0
            }
        }
        if (positionMs >= durationMs) {
            running = false
            // 마지막 프레임 뒤 시나리오 끝에 도달한 실제 시간까지만 센다.
            if (endedElapsedMs == null) endedElapsedMs = now - floor(remainingMs).toLong()
        }
    }
}
