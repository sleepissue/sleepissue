package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.FatigueConfig

/**
 * 위험 점수 R을 3분 지수평균으로 다듬는다. 단계 판단에는 이 값을 쓴다. (REQ_FAT_020)
 *
 * 평활값 ← 평활값 + α × (R − 평활값), α = 1 − e^(−30 s ÷ 180 s) = 0.1535 (FatigueConfig.smoothingAlpha).
 *
 * - 처음 값은 0이다 [해석]. 그래서 순간 점수 61이 계속 나와도 평활값이 60을 넘기까지 12분 30초 걸린다
 *   (SRS REQ_FAT_020 비고와 같은 계산). '졸음' 데모 시나리오는 순간 점수를 70 이상으로 만든다.
 * - 보조 주행·측정 대기로 갱신하지 않는 동안에는 값을 그대로 둔다 (REQ_AST_004 "위험 점수 갱신 0회").
 *
 * 순간 흔들림에 단계가 요동치지 않게 하는 저역 통과 필터라고 보면 된다.
 */
class RiskSmoother(config: FatigueConfig = FatigueConfig()) {

    private val alpha = config.smoothingAlpha
    private var smoothed = 0.0

    init {
        require(alpha > 0.0 && alpha <= 1.0) { "지수평균 비율 α는 0 초과 1 이하: $alpha" }
    }

    /** 지금 평활값, 0~100 */
    val value: Float get() = smoothed.toFloat()

    /** 새 순간 점수 R을 섞고 평활값을 돌려준다. 30초 갱신마다 한 번 부른다. */
    fun update(riskR: Float): Float {
        smoothed += alpha * (riskR - smoothed)
        return value
    }

    /** 새 운행이나 데모 처음부터 다시 재생할 때 0으로 되돌린다. */
    fun reset() {
        smoothed = 0.0
    }
}
