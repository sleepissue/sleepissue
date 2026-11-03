package com.sleepissue.awakedrive.engine.config

import com.sleepissue.awakedrive.engine.model.AgeGroup
import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.InvalidReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기본 설정값이 SRS v0.2 합격기준·기획서 숫자와 서로 맞는지 확인한다.
 * 계산 로직 테스트가 아니라 "값을 잘못 옮기지 않았는지" 보는 안전장치다.
 */
class EngineConfigTest {

    private val config = EngineConfig()
    private val fatigue = config.fatigue
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
        fun vulnerable(hour: Int) = context.vulnerableWindows.any { it.contains(hour) }
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
        val window = context.vulnerableWindowsByAge.getValue(AgeGroup.THIRTIES_FORTIES).single()
        assertFalse(window.contains(21))
        assertTrue(window.contains(22))
        assertTrue(window.contains(0))
        assertTrue(window.contains(1))
        assertFalse(window.contains(2))
    }

    @Test
    fun unsetAgeGroupAddsNoWindow() {
        assertFalse(context.vulnerableWindowsByAge.containsKey(AgeGroup.UNSET))
    }

    @Test
    fun defaultFatigueStateIsWaitingForGate() {
        // 처음에는 지표가 없으므로 '측정 대기' (REQ_UI_003)
        val state = FatigueState(tMs = 0L)
        assertFalse(state.valid)
        assertEquals(InvalidReason.GATE_WAIT, state.invalidReason)
        assertEquals(1f, state.contextK, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun hourWindowRejectsEmptyRange() {
        HourWindow(5, 5)
    }
}
