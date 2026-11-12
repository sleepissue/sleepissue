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
 * - 급조향 칸: 급조향이 확인되면 그 급조향까지의 60초로도 이상 분 조건을 본다([onSuddenWindow]). 만족하면 그 급조향
 *   시각이 든 칸 하나만 이상 분이다. 멈춘 구간과 급조향이 칸 경계로 갈려 딴짓 1분을 놓치던 것을 막는다 (고칠 것 1).
 * - 안전 우회: 급조향이 확인될 때마다 바로 본다. 패턴 상태는 30초마다 다시 만든다.
 * - 음성 확인 결과와 합쳐 단계를 바꾸는 판단(REQ_PAT_004·006·007)은 1번 InterventionPolicy가 [PatternState]로 한다.
 * - 딴짓 기록(REQ_PAT_004)은 여기서 한다: 정상 대답([onCheckResult]) 뒤 패턴이 튐형으로 확인되면 1건.
 *
 * ROS로 치면 30초 지표 토픽과 급조향 이벤트를 구독해 패턴 토픽을 내는 노드다.
 */
class PatternClassifier(private val config: PatternConfig = PatternConfig()) {

    private data class Minute(val slot: Long, val status: MinuteStatus)

    private val minutes = ArrayDeque<Minute>()
    private val suddenTimes = ArrayDeque<Long>()

    /** 그 급조향까지의 60초가 이상 분 조건을 만족한 급조향 시각들. 그 시각이 든 1분 칸이 만들어질 때 꺼낸다 */
    private val abnormalSuddenTimes = ArrayDeque<Long>()
    private var originMs: Long? = null
    private var lastWindowMs: Long? = null
    private var bypassUntilMs: Long? = null
    private var nowMs: Long = Long.MIN_VALUE

    /** 이번 운행 딴짓 기록 수와 마지막 기록 시각 (REQ_PAT_004) */
    private var distractionCount = 0
    private var lastDistractionMs: Long? = null

    /** 딴짓인지 아직 정하지 못한 정상 대답의 시각. 없으면 null */
    private var pendingNormalCheckMs: Long? = null

    /** 마지막 딴짓 기록에 쓴 이상 분 칸. 같은 이상 분으로 두 번 세지 않는다 */
    private var recordedAbnormalSlot: Long? = null

    /** 지금 패턴 상태. 값이 바뀔 때만 새 객체가 된다 */
    var state: PatternState = PatternState()
        private set

    /**
     * 30초마다 60초 창 지표를 넣는다.
     * @param measured 이 창을 판단에 써도 되는지 (FatigueState.valid와 같음). false면 측정 못 한 칸
     * @param reference 지금 비교 기준선 (BaselineState.reference)
     * @param microEnabled 미세 수정 지표를 쓸 수 있는지 (조향 원천 5 Hz 이상, REQ_SIG_009)
     */
    internal fun onWindow(
        tMs: Long,
        microPerMin: Float,
        largePerMin: Float,
        suddenInWindow: Int,
        measured: Boolean,
        reference: SteeringBaseline,
        microEnabled: Boolean = true,
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
                takeAbnormalSuddens(origin, slot) -> MinuteStatus.ABNORMAL
                !measured -> MinuteStatus.UNMEASURED
                isAbnormalMinute(microPerMin, largePerMin, suddenInWindow, reference, config, microEnabled) ->
                    MinuteStatus.ABNORMAL
                else -> MinuteStatus.NORMAL
            }
            minutes.addLast(Minute(slot, status))
            while (minutes.size > config.lookbackMinutes) minutes.removeFirst()
        }
        refresh()
    }

    /**
     * 졸음 점수용 조향에서 멈춤 뒤 급조향이 확인된 순간, 그 급조향까지의 60초 창 지표로 이상 분 조건을 본다 (REQ_PAT_001).
     * 만족하면 그 급조향 시각이 든 1분 칸 하나만 이상 분으로 매긴다(급조향 하나는 칸 하나에만).
     *
     * 1분 칸은 칸마다 첫 창만 보므로, 운전대를 멈춘 구간(미세 수정 감소)과 급조향이 서로 다른 칸으로 갈리면
     * 어느 칸도 '미세 70% 이하 + 급조향'을 못 채워 딴짓 1분을 놓쳤다(시작 시각 60가지 중 18가지).
     * @param measured 그 60초에 게이트 통과가 30초 이상인지. false면 판단하지 않는다
     */
    internal fun onSuddenWindow(
        tMs: Long,
        microPerMin: Float,
        largePerMin: Float,
        measured: Boolean,
        reference: SteeringBaseline,
        microEnabled: Boolean = true,
    ) {
        if (!measured) return
        if (!isAbnormalMinute(microPerMin, largePerMin, 1, reference, config, microEnabled)) return
        if (abnormalSuddenTimes.lastOrNull()?.let { tMs < it } == true) return
        abnormalSuddenTimes.addLast(tMs)
    }

    /**
     * [slot] 칸까지에 드는 급조향 사건을 꺼낸다. 이번 칸에 든 것이 있으면 true.
     * 이미 지나간 칸에 든 것(창이 빠져 칸을 늦게 만든 경우)은 그 칸이 남아 있으면 이상 분으로 올린다.
     */
    private fun takeAbnormalSuddens(origin: Long, slot: Long): Boolean {
        var hit = false
        while (abnormalSuddenTimes.isNotEmpty()) {
            val s = slotOfSudden(abnormalSuddenTimes.first(), origin)
            if (s > slot) break
            abnormalSuddenTimes.removeFirst()
            if (s == slot) {
                hit = true
            } else {
                val i = minutes.indexOfFirst { it.slot == s }
                if (i >= 0 && minutes[i].status != MinuteStatus.ABNORMAL) minutes[i] = minutes[i].copy(status = MinuteStatus.ABNORMAL)
            }
        }
        return hit
    }

    /**
     * 급조향 시각이 드는 칸. 칸 k의 첫 창(시각 origin + k분)은 (origin + (k−1)분, origin + k분]을 보므로
     * 그 범위에 든 급조향은 칸 k다. 칸 k = ⌈(시각 − origin) ÷ 1분⌉.
     */
    private fun slotOfSudden(tMs: Long, origin: Long): Long = Math.floorDiv(tMs - origin + MINUTE_MS - 1, MINUTE_MS)

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

    /**
     * 운전자·동승자 주의 확인 결과를 넣는다 (REQ_PAT_004).
     * - 정상 대답이면 딴짓 후보로 둔다. 지금 또는 [PatternConfig.distractionResolveMs] 안에 새 이상 분으로 튐형이 되면
     *   딴짓 1건으로 기록한다. 추세형이 되거나 끝까지 애매면 기록하지 않는다(정상 + 추세형은 2단계 유지, REQ_PAT_006)
     * - 느림·무응답은 딴짓이 아니므로 후보를 지운다
     * - 후보가 남아 있는 동안 온 정상 대답은 같은 일로 보고 한 건으로 센다(시각은 마지막 대답).
     *   이미 기록한 이상 분만으로 튐형인 동안의 정상 대답은 새로 세지 않는다
     * - 단계를 내리는 것은 1번 InterventionPolicy가 한다(정상 + 점수 60 미만 10분, REQ_INT_007)
     * @return 이번 호출로 바로 기록했으면 true. 튐형 확정을 기다리는 동안은 false(뒤 30초 갱신 때 기록될 수 있음)
     */
    internal fun onCheckResult(tMs: Long, normal: Boolean): Boolean {
        nowMs = maxOf(nowMs, tMs)
        val before = distractionCount
        pendingNormalCheckMs = if (normal) tMs else null
        refresh()
        return distractionCount > before
    }

    /** 새 운행이나 데모 처음부터 다시 재생할 때 모두 지운다. */
    internal fun reset() {
        minutes.clear()
        suddenTimes.clear()
        abnormalSuddenTimes.clear()
        originMs = null
        lastWindowMs = null
        bypassUntilMs = null
        nowMs = Long.MIN_VALUE
        distractionCount = 0
        lastDistractionMs = null
        pendingNormalCheckMs = null
        recordedAbnormalSlot = null
        refresh()
    }

    private fun refresh() {
        val window = minutes.map { it.status }
        val type = classify(window, config)
        resolveDistraction(type)
        val until = bypassUntilMs
        val next = PatternState(
            abnormalMinutes10m = window.count { it == MinuteStatus.ABNORMAL },
            patternType = type,
            safetyBypass = until != null && nowMs < until,
            distractionCount = distractionCount,
            lastDistractionMs = lastDistractionMs,
        )
        if (next != state) state = next
    }

    /** 정상 대답 후보를 지금 패턴으로 정한다: 새 이상 분으로 튐형 → 기록, 추세형·기다린 시간 지남 → 버림, 그 밖 → 계속 기다림 */
    private fun resolveDistraction(type: PatternType) {
        val pending = pendingNormalCheckMs ?: return
        val newest = minutes.lastOrNull { it.status == MinuteStatus.ABNORMAL }?.slot
        val newAbnormal = newest != null && recordedAbnormalSlot.let { it == null || newest > it }
        when {
            type == PatternType.SPIKE && newAbnormal -> {
                distractionCount++
                lastDistractionMs = pending
                recordedAbnormalSlot = newest
                pendingNormalCheckMs = null
            }
            type == PatternType.TREND || nowMs - pending >= config.distractionResolveMs -> pendingNormalCheckMs = null
        }
    }

    companion object {
        private const val MINUTE_MS = 60_000L

        /** 비율 경계(0.70, 1.50)에서 Float 오차로 판정이 뒤집히지 않게 하는 여유 */
        private const val RATIO_EPS = 1e-6

        /**
         * 이상 분: 미세 수정이 기준의 70% 이하이면서, 큰 수정이 기준의 1.5배 이상이거나 급조향이 있었던 분 (REQ_PAT_001).
         * 미세 0.70·큰 1.50 → 이상, 미세 0.71 → 아님, 미세 0.50·큰 1.49·급조향 0 → 아님, 미세 0.50·급조향 1 → 이상.
         * 기준 미세가 0이면 줄어들 수 없으므로 아님, 기준 큰 수정이 0인데 지금 있으면 늘어난 것 [해석, f값과 같음].
         *
         * [microEnabled]가 false(조향 원천 5 Hz 미만, REQ_SIG_009)면 미세 수정 조건을 빼고
         * '큰 수정 1.5배 이상 또는 급조향'만 본다 [해석]. 갱신이 느리면 미세 수정이 거의 안 세져서
         * 원래 규칙도 결국 같은 결과가 되므로, 그 상황을 드러내 놓고 같은 규칙으로 둔다.
         */
        fun isAbnormalMinute(
            microPerMin: Float,
            largePerMin: Float,
            suddenCount: Int,
            reference: SteeringBaseline,
            config: PatternConfig = PatternConfig(),
            microEnabled: Boolean = true,
        ): Boolean {
            val microLow = !microEnabled || reference.microPerMin > 0f &&
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
