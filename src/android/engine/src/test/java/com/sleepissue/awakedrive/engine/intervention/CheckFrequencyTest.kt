package com.sleepissue.awakedrive.engine.intervention

import com.sleepissue.awakedrive.engine.config.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckFrequencyTest {
    private val config = EngineConfig()
    @Test fun `기본 설정은 보조 주행 기본 간격을 따른다`() {
        assertEquals(config.assistedCheckIntervalMs, assistedCheckIntervalMs(config, false, false))
    }
    @Test fun `자주 확인은 기존 위험 맥락 간격을 사용한다`() {
        assertEquals(config.assistedRiskCheckIntervalMs, assistedCheckIntervalMs(config, true, false))
    }
    @Test fun `위험 맥락에서는 사용자 설정과 무관하게 짧은 간격을 따른다`() {
        assertEquals(config.assistedRiskCheckIntervalMs, assistedCheckIntervalMs(config, false, true))
        assertEquals(config.assistedRiskCheckIntervalMs, assistedCheckIntervalMs(config, true, true))
    }
}
