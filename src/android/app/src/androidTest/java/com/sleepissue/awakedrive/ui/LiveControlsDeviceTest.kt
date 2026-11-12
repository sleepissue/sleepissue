package com.sleepissue.awakedrive.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.*
import android.os.IBinder
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.service.MonitoringPipeline
import com.sleepissue.awakedrive.service.RealTripMonitorService
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 실제 서비스·제품 화면 연결 시험. 확인 발생만 합성 점수로 요청하며 발화·마이크를 사용하지 않는다. */
class LiveControlsDeviceTest {
    @Test fun settingsAndPassengerButtons() = runBlocking {
        assumeTrue("설정·권한·가상 차량 복구 도구와 함께 실행", InstrumentationRegistry.getArguments().getString("role5LiveControls") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val originalFlags = info.flags
        info.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        val settings = DriverUiSettingsStore(context)
        val original = settings.read()
        val preferences = context.getSharedPreferences("driver-ui-settings", 0)
        val hadOnboarding = preferences.contains("onboarding")
        val originalOnboarding = preferences.getBoolean("onboarding", false)
        val report = linkedMapOf<String, Any?>("scope" to "REAL_SERVICE_UI_SYNTHETIC_CHECK_TRIGGER_NO_MICROPHONE")
        val connected = CompletableDeferred<RealTripMonitorService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete((binder as RealTripMonitorService.LocalBinder).service)
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false
        var service: RealTripMonitorService? = null
        var sequence = 0
        var startedByTest = false
        fun nodes(): List<AccessibilityNodeInfo> {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(queue::add)
            automation.windows.forEach { it.root?.let(queue::add) }
            val result = mutableListOf<AccessibilityNodeInfo>()
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                result += node
                repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
            }
            return result.filter { it.packageName?.toString() == context.packageName }
        }
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(35_000) {
            while (!condition()) delay(100)
        }
        suspend fun click(text: String) = withTimeout(15_000) {
            report["waitingFor"] = text
            while (true) {
                val all = nodes()
                var button = all.firstOrNull { it.text?.toString() == text }
                while (button != null && !button.isClickable) button = button.parent
                if (button?.isVisibleToUser == true && button.isEnabled && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
                all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(100)
            }
        }
        suspend fun start(): MonitoringPipeline {
            withContext(Dispatchers.Main) {
                context.startForegroundService(Intent(context, RealTripMonitorService::class.java).setAction(RealTripMonitorService.ACTION_START))
            }
            waitFor { service?.pipeline != null }
            startedByTest = true
            return service!!.pipeline!!
        }
        suspend fun stop() {
            withContext(Dispatchers.Main) { service?.stopMonitoring() }
            waitFor { service?.pipeline == null && service?.runtime?.running == false && service?.stopping == false }
        }
        try {
            settings.write(original.copy(microphoneConsent = false, ventilationConsent = false,
                ageBand = "미설정", frequentChecks = false, passengerChecks = true))
            context.startActivity(Intent(context, DrivingPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            bound = context.bindService(Intent(context, RealTripMonitorService::class.java), connection, Context.BIND_AUTO_CREATE)
            service = withTimeout(10_000) { connected.await() }
            assertFalse("기존 실제 감시가 실행 중이면 시험을 중단해야 함", service!!.runtime.running)
            val first = start()
            waitFor { service!!.input?.hub?.latestFrame?.value?.let { it.gear == Gear.P && it.speedKph == 0f } == true }
            delay(750)
            click("설정")
            click("중년")
            waitFor { first.runner.ageBand == AgeBand.THIRTIES_FORTIES }
            click("보조 주행 중 자주 확인 (10분)")
            waitFor { settings.read().frequentChecks }
            click("동승자 확인 사용")
            waitFor { !settings.read().passengerChecks }
            assertEquals(ExecutionStatus.FAILED, first.respondPassenger(true).status)
            report["uiAgeApplied"] = true
            report["frequentSettingSaved"] = true
            report["passengerDisabledRejected"] = true
            click("동승자 확인 사용")
            waitFor { settings.read().passengerChecks }
            stop()
            val pipeline = start()
            assertNotSame(first, pipeline)
            assertEquals(AgeBand.THIRTIES_FORTIES, pipeline.runner.ageBand)
            assertTrue(settings.read().frequentChecks)
            report["monitorRestartRestoredAge"] = true
            report["microphoneConsent"] = settings.read().microphoneConsent
            delay(750)
            click("주행")
            if (!preferences.getBoolean("onboarding", false)) click("선택 내용 저장하고 계속")
            click("개운해요")
            click("다음  ❯")
            click("안전벨트 착용 완료")
            click("운전 준비 완료")
            click("다음  ❯")
            click("주행 화면으로 이동  ❯")
            // 30초 단계 상승 규칙을 실제 시계로 기다린다. 센서가 졸음을 검출했다고 주장하지 않는다.
            suspend fun score() = pipeline.manager.submit(RequestId("live-controls-${++sequence}"),
                InterventionInput.Assessment(DrivingAssessment(service!!.clock.scenarioTimeMs(),
                    EvaluationStatus.EVALUATED, 65f, DrivingPattern.UNCERTAIN, false)))
            score()
            delay(31_000)
            score()
            waitFor { pipeline.manager.state.value.stage == InterventionStage.CHECK }
            click("설정")
            click("동승자 확인 사용")
            waitFor { !settings.read().passengerChecks }
            delay(500)
            assertEquals(ExecutionStatus.FAILED, pipeline.respondPassenger(false).status)
            report["passengerDisabledDuringPendingCheckRejected"] = true
            click("동승자 확인 사용")
            waitFor { settings.read().passengerChecks }
            delay(500)
            click("주행")
            click("동승자: 괜찮아요")
            waitFor { nodes().any { it.text?.toString() == "동승자 응답을 전달했습니다" } }
            assertEquals(InterventionStage.CHECK, pipeline.manager.state.value.stage)
            assertEquals(ExecutionStatus.FAILED, pipeline.respondPassenger(false).status)
            report["passengerOkayUiDeliveredOnce"] = true
            // 같은 단계에서 새 요청을 만들어 ‘쉬어 가요’ 버튼의 연결도 확인한다.
            pipeline.manager.submit(RequestId("live-controls-repeat"), InterventionInput.Safety(
                SafetyEvent(service!!.clock.scenarioTimeMs(), SafetyEventType.RAPID_STEERING, Source.REAL)))
            delay(500)
            click("동승자: 쉬어 가요")
            waitFor { pipeline.manager.state.value.stage == InterventionStage.REST_GUIDANCE }
            assertEquals(TransitionReason.CHECK_RESULT, pipeline.manager.state.value.reason)
            report["passengerRestUiStage3"] = true
        } finally {
            report["visibleTextsAtEnd"] = nodes().mapNotNull { it.text?.toString() }.distinct()
            automation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "live-controls-device.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            if (startedByTest && service?.runtime?.running == true) stop()
            if (bound) context.unbindService(connection)
            settings.write(original)
            preferences.edit().also {
                if (hadOnboarding) it.putBoolean("onboarding", originalOnboarding) else it.remove("onboarding")
            }.commit()
            info.flags = originalFlags
            automation.serviceInfo = info
            File(context.cacheDir, "live-controls-device.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        }
    }
}
