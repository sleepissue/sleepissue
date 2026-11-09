package com.sleepissue.awakedrive.data

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** 명시한 runner 인자에서만 공식 입력 API를 시험한다. 제품 내비/통풍 제어·사용자 DB와 연결하지 않는다. */
@RunWith(AndroidJUnit4::class)
class Role1SdkInputProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun enabled() = InstrumentationRegistry.getArguments().getString("role1SdkFixtures") == "true"
    private fun write(name: String, value: Any) {
        File(context.cacheDir, name).writeText(GsonBuilder().serializeNulls().create().toJson(value))
    }

    @Test fun officialVentilationInputAndReadbackAreComparedWithoutUsingSeatTemperature() = runBlocking {
        assumeTrue("명시적 SDK 입력 시험에서만 실행", enabled())
        val vehicle = Vehicle(context).also { it.initialize() }
        val live = Role1LiveSdkSession(context)
        val results = linkedMapOf<String, Any?>()
        var original: Int? = null
        try {
            val seat = vehicle.getSeat()
            val driver = SeatArea(SeatValue.ROW_1_LEFT)
            val checked = CompletableDeferred<Boolean>()
            seat.checkSeatVentilationCapabilities({ config, areas ->
                results["capability"] = config.accessType.toString()
                results["bounds"] = areas[driver]?.toString()
                checked.complete(driver in areas)
            }, { checked.completeExceptionally(it) })
            assertTrue(withTimeout(5_000) { checked.await() })
            suspend fun current(): Int? {
                val value = CompletableDeferred<Int?>()
                seat.getHvacSeatVentilationState(driver, { value.complete(it) }, { value.completeExceptionally(it) })
                return withTimeout(5_000) { value.await() }
            }
            suspend fun set(level: Int) {
                val done = CompletableDeferred<Int?>()
                seat.setHvacSeatVentilation(driver, level, { _, value -> done.complete(value) }, { done.completeExceptionally(it) })
                results["set$level"] = withTimeout(5_000) { done.await() }
            }
            original = current(); results["before"] = original
            assumeTrue("원래 통풍 상태를 복원할 수 있어야 입력 시험 가능", original != null)
            live.start(); live.awaitVentilation(requireNotNull(original))
            results["hubBefore"] = live.hub.latestFrame.value?.ventilationLevel
            try {
                set(1); live.awaitVentilation(1); results["afterSet1"] = current()
                results["hubAfterSet1"] = live.hub.latestFrame.value?.ventilationLevel
                val beforeQueries = withContext(Dispatchers.Main) {
                    live.adapter.store.readings().first { it.key == "VENTILATION_LEVEL" } to live.ventilationObservations
                }
                val gearBefore = withContext(Dispatchers.Main) {
                    live.adapter.store.readings().first { it.key == "GEAR" } to live.gearObservations
                }
                delay(4_000)
                live.awaitVentilation(1)
                val afterQueries = withContext(Dispatchers.Main) {
                    live.adapter.store.readings().first { it.key == "VENTILATION_LEVEL" } to live.ventilationObservations
                }
                val gearAfter = withContext(Dispatchers.Main) {
                    live.adapter.store.readings().first { it.key == "GEAR" } to live.gearObservations
                }
                results["gearBeforeRequeries"] = gearBefore.first
                results["gearAfterRequeries"] = gearAfter.first
                results["gearRawObservationsBefore"] = gearBefore.second
                results["gearRawObservationsAfter"] = gearAfter.second
                assertTrue(gearAfter.first.queries > gearBefore.first.queries)
                assertEquals(gearBefore.first.callbacks, gearAfter.first.callbacks)
                assertEquals(gearBefore.second, gearAfter.second)
                results["beforeRequeries"] = beforeQueries.first
                results["afterRequeries"] = afterQueries.first
                results["rawObservationsBefore"] = beforeQueries.second
                results["rawObservationsAfter"] = afterQueries.second
                assertTrue(afterQueries.first.queries > beforeQueries.first.queries)
                assertEquals(beforeQueries.first.callbacks, afterQueries.first.callbacks)
                assertEquals(beforeQueries.second, afterQueries.second)
            }
            finally {
                original?.let {
                    set(it); live.awaitVentilation(it); results["restored"] = current()
                    results["hubRestored"] = live.hub.latestFrame.value?.ventilationLevel
                }
            }
            write("role1-ventilation-input.json", results)
            assumeTrue("공식 setter 뒤 실제 통풍 1단계 반영을 관찰하지 못함", results["afterSet1"] == 1)
            assertEquals(original, results["restored"])
        } catch (error: Exception) {
            results["errorClass"] = error.javaClass.simpleName
            write("role1-ventilation-input.json", results)
            throw error
        } finally { live.close(); vehicle.release() }
    }

    @Test fun officialSampleRouteRequestCapturesActiveDestinationAndTurnReports() = runBlocking {
        assumeTrue("명시적 SDK 입력 시험에서만 실행", enabled())
        val helper = NaviHelper(context)
        val connected = CompletableDeferred<Unit>()
        val initial = CompletableDeferred<RouteStateInfo>()
        val events = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        val active = AtomicBoolean()
        var requested = false
        val listener = object : NaviHelperEventListener {
            override fun onRouteStateInfo(info: RouteStateInfo) {
                active.set(info.naviMode == NaviMode.ROUTE_GUIDING || info.naviMode == NaviMode.SIMULATION_GUIDING)
                initial.complete(info)
                events += mapOf("kind" to "route", "value" to info)
            }
            override fun onCurrentLocationInfo(info: CurrentLocationInfo) { events += mapOf("kind" to "location", "value" to info) }
            override fun onDestinationInfo(info: DestinationInfo) { events += mapOf("kind" to "destination", "value" to info) }
            override fun onTBTInfo(info: List<TBTInfo>) { events += mapOf("kind" to "tbt", "value" to info) }
            override fun onError(errorCode: NaviErrorCode) { events += mapOf("kind" to "error", "value" to errorCode.toString()) }
        }
        helper.addListener(listener)
        try {
            helper.initialize({ connected.complete(Unit) }, { connected.completeExceptionally(it) })
            withTimeout(5_000) { connected.await() }
            helper.getRouteStateInfo()
            val initialState = withTimeout(5_000) { initial.await() }
            val wasActive = initialState.isRouting || initialState.naviMode == NaviMode.ROUTE_GUIDING ||
                initialState.naviMode == NaviMode.SIMULATION_GUIDING
            if (!wasActive) {
                // 공식 샘플 76ed592의 공개 목적지 payload 그대로 사용한다.
                requested = true
                helper.requestRoute(RouteInfo(latitude = 37.48544722, longitude = 127.03636666,
                    poiId = "36khp37hh8gqcm5nqhe5", poiName = "42dot", poiSubId = "0",
                    address = "서울시 강남구 남부순환로 2621"))
            }
            repeat(20) {
                helper.getRouteStateInfo(); helper.getCurrentLocationInfo(); helper.getDestinationInfo(); helper.getTBTInfo()
                delay(1_000)
            }
            val captured = synchronized(events) { events.toList() }
            write("role1-navi-input.json", mapOf("initiallyActive" to wasActive, "fixtureRequested" to requested,
                "activeObserved" to captured.any { it["kind"] == "route" && (it["value"] as RouteStateInfo).naviMode in listOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING) },
                "events" to captured))
            assumeTrue("공식 요청 뒤 활성 경로를 관찰하지 못함 · API 미지원 판정과 구분", active.get())
            assertTrue(captured.any { it["kind"] == "destination" })
        } finally {
            // 원래 활성 경로는 건드리지 않는다. 이번 시험이 요청한 경로만 취소한다.
            if (requested) { helper.cancelRoute(); delay(1_000) }
            helper.removeListener(listener); helper.release()
        }
    }
}
