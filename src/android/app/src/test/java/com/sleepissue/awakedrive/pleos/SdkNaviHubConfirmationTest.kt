package com.sleepissue.awakedrive.pleos

import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteStateInfo

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal fun naviRouteState(mode: NaviMode = NaviMode.ROUTE_GUIDING, isRouting: Boolean = false) =
    RouteStateInfo(true, mode, isRouting, RouteOption.RECOMMENDED, 0, emptyList(), null)

@OptIn(ExperimentalCoroutinesApi::class)
class SdkNaviHubConfirmationTest {
    @Test fun headingAtHubRestartsAfterLocationGapInvalidCoordinatesAndProviderError() = runTest {
        val clock = FakeClock()
        val policy = SdkProbePolicy(3_000, 1_000)
        val store = SdkSignalStore(clock, policy).apply {
            NaviField.entries.forEach { declare(it.name, "permission", true); capability(it.name, true) }
        }
        val bus = MutableStateFlow(store.naviState())
        val source = object : NaviSignalSource {
            override val states = bus.asStateFlow()
            override val stateReportsOnly = true
        }
        val limits = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, navi = source,
            rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
            HubPolicy(SignalField.entries.associateWith { limits }, NaviField.entries.associateWith { limits }, limits,
                SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME),
                ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.MANUAL), EngineConfig())
        val reports = SdkNaviDelivery(store, SdkRouteGate(clock, policy)) { bus.value = store.naviState() }
        hub.start(backgroundScope)
        fun advance(ms: Long) { clock.elapsedMs = ms; clock.scenarioMs = ms }
        suspend fun publish() { runCurrent(); assertTrue(hub.publishDue()); runCurrent() }
        suspend fun location(ms: Long, lat: Double, lon: Double): Float? {
            advance(ms)
            // 원시 SDK heading이 있어도 위치 쌍이 없으면 방위각으로 채택하지 않는다.
            reports.location(lat, lon, 9_999.0, ms)
            publish()
            return hub.latestNavi.value!!.headingDeg
        }
        try {
            assertNull(location(100, 37.0, 127.0))
            assertEquals(90f, location(1_100, 37.0, 127.001)!!, .01f)
            advance(4_200); publish()
            assertNull(hub.latestNavi.value!!.headingDeg)
            assertNull(location(4_300, 38.0, 128.0))
            assertEquals(0f, location(5_300, 38.001, 128.0)!!, .01f)
            assertNull(location(6_300, Double.NaN, 128.0))
            assertNull(hub.latestNavi.value!!.lat)
            assertNull(location(7_300, 39.0, 129.0))
            assertEquals(90f, location(8_300, 39.0, 129.001)!!, .01f)
            advance(9_300); reports.failed(IllegalStateException("provider 위치 오류")); publish()
            assertNull(hub.latestNavi.value!!.headingDeg)
            assertEquals(SignalAvailability.ERROR, hub.latestNavi.value!!.quality.getValue(NaviField.LAT).availability)
            assertNull(location(10_300, 40.0, 130.0))
            assertEquals(0f, location(11_300, 40.001, 130.0)!!, .01f)
        } finally { hub.stop() }
    }

    @Test fun onlyExplicitGuidanceModesOpenRouteValuesEvenWhenProviderBooleanDisagrees() {
        val clock = FakeClock()
        val store = SdkSignalStore(clock, SdkProbePolicy(3_000, 1_000)).apply {
            NaviField.entries.forEach { declare(it.name, "permission", true); capability(it.name, true) }
        }
        val reports = SdkNaviDelivery(store, SdkRouteGate(clock, store.policy)) { }
        for (mode in NaviMode.entries) for (routing in listOf(false, true)) {
            reports.route(naviRouteState(mode, routing), 0)
            reports.destination(60, 50, 0); reports.turns(listOf(50.0), 0)
            val guiding = mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING
            assertEquals("$mode / isRouting=$routing", if (guiding) 60L else null, store.naviState().remainTimeSec)
        }
    }

    @Test fun sdkReportsCanChangeNavigationWithoutInventingSensorOrVehicleObservations() = runTest {
        val clock = FakeClock()
        val policy = SdkProbePolicy(3_000, 1_000)
        val store = SdkSignalStore(clock, policy).apply {
            NaviField.entries.forEach { declare(it.name, "permission", true); capability(it.name, true) }
        }
        val bus = MutableStateFlow(store.naviState())
        val source = object : NaviSignalSource {
            override val states = bus.asStateFlow()
            override val stateReportsOnly = true
        }
        val limits = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, navi = source,
            rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
            HubPolicy(SignalField.entries.associateWith { limits }, NaviField.entries.associateWith { limits }, limits,
                SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME),
                ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.MANUAL), EngineConfig())
        val reports = SdkNaviDelivery(store, SdkRouteGate(clock, policy)) { bus.value = store.naviState() }
        val raw = mutableListOf<VehicleObservation>()
        hub.start(backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.rawVehicleObservations.toList(raw) }
        for (at in listOf(100L, 1_100L)) {
            clock.elapsedMs = at; clock.scenarioMs = at
            reports.route(naviRouteState(), at)
            reports.location(37.0, 127.0 + at / 1_000_000.0, 0.0, at)
            reports.destination(600 - at.toInt() / 100, 5_000 - at.toInt(), at)
            reports.turns(listOf(1_200.0 - at), at)
            runCurrent(); assertTrue(hub.publishDue()); runCurrent()
            assertTrue(hub.latestNavi.value!!.quality.values.all { it.observationSequence == 0L })
        }
        assertEquals(589L, hub.latestNavi.value!!.remainTimeSec)
        assertEquals(3_900f, hub.latestNavi.value!!.remainDistM!!, 0f)
        assertEquals(100f, hub.latestNavi.value!!.distToNextTurnM!!, 0f)
        assertEquals(90f, hub.latestNavi.value!!.headingDeg!!, .01f)
        assertEquals(0L, hub.diagnostics.value.inputs.getValue(Source.REAL).vehicleCallbacks)
        assertTrue(raw.isEmpty())
        assertTrue(store.readings().all { it.callbacks == 0L && it.observations == 0L })
        // 경로 취소 후 늦은 목적지/TBT 보고로 과거 값을 되살리지 않는다.
        clock.elapsedMs = 1_200; clock.scenarioMs = 1_200
        reports.route(naviRouteState(NaviMode.SAFE_GUIDING), 1_200)
        reports.destination(589, 3_900, 1_200); reports.turns(listOf(100.0), 1_200)
        runCurrent(); assertTrue(hub.publishDue()); runCurrent()
        assertNull(hub.latestNavi.value!!.remainTimeSec)
        assertNull(hub.latestNavi.value!!.remainDistM)
        assertNull(hub.latestNavi.value!!.distToNextTurnM)
        hub.stop()
    }
}
