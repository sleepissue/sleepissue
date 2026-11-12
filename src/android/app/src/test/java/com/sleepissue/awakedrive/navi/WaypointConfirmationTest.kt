package com.sleepissue.awakedrive.navi

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WaypointConfirmationTest {
    @Test fun `반영 확인 뒤 화면 요청이 길어져도 추가 성공은 유지한다`() = runTest {
        var opened = false
        val result = confirmRestWaypoint(EngineConfig(waypointConfirmTimeoutMs = 2000), confirm = {
            delay(1000)
            WaypointOutcome.Added
        }, afterAdded = {
            delay(3000)
            opened = true
        })
        assertEquals(WaypointOutcome.Added, result)
        assertTrue(opened)
    }

    @Test fun `반영 확인이 한도를 넘으면 실패하고 후속 화면은 열지 않는다`() = runTest {
        var opened = false
        val config = EngineConfig()
        val result = confirmRestWaypoint(config, confirm = {
            delay(config.waypointConfirmTimeoutMs + 1)
            WaypointOutcome.Added
        }, afterAdded = { opened = true })
        assertEquals(ExecutionFailure.TIMED_OUT, (result as WaypointOutcome.Failed).failure)
        assertFalse(opened)
    }
}
