package com.sleepissue.awakedrive.data

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.debug.DebugCapabilityReadFeatures
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.pleos.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** TC_SIG_007: 공개 API 경계 모의 → 제품 어댑터/저장소 → 실제 타이머 Hub. DB는 열지 않는다. */
@RunWith(AndroidJUnit4::class)
class Role1CapabilityDeviceTest {
    @Test fun steeringCapabilityFalse() = verify(SignalField.STEERING_DEG)
    @Test fun occupancyCapabilityFalse() = verify(SignalField.SEATS)
    @Test fun ventilationCapabilityFalse() = verify(SignalField.VENTILATION_LEVEL)

    private fun verify(field: SignalField) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("명시적 TC_SIG_007 기기 모의", args.getString("role1CapabilityFalse") == "true")
        val runId = requireNotNull(args.getString("role1CapabilityRunId")).also { require(it.matches(Regex("[a-zA-Z0-9-]+"))) }
        val pid = Process.myPid()
        val context = instrumentation.targetContext
        val result = linkedMapOf<String, Any?>("field" to field, "pidBefore" to pid, "simulatedSdkOnly" to true)
        val clock = object : Clock {
            override fun scenarioTimeMs() = SystemClock.elapsedRealtime()
            override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
            override fun wallTimeMs() = java.time.Clock.systemUTC().millis()
        }
        // true 대조군도 같은 SDK 경계를 지나야 의미 보류 null을 false 성공으로 오인하지 않는다.
        for (readable in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val fake = DebugCapabilityReadFeatures(field, readable)
            val lease = checkNotNull(PlaygroundSdkOwnership.acquire("TC_SIG_007 $field $readable"))
            val adapter = withContext(Dispatchers.Main) {
                PlaygroundSignalAdapter(context, clock, SdkProbePolicy(3_000, 1_000), scope, fake::wrap)
            }
            val fresh = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
            val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, adapter.vehicleSource, adapter.naviSource,
                rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
                HubPolicy(SignalField.entries.associateWith { fresh }, NaviField.entries.associateWith { fresh }, fresh,
                    SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME),
                    ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.AUTOMATIC), EngineConfig())
            var stoppedFrames = 0L
            try {
                hub.start(CoroutineScope(scope.coroutineContext + Dispatchers.Default))
                withContext(Dispatchers.Main) { adapter.start() }
                val keys = if (field == SignalField.SEATS) listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT").map { "SEAT:$it" } else listOf(field.name)
                withTimeout(20_000) {
                    while (!withContext(Dispatchers.Main) {
                        val rows = adapter.store.readings().filter { it.key in keys }
                        rows.size == keys.size && rows.all { it.apiReadable == readable && it.permissionGranted &&
                            (!readable || it.receivedMs != null) }
                    } || hub.latestFrame.value?.qualityOf(field)?.availability !=
                        if (!readable || field != SignalField.VENTILATION_LEVEL) SignalAvailability.UNSUPPORTED else SignalAvailability.AVAILABLE) delay(50)
                }
                // 반복 발행 중에도 false가 재조회/실수신으로 바뀌거나 로그가 중복되지 않는지 확인한다.
                delay(1_200)
                val snapshot = withContext(Dispatchers.Main) {
                    val readings = adapter.store.readings().filter { it.key in keys }
                    val history = adapter.store.history()
                    val lines = history.filter { it.contains(" · STATE · ${field.name} · UNSUPPORTED ·") }
                    Triple(readings, lines, history)
                }
                val frame = checkNotNull(hub.latestFrame.value)
                val calls = fake.calls()
                assertEquals(1, calls.count { it == "check:$readable" })
                assertEquals(Source.REAL, frame.qualityOf(field).source)
                if (!readable) {
                    assertTrue(calls.none { it.startsWith("query") || it.startsWith("subscribe") })
                    snapshot.first.forEach {
                        assertFalse(it.apiReadable!!); assertEquals(SdkNormalization.UNASSESSED, it.normalization)
                        assertNull(it.receivedMs); assertEquals(0L, it.callbacks); assertEquals(0L, it.queries)
                    }
                    assertEquals("${field.name} 필드 비활성화 전이 로그", 1, snapshot.second.size)
                    assertEquals(SignalAvailability.UNSUPPORTED, frame.qualityOf(field).availability)
                    when (field) {
                        SignalField.STEERING_DEG -> assertNull(frame.steeringDeg)
                        SignalField.SEATS -> assertNull(frame.seats)
                        SignalField.VENTILATION_LEVEL -> assertNull(frame.ventilationLevel)
                        else -> error("시험 대상 아님")
                    }
                } else {
                    assertTrue(calls.any { it.startsWith("query") })
                    assertTrue(calls.any { it.startsWith("subscribe") })
                    val expected = when (field) {
                        SignalField.STEERING_DEG -> SdkNormalization.SEMANTIC_UNCONFIRMED
                        SignalField.SEATS -> SdkNormalization.LOSSY_SDK_BOOLEAN
                        else -> SdkNormalization.NORMALIZED
                    }
                    assertTrue(snapshot.first.all { it.apiReadable == true && it.normalization == expected })
                    if (field == SignalField.VENTILATION_LEVEL) assertEquals(1, frame.ventilationLevel)
                }
                assertEquals(pid, Process.myPid())
                result["readable=$readable"] = mapOf("calls" to calls, "readings" to snapshot.first,
                    "disableLogLines" to snapshot.second, "frame" to frame, "history" to snapshot.third)
            } finally {
                withContext(Dispatchers.Main) { adapter.close() }
                scope.coroutineContext[Job]?.cancelAndJoin()
                hub.stop()
                assertTrue(PlaygroundSdkOwnership.release(lease))
                assertNull(PlaygroundSdkOwnership.currentOwner)
                assertFalse(hub.diagnostics.value.running)
                stoppedFrames = hub.diagnostics.value.emittedFrames
                delay(250)
                assertEquals(stoppedFrames, hub.diagnostics.value.emittedFrames)
                val calls = fake.calls()
                assertEquals(calls.count { it.startsWith("subscribe") }, calls.count { it.startsWith("unsubscribe") })
                result["cleanup:$readable"] = mapOf("hubRunning" to false, "owner" to PlaygroundSdkOwnership.currentOwner,
                    "framesAtStop" to stoppedFrames, "framesAfterWait" to hub.diagnostics.value.emittedFrames, "calls" to calls)
            }
        }
        assertEquals(pid, Process.myPid())
        result["pidAfter"] = Process.myPid()
        val directory = File(context.cacheDir, "role1-sig007-$runId").also { check(it.mkdirs() || it.isDirectory) }
        val file = File(directory, "${field.name}.json")
        check(!file.exists()) { "기존 진단 덮기 금지" }
        file.writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result))
    }
}
