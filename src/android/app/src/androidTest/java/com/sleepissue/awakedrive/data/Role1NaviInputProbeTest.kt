package com.sleepissue.awakedrive.data

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/** 공개 요청과 실제 SDK→어댑터→Hub를 비교한다. Room·제품 내비 제어와 분리된 명시적 기기 시험이다. */
@RunWith(AndroidJUnit4::class)
class Role1NaviInputProbeTest {
    @Test fun explicitGuidanceReachesHubAndOwnRouteCancellationInvalidatesValues() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("명시한 기기 입력 시험에서만 실행",
            InstrumentationRegistry.getArguments().getString("role1SdkFixtures") == "true")
        val context = instrumentation.targetContext
        val helper = NaviHelper(context)
        val live = Role1LiveSdkSession(context)
        val result = linkedMapOf<String, Any?>()
        val events = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        val route = AtomicReference<RouteStateInfo?>()
        val destination = AtomicReference<DestinationInfo?>()
        val turns = AtomicReference<List<TBTInfo>>(emptyList())
        var requested = false
        fun event(kind: String, value: Any?) {
            events += mapOf("elapsedMs" to SystemClock.elapsedRealtime(), "kind" to kind, "value" to value)
        }
        fun guiding() = route.get()?.naviMode in listOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)
        fun stage(value: String) { File(context.cacheDir, "role1-sig-input-stage.txt").writeText(value) }
        suspend fun poll() {
            helper.getRouteStateInfo(); helper.getDestinationInfo(); helper.getTBTInfo(); helper.getCurrentLocationInfo()
            delay(250)
        }
        val listener = object : NaviHelperEventListener {
            override fun onRouteStateInfo(info: RouteStateInfo) { route.set(info); event("route", info) }
            override fun onDestinationInfo(info: DestinationInfo) { destination.set(info); event("destination", info) }
            override fun onTBTInfo(info: List<TBTInfo>) { turns.set(info); event("tbt", info) }
            override fun onCurrentLocationInfo(info: CurrentLocationInfo) { event("location", info) }
            override fun onRouteStarted(info: RouteStartInfo) { event("routeStarted", info) }
            override fun onRoutingCompleted() { event("routingCompleted", true) }
            override fun onRouteCancelled() { event("cancelled", true) }
            override fun onError(errorCode: NaviErrorCode) { event("error", errorCode.toString()) }
        }
        helper.addListener(listener)
        try {
            stage("CONNECT")
            val connected = CompletableDeferred<Unit>()
            helper.initialize({ connected.complete(Unit) }, { connected.completeExceptionally(it) })
            withTimeout(5_000) { connected.await() }
            result["connected"] = true
            result["routeReadPermission"] = context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE")
            result["routeSearchPermission"] = context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE_SEARCH")
            withTimeout(5_000) { while (route.get() == null) poll() }
            result["initialRoute"] = route.get()
            // Boolean 또는 안내 모드에 기존 경로의 근거가 있으면 덮지 않는다.
            assumeTrue("기존 사용자 경로 보존", !guiding() && route.get()?.isRouting == false)
            live.start()
            requested = true
            helper.requestRoute(RouteInfo(latitude = 37.48544722, longitude = 127.03636666,
                poiId = "36khp37hh8gqcm5nqhe5", poiName = "42dot", poiSubId = "0",
                address = "서울시 강남구 남부순환로 2621"))
            result["requestReturnedAtMs"] = SystemClock.elapsedRealtime()
            withTimeout(20_000) { while (!guiding() || destination.get()?.remainDistance == 0 || turns.get().isEmpty()) poll() }
            result["guidingRoute"] = route.get()
            val expected = requireNotNull(destination.get())
            val turn = turns.get().map { it.distance }.filter { it.isFinite() && it >= 0 }.minOrNull()
            withTimeout(10_000) {
                while (live.hub.latestNavi.value?.let {
                    it.remainTimeSec == expected.remainTime.toLong() && it.remainDistM == expected.remainDistance.toFloat() &&
                        it.distToNextTurnM == turn?.toFloat()
                } != true) poll()
            }
            result["rawActiveDestination"] = expected
            result["rawActiveTbt"] = turns.get()
            result["hubActive"] = live.hub.latestNavi.value
            val cabinBefore = withContext(Dispatchers.Main) { live.adapter.store.readings().first { it.key == "CABIN_TEMP_C" } }
            result["cabinBefore"] = cabinBefore
            stage("MOVE")
            // 외부의 공식 emulator geo fix 입력 구간을 원시 provider 보고와 Hub 양쪽에서 기록한다.
            val samples = mutableListOf<Map<String, Any?>>()
            repeat(50) {
                samples += mapOf("elapsedMs" to SystemClock.elapsedRealtime(), "navi" to live.hub.latestNavi.value,
                    "cabinTempC" to live.hub.latestFrame.value?.cabinTempC)
                delay(1_000)
            }
            result["samples"] = samples
            val cabinAfter = withContext(Dispatchers.Main) { live.adapter.store.readings().first { it.key == "CABIN_TEMP_C" } }
            result["cabinAfter"] = cabinAfter
            assertEquals(SignalAvailability.AVAILABLE, cabinAfter.availability)
            assertTrue(cabinAfter.queries > cabinBefore.queries)
            assertEquals(cabinBefore.callbacks, cabinAfter.callbacks)
            assertEquals(cabinBefore.observations, cabinAfter.observations)
            assertTrue(live.hub.latestNavi.value!!.quality.values.all { it.observationSequence == 0L })
            helper.cancelRoute()
            withTimeout(10_000) {
                while (guiding() || live.hub.latestNavi.value?.let {
                    it.remainTimeSec == null && it.remainDistM == null && it.distToNextTurnM == null
                } != true) poll()
            }
            result["routeAfterCancel"] = route.get()
            result["hubAfterCancel"] = live.hub.latestNavi.value
            requested = false
            result["completed"] = true
        } catch (error: Exception) {
            result["error"] = "${error.javaClass.simpleName}: ${error.message}"
            throw error
        } finally {
            if (requested) { helper.cancelRoute(); delay(1_000) }
            live.close(); helper.removeListener(listener); helper.release()
            result["events"] = synchronized(events) { events.toList() }
            File(context.cacheDir, "role1-sig-input-live.json").writeText(GsonBuilder().serializeNulls().create().toJson(result))
            stage("DONE")
        }
    }
}
