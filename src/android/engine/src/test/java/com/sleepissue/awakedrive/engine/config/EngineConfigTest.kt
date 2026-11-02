package com.sleepissue.awakedrive.engine.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineConfigTest {
    @Test
    fun normalAndSensitiveThresholdsMatchCurrentPlan() {
        val config = EngineConfig()

        assertEquals(StageThresholds(40, 60, 80), config.normalStageThresholds)
        assertEquals(StageThresholds(30, 50, 70), config.sensitiveStageThresholds)
        assertEquals(100L, config.signalFrameIntervalMs)
        assertEquals(10_000L, config.stateSaveIntervalMs)
    }

    @Test
    fun reversedThresholdsAndInvalidIntervalsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { StageThresholds(60, 40, 80) }
        assertThrows(IllegalArgumentException::class.java) { StageThresholds(40, 40, 80) }
        assertThrows(IllegalArgumentException::class.java) { StageThresholds(40, 60, 101) }
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(signalFrameIntervalMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(stateSaveIntervalMs = -1) }
    }

    @Test
    fun tripBoundariesAreOrderedAndConfigurable() {
        val config = EngineConfig()
        assertEquals(300_000L, config.tripStopPauseMs)
        assertEquals(900_000L, config.tripRestResetMs)
        assertEquals(7_200_000L, config.newTripAfterOffMs)
        assertEquals(86_400_000L, config.rollingDriveWindowMs)
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(tripStopPauseMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(tripStopPauseMs = 900_000) }
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(tripRestResetMs = 300_000) }
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(newTripAfterOffMs = 899_999) }
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(rollingDriveWindowMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(rollingDriveWindowMs = 299_999) }
    }

    @Test
    fun invalidInterventionDelaysCountsAndDistancesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(interventionStepMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(slowCheckCountForRest = 0) }
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(waypointAutoAddGraceMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { EngineConfig(restDistanceReminderM = Float.NaN) }
    }
}
