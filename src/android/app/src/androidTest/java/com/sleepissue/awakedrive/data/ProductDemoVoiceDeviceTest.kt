package com.sleepissue.awakedrive.data

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepissue.awakedrive.demo.*
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.CheckRequest
import com.sleepissue.awakedrive.ui.ProductDemoActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.google.gson.GsonBuilder

/** 실제 ON_DEVICE 확인 포트를 실행한다. 판단 점수/단계/응답 결과를 주입하지 않는다. */
class ProductDemoVoiceDeviceTest {
    @Test fun realTtsStartLocksAndRealCheckEndRestoresSpeed() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val activity = instrument.startActivitySync(Intent(context, ProductDemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val clock = object : Clock {
            override fun scenarioTimeMs() = SystemClock.elapsedRealtime()
            override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
            override fun wallTimeMs() = java.time.Instant.now().toEpochMilli()
        }
        val controller = ProductDemoController(context, scope, clock)
        val rows = linkedMapOf<String, Any?>()
        try {
            withTimeout(120_000) { withContext(Dispatchers.Main) { controller.loadAndStart("normal") } }
            val request = RequestId("actual-voice-speed-20261111")
            withContext(Dispatchers.Main) { controller.pipeline!!.check.start(CheckRequest(request, CheckContext.DRIVER_ALERTNESS)) }
            withTimeout(30_000) { controller.playback.first { it.questionRequestId == request && it.speedMultiplier == 1 } }
            val start = controller.timingSnapshot()!!
            rows["actualTtsStart"] = start
            delay(600)
            val during = controller.timingSnapshot()!!
            rows["duringPrompt"] = during
            assertEquals(1, during.speedMultiplier)
            rows["realDeltaMs"] = during.realElapsedMs - start.realElapsedMs
            rows["scenarioDeltaMs"] = during.scenarioTimeMs - start.scenarioTimeMs
            val finished = withTimeout(30_000) { controller.playback.first { it.questionRequestId == null && it.questionOutcome != null && it.speedMultiplier == 10 } }
            rows["afterActualCheck"] = finished
            assertEquals(10, finished.speedMultiplier)
            rows["completed"] = true
        } finally {
            File(context.cacheDir, "product-demo-voice-20261111.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(rows))
            withContext(Dispatchers.Main) { controller.stop(); activity.finish() }
            scope.cancel()
        }
    }
}
