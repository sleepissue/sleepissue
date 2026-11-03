package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.BaselineConfig
import com.sleepissue.awakedrive.engine.config.FatigueConfig
import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.InvalidReason
import com.sleepissue.awakedrive.engine.model.SteeringBaseline

/**
 * 졸음 판단 엔진. 30초마다 [FatigueState]를 낸다. (REQ_FAT_001~020)
 *
 * 흐름: 조향 게이트 → 조향 지표 3개 → f값 → 행동 점수 B × 맥락 가중 K = 위험 점수 R → 3분 지수평균
 *
 * 쓰는 법
 * - [push]에 10 Hz 프레임을 넣는다. 30초 갱신 시각이 된 호출에서만 FatigueState가 하나 나오고, 나머지는 빈 목록이다.
 * - 맥락(연속 운전·시각·착석·실내 온도)이 바뀌면 [context]를 바꾼다. K는 FatigueState를 낼 때의 맥락으로 계산한다.
 * - 기준선은 [baseline]으로 바꾼다. 지금은 운행 기록 3회 전 기본값으로 시작한다 (REQ_BSL_003).
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
    initialBaseline: SteeringBaseline = BaselineConfig().defaultBaseline,
) {
    /** f미세·f큰의 비교 기준. BaselineStore가 정해 넣는다 (REQ_BSL_003~007). 다음 갱신부터 쓴다. */
    var baseline: SteeringBaseline = initialBaseline

    /** 맥락 가중 K의 입력. 1번 TripState·SignalFrame에서 만들어 바뀔 때마다 넣는다. */
    var context: ContextInput = ContextInput()

    private val gate = SteeringGate(config)
    private val metrics = SteeringMetrics(config)
    private val smoother = RiskSmoother(config)

    /** 프레임 하나를 넣는다. 30초 갱신 시각이 되면 FatigueState가 들어 있는 목록을 돌려준다. */
    fun push(input: GateInput): List<FatigueState> = gate.push(input).mapNotNull(::onDecision)

    /** 게이트에 남은 프레임을 모두 확정해 남은 갱신을 낸다. 운행이 끝날 때만 쓴다. */
    fun flush(): List<FatigueState> = gate.flush().mapNotNull(::onDecision)

    /** 새 운행이나 데모 처음부터 다시 재생할 때 지표·평활값을 지운다. 기준선과 맥락은 그대로 둔다. */
    fun reset() {
        gate.reset()
        metrics.reset()
        smoother.reset()
    }

    private fun onDecision(decision: GateDecision): FatigueState? {
        val indicators = metrics.accept(decision) ?: return null
        return evaluate(indicators, decision.reason)
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

        val fMicro = FatigueFactors.fMicro(indicators.microPerMin, baseline.microPerMin, config)
        val fLarge = FatigueFactors.fLarge(indicators.largePerMin, baseline.largePerMin, config)
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
