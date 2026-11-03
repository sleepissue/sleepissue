package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.FatigueConfig

/**
 * 30초마다 내보내는 조향 지표 3개. (REQ_FAT_005~008)
 * FatigueEngine이 f값·위험 점수(REQ_FAT_009~)를 계산하는 재료다.
 */
data class SteeringIndicators(
    /** 이 지표를 계산한 시각(ms). 게이트 판정 시각 기준 */
    val tMs: Long,
    /** 미세 수정(0.5~3°) 분당 횟수, 최근 60초 창 */
    val microPerMin: Float,
    /** 큰 수정(5° 이상) 분당 횟수, 최근 60초 창 */
    val largePerMin: Float,
    /** 최근 5분 동안 멈춤 뒤 급조향 횟수 (분당 환산 안 함) */
    val suddenCount5m: Int,
    /** 최근 60초 창에서 게이트를 통과한 시간(ms) */
    val gatedMsInWindow: Long,
    /** 게이트 통과 시간이 충분해(30초 이상) 이 지표를 써도 되는지 (REQ_FAT_005 [제안값]) */
    val valid: Boolean,
)

/** 방향이 바뀐 조향 한 번의 크기 구분. */
internal enum class SwingClass { MICRO, LARGE, NONE }

/**
 * 극값 사이 크기로 미세·큰 수정을 나눈다. (REQ_FAT_006·007)
 * 0.5~3.0°(경계 포함) → 미세, 5.0° 이상 → 큰, 그 밖(0.5° 미만, 3.0° 초과~5.0° 미만) → 세지 않음.
 */
internal fun FatigueConfig.swingClassOf(amplitudeDeg: Double): SwingClass = when {
    amplitudeDeg >= microMinDeg - ANGLE_EPS_DEG && amplitudeDeg <= microMaxDeg + ANGLE_EPS_DEG -> SwingClass.MICRO
    amplitudeDeg >= largeMinDeg - ANGLE_EPS_DEG -> SwingClass.LARGE
    else -> SwingClass.NONE
}

/**
 * 게이트 판정을 받아 조향 지표를 센다. (REQ_FAT_005~008)
 *
 * - 게이트를 통과한 프레임만 쓴다. 통과 못 한 구간을 건너 각도를 잇지 않는다(가짜 조향 방지).
 * - 처음 판정에서 60초 뒤부터 30초마다 [SteeringIndicators]를 낸다. 300초 연속 통과면 60, 90, …, 300초에 9회.
 * - 분당 횟수 = 창 안 횟수 × 60초 ÷ 창 안 게이트 통과 시간. 통과 시간이 30초 미만이면 valid = false.
 *
 * ROS로 치면 게이트 노드의 출력을 구독해 30초 주기로 지표 토픽을 발행하는 노드다.
 */
class SteeringMetrics(private val config: FatigueConfig = FatigueConfig()) {

    private val reversal = SteeringReversalDetector(config.microMinDeg.toDouble())
    private val sudden = SuddenSteerDetector(config)

    private val microTimes = ArrayDeque<Long>()
    private val largeTimes = ArrayDeque<Long>()
    private val suddenTimes = ArrayDeque<Long>()

    /** 게이트를 통과한 시간 조각: (끝 시각, 길이) */
    private val gatedSlices = ArrayDeque<Pair<Long, Long>>()

    private var lastDecisionMs: Long? = null
    private var lastPassed = false
    private var nextUpdateMs: Long? = null

    // reset 뒤 누적값. 오늘의 나(유효 구간 처음 15분, REQ_BSL_004)를 만드는 재료다.
    /** 게이트를 통과한 시간 합(ms) */
    internal var gatedMsTotal = 0L
        private set

    /** 미세 수정 횟수 합 */
    internal var microTotal = 0
        private set

    /** 큰 수정 횟수 합 */
    internal var largeTotal = 0
        private set

    /** 멈춤 뒤 급조향 횟수 합 */
    internal var suddenTotal = 0
        private set

    /** 게이트 판정 하나를 넣는다. 30초 갱신 시각이 되면 지표를 돌려주고, 아니면 null. */
    fun accept(decision: GateDecision): SteeringIndicators? {
        val t = decision.tMs
        val previous = lastDecisionMs
        if (previous != null && t < previous) return null
        if (nextUpdateMs == null) nextUpdateMs = t + config.windowMs

        val angle = decision.steeringDeg
        if (decision.passed && angle != null) {
            if (previous != null && lastPassed) {
                val gap = t - previous
                if (gap <= config.gateMaxFrameGapMs) {
                    gatedSlices.addLast(t to gap)
                    gatedMsTotal += gap
                } else {
                    resetDetectors()
                }
            }
            reversal.add(t, angle)?.let { swing ->
                when (config.swingClassOf(swing.amplitudeDeg)) {
                    SwingClass.MICRO -> {
                        microTimes.addLast(swing.endMs)
                        microTotal++
                    }
                    SwingClass.LARGE -> {
                        largeTimes.addLast(swing.endMs)
                        largeTotal++
                    }
                    SwingClass.NONE -> Unit
                }
            }
            if (sudden.add(t, angle)) {
                suddenTimes.addLast(t)
                suddenTotal++
            }
            lastPassed = true
        } else {
            if (lastPassed) resetDetectors()
            lastPassed = false
        }
        lastDecisionMs = t

        val due = nextUpdateMs ?: return null
        if (t < due) return null
        var next = due
        while (next <= t) next += config.updateIntervalMs
        nextUpdateMs = next
        return snapshot(t)
    }

    /** 새 운행이나 데모 처음부터 다시 재생할 때 모든 상태를 지운다. */
    fun reset() {
        resetDetectors()
        microTimes.clear()
        largeTimes.clear()
        suddenTimes.clear()
        gatedSlices.clear()
        lastDecisionMs = null
        lastPassed = false
        nextUpdateMs = null
        gatedMsTotal = 0L
        microTotal = 0
        largeTotal = 0
        suddenTotal = 0
    }

    private fun resetDetectors() {
        reversal.reset()
        sudden.reset()
    }

    private fun snapshot(t: Long): SteeringIndicators {
        // 60초 창: (t − 60 s, t]
        dropOlder(microTimes, t, config.windowMs)
        dropOlder(largeTimes, t, config.windowMs)
        while (gatedSlices.isNotEmpty() && t - gatedSlices.first().first >= config.windowMs) gatedSlices.removeFirst()
        // 5분: t − 급조향 시각이 5분 이하인 것만 (5분 1초 전 → 제외, REQ_FAT_011)
        while (suddenTimes.isNotEmpty() && t - suddenTimes.first() > config.suddenCountWindowMs) suddenTimes.removeFirst()

        val gatedMs = gatedSlices.sumOf { it.second }
        val perMinute = if (gatedMs > 0) MINUTE_MS.toFloat() / gatedMs else 0f
        return SteeringIndicators(
            tMs = t,
            microPerMin = microTimes.size * perMinute,
            largePerMin = largeTimes.size * perMinute,
            suddenCount5m = suddenTimes.size,
            gatedMsInWindow = gatedMs,
            valid = gatedMs >= config.minGatedMsPerWindow,
        )
    }

    private fun dropOlder(times: ArrayDeque<Long>, t: Long, windowMs: Long) {
        while (times.isNotEmpty() && t - times.first() >= windowMs) times.removeFirst()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
