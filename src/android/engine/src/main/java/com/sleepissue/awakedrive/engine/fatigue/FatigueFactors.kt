package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.FatigueConfig

/**
 * 조향 지표를 기준선과 비교해 0~1 값으로 바꾼다. (REQ_FAT_009~011)
 * 이 값들로 행동 점수 B = 100 × (0.35·f미세 + 0.25·f큰 + 0.40·f급조향)를 계산한다(REQ_FAT_012).
 */
object FatigueFactors {

    /**
     * f미세 = (1 − 현재 ÷ 기준) ÷ 0.5, 0~1로 자름. 졸리면 미세 수정이 줄어든다. (REQ_FAT_009)
     * 기준 20: 현재 20 → 0, 15 → 0.5, 10 → 1.0, 5 → 1.0(상한), 25 → 0(하한)
     * 기준이 0 이하면 줄어들 것이 없으므로 0 [해석].
     */
    fun fMicro(currentPerMin: Float, baselinePerMin: Float, config: FatigueConfig = FatigueConfig()): Float {
        if (baselinePerMin <= 0f) return 0f
        return clamp01((1f - currentPerMin / baselinePerMin) / config.fMicroDropForFull)
    }

    /**
     * f큰 = (현재 ÷ 기준 − 1) ÷ 1.0, 0~1로 자름. 졸리면 큰 수정이 늘어난다. (REQ_FAT_010)
     * 기준 4: 현재 4 → 0, 6 → 0.5, 8 → 1.0, 12 → 1.0(상한), 2 → 0(하한)
     * 기준이 0 이하인데 현재 큰 수정이 있으면 '늘어난 것'이므로 1, 없으면 0 [해석].
     */
    fun fLarge(currentPerMin: Float, baselinePerMin: Float, config: FatigueConfig = FatigueConfig()): Float {
        if (baselinePerMin <= 0f) return if (currentPerMin > 0f) 1f else 0f
        return clamp01((currentPerMin / baselinePerMin - 1f) / config.fLargeRiseForFull)
    }

    /**
     * f급조향 = 최근 5분 급조향 횟수 ÷ 2, 0~1로 자름. (REQ_FAT_011)
     * 0회 → 0, 1회 → 0.5, 2회 → 1.0, 3회 → 1.0(상한)
     */
    fun fSudden(suddenCount5m: Int, config: FatigueConfig = FatigueConfig()): Float =
        clamp01(suddenCount5m.toFloat() / config.fSuddenCountForFull)

    private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)
}
