package com.sleepissue.awakedrive.audit

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.demo.*
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.pleos.PlaygroundSdkOwnership
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 제품 Player·Hub·판단을 실제 AVD에서 실행한다. 사용자 DB/휴식 표식과 캐시 원본은 건드리지 않는다. */
class Role1ProductAuditDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = object : Clock {
        override fun scenarioTimeMs() = SystemClock.elapsedRealtime()
        override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
        override fun wallTimeMs() = java.time.Instant.now().toEpochMilli()
    }
    private fun write(name: String, rows: Any) = File(context.cacheDir, "role1-audit-$name.json")
        .writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(rows))

    @Test fun actualSdkCanOverrideProductDemoThroughSameHub() = runBlocking {
        assertNull("기존 SDK 소유자 없이 시작", PlaygroundSdkOwnership.currentOwner)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = ProductDemoController(DemoStorageContext(context, "role1_audit_sdk"), scope, clock)
        val rows = linkedMapOf<String, Any?>()
        try {
            withTimeout(120_000) { withContext(Dispatchers.Main) { controller.loadAndStart("normal") } }
            withTimeout(10_000) { controller.frame.first { it?.speedKph == 98f } }
            rows["before"] = controller.frame.value
            withContext(Dispatchers.Main) { controller.enableSdk() }
            val actual = withTimeout(20_000) { controller.frame.first {
                it?.qualityOf(SignalField.SPEED_KPH)?.source == Source.REAL && it.speedKph == 0f
            } }!!
            rows["actualSdkFrame"] = actual
            val position = withTimeout(15_000) { controller.navi.first {
                it?.lat != null && it.qualityOf(NaviField.LAT).source == Source.REAL
            } }
            rows["naviNoJsonCoordinates"] = position
            assertEquals(Source.REAL, actual.qualityOf(SignalField.SPEED_KPH).source)
            rows["result"] = "SDK speed REAL + absent JSON location uses REAL; deadline not measured"
        } finally {
            withContext(Dispatchers.Main) { controller.stop() }; scope.cancel()
            rows["finalFrame"] = controller.frame.value
            rows["finalSdkOwner"] = PlaygroundSdkOwnership.currentOwner
            write("sdk-precedence", rows)
        }
    }

    @Test fun productDepartureMustDetectFatigueAtFifteenScenarioMinutes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = ProductDemoController(DemoStorageContext(context, "role1_audit_departure"), scope, clock)
        val rows = linkedMapOf<String, Any?>()
        val timeline = mutableListOf<Map<String, Any?>>()
        var atFifteen: Boolean? = null
        try {
            withTimeout(120_000) { withContext(Dispatchers.Main) { controller.loadAndStart("departure") } }
            val began = SystemClock.elapsedRealtime()
            var lastBucket = -1L
            withTimeout(210_000) {
                while (true) {
                    val playback = controller.timingSnapshot()!!
                    val snapshot = controller.pipeline!!.runner.snapshots.value
                    val bucket = playback.scenarioTimeMs / 30_000
                    if (bucket != lastBucket || playback.scenarioTimeMs >= 900_000 && atFifteen == null) {
                        timeline += mapOf("scenarioMs" to playback.scenarioTimeMs, "actualElapsedMs" to SystemClock.elapsedRealtime() - began,
                            "speed" to playback.speedMultiplier, "delivered" to playback.deliveredSamples,
                            "frameMs" to controller.frame.value?.tMs, "fatigue" to snapshot,
                            "stage" to controller.pipeline!!.manager.state.value)
                        lastBucket = bucket
                    }
                    if (playback.scenarioTimeMs >= 900_000 && atFifteen == null) atFifteen = snapshot.baseline.departureFatigue
                    if (playback.scenarioTimeMs >= 915_000) break
                    delay(40)
                }
            }
            rows["atFifteenDeparture"] = atFifteen
            rows["atFifteenPlus15Sec"] = controller.pipeline!!.runner.snapshots.value
            assertEquals("DMO006: 재생15분에 출발 피로가 판정돼야 함", true, atFifteen)
        } finally {
            rows["timeline"] = timeline
            rows["finalPlayback"] = controller.timingSnapshot()
            rows["finalMessage"] = controller.message.value
            withContext(Dispatchers.Main) { controller.stop() }; scope.cancel()
            write("departure", rows)
        }
    }
}
