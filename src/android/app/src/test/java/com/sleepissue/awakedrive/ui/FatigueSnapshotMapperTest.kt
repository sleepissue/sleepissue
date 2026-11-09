package com.sleepissue.awakedrive.ui

import com.sleepissue.awakedrive.engine.model.AssistState
import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.InvalidReason
import com.sleepissue.awakedrive.fatigue.FatigueBridge
import org.junit.Assert.*
import org.junit.Test

class FatigueSnapshotMapperTest {

    @Test
    fun initialSnapshotShowsWaiting() {
        val snapshot = FatigueBridge().snapshot

        val uiState = snapshot.toFatigueUiState()

        assertEquals(
            MeasurementStatus.WAITING,
            uiState.measurementStatus
        )
        assertNull(uiState.currentRisk)
        assertNull(uiState.smoothedRisk)
    }

    @Test
    fun validSnapshotPreservesRiskScores() {
        val snapshot = FatigueBridge().snapshot.copy(
            fatigue = FatigueState(
                tMs = 30000L,
                riskR = 75f,
                riskSmoothed = 62f,
                valid = true,
                invalidReason = InvalidReason.NONE
            )
        )

        val uiState = snapshot.toFatigueUiState()

        assertEquals(
            MeasurementStatus.VALID,
            uiState.measurementStatus
        )
        assertEquals(75f, uiState.currentRisk!!, 0.001f)
        assertEquals(62f, uiState.smoothedRisk!!, 0.001f)
    }

    @Test
    fun assistSnapshotHidesInvalidRisk() {
        val snapshot = FatigueBridge().snapshot.copy(
            fatigue = FatigueState(
                tMs = 30000L,
                riskR = 0f,
                riskSmoothed = 50f,
                valid = false,
                invalidReason = InvalidReason.ASSIST
            ),
            assist = AssistState(
                assistMode = true
            )
        )

        val uiState = snapshot.toFatigueUiState()

        assertEquals(
            MeasurementStatus.ASSIST,
            uiState.measurementStatus
        )
        assertNull(uiState.currentRisk)
        assertNull(uiState.smoothedRisk)
        assertEquals(true, uiState.assistMode)
    }
}
