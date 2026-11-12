package com.sleepissue.awakedrive.climate

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.pleos.PleosSeatVentilationDevice
import com.sleepissue.awakedrive.signal.ClimateAction
import com.sleepissue.awakedrive.signal.ClimateRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 기획서 6장: 동의 없으면 제어하지 않고, 켠 뒤 재조회하며 원래 값으로 되돌린다. */
class VentilationDeviceTest {
    @Test fun consentReadbackAndRestore() = runBlocking {
        assumeTrue("통풍 기기 시험을 명시한 경우 실행", InstrumentationRegistry.getArguments().getString("role5ClimateDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = PleosSeatVentilationDevice(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val config = EngineConfig()
        val report = linkedMapOf<String, Any?>()
        var original: Int? = null
        var prompts = 0
        try {
            val capability = withTimeout(10_000) { device.capability() }
            assertNotNull("운전석 통풍 지원 정보 없음", capability)
            assertTrue("운전석 통풍 읽기 미지원", capability!!.readable)
            assertTrue("운전석 통풍 쓰기 미지원", capability.writable)
            original = withTimeout(10_000) { device.read() }
            assertNotNull("원래 통풍 값 미수신", original)
            report["original"] = original
            val controller = DriverVentilationController(device, InMemoryVentilationMemory(), { prompts++ }, scope, config)
            val denied = controller.start(ClimateRequest(RequestId("device-no-consent"),
                ClimateAction.ENABLE_DRIVER_VENTILATION, priorConsent = false))
            report["withoutConsent"] = denied
            assertEquals(ExecutionFailure.PERMISSION_DENIED, denied.failure)
            assertEquals(original, device.read())

            // 시험 시작값을 꺼짐으로 맞춘다. 원래 사용자 값은 마지막에 별도로 복구한다.
            device.write(0)
            assertEquals(0, device.read())
            suspend fun execute(id: String, action: ClimateAction): ExecutionResult = coroutineScope {
                val requestId = RequestId(id)
                val final = async(start = CoroutineStart.UNDISPATCHED) {
                    controller.results.first { it.requestId == requestId && it.status in
                        setOf(ExecutionStatus.SUCCEEDED, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED) }
                }
                val accepted = controller.start(ClimateRequest(requestId, action, priorConsent = true))
                if (accepted.status == ExecutionStatus.FAILED) { final.cancel(); accepted }
                else withTimeout(config.ventilationVerifyTimeoutMs + config.ventilationManualWaitMs + 10_000) { final.await() }
            }
            val enabled = execute("device-enable", ClimateAction.ENABLE_DRIVER_VENTILATION)
            val afterEnable = device.read()
            report["enabled"] = enabled
            report["afterEnable"] = afterEnable
            report["manualPrompts"] = prompts
            assertEquals(ExecutionStatus.SUCCEEDED, enabled.status)
            assertTrue("재조회한 통풍 값이 켜짐이 아님", afterEnable != null && afterEnable > 0)
            assertEquals("자동 제어 반영 대신 수동 안내로 넘어감", 0, prompts)
            val restored = execute("device-restore", ClimateAction.RESTORE_DRIVER_VENTILATION)
            report["restored"] = restored
            report["afterRestore"] = device.read()
            assertEquals(ExecutionStatus.SUCCEEDED, restored.status)
            assertEquals(0, device.read())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            withContext(NonCancellable) {
                try {
                    original?.let { withTimeout(10_000) { device.write(it) } }
                    report["afterCleanup"] = withTimeout(10_000) { device.read() }
                } finally {
                    device.close()
                    File(context.cacheDir, "ventilation-device.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
                }
            }
        }
    }
}
