package com.sleepissue.awakedrive.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 실제 제품 화면과 시스템 권한 창을 조작한다. 권한 자체는 실행 도구가 시험 후 원복한다. */
class FirstRunPermissionDeviceTest {
    @Test fun firstRunDenialAndRetry() = runBlocking {
        assumeTrue("권한 원복 도구와 함께 실행", InstrumentationRegistry.getArguments().getString("role5PermissionUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val accessibility = instrumentation.uiAutomation.serviceInfo
        val originalFlags = accessibility.flags
        accessibility.flags = accessibility.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = accessibility
        val preferences = context.getSharedPreferences("driver-ui-settings", 0)
        val hadOnboarding = preferences.contains("onboarding")
        val onboarding = preferences.getBoolean("onboarding", false)
        val store = DriverUiSettingsStore(context)
        val original = store.read()
        val report = linkedMapOf<String, Any?>()

        fun nodes(): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            instrumentation.uiAutomation.windows.forEach { it.root?.let(queue::add) }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                result += node
                repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
            }
            return result
        }
        suspend fun waitFor(predicate: () -> Boolean) = withTimeout(15_000) {
            while (!predicate()) delay(100)
        }
        fun matches(node: AccessibilityNodeInfo, text: String) =
            node.text?.contains(text) == true || node.contentDescription?.contains(text) == true
        suspend fun click(text: String) = withTimeout(15_000) {
            while (true) {
                val all = nodes()
                val target = all.firstOrNull { it.text?.toString() == text && it.isClickable }
                    ?: all.firstOrNull { it.text?.toString() == text }
                    ?: all.firstOrNull { matches(it, text) && it.isClickable }
                    ?: all.firstOrNull { matches(it, text) }
                if (target != null) {
                    var button: AccessibilityNodeInfo? = target
                    while (button != null && !button.isClickable) button = button.parent
                    if (button?.isVisibleToUser == true && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
                    target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                }
                all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(100)
            }
        }
        suspend fun choosePermission(allow: Boolean) = withTimeout(15_000) {
            val ids = if (allow) listOf("permission_allow_foreground_only_button", "permission_allow_button")
                else listOf("permission_deny_button", "permission_deny_and_dont_ask_again_button")
            val labels = if (allow) setOf("앱 사용 중에만 허용", "허용", "While using the app", "Allow")
                else setOf("허용 안함", "허용 안 함", "Don't allow", "Don’t allow")
            while (true) {
                val target = nodes().firstOrNull { node -> ids.any { node.viewIdResourceName?.endsWith("/$it") == true } ||
                    node.packageName?.contains("permissioncontroller") == true && node.text?.toString() in labels }
                if (target != null && target.isVisibleToUser) {
                    val bounds = Rect()
                    target.getBoundsInScreen(bounds)
                    val clock = AndroidUiClock()
                    val start = clock.elapsedRealtimeMs()
                    val down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    instrumentation.uiAutomation.injectInputEvent(down, true)
                    down.recycle()
                    delay(30)
                    val up = MotionEvent.obtain(start, clock.elapsedRealtimeMs(), MotionEvent.ACTION_UP,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    instrumentation.uiAutomation.injectInputEvent(up, true)
                    up.recycle()
                    break
                }
                delay(100)
            }
            while (nodes().any { it.isVisibleToUser && it.packageName?.contains("permissioncontroller") == true &&
                    it.viewIdResourceName?.endsWith("car_ui_alert_title") == true }) delay(100)
        }
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        try {
            preferences.edit().putBoolean("onboarding", false).commit()
            store.write(original.copy(microphoneConsent = false))
            context.startActivity(Intent(context, DrivingPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click("주행 시작하기")
            waitFor { nodes().any { matches(it, "첫 실행 · 권한과 동의") } }
            report["firstRunScreen"] = true

            assertFalse("실행 도구에서 마이크 권한을 먼저 거절 상태로 준비해야 함", granted(Manifest.permission.RECORD_AUDIO))
            report["step"] = "마이크 요청"
            click("마이크 사용 동의")
            report["step"] = "마이크 거절 선택"
            choosePermission(false)
            waitFor { !store.read().microphoneConsent }
            assertFalse(granted(Manifest.permission.RECORD_AUDIO))
            report["microphoneDenied"] = true
            report["step"] = "마이크 재요청"
            click("마이크 사용 동의")
            choosePermission(true)
            waitFor { granted(Manifest.permission.RECORD_AUDIO) && store.read().microphoneConsent }
            report["microphoneGrantedOnRetry"] = true

            if (!granted("android.car.permission.CAR_SPEED")) {
                click("차속·기어 권한 허용")
                choosePermission(true)
                waitFor { granted("android.car.permission.CAR_SPEED") }
            }
            report["vehiclePermissionGranted"] = granted("android.car.permission.CAR_SPEED")
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
                click("알림 권한 허용")
                choosePermission(true)
                waitFor { granted(Manifest.permission.POST_NOTIFICATIONS) }
            }
            report["notificationGranted"] = granted(Manifest.permission.POST_NOTIFICATIONS)
            click("선택 내용 저장하고 계속")
            waitFor { nodes().any { matches(it, "지금 운전 컨디션은 어떤가요?") } }
            report["continuedToCondition"] = true
            preferences.edit().putBoolean("onboarding", false).commit()
            context.startActivity(Intent(context, DrivingPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            report["step"] = "하단 주행 탭 확인"
            waitFor { nodes().any { matches(it, "주행 시작하기") } }
            click("주행")
            waitFor { nodes().any { matches(it, "첫 실행 · 권한과 동의") } }
            report["bottomDriveAlsoRequestsSetup"] = true
            report["step"] = "완료"
        } finally {
            report["visibleNodes"] = nodes().filter { node ->
                node.isVisibleToUser && (node.packageName?.toString() == context.packageName ||
                    node.packageName?.contains("permissioncontroller") == true)
            }.take(60).map { mapOf("text" to it.text?.toString(), "description" to it.contentDescription?.toString(),
                "id" to it.viewIdResourceName, "clickable" to it.isClickable) }
            accessibility.flags = originalFlags
            instrumentation.uiAutomation.serviceInfo = accessibility
            store.write(original)
            preferences.edit().apply {
                if (hadOnboarding) putBoolean("onboarding", onboarding) else remove("onboarding")
            }.commit()
            File(context.cacheDir, "first-run-permission-ui.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        }
    }
}
