package com.sleepissue.awakedrive.climate

import com.sleepissue.awakedrive.engine.climate.VentilationCapability
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.signal.ClimateAction
import com.sleepissue.awakedrive.signal.ClimateRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// SRS CLM / 기획서 6장 통풍 규칙: 호출 → 다시 읽어 확인 → 안 되면 "통풍을 켜 보세요" → 운전자 조작 확인 → 휴식 뒤 되돌림.
@OptIn(ExperimentalCoroutinesApi::class)
class DriverVentilationControllerTest {

    /** applyWrites=false면 공식 샘플 기록처럼 쓰기가 반영되지 않는 차. */
    private class FakeDevice(
        var level: Int = 0,
        var capability: VentilationCapability? = VentilationCapability(0, 3, readable = true, writable = true),
        var applyWrites: Boolean = true,
        var denied: Boolean = false,
    ) : SeatVentilationDevice {
        val writes = mutableListOf<Int>()
        override suspend fun capability(): VentilationCapability? {
            if (denied) throw SecurityException("CAR_SEATS 없음")
            return capability
        }
        override suspend fun read(): Int? = level
        override suspend fun write(level: Int) {
            writes += level
            if (applyWrites) this.level = level
        }
    }

    private val device = FakeDevice()
    private val memory = InMemoryVentilationMemory()
    private var prompts = 0
    // 안내를 들은 운전자가 직접 켜는 흉내
    private var driverTurnsOnTo: Int? = null

    private fun TestScope.controller(): Pair<DriverVentilationController, MutableList<ExecutionResult>> {
        val c = DriverVentilationController(device, memory, {
            prompts++
            driverTurnsOnTo?.let { device.level = it }
        }, workScope())
        val results = mutableListOf<ExecutionResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.results.toList(results) }
        return c to results
    }

    private fun enable(id: String, consent: Boolean = true) =
        ClimateRequest(RequestId(id), ClimateAction.ENABLE_DRIVER_VENTILATION, priorConsent = consent)

    private fun restore(id: String) = ClimateRequest(RequestId(id), ClimateAction.RESTORE_DRIVER_VENTILATION, priorConsent = true)

    @Test
    fun `동의 없으면 바로 FAILED, 장치를 건드리지 않음`() = runTest {
        val (c, _) = controller()
        assertEquals(ExecutionFailure.PERMISSION_DENIED, c.start(enable("e0", consent = false)).failure)
        assertTrue(device.writes.isEmpty())
    }

    @Test
    fun `SDK로 켜지고 다시 읽어 확인되면 SUCCEEDED, 바꾸기 전 값 저장`() = runTest {
        val (c, results) = controller()
        assertEquals(ExecutionStatus.REQUESTED, c.start(enable("e1")).status)
        advanceUntilIdle()
        assertEquals(listOf(ExecutionStatus.STARTED, ExecutionStatus.SUCCEEDED), results.map { it.status })
        assertEquals(listOf(3), device.writes)
        assertEquals(0, memory.priorLevel)
        assertEquals(0, prompts)
        assertTrue(c.activeByApp.value)
    }

    @Test
    fun `쓰기가 반영 안 되면 안내하고, 운전자가 켜면 SUCCEEDED`() = runTest {
        device.applyWrites = false
        driverTurnsOnTo = 1
        val (c, results) = controller()
        c.start(enable("e2"))
        advanceUntilIdle()
        assertEquals(1, prompts)
        assertEquals(ExecutionStatus.SUCCEEDED, results.last().status)
        assertEquals(0, memory.priorLevel) // 운전자가 켠 것도 휴식 뒤 되돌림 [가정]
    }

    @Test
    fun `안내 뒤에도 안 켜지면 TIMED_OUT, 되돌릴 값 지움`() = runTest {
        device.applyWrites = false
        val (c, results) = controller()
        c.start(enable("e3"))
        advanceUntilIdle()
        assertEquals(ExecutionResult(RequestId("e3"), ExecutionStatus.FAILED, ExecutionFailure.TIMED_OUT), results.last())
        assertNull(memory.priorLevel)
    }

    @Test
    fun `쓰기 권한이 없으면 SDK 호출 없이 바로 안내`() = runTest {
        device.capability = VentilationCapability(0, 3, readable = true, writable = false)
        driverTurnsOnTo = 2
        val (c, results) = controller()
        c.start(enable("e4"))
        advanceUntilIdle()
        assertTrue(device.writes.isEmpty())
        assertEquals(ExecutionStatus.SUCCEEDED, results.last().status)
    }

    @Test
    fun `운전석 통풍이 없으면 NOT_SUPPORTED, 안내하지 않음`() = runTest {
        device.capability = null
        val (c, results) = controller()
        c.start(enable("e5"))
        advanceUntilIdle()
        assertEquals(ExecutionFailure.NOT_SUPPORTED, results.last().failure)
        assertEquals(0, prompts)
    }

    @Test
    fun `읽기 권한이 없으면 PERMISSION_DENIED`() = runTest {
        device.denied = true
        val (c, results) = controller()
        c.start(enable("e6"))
        advanceUntilIdle()
        assertEquals(ExecutionFailure.PERMISSION_DENIED, results.last().failure)
    }

    @Test
    fun `이미 켜져 있으면 그대로 두고 되돌리지도 않음`() = runTest {
        device.level = 3
        val (c, results) = controller()
        c.start(enable("e7"))
        c.start(restore("r7"))
        advanceUntilIdle()
        assertTrue(device.writes.isEmpty())
        assertEquals(ExecutionStatus.SUCCEEDED, results.last().status)
    }

    @Test
    fun `휴식 인증 뒤 되돌리기 - 바꾸기 전 값으로`() = runTest {
        device.level = 1
        val (c, results) = controller()
        c.start(enable("e8"))
        advanceUntilIdle()
        c.start(restore("r8"))
        advanceUntilIdle()
        assertEquals(listOf(3, 1), device.writes)
        assertEquals(1, device.level)
        assertNull(memory.priorLevel)
        assertEquals(ExecutionResult(RequestId("r8"), ExecutionStatus.SUCCEEDED), results.last())
    }

    @Test
    fun `되돌리기가 반영 안 되면 FAILED, 값을 남겨 다음에 다시`() = runTest {
        memory.priorLevel = 0
        device.level = 3
        device.applyWrites = false
        val (c, results) = controller()
        c.start(restore("r9"))
        advanceUntilIdle()
        assertEquals(ExecutionFailure.TIMED_OUT, results.last().failure)
        assertEquals(0, memory.priorLevel)
    }

    @Test
    fun `두 번째 켜기는 처음 저장한 값을 지킴`() = runTest {
        device.level = 1
        val (c, _) = controller()
        c.start(enable("e10"))
        advanceUntilIdle()
        device.level = 2 // 운전자가 중간에 낮춤
        c.start(enable("e11"))
        advanceUntilIdle()
        assertEquals(1, memory.priorLevel)
    }

    // 같은 가상 시계를 쓰는 작업 scope. backgroundScope에 띄운 작업은 advanceUntilIdle이 돌려 주지 않는다.
    private fun TestScope.workScope() = CoroutineScope(StandardTestDispatcher(testScheduler))
}
