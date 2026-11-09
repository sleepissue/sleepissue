package com.sleepissue.awakedrive.engine.fatigue

import org.junit.Assert.assertEquals
import org.junit.Test

/** f미세·f큰·f급조향 시험. 기대값은 SRS v0.2 REQ_FAT_009~011 합격기준. */
class FatigueFactorsTest {

    @Test
    fun fMicroMatchesSrs() {
        // 기준 20, 현재 20 → 0, 15 → 0.5, 10 → 1.0, 5 → 1.0(상한), 25 → 0(하한) (REQ_FAT_009)
        assertEquals(0f, FatigueFactors.fMicro(20f, 20f), 1e-6f)
        assertEquals(0.5f, FatigueFactors.fMicro(15f, 20f), 1e-6f)
        assertEquals(1f, FatigueFactors.fMicro(10f, 20f), 1e-6f)
        assertEquals(1f, FatigueFactors.fMicro(5f, 20f), 1e-6f)
        assertEquals(0f, FatigueFactors.fMicro(25f, 20f), 1e-6f)
    }

    @Test
    fun fLargeMatchesSrs() {
        // 기준 4, 현재 4 → 0, 6 → 0.5, 8 → 1.0, 12 → 1.0(상한), 2 → 0(하한) (REQ_FAT_010)
        assertEquals(0f, FatigueFactors.fLarge(4f, 4f), 1e-6f)
        assertEquals(0.5f, FatigueFactors.fLarge(6f, 4f), 1e-6f)
        assertEquals(1f, FatigueFactors.fLarge(8f, 4f), 1e-6f)
        assertEquals(1f, FatigueFactors.fLarge(12f, 4f), 1e-6f)
        assertEquals(0f, FatigueFactors.fLarge(2f, 4f), 1e-6f)
    }

    @Test
    fun fSuddenMatchesSrs() {
        // 0회 → 0, 1회 → 0.5, 2회 → 1.0, 3회 → 1.0(상한) (REQ_FAT_011)
        assertEquals(0f, FatigueFactors.fSudden(0), 1e-6f)
        assertEquals(0.5f, FatigueFactors.fSudden(1), 1e-6f)
        assertEquals(1f, FatigueFactors.fSudden(2), 1e-6f)
        assertEquals(1f, FatigueFactors.fSudden(3), 1e-6f)
    }

    @Test
    fun zeroBaselineIsHandled() {
        // 기준이 0이면: f미세 0, f큰은 지금 큰 수정이 있으면 1 [해석]
        assertEquals(0f, FatigueFactors.fMicro(5f, 0f), 0f)
        assertEquals(1f, FatigueFactors.fLarge(1f, 0f), 0f)
        assertEquals(0f, FatigueFactors.fLarge(0f, 0f), 0f)
    }
}
