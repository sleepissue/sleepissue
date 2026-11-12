package com.sleepissue.awakedrive.data

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepissue.awakedrive.ui.ProductDemoActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 제품 버튼을 접근성 트리로 누른다. 샘플·점수·단계를 시험 코드에서 주입하지 않는다. */
@RunWith(AndroidJUnit4::class)
class ProductDemoDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun root(): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        val displays = instrumentation.uiAutomation.windowsOnAllDisplays
        return (0 until displays.size()).flatMap { displays.valueAt(it) }.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == "com.sleepissue.awakedrive" && it.isVisibleToUser }
    }
    private fun text(): String {
        val out = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.let { out += it.toString() }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root())
        return out.joinToString("\n")
    }
    private fun await(message: String, predicate: (String) -> Boolean): String {
        val end = android.os.SystemClock.elapsedRealtime() + 120_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val value = text()
            if (predicate(value)) return value
            Thread.sleep(100)
        }
        File(instrumentation.targetContext.cacheDir, "product-demo-ui-failure-20261111.txt").writeText("$message\n${text()}")
        error("$message\n${text()}")
    }
    private fun click(label: String) {
        val root = root() ?: error("제품 화면 없음")
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child)?.let { return it } }
            return null
        }
        var node = find(root) ?: error("버튼 없음 $label\n${text()}")
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "클릭 거절 $label" }
    }
    @Test fun sixProductButtonsPlayThroughHubAndPauseResumeStopReset() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val resultFile = File(instrumentation.targetContext.cacheDir, "product-demo-six-20261111.txt")
        resultFile.writeText("")
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ProductDemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val evidence = mutableListOf<String>()
        try {
            await("정차 중 데모 화면") { "정상" in it && "주행 제한 중" !in it }
            for (title in listOf("정상", "딴짓", "졸음", "대화", "보조 주행", "출발 피로")) {
                click(title)
                val playing = await("$title 재생/Hub 값") { "데모 재생 중" in it && "실내 23.5" in it && "출처 DEMO" in it }
                assertTrue(playing.contains("원본"))
                if (title == "졸음") assertTrue(playing.contains("연속 78"))
                evidence += "$title\n$playing"
                resultFile.appendText("$title 재생 확인\n$playing\n\n")
                click("일시정지")
                await("일시정지 배지") { "데모 일시정지" in it }
                click("재개")
                await("재개 배지") { "데모 재생 중" in it }
                click("중지")
                await("중지 배지 제거") { "STOPPED" in it && "데모 재생 중" !in it && "데모 일시정지" !in it }
            }
            click("초기화")
            await("초기화 배지 제거") { "NOT_LOADED" in it && "데모 재생 중" !in it }
            File(instrumentation.targetContext.cacheDir, "product-demo-six-20261111.txt").writeText(evidence.joinToString("\n\n"))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
