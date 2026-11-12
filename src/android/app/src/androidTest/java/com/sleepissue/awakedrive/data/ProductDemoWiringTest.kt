package com.sleepissue.awakedrive.data

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepissue.awakedrive.demo.*
import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import com.sleepissue.awakedrive.engine.model.*

class ProductDemoWiringTest {
    @Test fun productAssemblyLoadsOriginalAndPublishesHubValues() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val clock = object : Clock {
            override fun scenarioTimeMs() = SystemClock.elapsedRealtime()
            override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
            override fun wallTimeMs() = java.time.Instant.now().toEpochMilli()
        }
        val controller = ProductDemoController(context, owner, clock)
        try {
            val log = java.io.File(context.cacheDir, "product-wiring-progress-20261111.txt")
            val watch = launch { repeat(120) { log.appendText(controller.message.value + "\n"); delay(1000) } }
            try { withTimeout(120_000) { withContext(Dispatchers.Main) { controller.loadAndStart("normal") } } }
            finally { watch.cancel() }
            withTimeout(10_000) { while (controller.frame.value?.cabinTempC != 23.5f) delay(100) }
            assertEquals(DemoPhase.PLAYING, controller.playback.value.phase)
            withContext(Dispatchers.Main) {
                controller.preparePark()
                val before = controller.trip.value
                controller.savePowerOff()
                val restored = ProductDemoController(context, owner, clock)
                assertTrue(restored.restorePowerOff())
                assertEquals(0L, restored.trip.value.continuousDriveSec)
                assertEquals(before.last24hDriveSec, restored.trip.value.last24hDriveSec)
                assertEquals(before.tripId, restored.trip.value.tripId)
                assertFalse(restored.restorePowerOff())
                val again = ProductDemoController(context, owner, clock)
                again.showLastResult()
                assertEquals(restored.trip.value, again.trip.value)
            }
        } finally { withTimeoutOrNull(5000) { withContext(Dispatchers.Main) { controller.stop() } }; owner.cancel() }
    }
}
