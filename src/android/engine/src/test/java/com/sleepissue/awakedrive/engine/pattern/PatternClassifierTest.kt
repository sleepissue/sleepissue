package com.sleepissue.awakedrive.engine.pattern

import com.sleepissue.awakedrive.engine.model.PatternType
import com.sleepissue.awakedrive.engine.model.SteeringBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이상 분·추세형·튐형·안전 우회 시험. 기대값은 SRS v0.2 REQ_PAT_001~003·005 합격기준. */
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
}
