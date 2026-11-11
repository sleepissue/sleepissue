package com.sleepissue.awakedrive.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 공식 콘솔이 실제 기어·속도를 바꾼다. 가짜 제한 상태를 화면에 주입하지 않는다. */
class DrivingRestrictionDeviceTest {
    @Test fun platformRestrictionLocksAndUnlocks() = runBlocking {
        assumeTrue("공식 콘솔 원복 도구와 함께 실행", InstrumentationRegistry.getArguments().getString("role5DrivingUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val flags = info.flags
        info.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        val report = linkedMapOf<String, Any?>()
        val ready = File(context.cacheDir, "ux-test-ready")
        val locked = File(context.cacheDir, "ux-test-locked")
        ready.delete(); locked.delete()
        val car = Car.createCar(context)
        val manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
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
            return result.filter { it.packageName?.toString() == context.packageName && it.isVisibleToUser }
        }
        fun contains(text: String) = nodes().any { it.text?.contains(text) == true }
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(30_000) {
            while (!condition()) delay(100)
        }
        fun button(text: String): AccessibilityNodeInfo? {
            var node = nodes().firstOrNull { it.text?.toString() == text }
            while (node != null && !node.isClickable) node = node.parent
            return node
        }
        suspend fun click(text: String) {
            waitFor { button(text)?.let { it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true }
        }
        try {
            assertFalse("공식 콘솔에서 먼저 정차 상태로 준비해야 함", manager.currentCarUxRestrictions.isRequiresDistractionOptimization)
            context.startActivity(Intent(context, DrivingPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("설정")
            waitFor { contains("연령대 (선택)") }
            report["parkedSettingsVisible"] = true
            ready.writeText("정차 설정 화면 준비")
            waitFor { manager.currentCarUxRestrictions.isRequiresDistractionOptimization }
            report["platformRestricted"] = true
            waitFor { contains("주행 중에는 설정") }
            assertFalse("제한 중 설정 내용 노출", contains("연령대 (선택)"))
            for (text in listOf("설정", "리포트", "데모")) {
                val tab = button(text)
                assertNotNull("잠금 확인 대상 탭 없음: $text", tab)
                assertFalse("제한 중 탭 활성: $text", tab!!.isEnabled)
            }
            report["restrictedTabsDisabled"] = true
            locked.writeText("실제 주행 제한과 화면 잠금 확인")
            waitFor { !manager.currentCarUxRestrictions.isRequiresDistractionOptimization }
            click("설정")
            waitFor { contains("연령대 (선택)") }
            report["parkedSettingsRestored"] = true
        } finally {
            car.disconnect()
            info.flags = flags; automation.serviceInfo = info
            File(context.cacheDir, "driving-restriction-ui.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        }
    }
}
