package com.sleepissue.awakedrive.ui

import org.junit.Assert.*
import org.junit.Test

class FatigueUiMapperTest {

    @Test
    fun waitingStateShowsNoRisk() {
        val state = FatigueUiState()

        assertEquals("판정 대기 중", FatigueUiFormatter.statusText(state))
        assertEquals("측정 전", FatigueUiFormatter.riskText(state))
        assertNull(state.currentRisk)
    }

    @Test
    fun gateWaitDoesNotShowRisk() {
        val state = FatigueUiMapper.fromEngineValues(
            currentRisk = 80f,
            smoothedRisk = 65f,
            measurementStatus = MeasurementStatus.GATE_WAIT
        )

        assertEquals("측정 대기 중", FatigueUiFormatter.statusText(state))
        assertNull(state.currentRisk)
        assertNull(state.smoothedRisk)
    }

    @Test
    fun assistModeDoesNotShowRisk() {
        val state = FatigueUiMapper.fromEngineValues(
            currentRisk = 70f,
            smoothedRisk = 60f,
            measurementStatus = MeasurementStatus.ASSIST,
            assistMode = true
        )

        assertEquals("보조 주행 중", FatigueUiFormatter.statusText(state))
        assertEquals("측정 전", FatigueUiFormatter.riskText(state))
        assertNull(state.currentRisk)
    }

    @Test
    fun validMeasurementShowsActualRisk() {
        val state = FatigueUiMapper.fromEngineValues(
            currentRisk = 75f,
            smoothedRisk = 62f,
            measurementStatus = MeasurementStatus.VALID
        )

        assertEquals("측정 중", FatigueUiFormatter.statusText(state))
        assertEquals("75점", FatigueUiFormatter.riskText(state))
        assertEquals("62점", FatigueUiFormatter.smoothedRiskText(state))
    }

    @Test
    fun invalidMeasurementDoesNotBecomeZeroRisk() {
        val state = FatigueUiMapper.fromEngineValues(
            currentRisk = 0f,
            smoothedRisk = 0f,
            measurementStatus = MeasurementStatus.GATE_WAIT
        )

        assertNull(state.currentRisk)
        assertNull(state.smoothedRisk)
        assertEquals("측정 전", FatigueUiFormatter.riskText(state))
    }
}
