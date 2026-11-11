package com.sleepissue.awakedrive.engine.pattern

import com.sleepissue.awakedrive.engine.model.PatternType
import com.sleepissue.awakedrive.engine.model.SteeringBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이상 분·추세형·튐형·안전 우회·딴짓 기록 시험. 기대값은 SRS v0.2 REQ_PAT_001~005 합격기준. */
class PatternClassifierTest {

    /** 비교 기준: 미세 20/분, 큰 4/분 */
    private val reference = SteeringBaseline(microPerMin = 20f, largePerMin = 4f)

    /** "NAU" 문자열 → 1분 칸 목록 (N 정상, A 이상, U 측정 못 함), 오래된 것부터 */
    private fun minutes(code: String) = code.map {
        when (it) {
            'A' -> MinuteStatus.ABNORMAL
            'N' -> MinuteStatus.NORMAL
            else -> MinuteStatus.UNMEASURED
        }
    }

    private fun classify(code: String) = PatternClassifier.classify(minutes(code))

    /** 30초마다 오는 60초 창 하나를 넣는다 */
    private fun PatternClassifier.window(tMs: Long, micro: Float, large: Float, sudden: Int = 0, measured: Boolean = true) =
        onWindow(tMs, micro, large, sudden, measured, reference)

    @Test
    fun abnormalMinuteNeedsFewMicroAndMoreLargeOrSudden() {
        // 미세 0.70·큰 1.50 → 이상, 미세 0.71 → 아님, 미세 0.50·큰 1.49·급조향 0 → 아님, 미세 0.50·급조향 1 → 이상 (REQ_PAT_001)
        fun abnormal(microRatio: Float, largeRatio: Float, sudden: Int) =
            PatternClassifier.isAbnormalMinute(20f * microRatio, 4f * largeRatio, sudden, reference)
        assertTrue(abnormal(0.70f, 1.50f, 0))
        assertFalse(abnormal(0.71f, 1.50f, 0))
        assertFalse(abnormal(0.50f, 1.49f, 0))
        assertTrue(abnormal(0.50f, 1.00f, 1))
    }

    @Test
    fun withoutMicroAbnormalMinuteIsLargeOrSuddenOnly() {
        // 조향 원천 5 Hz 미만이면 미세 수정 조건을 빼고 '큰 수정 1.5배 이상 또는 급조향'만 본다 (REQ_SIG_009) [해석]
        fun abnormal(micro: Float, large: Float, sudden: Int) =
            PatternClassifier.isAbnormalMinute(micro, large, sudden, reference, microEnabled = false)
        assertTrue("미세가 평소대로여도 큰 수정 1.5배면 이상", abnormal(20f, 6f, 0))
        assertTrue(abnormal(20f, 4f, 1))
        assertFalse(abnormal(20f, 5.96f, 0))
    }

    @Test
    fun trendNeedsSixAbnormalMinutesOutOfTen() {
        // 이상 분 5 → 추세형 아님, 6 → 추세형, 10 → 추세형 (REQ_PAT_002)
        assertEquals(PatternType.AMBIGUOUS, classify("AAAAANNNNN"))
        assertEquals(PatternType.TREND, classify("AAAAAANNNN"))
        assertEquals(PatternType.TREND, classify("AAAAAAAAAA"))
    }

    @Test
    fun spikeNeedsRecoveryWithinOneMinuteEveryTime() {
        // 이상 분 3 + 모두 1분 안 복귀 → 튐형, 이상 분 4·5 → 애매, 이상 분 3 + 복귀 실패 1회 → 애매 (REQ_PAT_003)
        assertEquals(PatternType.SPIKE, classify("NANNANNANN"))
        assertEquals(PatternType.AMBIGUOUS, classify("ANANANANNN"))
        assertEquals(PatternType.AMBIGUOUS, classify("ANANANANAN"))
        assertEquals(PatternType.AMBIGUOUS, classify("NAANNANNNN"))
        assertEquals(PatternType.NONE, classify("NNNNNNNNNN"))
        // [해석] 방금 끝난 1분이 이상이면 아직 복귀를 못 봤으므로 애매. 이상 다음 칸을 측정 못 했어도 애매
        assertEquals(PatternType.AMBIGUOUS, classify("NNNNNNNNNA"))
        assertEquals(PatternType.AMBIGUOUS, classify("NNNNAUNNNN"))
    }

    @Test
    fun minutesUseOneWindowPerMinuteAndLookBackTen() {
        // 30초마다 60초 창이 오지만 앞 창과 절반이 겹치는 창은 건너뛰고, 1분 칸마다 처음 온 창만 쓴다
        val classifier = PatternClassifier()
        var t = 60_000L
        repeat(11) { // 60, 90, …, 360 s → 1분 칸 6개 모두 이상(미세 0.5배, 큰 2배)
            classifier.window(t, micro = 10f, large = 8f)
            t += 30_000L
        }
        assertEquals(6, classifier.state.abnormalMinutes10m)
        assertEquals(PatternType.TREND, classifier.state.patternType)

        // 그 뒤 정상 9분(390~900 s): 최근 10칸 안에는 마지막 이상 분 하나만 남고, 다음 분에 복귀했으므로 튐형
        repeat(18) {
            classifier.window(t, micro = 20f, large = 4f)
            t += 30_000L
        }
        assertEquals(1, classifier.state.abnormalMinutes10m)
        assertEquals(PatternType.SPIKE, classifier.state.patternType)

        // 다음 1분 칸(960 s)이 오면 그 이상 분이 10분 밖으로 빠짐
        classifier.window(960_000L, micro = 20f, large = 4f)
        assertEquals(0, classifier.state.abnormalMinutes10m)
        assertEquals(PatternType.NONE, classifier.state.patternType)
    }

    @Test
    fun unmeasuredMinutesAreNeitherAbnormalNorRecovery() {
        // 게이트 대기·보조 주행 칸은 이상 분으로 세지 않음. 창이 아예 오지 않은 칸도 측정 못 함으로 채움 [해석]
        val classifier = PatternClassifier()
        classifier.window(60_000L, micro = 10f, large = 8f) // 이상
        classifier.window(120_000L, micro = 10f, large = 8f, measured = false) // 측정 못 함
        classifier.window(300_000L, micro = 20f, large = 4f) // 2분 비고 4분째 정상
        assertEquals(1, classifier.state.abnormalMinutes10m)
        assertEquals(PatternType.AMBIGUOUS, classifier.state.patternType)
    }

    @Test
    fun safetyBypassNeedsThreeSuddenSteersWithinFiveMinutes() {
        // 급조향 시각 (0, 100, 299.9) → 신호 1건, (0, 100) → 0건, (0, 100, 300.1) → 0건 (REQ_PAT_005)
        assertEquals(1, bypassSignals(0L, 100_000L, 299_900L))
        assertEquals(0, bypassSignals(0L, 100_000L))
        assertEquals(0, bypassSignals(0L, 100_000L, 300_100L))
        // 300.0 s는 '5분 안'에 넣음 [해석]. 4회째가 이어져도 신호는 한 번
        assertEquals(1, bypassSignals(0L, 100_000L, 300_000L))
        assertEquals(1, bypassSignals(0L, 100_000L, 200_000L, 250_000L))
    }

    @Test
    fun safetyBypassStaysOnForFiveMinutes() {
        // 상태를 30초마다 내보내므로 3회째 급조향부터 5분 동안 켜 둔다 [제안값]
        val classifier = PatternClassifier()
        listOf(0L, 100_000L, 200_000L).forEach { classifier.onSudden(it) }
        assertTrue(classifier.state.safetyBypass)
        classifier.window(499_900L, micro = 20f, large = 4f)
        assertTrue(classifier.state.safetyBypass)
        classifier.window(500_000L, micro = 20f, large = 4f)
        assertFalse(classifier.state.safetyBypass)
    }

    /** 급조향을 차례로 넣으며 안전 우회 신호가 꺼짐→켜짐으로 바뀐 횟수를 센다 */
    private fun bypassSignals(vararg suddenMs: Long): Int {
        val classifier = PatternClassifier()
        var on = false
        var signals = 0
        for (t in suddenMs) {
            classifier.onSudden(t)
            val now = classifier.state.safetyBypass
            if (now && !on) signals++
            on = now
        }
        return signals
    }

    // ── 급조향 칸: 그 급조향까지의 60초로 이상 분을 본다 (REQ_PAT_001, 고칠 것 1) ──

    /** 급조향까지의 60초 창 하나를 넣는다 (기준 미세 20·큰 4이므로 미세 14 이하면 '줄어든 것') */
    private fun PatternClassifier.suddenWindow(tMs: Long, micro: Float, measured: Boolean = true) =
        onSuddenWindow(tMs, micro, 0f, measured, reference)

    @Test
    fun suddenWindowMarksTheMinuteHoldingTheSudden() {
        // 칸 0 = 60 s 창. 90 s에 급조향, 그 60초는 미세 10(기준의 50%) → 이상 분 조건 만족.
        // 120 s 창(칸 1)은 미세가 많아 원래 규칙으로는 정상이지만, 90 s 급조향이 든 칸이라 이상 분이 된다
        val classifier = PatternClassifier()
        classifier.window(60_000L, micro = 20f, large = 0f)
        classifier.suddenWindow(90_000L, micro = 10f)
        classifier.window(90_000L, micro = 18f, large = 0f, sudden = 1) // 칸 0의 두 번째 창: 건너뜀
        assertEquals(0, classifier.state.abnormalMinutes10m)
        classifier.window(120_000L, micro = 19f, large = 0f, sudden = 1)
        assertEquals(1, classifier.state.abnormalMinutes10m)
        // 이상 분은 마지막 칸(칸 1)이라 아직 복귀를 못 봄 → 애매. 다음 칸이 정상이면 튐형
        assertEquals(PatternType.AMBIGUOUS, classifier.state.patternType)
        classifier.window(180_000L, micro = 20f, large = 0f)
        assertEquals(1, classifier.state.abnormalMinutes10m)
        assertEquals(PatternType.SPIKE, classifier.state.patternType)
    }

    @Test
    fun oneSuddenMarksOnlyOneMinute() {
        // 급조향 칸도 이상이고 원래 규칙으로도 이상이면 이상 분은 1개 (급조향 하나는 칸 하나에만)
        val classifier = PatternClassifier()
        classifier.window(60_000L, micro = 20f, large = 0f)
        classifier.suddenWindow(100_000L, micro = 10f)
        classifier.window(120_000L, micro = 10f, large = 0f, sudden = 1)
        classifier.window(150_000L, micro = 10f, large = 0f, sudden = 1)
        classifier.window(180_000L, micro = 20f, large = 0f)
        assertEquals(1, classifier.state.abnormalMinutes10m)
        assertEquals(PatternType.SPIKE, classifier.state.patternType)
    }

    @Test
    fun suddenWindowNeedsFewMicroAndEnoughGatedTime() {
        // 그 60초 미세가 15(기준의 75%)면 이상 아님, 게이트 통과가 30초 미만(measured = false)이면 판단 안 함
        val classifier = PatternClassifier()
        classifier.window(60_000L, micro = 20f, large = 0f)
        classifier.suddenWindow(90_000L, micro = 15f)
        classifier.suddenWindow(100_000L, micro = 5f, measured = false)
        classifier.window(120_000L, micro = 20f, large = 0f)
        classifier.window(180_000L, micro = 20f, large = 0f)
        assertEquals(0, classifier.state.abnormalMinutes10m)
        assertEquals(PatternType.NONE, classifier.state.patternType)
    }

    @Test
    fun resetDropsPendingSudden() {
        val classifier = PatternClassifier()
        classifier.window(60_000L, micro = 20f, large = 0f)
        classifier.suddenWindow(90_000L, micro = 10f)
        classifier.reset()
        classifier.window(120_000L, micro = 20f, large = 0f)
        classifier.window(180_000L, micro = 20f, large = 0f)
        assertEquals(0, classifier.state.abnormalMinutes10m)
    }

    // ── 딴짓 기록 (REQ_PAT_004) ──

    /** 1분 칸 정상 → 이상(미세 0.5배·큰 2배) → 정상: 튐형 */
    private fun PatternClassifier.spike(): PatternClassifier = apply {
        window(60_000L, micro = 20f, large = 4f)
        window(120_000L, micro = 10f, large = 8f)
        window(180_000L, micro = 20f, large = 4f)
    }

    @Test
    fun normalCheckOnSpikeIsOneDistraction() {
        // (정상, 튐형) → 딴짓 기록 1건 (TC_PAT_004). 느림·무응답은 딴짓이 아니다
        val classifier = PatternClassifier().spike()
        assertEquals(PatternType.SPIKE, classifier.state.patternType)
        assertTrue(classifier.onCheckResult(185_000L, normal = true))
        assertEquals(1, classifier.state.distractionCount)
        assertEquals(185_000L, classifier.state.lastDistractionMs)
        assertFalse(classifier.onCheckResult(190_000L, normal = false))
        assertEquals(1, classifier.state.distractionCount)
        // 단계는 바꾸지 않는다: 패턴·이상 분은 그대로
        assertEquals(PatternType.SPIKE, classifier.state.patternType)
        assertEquals(1, classifier.state.abnormalMinutes10m)
    }

    @Test
    fun normalCheckRightAfterSuddenWaitsUntilSpikeIsConfirmed() {
        // 급조향 바로 뒤의 확인: 그 1분 칸이 아직 판정 전이라 패턴이 '없음'. 정상 대답을 후보로 두고,
        // 이상 분 → 다음 1분 복귀로 튐형이 확인되는 순간 기록한다. 기록 시각은 대답 시각
        val classifier = PatternClassifier()
        classifier.window(60_000L, micro = 20f, large = 4f)
        assertFalse(classifier.onCheckResult(70_000L, normal = true))
        assertEquals(0, classifier.state.distractionCount)
        classifier.window(120_000L, micro = 10f, large = 8f)
        assertEquals(PatternType.AMBIGUOUS, classifier.state.patternType)
        assertEquals("복귀를 보기 전에는 기다린다", 0, classifier.state.distractionCount)
        classifier.window(180_000L, micro = 20f, large = 4f)
        assertEquals(PatternType.SPIKE, classifier.state.patternType)
        assertEquals(1, classifier.state.distractionCount)
        assertEquals(70_000L, classifier.state.lastDistractionMs)
        // 이미 기록한 이상 분만으로 튐형인 동안 정상 대답이 또 와도 같은 일이라 새로 세지 않는다
        assertFalse(classifier.onCheckResult(200_000L, normal = true))
        assertEquals(1, classifier.state.distractionCount)
        // 새 이상 분(다른 딴짓) 뒤의 정상 대답은 새 1건. 후보가 여러 번이면 마지막 대답 시각으로 기록
        classifier.window(240_000L, micro = 10f, large = 8f)
        classifier.onCheckResult(245_000L, normal = true)
        classifier.window(300_000L, micro = 20f, large = 4f)
        assertEquals(PatternType.SPIKE, classifier.state.patternType)
        assertEquals(2, classifier.state.distractionCount)
        assertEquals(245_000L, classifier.state.lastDistractionMs)
    }

    @Test
    fun normalCheckFollowedByTrendIsNotDistraction() {
        // (정상, 추세형) → 딴짓 아님, 2단계 유지·10분 뒤 재확인은 1번 규칙 (REQ_PAT_006)
        val classifier = PatternClassifier()
        classifier.window(60_000L, micro = 10f, large = 8f)
        assertFalse(classifier.onCheckResult(65_000L, normal = true))
        var t = 120_000L
        repeat(5) { classifier.window(t, micro = 10f, large = 8f); t += 60_000L }
        assertEquals(PatternType.TREND, classifier.state.patternType)
        assertEquals(0, classifier.state.distractionCount)
        // 추세형인 채로 기다린 시간이 지나도 기록하지 않는다
        repeat(4) { classifier.window(t, micro = 10f, large = 8f); t += 60_000L }
        assertEquals(0, classifier.state.distractionCount)
    }

    @Test
    fun pendingNormalCheckIsDroppedBySlowAnswerOrWhenNothingWasAbnormal() {
        // 느림·무응답이 오면 후보를 지운다. 이상 분이 하나도 없이 기다린 시간이 지나면(정기 확인 등) 버린다
        val slow = PatternClassifier()
        slow.window(60_000L, micro = 20f, large = 4f)
        slow.onCheckResult(70_000L, normal = true)
        slow.onCheckResult(100_000L, normal = false)
        slow.window(120_000L, micro = 10f, large = 8f)
        slow.window(180_000L, micro = 20f, large = 4f)
        assertEquals(PatternType.SPIKE, slow.state.patternType)
        assertEquals(0, slow.state.distractionCount)

        val calm = PatternClassifier()
        var t = 60_000L
        calm.window(t, micro = 20f, large = 4f)
        calm.onCheckResult(t, normal = true)
        repeat(4) { t += 60_000L; calm.window(t, micro = 20f, large = 4f) }
        // 3분이 지난 뒤 생긴 튐형은 그 대답과 상관없다
        t += 60_000L; calm.window(t, micro = 10f, large = 8f)
        t += 60_000L; calm.window(t, micro = 20f, large = 4f)
        assertEquals(PatternType.SPIKE, calm.state.patternType)
        assertEquals(0, calm.state.distractionCount)
    }

    @Test
    fun ambiguousUntilTheEndIsNotRecordedAsDistraction() {
        // 정상 대답 뒤 3분이 지나도 애매(이상 분 4~5)면 딴짓이라고 단정하지 않는다 [해석, 기획서 4-1 원칙 3].
        // 단계 판단(정상 대답 → 2단계에서 내려옴)은 1번 규칙이 따로 한다
        val classifier = PatternClassifier()
        classifier.window(60_000L, micro = 10f, large = 8f)
        classifier.onCheckResult(70_000L, normal = true)
        var t = 120_000L
        repeat(3) { classifier.window(t, micro = 10f, large = 8f); t += 60_000L } // 이상 분 4개 → 애매
        assertEquals(PatternType.AMBIGUOUS, classifier.state.patternType)
        classifier.window(t, micro = 20f, large = 4f) // 300 s: 대답 뒤 3분 지남, 이상 분 4개 그대로 애매
        assertEquals(PatternType.AMBIGUOUS, classifier.state.patternType)
        assertEquals(0, classifier.state.distractionCount)
        t += 60_000L; classifier.window(t, micro = 20f, large = 4f)
        assertEquals(0, classifier.state.distractionCount)
    }

    @Test
    fun resetClearsDistractionRecords() {
        val classifier = PatternClassifier().spike()
        classifier.onCheckResult(185_000L, normal = true)
        classifier.reset()
        assertEquals(0, classifier.state.distractionCount)
        assertNull(classifier.state.lastDistractionMs)
    }
}
