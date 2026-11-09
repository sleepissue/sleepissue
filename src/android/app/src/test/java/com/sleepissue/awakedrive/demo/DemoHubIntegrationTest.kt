package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Fake REAL과 검증한 JSON의 통합 시험이다. 실제 SDK 동시 수신 시험이 아니다. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoHubIntegrationTest {
    private fun id(value: String) = RequestId(value)

    private fun policy(): HubPolicy {
        // [가정] 시험용 TTL과 즉시 DEMO 대체다. 제품 기본값을 확정하지 않는다.
        val limits = SignalFreshnessPolicy(1_000L, 1_000L, MissingObservationPolicy.REQUIRE_TIMESTAMP)
        return HubPolicy(
            vehicleFreshness = SignalField.entries.associateWith { limits },
            naviFreshness = NaviField.entries.associateWith { limits },
            powerFreshness = limits,
            fallback = SignalFallbackPolicy(true, 0L, ObservationTimeBasis.ELAPSED_REALTIME),
            publicationTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME,
            tickDriver = HubTickDriver.MANUAL,
        )
    }

    private fun realQuality(atElapsedMs: Long) = SignalQuality(
        availability = SignalAvailability.AVAILABLE,
        source = Source.REAL,
        observedAtMs = atElapsedMs,
        observedTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME,
    )

    private fun realVehicle(atElapsedMs: Long, scenarioMs: Long, speed: Float = 80f) = VehicleSignalInput(
        tMs = scenarioMs, source = Source.REAL, steeringDeg = 1f, speed = speed, gear = Gear.D,
        quality = SignalField.entries.associateWith { realQuality(atElapsedMs) },
    )

    private fun realNavi(atElapsedMs: Long, scenarioMs: Long) = NaviState(
        tMs = scenarioMs, lat = 37.5, lon = 127.0, distToNextTurnM = 500f,
        quality = NaviField.entries.associateWith { realQuality(atElapsedMs) },
    )

    @Test
    fun realVehicleWinsWhileJsonCoordinatePairUsesTheExistingDemoException() = runTest {
        val realTime = DemoTestClock()
        val clock = DemoClock(realTime)
        val scenario = demoTestScenario()
        val player = ScenarioDemoPlayer(clock, demoTestRepository(scenario), backgroundScope, false)
        val real = FakeSignalSource()
        val navigation = FakeNaviSource()
        val hub = VehicleSignalHub(listOf(
            HubSourceSet(Source.REAL, real, navigation, rateTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME),
            HubSourceSet(Source.DEMO, player, player, player, ObservationTimeBasis.SCENARIO),
        ), clock, policy())

        player.execute(DemoCommand.Load(id("load"), scenario.id))
        hub.start(backgroundScope)
        real.emit(realVehicle(0L, 0L))
        navigation.emit(realNavi(0L, 0L))
        player.execute(DemoCommand.Start(id("start")))
        runCurrent()
        realTime.realMs = 100L
        player.pump()
        runCurrent()
        assertTrue(hub.publishDue())
        val frame = requireNotNull(hub.latestFrame.value)
        val navi = requireNotNull(hub.latestNavi.value)
        assertEquals(80f, frame.speedKph!!, 0f)
        assertEquals(1f, frame.steeringDeg!!, 0f)
        assertEquals(Source.REAL, frame.qualityOf(SignalField.SPEED_KPH).source)
        assertEquals(SignalSelectionReason.REAL_FRESH, frame.qualityOf(SignalField.SPEED_KPH).selectionReason)
        assertEquals(35.5, navi.lat!!, 0.0)
        assertEquals(129.0, navi.lon!!, 0.0)
        assertEquals(Source.DEMO, navi.qualityOf(NaviField.LAT).source)
        assertEquals(SignalSelectionReason.DEMO_LOCATION, navi.qualityOf(NaviField.LON).selectionReason)
        assertEquals(500f, navi.distToNextTurnM!!, 0f)
        assertEquals(Source.REAL, navi.qualityOf(NaviField.DIST_TO_NEXT_TURN_M).source)

        realTime.realMs = 1_100L
        player.pump()
        runCurrent()
        assertTrue(hub.publishDue())
        assertEquals(40f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(SignalSelectionReason.DEMO_FALLBACK,
            hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).selectionReason)
        assertEquals(SignalAvailability.STALE,
            hub.diagnostics.value.inputs.getValue(Source.REAL).vehicleQuality.getValue(SignalField.SPEED_KPH).availability)

        // 새 REAL 관측이 들어오면 다른 우회 경로 없이 같은 Hub에서 우선권을 되찾는다.
        realTime.realMs = 1_200L
        player.pump()
        real.emit(realVehicle(1_200L, clock.scenarioTimeMs(), 90f))
        navigation.emit(realNavi(1_200L, clock.scenarioTimeMs()))
        runCurrent()
        assertTrue(hub.publishDue())
        assertEquals(90f, hub.latestFrame.value!!.speedKph!!, 0f)

        player.execute(DemoCommand.Stop(id("stop")))
        runCurrent()
        realTime.realMs = 1_300L
        assertTrue(hub.publishDue())
        assertEquals(90f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(37.5, hub.latestNavi.value!!.lat!!, 0.0)
        assertEquals(127.0, hub.latestNavi.value!!.lon!!, 0.0)
        assertEquals(SignalSelectionReason.REAL_LOCATION, hub.latestNavi.value!!.qualityOf(NaviField.LAT).selectionReason)
        assertEquals(SignalAvailability.UNAVAILABLE,
            hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH).availability)
        hub.stop()
    }

    @Test
    fun playerStopInvalidatesDemoBeforeItsFreshnessTtlExpires() = runTest {
        val realTime = DemoTestClock()
        val clock = DemoClock(realTime)
        val scenario = demoTestScenario()
        val player = ScenarioDemoPlayer(clock, demoTestRepository(scenario), backgroundScope, false)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, player, player, player,
            ObservationTimeBasis.SCENARIO)), clock, policy())
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        hub.start(backgroundScope)
        player.execute(DemoCommand.Start(id("start")))
        realTime.realMs = 100L
        player.pump()
        runCurrent()
        assertTrue(hub.publishDue())
        assertEquals(40f, hub.latestFrame.value!!.speedKph!!, 0f)
        player.execute(DemoCommand.Stop(id("stop")))
        runCurrent()
        realTime.realMs = 200L
        assertTrue(hub.publishDue())
        assertEquals(100L, hub.latestFrame.value!!.tMs)
        assertNull(hub.latestFrame.value!!.speedKph)
        assertNull(hub.latestNavi.value!!.lat)
        assertNull(hub.latestNavi.value!!.lon)
        assertEquals(PowerState.UNKNOWN, hub.latestPower.value!!.state)
        assertEquals(SignalSelectionReason.NO_VALID_INPUT,
            hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).selectionReason)
        hub.stop()
    }

    @Test
    fun sixtyTimesJsonDoesNotAccelerateRealSensorWarmupAndRetainsScenarioRateBasis() = runTest {
        val realTime = DemoTestClock()
        val clock = DemoClock(realTime)
        val scenario = demoTestScenario(20_000L, listOf(DemoSpeedSegment(0, 20_000, 60)))
        val player = ScenarioDemoPlayer(clock, demoTestRepository(scenario), backgroundScope, false)
        val real = FakeSignalSource()
        val hub = VehicleSignalHub(listOf(
            HubSourceSet(Source.REAL, real, rateTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME),
            HubSourceSet(Source.DEMO, player, player, player, ObservationTimeBasis.SCENARIO),
        ), clock, policy())
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        hub.start(backgroundScope)
        real.emit(realVehicle(0, 0))
        player.execute(DemoCommand.Start(id("start")))
        realTime.realMs = 200L
        player.pump()
        runCurrent()
        assertTrue(hub.publishDue())
        val inputs = hub.diagnostics.value.inputs
        val realRate = inputs.getValue(Source.REAL).steeringRate
        val demoRate = inputs.getValue(Source.DEMO).steeringRate
        assertEquals(12_000L, hub.latestFrame.value!!.tMs)
        assertEquals(200L, hub.latestFrame.value!!.publication!!.publishedAtElapsedMs)
        assertEquals(RateStatus.WARMING_UP, realRate.status)
        assertNull(realRate.hz)
        assertEquals(ObservationTimeBasis.ELAPSED_REALTIME, realRate.timeBasis)
        assertEquals(1L, realRate.acceptedObservations)
        assertEquals(RateStatus.MEASURED, demoRate.status)
        assertEquals(10f, demoRate.hz!!, 0f)
        assertEquals(ObservationTimeBasis.SCENARIO, demoRate.timeBasis)
        assertEquals(121L, demoRate.acceptedObservations)
        assertEquals(121L, inputs.getValue(Source.DEMO).vehicleCallbacks)
        assertEquals(80f, hub.latestFrame.value!!.speedKph!!, 0f)
        player.execute(DemoCommand.Stop(id("stop")))
        hub.stop()
    }

    @Test
    fun resettingWithANewHubSessionDiscardsThePreviousClockAndCacheGeneration() = runTest {
        val realTime = DemoTestClock()
        val clock = DemoClock(realTime)
        val scenario = demoTestScenario()
        val player = ScenarioDemoPlayer(clock, demoTestRepository(scenario), backgroundScope, false)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, player, player, player,
            ObservationTimeBasis.SCENARIO)), clock, policy())
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        hub.start(backgroundScope)
        player.execute(DemoCommand.Start(id("start")))
        realTime.realMs = 500L
        player.pump()
        runCurrent()
        assertTrue(hub.publishDue())
        assertEquals(500L, hub.latestFrame.value!!.tMs)
        val oldHubSession = hub.diagnostics.value.sessionId
        val oldPlayerSession = player.playbackState.value.sessionId

        // 시계 역행을 기존 Hub에 전달하지 않고 중지 → 초기화 → 새 구독 → 시작 순서를 지킨다.
        hub.stop()
        player.execute(DemoCommand.Reset(id("reset")))
        player.execute(DemoCommand.Load(id("reload"), scenario.id))
        hub.start(backgroundScope)
        assertNull(hub.latestFrame.value)
        player.execute(DemoCommand.Start(id("restart")))
        realTime.realMs = 600L
        player.pump()
        runCurrent()
        assertTrue(hub.publishDue())
        assertEquals(100L, hub.latestFrame.value!!.tMs)
        assertEquals(100L, hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).observedAtMs)
        assertNotEquals(oldHubSession, hub.diagnostics.value.sessionId)
        assertNotEquals(oldPlayerSession, player.playbackState.value.sessionId)
        assertFalse(hub.diagnostics.value.clockFailure)
        player.execute(DemoCommand.Stop(id("stop")))
        hub.stop()
    }
}
