package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.baseline.BaselineStore
import com.sleepissue.awakedrive.engine.config.BaselineConfig
import com.sleepissue.awakedrive.engine.config.FatigueConfig
import com.sleepissue.awakedrive.engine.config.PatternConfig
import com.sleepissue.awakedrive.engine.model.BaselineState
import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.InvalidReason
import com.sleepissue.awakedrive.engine.model.PatternState
import com.sleepissue.awakedrive.engine.pattern.PatternClassifier

/**
 * 졸음 판단 엔진. 30초마다 [FatigueState]를 낸다. (REQ_FAT_001~020, REQ_BSL_003~007, REQ_PAT_001~005)
 *
 * 흐름: 조향 게이트 → 조향 지표 3개 → 기준선과 비교한 f값 → 행동 점수 B × 맥락 가중 K = 위험 점수 R → 3분 지수평균
 *
 * 쓰는 법
 * - [push]에 10 Hz 프레임을 넣는다. 30초 갱신 시각이 된 호출에서만 FatigueState가 하나 나오고, 나머지는 빈 목록이다.
 * - 맥락(연속 운전·시각·착석·실내 온도)이 바뀌면 [context]를 바꾼다. K는 FatigueState를 낼 때의 맥락으로 계산한다.
 * - 기준선은 [baselineStore]가 정한다. 앱을 켤 때 운행 기록을 `baselineStore.loadHistory(...)`로,
 *   출발 전 컨디션을 `baselineStore.reportCondition(...)`으로 넣는다. 상태는 [baselineState]로 읽는다.
 * - 딴짓·졸음 패턴과 안전 우회는 [patternState]로 읽는다 (MSG-03). 급조향이 확인되면 그 프레임에서 바로 바뀐다.
 * - 새 운행이면 [reset], 휴식 인증 뒤면 [resetRiskAfterRest]를 부른다.
 *
 * 게이트가 방향지시등을 켜기 10초 전 프레임까지 빼느라 판정을 10초 늦게 확정하므로,
 * FatigueState.tMs는 넣은 프레임 시각보다 10초 늦다. 60초 시점 값은 70초 프레임을 넣을 때 나온다.
 *
 * 유효하지 않은 갱신 (valid = false)
 * - 보조 주행 중(그 시점 판정이 ASSIST) → invalidReason = ASSIST. 위험 점수를 갱신하지 않는다 (REQ_AST_004)
 * - 60초 창에서 게이트 통과가 30초 미만 → invalidReason = GATE_WAIT (REQ_FAT_005)
 * - 이때 f값·B·R은 0이고(이번 창은 계산하지 않음), 평활값은 마지막 값 그대로 둔다 [해석].
 *   지표(분당 횟수·급조향 횟수)는 화면이 회색으로 보여 줄 수 있게 그대로 담는다.
 *
 * 한 스레드(코루틴 하나)에서만 부른다. Android에 의존하지 않는다.
 * ROS로 치면 신호 토픽을 구독해 30초마다 졸음 점수 토픽을 발행하는 노드다.
 */
class FatigueEngine(
    private val config: FatigueConfig = FatigueConfig(),
    baselineConfig: BaselineConfig = BaselineConfig(),
    patternConfig: PatternConfig = PatternConfig(),
) {
    /** 맥락 가중 K의 입력. 1번 TripState·SignalFrame에서 만들어 바뀔 때마다 넣는다. */
    var context: ContextInput = ContextInput()

    /** 기본값·평소의 나·오늘의 나 가운데 비교 기준을 고르고, 출발 피로·민감 모드를 정한다. */
    val baselineStore = BaselineStore(baselineConfig)

    /** 지금 기준선·민감 모드 상태 (1번·4번·5번이 읽음) */
    val baselineState: BaselineState get() = baselineStore.state

    /** 지금 딴짓·졸음 패턴과 안전 우회 신호 (1번 단계 전환·5번 화면이 읽음, MSG-03) */
    val patternState: PatternState get() = pattern.state

    private val gate = SteeringGate(config)
    private val metrics = SteeringMetrics(config)
    private val smoother = RiskSmoother(config)
    private val pattern = PatternClassifier(patternConfig)

    /** 마지막으로 넣은 프레임 시각. 민감 모드 시작·첫 음성 확인 시각을 이 시계로 잡는다 */
    private var lastInputMs: Long? = null

    /** 지난 판정까지 센 급조향 수. 늘어나면 그 판정에서 급조향이 확인된 것 */
    private var lastSuddenTotal = 0

    /** 프레임 하나를 넣는다. 30초 갱신 시각이 되면 FatigueState가 들어 있는 목록을 돌려준다. */
    fun push(input: GateInput): List<FatigueState> {
        lastInputMs = input.tMs
        if (input.inDrive == true) baselineStore.onDriveFrame(input.tMs)
        return gate.push(input).mapNotNull { onDecision(it, input.tMs) }
    }

    /** 게이트에 남은 프레임을 모두 확정해 남은 갱신을 낸다. 운행이 끝날 때만 쓴다. */
    fun flush(): List<FatigueState> = gate.flush().mapNotNull { onDecision(it, lastInputMs ?: it.tMs) }

    /**
     * 새 운행(꺼져 있던 시간 2시간 초과, REQ_TRP_008): 지표·평활값·패턴·오늘의 나·출발 피로·컨디션을 지운다.
     * 평소의 나(운행 기록)와 맥락은 그대로 둔다. 시동을 켠 직후, 출발 전 컨디션을 받기 전에 부른다.
     */
    fun reset() {
        gate.reset()
        metrics.reset()
        smoother.reset()
        baselineStore.resetTrip()
        pattern.reset()
        lastInputMs = null
        lastSuddenTotal = 0
    }

    /**
     * 휴식 인증 + 회복 확인 뒤(REQ_RES_011): 위험 점수 평활값만 0부터 다시 센다.
     * 오늘의 나는 그대로 둔다. 쉬고 난 직후로 다시 잡으면 덜 깬 상태가 기준이 될 수 있어서다.
     */
    fun resetRiskAfterRest() {
        smoother.reset()
    }

    private fun onDecision(decision: GateDecision, nowMs: Long): FatigueState? {
        val indicators = metrics.accept(decision)
        if (metrics.suddenTotal > lastSuddenTotal) {
            lastSuddenTotal = metrics.suddenTotal
            pattern.onSudden(decision.tMs)
        }
        if (baselineState.today == null) {
            baselineStore.updateToday(
                nowMs = nowMs,
                gatedMs = metrics.gatedMsTotal,
                microCount = metrics.microTotal,
                largeCount = metrics.largeTotal,
                suddenCount = metrics.suddenTotal,
            )
        }
        if (indicators == null) return null
        val state = evaluate(indicators, decision.reason)
        pattern.onWindow(
            tMs = indicators.tMs,
            microPerMin = indicators.microPerMin,
            largePerMin = indicators.largePerMin,
            suddenInWindow = indicators.suddenInWindow,
            measured = state.valid,
            reference = baselineState.reference,
        )
        return state
    }

    private fun evaluate(indicators: SteeringIndicators, reason: GateReason): FatigueState {
        val contextK = ContextWeight.kOf(context, config.context)
        val invalidReason = when {
            // 창 안에 통과 구간이 남아 있어도 보조 주행이 켜진 동안은 점수를 갱신하지 않는다 (REQ_AST_004)
            reason == GateReason.ASSIST -> InvalidReason.ASSIST
            !indicators.valid -> InvalidReason.GATE_WAIT
            else -> InvalidReason.NONE
        }
        if (invalidReason != InvalidReason.NONE) {
            return FatigueState(
                tMs = indicators.tMs,
                microPerMin = indicators.microPerMin,
                largePerMin = indicators.largePerMin,
                suddenCount5m = indicators.suddenCount5m,
                contextK = contextK,
                riskSmoothed = smoother.value,
                valid = false,
                invalidReason = invalidReason,
            )
        }

        val reference = baselineState.reference
        val fMicro = FatigueFactors.fMicro(indicators.microPerMin, reference.microPerMin, config)
        val fLarge = FatigueFactors.fLarge(indicators.largePerMin, reference.largePerMin, config)
        val fSudden = FatigueFactors.fSudden(indicators.suddenCount5m, config)
        val behaviorB = RiskScore.behaviorScore(fMicro, fLarge, fSudden, config)
        val riskR = RiskScore.riskScore(behaviorB, contextK, config)
        return FatigueState(
            tMs = indicators.tMs,
            microPerMin = indicators.microPerMin,
            largePerMin = indicators.largePerMin,
            suddenCount5m = indicators.suddenCount5m,
            fMicro = fMicro,
            fLarge = fLarge,
            fSudden = fSudden,
            behaviorB = behaviorB,
            contextK = contextK,
            riskR = riskR,
            riskSmoothed = smoother.update(riskR),
            valid = true,
            invalidReason = InvalidReason.NONE,
        )
    }
}
