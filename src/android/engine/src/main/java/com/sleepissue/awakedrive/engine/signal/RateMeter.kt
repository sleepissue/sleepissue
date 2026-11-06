package com.sleepissue.awakedrive.engine.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.ObservationTimeBasis
import java.util.ArrayDeque

enum class RateStatus { NOT_STARTED, WARMING_UP, MEASURED, UNMEASURABLE, INTERRUPTED }
enum class RateObservationResult { ACCEPTED, DUPLICATE, REVERSED, INVALID_TIME_BASIS, INACTIVE, BEFORE_START }

/** belowMinimum은 2번 담당에게 보내는 입력 품질이다. 지표 비활성·가중치 계산은 수행하지 않는다. */
data class SourceRate(
    val status: RateStatus = RateStatus.NOT_STARTED,
    val timeBasis: ObservationTimeBasis = ObservationTimeBasis.UNKNOWN,
    val hz: Float? = null,
    val belowMinimum: Boolean? = null,
    val observationsInWindow: Int = 0,
    val acceptedObservations: Long = 0,
    val duplicateObservations: Long = 0,
    val reversedObservations: Long = 0,
)

/**
 * 원천 시각의 (now - window, now]를 센다. 시작 후 온전한 창 전에는 Hz를 확정하지 않는다.
 * 같은 시각은 중복, 역행·시계 변경은 재시작 전까지 측정 불가다. WALL/UNKNOWN은 Hz 근거가 아니다.
 * 활성 창에 관측이 없으면 측정한 0Hz, 수집 중단이면 INTERRUPTED/null이다.
 */
class RateMeter(private val config: EngineConfig = EngineConfig()) {
    private val times = ArrayDeque<Long>()
    private var startedAt: Long? = null
    private var basis = ObservationTimeBasis.UNKNOWN
    private var lastObservation: Long? = null
    private var lastNow: Long? = null
    private var active = false
    private var invalid = false
    private var accepted = 0L
    private var duplicates = 0L
    private var reversed = 0L

    fun start(atMs: Long, timeBasis: ObservationTimeBasis) {
        require(atMs >= 0)
        times.clear(); startedAt = atMs; basis = timeBasis; lastObservation = null; lastNow = atMs
        active = true; invalid = timeBasis !in setOf(ObservationTimeBasis.SCENARIO, ObservationTimeBasis.ELAPSED_REALTIME)
        accepted = 0; duplicates = 0; reversed = 0
    }

    fun stop() { active = false; times.clear() }

    fun observe(atMs: Long?, timeBasis: ObservationTimeBasis, nowMs: Long): RateObservationResult {
        if (!active) return RateObservationResult.INACTIVE
        if (atMs == null || timeBasis != basis || timeBasis !in setOf(ObservationTimeBasis.SCENARIO, ObservationTimeBasis.ELAPSED_REALTIME)) {
            invalid = true
            return RateObservationResult.INVALID_TIME_BASIS
        }
        // 구독 직전 생성되어 늦게 도착한 최초 자료는 감시한 창 밖이다. 이후 정상 관측을 막지 않는다.
        if (atMs < requireNotNull(startedAt)) return RateObservationResult.BEFORE_START
        if (atMs > nowMs || lastObservation?.let { atMs < it } == true ||
            lastNow?.let { nowMs < it } == true) {
            invalid = true; reversed++
            return RateObservationResult.REVERSED
        }
        lastNow = nowMs
        if (atMs == lastObservation) { duplicates++; return RateObservationResult.DUPLICATE }
        lastObservation = atMs
        accepted++
        times.addLast(atMs)
        prune(nowMs)
        return RateObservationResult.ACCEPTED
    }

    fun sample(nowMs: Long): SourceRate {
        val start = startedAt ?: return SourceRate()
        if (lastNow?.let { nowMs < it } == true) { invalid = true; reversed++ }
        lastNow = nowMs
        prune(nowMs)
        val status = when {
            !active -> RateStatus.INTERRUPTED
            invalid -> RateStatus.UNMEASURABLE
            nowMs - start < config.steeringRateWindowMs -> RateStatus.WARMING_UP
            else -> RateStatus.MEASURED
        }
        val hz = if (status == RateStatus.MEASURED) times.size * 1_000f / config.steeringRateWindowMs else null
        return SourceRate(status, basis, hz, hz?.let { it < config.minimumSteeringRateHz }, times.size,
            accepted, duplicates, reversed)
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - config.steeringRateWindowMs
        while (times.isNotEmpty() && times.first <= cutoff) times.removeFirst()
    }
}
