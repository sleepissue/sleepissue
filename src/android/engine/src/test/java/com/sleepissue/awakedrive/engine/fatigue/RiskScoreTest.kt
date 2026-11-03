package com.sleepissue.awakedrive.engine.fatigue

import org.junit.Assert.assertEquals
import org.junit.Test

/** 행동 점수 B·위험 점수 R 시험. 기대값은 SRS v0.2 REQ_FAT_012·019 합격기준. */
class RiskScoreTest {

    @Test
    fun behaviorAndRiskMatchSrsVectors() {
        // (1.0, 0.5, 0.5) → B=67.5, K=1.2 → R=81.0. B=80, K=1.35 → R=100(상한) (REQ_FAT_012)
        val behaviorB = RiskScore.behaviorScore(fMicro = 1.0f, fLarge = 0.5f, fSudden = 0.5f)
        assertEquals(67.5f, behaviorB, 1e-3f)
        assertEquals(81.0f, RiskScore.riskScore(behaviorB, 1.2f), 1e-3f)
        assertEquals(100f, RiskScore.riskScore(80f, 1.35f), 0f)
    }

    @Test
    fun scenarioOneGivesSixtyOneBeforeSmoothing() {
        // 기획서 시나리오 1(연령대 미설정: f미세 0.8, f큰 1.0, f급 0, K=1.15) → B=53.0, R=61.0 ±0.5 (REQ_FAT_012)
        // 23:10, 21:00 출발이라 연속 2시간 10분, 혼자 → K = 1.00 + 0.10 + 0.05. 23시는 기본 취약 시간대가 아님
        val contextK = ContextWeight.kOf(
            ContextInput(continuousDriveMs = 130 * 60_000L, minuteOfDay = 23 * 60 + 10, passengerCount = 0),
        )
        assertEquals(1.15f, contextK, 1e-6f)
        val behaviorB = RiskScore.behaviorScore(fMicro = 0.8f, fLarge = 1.0f, fSudden = 0f)
        assertEquals(53.0f, behaviorB, 1e-3f)
        assertEquals(61.0f, RiskScore.riskScore(behaviorB, contextK), 0.5f)
    }

    @Test
    fun noBehaviorMeansNoRiskWhateverTheContext() {
        // B=0, K=1.35 → R=0 (REQ_FAT_019)
        assertEquals(0f, RiskScore.behaviorScore(0f, 0f, 0f), 0f)
        assertEquals(0f, RiskScore.riskScore(0f, 1.35f), 0f)
    }

    @Test
    fun behaviorScoreWithoutMicroRenormalizesTheOtherTwoWeights() {
        // 조향 원천 5 Hz 미만: B = 100 × (0.25·f큰 + 0.40·f급조향) ÷ 0.65 (REQ_SIG_009 [제안값])
        assertEquals(38.46f, RiskScore.behaviorScoreWithoutMicro(fLarge = 1f, fSudden = 0f), 0.01f)
        assertEquals(69.23f, RiskScore.behaviorScoreWithoutMicro(fLarge = 1f, fSudden = 0.5f), 0.01f)
        assertEquals(100f, RiskScore.behaviorScoreWithoutMicro(fLarge = 1f, fSudden = 1f), 1e-3f)
        assertEquals(0f, RiskScore.behaviorScoreWithoutMicro(fLarge = 0f, fSudden = 0f), 0f)
    }

    @Test
    fun behaviorScoreTopsOutAtHundred() {
        // f값 세 개가 모두 1이면 가중치 합(1.00) × 100 = 100
        assertEquals(100f, RiskScore.behaviorScore(1f, 1f, 1f), 1e-3f)
    }
}
