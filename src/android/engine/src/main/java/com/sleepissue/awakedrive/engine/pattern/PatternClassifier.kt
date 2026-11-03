package com.sleepissue.awakedrive.engine.pattern

import com.sleepissue.awakedrive.engine.config.PatternConfig
import com.sleepissue.awakedrive.engine.model.PatternState
import com.sleepissue.awakedrive.engine.model.PatternType
import com.sleepissue.awakedrive.engine.model.SteeringBaseline

/** 1분 칸 하나의 판정. */
internal enum class MinuteStatus {
    /** 기준과 비슷함 */
    NORMAL,

    /** 이상 분 (REQ_PAT_001) */
    ABNORMAL,

    /** 게이트 대기·보조 주행 등으로 측정하지 못함. 이상 분으로 세지 않고, 복귀로도 보지 않는다 [해석] */
    UNMEASURED,
}

/**
 * 딴짓·졸음 패턴 분류기. 최근 10분의 '이상 분'으로 추세형·튐형·애매를 나누고, 5분 안 급조향 3회를 안전 우회로 알린다.
 * (REQ_PAT_001~003·005)
 *
 * - 1분 칸: FatigueEngine이 30초마다 60초 창 지표를 넣는다. 첫 창 시각부터 1분마다 끊고, 칸마다 처음 들어온 창만 쓴다
 *   (바로 뒤 30초 창은 앞 창과 절반이 겹치므로 건너뜀). 창이 빠진 칸은 측정 못 함으로 채운다.
 * - 안전 우회: 급조향이 확인될 때마다 바로 본다. 패턴 상태는 30초마다 다시 만든다.
 * - 음성 확인 결과와 합쳐 단계를 바꾸는 판단(REQ_PAT_004·006·007)은 1번 InterventionPolicy가 [PatternState]로 한다.
 *
 * ROS로 치면 30초 지표 토픽과 급조향 이벤트를 구독해 패턴 토픽을 내는 노드다.
 */
class PatternClassifier(private val config: PatternConfig = PatternConfig()) {

    private data class Minute(val slot: Long, val status: MinuteStatus)

    private val minutes = ArrayDeque<Minute>()
    private val suddenTimes = ArrayDeque<Long>()
    private var originMs: Long? = null
    private var lastWindowMs: Long? = null
    private var bypassUntilMs: Long? = null
    private var nowMs: Long = Long.MIN_VALUE

    /** 지금 패턴 상태. 값이 바뀔 때만 새 객체가 된다 */
    var state: PatternState = PatternState()
        private set

    /**
     * 30초마다 60초 창 지표를 넣는다.
     * @param measured 이 창을 판단에 써도 되는지 (FatigueState.valid와 같음). false면 측정 못 한 칸
     * @param reference 지금 비교 기준선 (BaselineState.reference)
     */
    internal fun onWindow(
        tMs: Long,
        microPerMin: Float,
        largePerMin: Float,
        suddenInWindow: Int,
        measured: Boolean,
        reference: SteeringBaseline,
    ) {
        val last = lastWindowMs
        if (last != null && tMs < last) return
        lastWindowMs = tMs
        nowMs = maxOf(nowMs, tMs)

        val origin = originMs ?: tMs.also { originMs = it }
        val slot = (tMs - origin) / MINUTE_MS
        val lastSlot = minutes.lastOrNull()?.slot
        if (lastSlot == null || slot > lastSlot) {
            if (lastSlot != null) {
                // 창이 오지 않은 칸(앱 정지·신호 끊김)은 측정 못 함. 긴 공백도 10칸 넘게는 채우지 않는다
                val fillFrom = maxOf(lastSlot + 1, slot - config.lookbackMinutes)
                for (s in fillFrom until slot) minutes.addLast(Minute(s, MinuteStatus.UNMEASURED))
            }
            val status = when {
                !measured -> MinuteStatus.UNMEASURED
                isAbnormalMinute(microPerMin, largePerMin, suddenInWindow, reference, config) -> MinuteStatus.ABNORMAL
                else -> MinuteStatus.NORMAL
            }
            minutes.addLast(Minute(slot, status))
            while (minutes.size > config.lookbackMinutes) minutes.removeFirst()
        }
        refresh()
    }

    /**
     * 멈춤 뒤 급조향이 확인될 때마다 그 시각(게이트 판정 시각)으로 부른다 (REQ_PAT_005).
     * 보조 주행 중의 급조향도 넣는다 (기획서 5-4 "안전 우회는 그대로 씁니다").
     * 안전 우회 신호가 이번에 새로 켜졌으면 true.
     */
    internal fun onSudden(tMs: Long): Boolean {
        // 마지막 상태가 아니라 이 시각 기준으로 켜져 있었는지 본다 (유지 시간이 막 끝난 직후의 새 신호를 놓치지 않게)
        val wasOn = bypassUntilMs?.let { tMs < it } ?: false
        nowMs = maxOf(nowMs, tMs)
        suddenTimes.addLast(tMs)
        // 5분 이하만 남김: 300.0 s → 포함, 300.1 s → 제외 [해석]
        while (suddenTimes.isNotEmpty() && tMs - suddenTimes.first() > config.safetyBypassWindowMs) suddenTimes.removeFirst()
        if (suddenTimes.size >= config.safetyBypassSuddenCount) bypassUntilMs = tMs + config.safetyBypassHoldMs
        refresh()
        val isOn = bypassUntilMs?.let { tMs < it } ?: false
        return isOn && !wasOn
    }

    /** 새 운행이나 데모 처음부터 다시 재생할 때 모두 지운다. */
    internal fun reset() {
        minutes.clear()
        suddenTimes.clear()
        originMs = null
        lastWindowMs = null
        bypassUntilMs = null
        nowMs = Long.MIN_VALUE
        refresh()
    }

    private fun refresh() {
        val window = minutes.map { it.status }
        val until = bypassUntilMs
        val next = PatternState(
            abnormalMinutes10m = window.count { it == MinuteStatus.ABNORMAL },
            patternType = classify(window, config),
            safetyBypass = until != null && nowMs < until,
        )
        if (next != state) state = next
    }

    companion object {
        private const val MINUTE_MS = 60_000L

        /** 비율 경계(0.70, 1.50)에서 Float 오차로 판정이 뒤집히지 않게 하는 여유 */
        private const val RATIO_EPS = 1e-6

        /**
         * 이상 분: 미세 수정이 기준의 70% 이하이면서, 큰 수정이 기준의 1.5배 이상이거나 급조향이 있었던 분 (REQ_PAT_001).
         * 미세 0.70·큰 1.50 → 이상, 미세 0.71 → 아님, 미세 0.50·큰 1.49·급조향 0 → 아님, 미세 0.50·급조향 1 → 이상.
         * 기준 미세가 0이면 줄어들 수 없으므로 아님, 기준 큰 수정이 0인데 지금 있으면 늘어난 것 [해석, f값과 같음].
         */
        fun isAbnormalMinute(
            microPerMin: Float,
            largePerMin: Float,
            suddenCount: Int,
            reference: SteeringBaseline,
            config: PatternConfig = PatternConfig(),
        ): Boolean {
            val microLow = reference.microPerMin > 0f &&
                microPerMin.toDouble() / reference.microPerMin <= config.abnormalMicroRatioMax + RATIO_EPS
            if (!microLow) return false
            val largeHigh = if (reference.largePerMin > 0f) {
                largePerMin.toDouble() / reference.largePerMin >= config.abnormalLargeRatioMin - RATIO_EPS
            } else {
                largePerMin > 0f
            }
            return largeHigh || suddenCount > 0
        }

        /**
         * 최근 칸들(오래된 것부터)로 패턴을 정한다 (REQ_PAT_002·003).
         * - 이상 분 0 → NONE, 6 이상 → TREND
         * - 3 이하이고 이상 분마다 다음 1분이 정상 → SPIKE
         * - 그 밖(4~5, 또는 3 이하인데 복귀 실패) → AMBIGUOUS.
         *   다음 칸이 아직 없거나 측정 못 한 칸이면 복귀를 확인하지 못한 것으로 보고 AMBIGUOUS [해석]
         */
        internal fun classify(window: List<MinuteStatus>, config: PatternConfig = PatternConfig()): PatternType {
            val abnormal = window.count { it == MinuteStatus.ABNORMAL }
            val recoverMinutes = (config.spikeRecoverWithinMs / MINUTE_MS).toInt().coerceAtLeast(1)
            val allRecovered = window.indices.filter { window[it] == MinuteStatus.ABNORMAL }.all { i ->
                (1..recoverMinutes).any { k -> window.getOrNull(i + k) == MinuteStatus.NORMAL }
            }
            return when {
                abnormal == 0 -> PatternType.NONE
                abnormal >= config.trendMinAbnormalMinutes -> PatternType.TREND
                abnormal <= config.spikeMaxAbnormalMinutes && allRecovered -> PatternType.SPIKE
                else -> PatternType.AMBIGUOUS
            }
        }
    }
}
