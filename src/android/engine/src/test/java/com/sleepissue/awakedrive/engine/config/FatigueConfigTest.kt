package com.sleepissue.awakedrive.engine.config

import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.InvalidReason
import com.sleepissue.awakedrive.engine.model.VulnerableTimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2번 설정 묶음의 기본값이 SRS v0.2 합격기준·기획서 숫자와 서로 맞는지 확인한다.
 * 계산 로직 테스트가 아니라 "값을 잘못 옮기지 않았는지" 보는 안전장치다.
 *
 * 묶음은 팀 EngineConfig의 fatigue·baseline·pattern·assist 칸에 들어 있다. 여기서는 묶음을 직접 만들어 확인한다.
 */
class FatigueConfigTest {

    private val fatigue = FatigueConfig()
    private val context = fatigue.context

    @Test
    fun scoreWeightsSumToOne() {
        // B = 100 × (0.35 + 0.25 + 0.40) 일 때 최대 100 (REQ_FAT_012)
        val sum = fatigue.weightMicro + fatigue.weightLarge + fatigue.weightSudden
        assertEquals(1.0f, sum, 1e-6f)
    }

    @Test
    fun allContextWeightsAddUpToKMax() {
        // "5개 전부 → 1.35" (REQ_FAT_013 합격기준)
        val all = context.kBase +
            context.longDriveWeight +
            context.vulnerableTimeWeight +
            context.soloDriveWeight +
            context.warmCabinWeight +
            context.longDayDriveWeight
        assertEquals(context.kMax, all, 1e-6f)
    }

    @Test
    fun smoothingAlphaMatchesSrs() {
        // 시정수 180 s, 갱신 30 s, α = 0.1535 (REQ_FAT_020 합격기준)
        assertEquals(0.1535, fatigue.smoothingAlpha, 1e-4)
    }

    @Test
    fun microAndLargeRangesDoNotOverlap() {
        // 3.1~4.9°는 어느 쪽에도 세지 않는다 (REQ_FAT_007 합격기준)
        assertTrue(fatigue.microMinDeg < fatigue.microMaxDeg)
        assertTrue(fatigue.microMaxDeg < fatigue.largeMinDeg)
    }

    @Test
    fun defaultVulnerableWindowsMatchSrsBoundaries() {
        // 01:59 → 0, 02:00 → +0.10, 06:59 → +0.10, 07:00 → 0, 13:59 → 0, 14:00 → +0.10 (REQ_FAT_015)
        fun vulnerable(hour: Int) = context.vulnerableBaseRanges.any { it.contains(hour * 60) }
        assertFalse(vulnerable(1))
        assertTrue(vulnerable(2))
        assertTrue(vulnerable(6))
        assertFalse(vulnerable(7))
        assertFalse(vulnerable(13))
        assertTrue(vulnerable(14))
        assertTrue(vulnerable(16))
        assertFalse(vulnerable(17))
    }

    @Test
    fun thirtiesFortiesWindowCrossesMidnight() {
        // 30대 설정 시 22:00 → +0.10 (REQ_FAT_015). 22~02시는 자정을 넘는다
        val window = context.vulnerableAgeRanges.getValue(AgeBand.THIRTIES_FORTIES).single()
        assertFalse(window.contains(21 * 60 + 59))
        assertTrue(window.contains(22 * 60))
        assertTrue(window.contains(0))
        assertTrue(window.contains(60 + 59))
        assertFalse(window.contains(2 * 60))
    }

    @Test
    fun everyAgeBandHasItsOwnRange() {
        // 연령대를 설정하지 않으면(null) 더하는 시간대가 없고, 설정하면 연령대마다 하나씩 더한다 (REQ_FAT_015)
        assertEquals(AgeBand.values().toSet(), context.vulnerableAgeRanges.keys)
    }

    @Test
    fun vulnerableRangesAreTheSameAsEngineConfig() {
        // 예보(4번)·1단계 안내(1번)와 K(2번)가 같은 취약 시간대를 쓴다. 값은 VulnerableTimeDefaults 한 곳
        val team = EngineConfig()
        assertEquals(team.vulnerableBaseRanges, context.vulnerableBaseRanges)
        assertEquals(team.vulnerableAgeRanges, context.vulnerableAgeRanges)
    }

    @Test
    fun engineConfigCarriesFatigueBundles() {
        // CLAUDE.md 4장: 2번 문턱값도 팀 EngineConfig 한 곳에서 꺼낸다
        val team = EngineConfig()
        assertEquals(FatigueConfig(), team.fatigue)
        assertEquals(BaselineConfig(), team.baseline)
        assertEquals(PatternConfig(), team.pattern)
        assertEquals(AssistConfig(), team.assist)
    }

    @Test
    fun resolvedFatigueConfigTakesTeamVulnerableRanges() {
        // 팀이 EngineConfig의 취약 시간대를 바꾸면 K 계산에도 그 값이 들어간다
        val midnightOnly = listOf(VulnerableTimeRange.hours(0, 1))
        val team = EngineConfig(vulnerableBaseRanges = midnightOnly, vulnerableAgeRanges = emptyMap())
        val resolved = team.resolvedFatigueConfig()
        assertEquals(midnightOnly, resolved.context.vulnerableBaseRanges)
        assertTrue(resolved.context.vulnerableAgeRanges.isEmpty())
        // 나머지 2번 값은 그대로
        assertEquals(team.fatigue.copy(context = resolved.context), resolved)
    }

    @Test
    fun defaultFatigueStateIsWaitingForGate() {
        // 처음에는 지표가 없으므로 '측정 대기' (REQ_UI_003)
        val state = FatigueState(tMs = 0L)
        assertFalse(state.valid)
        assertEquals(InvalidReason.GATE_WAIT, state.invalidReason)
        assertEquals(1f, state.contextK, 0f)
    }

}
