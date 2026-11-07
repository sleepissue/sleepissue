package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.FatigueConfig

/** 안전 감시 조향에서 이번 판정에 확인된 사건. */
internal data class SafetySignal(
    /** 멈춤 뒤 급조향이 확인된 시각. 없으면 null */
    val suddenAtMs: Long?,
    /** 큰 수정(5° 이상)이 끝난 시각(극값 시각). 없으면 null */
    val largeSwingEndMs: Long?,
)

/**
 * 안전 우회(REQ_PAT_005)와 인계 품질(REQ_AST_007)을 위한 조향 감시.
 *
 * 졸음 점수용 [SteeringMetrics]와 같은 감지기를 쓰지만, 보조 주행 중인 프레임도 쓴다
 * ([GateDecision.passedWithoutAssist]). 그래서 보조 주행 중의 급조향과, 해제 직전부터 이어진 멈춤 뒤 급조향을 잡는다.
 * 속도·기어·방향지시등·브레이크·회전 지점 조건은 그대로 적용한다.
 */
internal class SafetySteering(private val config: FatigueConfig = FatigueConfig()) {

    private val reversal = SteeringReversalDetector(config.microMinDeg.toDouble())
    private val sudden = SuddenSteerDetector(config)
    private var lastDecisionMs: Long? = null
    private var lastPassed = false

    /** 게이트 판정 하나를 넣는다. 급조향이나 큰 수정이 확인되면 그 사건을, 아니면 null을 돌려준다. */
    fun accept(decision: GateDecision): SafetySignal? {
        val t = decision.tMs
        val previous = lastDecisionMs
        if (previous != null && t < previous) return null
        lastDecisionMs = t

        val angle = decision.steeringDeg
        if (!decision.passedWithoutAssist || angle == null) {
            if (lastPassed) resetDetectors()
            lastPassed = false
            return null
        }
        // 신호가 끊겼으면 끊긴 구간을 건너 각도를 잇지 않는다 (SteeringMetrics와 같음)
        if (previous != null && lastPassed && t - previous > config.gateMaxFrameGapMs) resetDetectors()
        lastPassed = true

        val largeEnd = reversal.add(t, angle)
            ?.takeIf { config.swingClassOf(it.amplitudeDeg) == SwingClass.LARGE }
            ?.endMs
        val suddenAt = if (sudden.add(t, angle)) t else null
        return if (suddenAt != null || largeEnd != null) SafetySignal(suddenAt, largeEnd) else null
    }

    fun reset() {
        resetDetectors()
        lastDecisionMs = null
        lastPassed = false
    }

    private fun resetDetectors() {
        reversal.reset()
        sudden.reset()
    }
}
