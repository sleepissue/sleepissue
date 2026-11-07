package com.sleepissue.awakedrive.engine.signal

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.Clock

enum class MissingObservationPolicy { REQUIRE_TIMESTAMP, USE_RECEIPT_AGE }

/** 미확정 문턱이므로 제품 기본값을 두지 않는다. 정확히 경계인 관측은 유효하다. */
data class SignalFreshnessPolicy(val observationMaxAgeMs: Long, val receiptMaxAgeMs: Long,
    val missingObservation: MissingObservationPolicy) {
    init { require(observationMaxAgeMs >= 0 && receiptMaxAgeMs >= 0) }
}

/** REAL이 무효해진 첫 평가부터 holdMs 동안 null을 유지한 후 DEMO 대체를 허용할지 선택한다. */
data class SignalFallbackPolicy(val allowDemo: Boolean, val realLossHoldMs: Long, val timerBasis: ObservationTimeBasis) {
    init {
        require(realLossHoldMs >= 0)
        require(timerBasis in setOf(ObservationTimeBasis.SCENARIO, ObservationTimeBasis.ELAPSED_REALTIME))
    }
}

data class SignalCandidate<T>(val value: T?, val quality: SignalQuality)

fun Clock.timeAt(basis: ObservationTimeBasis): Long? = when (basis) {
    ObservationTimeBasis.SCENARIO -> scenarioTimeMs()
    ObservationTimeBasis.ELAPSED_REALTIME -> elapsedRealtimeMs()
    ObservationTimeBasis.WALL_TIME -> wallTimeMs()
    ObservationTimeBasis.UNKNOWN -> null
}

fun <T> SignalCandidate<T>.freshness(policy: SignalFreshnessPolicy, clock: Clock): SignalCandidate<T> {
    var availability = quality.availability
    if (availability == SignalAvailability.AVAILABLE) {
        val received = quality.receivedAtElapsedMs
        val observed = quality.observedAtMs
        val now = clock.timeAt(quality.observedTimeBasis)
        availability = when {
            value == null -> SignalAvailability.UNAVAILABLE
            received == null -> SignalAvailability.UNAVAILABLE
            clock.elapsedRealtimeMs() < received -> SignalAvailability.ERROR
            clock.elapsedRealtimeMs() - received > policy.receiptMaxAgeMs -> SignalAvailability.STALE
            observed != null && now != null && now < observed -> SignalAvailability.ERROR
            observed != null && now != null && now - observed > policy.observationMaxAgeMs -> SignalAvailability.STALE
            (observed == null || now == null) && policy.missingObservation == MissingObservationPolicy.REQUIRE_TIMESTAMP -> SignalAvailability.UNAVAILABLE
            else -> SignalAvailability.AVAILABLE
        }
    }
    return copy(value = value.takeIf { availability == SignalAvailability.AVAILABLE }, quality = quality.copy(availability = availability))
}

/** 한 필드/좌표 쌍의 선택기다. REAL 유지 시각은 필드마다 독립적이며 새 Hub 세션에서 폐기한다. */
class SignalSelector(private val fallback: SignalFallbackPolicy) {
    private var hadReal = false
    private var realLostAt: Long? = null
    private var lastNow: Long? = null

    fun <T> select(real: SignalCandidate<T>?, demo: SignalCandidate<T>?, nowMs: Long,
        demoLocationFirst: Boolean = false): SignalCandidate<T> {
        require(lastNow == null || nowMs >= requireNotNull(lastNow)) { "중재 시계가 역행했습니다." }
        lastNow = nowMs
        fun SignalCandidate<T>?.valid() = this?.quality?.availability == SignalAvailability.AVAILABLE && this.value != null
        fun selected(value: SignalCandidate<T>, reason: SignalSelectionReason) =
            value.copy(quality = value.quality.copy(selectionReason = reason))
        if (demoLocationFirst && demo.valid()) return selected(requireNotNull(demo), SignalSelectionReason.DEMO_LOCATION)
        if (real.valid()) {
            hadReal = true; realLostAt = null
            return selected(requireNotNull(real), if (demoLocationFirst) SignalSelectionReason.REAL_LOCATION else SignalSelectionReason.REAL_FRESH)
        }
        if (hadReal && realLostAt == null) realLostAt = nowMs
        if (!demoLocationFirst && realLostAt?.let { nowMs - it < fallback.realLossHoldMs } == true) {
            val q = real?.quality ?: SignalQuality()
            return SignalCandidate(null, q.copy(availability = if (q.availability == SignalAvailability.AVAILABLE) SignalAvailability.UNAVAILABLE else q.availability,
                selectionReason = SignalSelectionReason.REAL_LOSS_HOLD))
        }
        if ((fallback.allowDemo || demoLocationFirst) && demo.valid()) return selected(requireNotNull(demo), SignalSelectionReason.DEMO_FALLBACK)
        val unavailable = real ?: demo ?: SignalCandidate<T>(null, SignalQuality())
        return SignalCandidate(null, unavailable.quality.copy(availability = if (unavailable.quality.availability == SignalAvailability.AVAILABLE)
            SignalAvailability.UNAVAILABLE else unavailable.quality.availability, selectionReason = SignalSelectionReason.NO_VALID_INPUT))
    }
}
