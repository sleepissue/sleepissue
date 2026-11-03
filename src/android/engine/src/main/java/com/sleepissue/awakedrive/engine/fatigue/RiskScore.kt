package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.FatigueConfig

/**
 * 행동 점수 B와 위험 점수 R. (REQ_FAT_012·019)
 *
 * ```
 * B = 100 × (0.35·f미세 + 0.25·f큰 + 0.40·f급조향)
 * R = min(100, B × K)
 * ```
 * R은 순간값이다. 단계 판단에는 [RiskSmoother]로 3분 지수평균한 값을 쓴다(REQ_FAT_020).
 */
object RiskScore {

    /** 행동 점수 B, 0~100. f값 세 개는 [FatigueFactors]가 만든 0~1 값 (REQ_FAT_012) */
    fun behaviorScore(
        fMicro: Float,
        fLarge: Float,
        fSudden: Float,
        config: FatigueConfig = FatigueConfig(),
    ): Float {
        val weighted = config.weightMicro * fMicro + config.weightLarge * fLarge + config.weightSudden * fSudden
        return (POINTS_PER_UNIT * weighted).coerceIn(0f, POINTS_PER_UNIT)
    }

    /**
     * 미세 수정 지표를 끈 행동 점수 (REQ_SIG_009). 조향 원천 갱신이 5 Hz 미만이면 0.5~3° 미세 수정이 흐려지므로
     * 큰 수정·급조향 가중치만 다시 나눠 B = 100 × (0.25·f큰 + 0.40·f급조향) ÷ 0.65 [제안값 — 에뮬레이터 실측 후 확정].
     */
    fun behaviorScoreWithoutMicro(fLarge: Float, fSudden: Float, config: FatigueConfig = FatigueConfig()): Float {
        val weightSum = config.weightLarge + config.weightSudden
        if (weightSum <= 0f) return 0f
        val weighted = (config.weightLarge * fLarge + config.weightSudden * fSudden) / weightSum
        return (POINTS_PER_UNIT * weighted).coerceIn(0f, POINTS_PER_UNIT)
    }

    /**
     * 위험 점수 R = min(100, B × K) (REQ_FAT_012).
     * B가 0이면 맥락 가중 K가 아무리 커도 0이다. 맥락만으로는 점수를 만들지 않는다 (REQ_FAT_019).
     */
    fun riskScore(behaviorB: Float, contextK: Float, config: FatigueConfig = FatigueConfig()): Float {
        // 0 이하와 NaN을 함께 거른다. NaN이 지수평균에 들어가면 그 운행 내내 점수가 NaN이 된다
        if (!(behaviorB > 0f)) return 0f
        return (behaviorB * contextK).coerceIn(0f, config.riskMax)
    }

    /** 0~1 값을 점수(0~100)로 바꾸는 배율. 문턱값이 아니라 단위 변환이라 설정에 두지 않는다 */
    private const val POINTS_PER_UNIT = 100f
}
