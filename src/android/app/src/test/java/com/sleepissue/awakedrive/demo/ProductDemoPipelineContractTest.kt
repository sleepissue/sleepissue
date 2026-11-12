package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.TripRules
import com.sleepissue.awakedrive.engine.session.TripRulePolicy
import com.sleepissue.awakedrive.engine.session.StopTimePolicy
import com.sleepissue.awakedrive.engine.session.ShortStopAccumulationPolicy
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.engine.demo.DemoTripPreset
import com.sleepissue.awakedrive.fatigue.FatigueBridge
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 원본6종 → 제품 배속 정책 → 실제 Player/Hub/TripTracker → 기존 판단·개입의 JVM 결합 시험.
 * 시계만 수동 진행하며 점수·단계·사람의 응답을 주입하지 않는다. 실제 음성/SDK/AVD 성능 시험은 아니다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductDemoPipelineContractTest {
    @Test fun normal() = runTest { replay("normal") }
    @Test fun distraction() = runTest { replay("distraction") }
    @Test fun drowsy() = runTest { replay("drowsy") }
    @Test fun talk() = runTest { replay("talk") }
    @Test fun assist() = runTest { replay("assist") }
    @Test fun departure() = runTest { replay("departure") }
    @Test fun originalDepartureKeepsTheAuthoredTenToOneSpeedBoundary() = runTest {
        replay("departure", ProductDemoSpeed.ORIGINAL)
    }

    private suspend fun TestScope.replay(id: String, speed: ProductDemoSpeed = ProductDemoSpeed.TEN) {
        val original = (DemoJsonLoader().loadFile(File("../../../data/scenarios/$id.json")) as DemoLoadResult.Loaded).scenario
        val scenario = original.withPlaybackSpeed(speed)
        val real = DemoTestClock()
        val demo = DemoClock(real)
        val epoch = scenario.startEpochMs
        val clock = object : Clock {
            override fun scenarioTimeMs() = epoch + demo.scenarioTimeMs()
            override fun elapsedRealtimeMs() = real.realMs
            override fun wallTimeMs() = scenarioTimeMs()
        }
        val player = ScenarioDemoPlayer(demo, demoTestRepository(scenario), backgroundScope, false,
            transportMode = DemoTransportMode.COMPLETE_OBSERVATION)
        fun SignalQuality.epoch() = copy(observedAtMs = observedAtMs?.plus(epoch))
        val source = object : CompleteSignalSource {
            override val observations = player.observations.map { o -> CompleteSignalObservation(
                o.vehicle.copy(tMs = o.vehicle.tMs + epoch, quality = o.vehicle.quality.mapValues { it.value.epoch() }),
                o.navi.copy(tMs = o.navi.tMs + epoch, quality = o.navi.quality.mapValues { it.value.epoch() }),
                o.power.copy(tMs = o.power.tMs + epoch, quality = o.power.quality.epoch())) }
        }
        val fresh = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, rateTimeBasis = ObservationTimeBasis.SCENARIO, complete = source)),
            clock, HubPolicy(SignalField.entries.associateWith { fresh }, NaviField.entries.associateWith { fresh }, fresh,
                SignalFallbackPolicy(true, 0, ObservationTimeBasis.SCENARIO), ObservationTimeBasis.SCENARIO, HubTickDriver.MANUAL))
        val config = EngineConfig()
        val seed = (scenario.tripPreset ?: DemoTripPreset(0, 0)).seed(epoch, "contract-$id")
        val trip = TripStateStore(seed.trip)
        val tracker = TripTracker(TripRules(config, TripRulePolicy(StopTimePolicy.V03_RETROACTIVE,
            ShortStopAccumulationPolicy.EXCLUDE)) { "contract-$id" }, trip, Source.DEMO, seed)
        val bridge = FatigueBridge(config)
        var serial = 0
        val manager = PolicyInterventionManager(InterventionPolicy(config), config, clock, Source.DEMO,
            RequestTimerBasis.SCENARIO) { RequestId("action-${++serial}") }
        val requests = mutableListOf<ExecutionRequest>()
        val requestJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.requests.collect { requests += it } }
        manager.start(RequestId("start"))
        var publications = 0
        var invalidSignals = 0
        var maximumStage = 0
        var maximumRisk = 0f
        var evaluations = 0
        var lastEvaluatedAt: Long? = null
        var departureDetected = false
        var assistedSeen = false
        var firstCheckDue: Long? = null
        val frameJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            hub.samples.collect { frame ->
                publications++
                if (frame.steeringDeg == null || frame.speedKph == null || frame.gear == null) invalidSignals++
                tracker.accept(frame, hub.latestPower.value?.takeIf { it.tMs == frame.tMs })
                manager.submit(RequestId("trip-${++serial}"), InterventionInput.TripChanged(trip.state.value))
                for (input in bridge.onFrame(frame, hub.latestNavi.value, trip.state.value))
                    manager.submit(RequestId("engine-${++serial}"), input)
                trip.update { it.copy(stage = manager.state.value.stage) }
                maximumStage = maxOf(maximumStage, manager.state.value.stage.level)
                assistedSeen = assistedSeen || bridge.snapshot.assist.assistMode
                departureDetected = departureDetected || bridge.snapshot.baseline.departureFatigue
                firstCheckDue = bridge.snapshot.baseline.firstCheckDueMs ?: firstCheckDue
                bridge.snapshot.fatigue?.let {
                    if (it.valid && it.tMs != lastEvaluatedAt) {
                        evaluations++; maximumRisk = maxOf(maximumRisk, it.riskSmoothed)
                    }
                    lastEvaluatedAt = it.tMs
                }
            }
        }
        hub.start(backgroundScope)
        player.execute(DemoCommand.Load(RequestId("load"), id))
        player.execute(DemoCommand.Start(RequestId("play")))
        runCurrent()
        // 10배는 실제10ms당 시나리오100ms. 원본 모드는 원래 구간 경계에서만 배속이 바뀐다.
        for (sampleIndex in 1 until scenario.samples.size) {
            real.realMs += 100 / demo.snapshot().segmentMultiplier
            player.pump()
            runCurrent()
            assertTrue(hub.publishDue())
            runCurrent()
            // 제품 파이프라인의 Tick은 실제1초 주기이며 데모 시계로 10초다.
            if (real.realMs % 1_000L == 0L) manager.submit(RequestId("tick-${++serial}"), InterventionInput.Tick)
        }
        assertEquals(17_999, publications)
        assertEquals(0, invalidSignals)
        assertTrue("$id: 기존 엔진의 유효 계산 없음", evaluations > 0)
        assertEquals(18_000L, player.playbackState.value.deliveredSamples)
        if (id == "assist") assertTrue(assistedSeen)
        if (id == "departure") assertTrue("출발 피로 판정", departureDetected)
        if (id == "drowsy") assertEquals("졸음 휴식 유도 연결", InterventionStage.REST_GUIDANCE.level, maximumStage)
        if (id in listOf("normal", "talk")) {
            assertEquals(0f, maximumRisk, 0f)
            assertEquals("오후 취약 시간대는 안내만 하고 확인 단계로 올리지 않음", InterventionStage.CAUTION.level, maximumStage)
        }
        if (id == "departure") assertTrue("원본 종료 뒤에 오는 첫 확인 시각을 조작하지 않음",
            requireNotNull(firstCheckDue) > epoch + scenario.durationMs)
        println("PRODUCT_DEMO id=$id profile=$speed publications=$publications unexpectedNull=$invalidSignals " +
            "evaluations=$evaluations maxStage=$maximumStage " +
            "maxSmoothedRisk=$maximumRisk departure=$departureDetected assisted=$assistedSeen " +
            "firstCheckDueRelativeMs=${firstCheckDue?.minus(epoch)} requests=${requests.size}")
        real.realMs += 100 / demo.snapshot().segmentMultiplier
        player.pump()
        runCurrent()
        assertEquals(DemoPhase.COMPLETED, player.playbackState.value.phase)
        assertEquals(if (speed == ProductDemoSpeed.TEN) 180_000L else 990_000L, real.realMs)
        hub.stop()
        assertNull(hub.latestFrame.value)
        manager.stop(RequestId("stop"))
        frameJob.cancelAndJoin(); requestJob.cancelAndJoin()
    }
}
