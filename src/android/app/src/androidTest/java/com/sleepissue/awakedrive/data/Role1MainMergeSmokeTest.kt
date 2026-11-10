package com.sleepissue.awakedrive.data

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.pleos.PlaygroundSdkOwnership
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 새 UI·기존 SDK 화면의 기동과 소유권 경계를 확인한다. Room·운행·마이크·내비 제어는 시작하지 않는다. */
@RunWith(AndroidJUnit4::class)
class Role1MainMergeSmokeTest {
    @Test fun productEntryAndSdkProbePreserveOwnershipWithoutOpeningTripStorage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val results = linkedMapOf<String, Any?>()
        var activity: Activity? = null
        var productActivity: Activity? = null
        var held: PlaygroundSdkOwnership.Lease? = null
        fun launch(name: String, autoConnect: Boolean = false): Activity =
            instrumentation.startActivitySync(Intent().setClassName(context, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("sdkAutoConnect", autoConnect)
                .putExtra("sdkCaptureFile", "sdk-signal-main-merge-smoke.json")).also { activity = it }
        fun finish() {
            val current = activity ?: return
            instrumentation.runOnMainSync { current.finish() }
            // finish/메인 큐 idle만으로 onStop 완료를 단정하지 않는다. 실제 생명주기 종료를 기다린다.
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (true) {
                var destroyed = false
                instrumentation.runOnMainSync { destroyed = current.isDestroyed }
                if (destroyed) break
                assertTrue("검증 Activity 종료 대기", SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(50)
            }
            instrumentation.waitForIdleSync()
            activity = null
        }
        fun texts(view: View): String = when (view) {
            is TextView -> view.text.toString()
            is ViewGroup -> (0 until view.childCount).joinToString("\n") { texts(view.getChildAt(it)) }
            else -> ""
        }
        fun screenText(): String {
            var value = ""
            instrumentation.runOnMainSync { value = texts(requireNotNull(activity).window.decorView) }
            return value
        }
        try {
            assertNull("기존 SDK 소유자가 있으면 시험 전에 정상 중지해야 한다", PlaygroundSdkOwnership.currentOwner)
            val product = "com.sleepissue.awakedrive.ui.DrivingPreviewActivity"
            val probe = "com.sleepissue.awakedrive.debug.DebugSdkProbeActivity"
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
            assertTrue(context.packageManager.queryIntentActivities(launcher, 0).any { it.activityInfo.name == product })
            results["productRegisteredAsLauncher"] = true
            held = requireNotNull(PlaygroundSdkOwnership.acquire("병합 시험의 기존 SDK 소유권"))
            val originalOwner = PlaygroundSdkOwnership.currentOwner
            productActivity = launch(product)
            assertEquals(originalOwner, PlaygroundSdkOwnership.currentOwner)
            results["productLaunchedWithoutTakingSdk"] = true
            // 루트 Activity 종료 전환과 다음 SDK 화면 기동이 겹치지 않도록 제품 화면 위에서 시험한다.
            launch(probe, autoConnect = true)
            val blockedText = screenText()
            results["blockedScreen"] = blockedText
            assertTrue("SDK 연결 거부 안내: ${blockedText.take(400)}",
                blockedText.contains("현재 SDK 소유자: $originalOwner"))
            assertEquals(originalOwner, PlaygroundSdkOwnership.currentOwner)
            results["sdkProbeRejectedWhileLeaseHeld"] = true
            finish()
            assertTrue(PlaygroundSdkOwnership.release(requireNotNull(held)))
            held = null
            launch(probe, autoConnect = true)
            assertEquals("SDK 단독 검증 화면", PlaygroundSdkOwnership.currentOwner)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!screenText().contains("AVAILABLE") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue("실제 SDK의 정상 품질 수신 대기", screenText().contains("AVAILABLE"))
            results["sdkProbeReceivedAvailableAfterRelease"] = true
            finish()
            assertNull(PlaygroundSdkOwnership.currentOwner)
            results["sdkLeaseReleasedOnStop"] = true
            results["completed"] = true
        } finally {
            finish()
            productActivity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            instrumentation.waitForIdleSync()
            held?.let(PlaygroundSdkOwnership::release)
            results["finalSdkOwner"] = PlaygroundSdkOwnership.currentOwner
            File(context.cacheDir, "role1-main-merge-smoke.json")
                .writeText(GsonBuilder().serializeNulls().create().toJson(results))
        }
    }
}
