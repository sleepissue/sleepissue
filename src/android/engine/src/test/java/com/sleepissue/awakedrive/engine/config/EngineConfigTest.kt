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
}
