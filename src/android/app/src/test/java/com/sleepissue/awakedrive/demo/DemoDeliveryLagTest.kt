package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 처리 중에도 실제 시계가 흐르는 경우를 결정적으로 재현한다. 20ms는 시험 주입값이며 기기 실측이 아니다.
 * 원본 관측 누락, 아직 전달하지 못한 밀림, Hub 발행 deadline 누락을 서로 다른 수치로 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoDeliveryLagTest {
    private fun id(value: String) = RequestId("delivery-lag-$value")

    private fun policy(): HubPolicy {
        // TTL과 원본 시각을 늘리거나 다시 찍어서 밀림을 숨기지 않는다.
        val freshness = SignalFreshnessPolicy(1_000, 1_000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
        return HubPolicy(SignalField.entries.associateWith { freshness }, NaviField.entries.associateWith { freshness },
            freshness, SignalFallbackPolicy(true, 0, ObservationTimeBasis.ELAPSED_REALTIME),
            ObservationTimeBasis.SCENARIO, HubTickDriver.MANUAL)
    }

    @Test
    fun oneDeliveryDelayIsCaughtUpWithinTheSamePumpWithoutRestoringMissedDeadlines() = runTest {
        val real = DemoTestClock()
        val clock = DemoClock(real)
        val scenario = demoTestScenario(120_000, listOf(DemoSpeedSegment(0, 120_000, 60)))
        val player = ScenarioDemoPlayer(clock, demoTestRepository(scenario), backgroundScope, false)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, player, player, player,
            ObservationTimeBasis.SCENARIO)), clock, policy())
        val deliveredOriginalTimes = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.samples.collect { if (it.speed != null) deliveredOriginalTimes += it.tMs }
        }
        var injected = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect {
                if (!injected && it.tMs == 6_000L) {
                    injected = true
                    real.realMs += 20 // 60배에서는 시나리오1,200ms에 해당하는 전달 처리 시간이다.
                }
            }
        }

        player.execute(DemoCommand.Load(id("load"), scenario.id))
        hub.start(backgroundScope)
        player.execute(DemoCommand.Start(id("start")))
        real.realMs = 100
        player.pump() // 진입 목표6,000 뒤 실제20ms가 흐르면 같은 pump에서7,200까지 따라잡는다.
        runCurrent()
        assertTrue(hub.publishDue())

        assertTrue(injected)
        assertEquals(DemoPhase.PLAYING, player.playbackState.value.phase)
        assertEquals(7_200L, player.playbackState.value.scenarioTimeMs)
        assertEquals(120L, player.playbackState.value.realElapsedMs)
        assertEquals((0L..7_200L step 100).toList(), deliveredOriginalTimes)
        assertEquals(73L, player.playbackState.value.deliveredSamples)
        assertEquals(7_200L, player.playbackState.value.lastOriginalSampleMs)
        val diagnostics = hub.diagnostics.value
        val demo = diagnostics.inputs.getValue(Source.DEMO)
        assertEquals(73L, demo.vehicleCallbacks)
        assertEquals(73L, demo.steeringRate.acceptedObservations)
        assertEquals(1L, diagnostics.emittedFrames)
        assertEquals(71L, diagnostics.skippedDeadlines)
        val recovered = demo.vehicleQuality.getValue(SignalField.SPEED_KPH)
        assertEquals(7_200L, recovered.observedAtMs)
        assertEquals(ObservationTimeBasis.SCENARIO, recovered.observedTimeBasis)
        assertEquals(120L, recovered.receivedAtElapsedMs)
        assertEquals(SignalAvailability.AVAILABLE, recovered.availability)
        assertEquals(40f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(35.5, hub.latestNavi.value!!.lat!!, 0.0)
        assertEquals(SignalSelectionReason.DEMO_FALLBACK,
            hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).selectionReason)
        // 다음 회전 거리는 JSON에 원래 없다. 전달 지연 오류 개수에 포함하지 않는다.
        assertEquals(SignalAvailability.UNAVAILABLE,
            demo.naviQuality.getValue(NaviField.DIST_TO_NEXT_TURN_M).availability)

        // 다음 pump도 정확한 다음 원본부터 이어간다. 지난 deadline은 만들지 않는다.
        real.realMs = 122
        player.pump()
        runCurrent()
        assertTrue(hub.publishDue())
        assertEquals((0L..7_300L step 100).toList(), deliveredOriginalTimes)
        assertEquals(74L, hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleCallbacks)
        assertEquals(7_300L, hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).observedAtMs)
        assertEquals(40f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(2L, hub.diagnostics.value.emittedFrames)
        assertEquals(71L, hub.diagnostics.value.skippedDeadlines)
        player.execute(DemoCommand.Stop(id("stop")))
        hub.stop()
    }

    @Test
    fun questionCancellationRestoresSixtyTimesAndCatchesUpWithoutChangingOriginalTimes() = runTest {
        val real = DemoTestClock()
        val clock = DemoClock(real)
        val scenario = demoTestScenario(120_000, listOf(DemoSpeedSegment(0, 120_000, 60)))
        val player = ScenarioDemoPlayer(clock, demoTestRepository(scenario), backgroundScope, false)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, player, player, player,
            ObservationTimeBasis.SCENARIO)), clock, policy())
        val deliveredOriginalTimes = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.samples.collect { if (it.speed != null) deliveredOriginalTimes += it.tMs }
        }
        var injected = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect {
                if (!injected && it.tMs == 10_000L) {
                    injected = true
                    real.realMs += 20
                }
            }
        }
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        hub.start(backgroundScope)
        player.execute(DemoCommand.Start(id("start")))
        real.realMs = 100
        player.pump()
        val session = player.playbackState.value.sessionId
        val question = id("question")
        player.execute(DemoCommand.QuestionStarted(question, session))
        real.realMs = 1_100
        player.pump()
        assertEquals(7_000L, player.playbackState.value.scenarioTimeMs)
        player.execute(DemoCommand.QuestionFinished(question, session, DemoQuestionOutcome.CANCELLED))
        assertEquals(60, player.playbackState.value.speedMultiplier)
        real.realMs = 1_150
        player.pump()
        runCurrent()
        assertTrue(hub.publishDue())

        assertTrue(injected)
        assertEquals(DemoPhase.PLAYING, player.playbackState.value.phase)
        assertEquals(DemoQuestionOutcome.CANCELLED, player.playbackState.value.questionOutcome)
        assertNull(player.playbackState.value.questionRequestId)
        assertEquals(11_200L, player.playbackState.value.scenarioTimeMs)
        assertEquals((0L..11_200L step 100).toList(), deliveredOriginalTimes)
        assertEquals(113L, player.playbackState.value.deliveredSamples)
        assertEquals(11_200L, player.playbackState.value.lastOriginalSampleMs)
        val demo = hub.diagnostics.value.inputs.getValue(Source.DEMO)
        assertEquals(113L, demo.vehicleCallbacks)
        assertEquals(11_200L, demo.vehicleQuality.getValue(SignalField.SPEED_KPH).observedAtMs)
        assertEquals(SignalAvailability.AVAILABLE, demo.vehicleQuality.getValue(SignalField.SPEED_KPH).availability)
        assertEquals(40f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(35.5, hub.latestNavi.value!!.lat!!, 0.0)
        assertEquals(111L, hub.diagnostics.value.skippedDeadlines)
        assertEquals(1L, hub.diagnostics.value.emittedFrames)
        assertTrue(player.playbackState.value.scenarioTimeMs < scenario.durationMs - 100_000)
        player.pump()
        assertFalse(hub.publishDue())
        assertEquals(113L, player.playbackState.value.deliveredSamples)
        assertEquals(111L, hub.diagnostics.value.skippedDeadlines)
        player.execute(DemoCommand.Stop(id("stop")))
        hub.stop()
    }

    @Test
    fun sustainedDeliveryCostYieldsAtACompleteSampleAndResumesWithoutSkippingOriginals() = runTest {
        val real = DemoTestClock()
        val clock = DemoClock(real)
        val scenario = demoTestScenario(120_000, listOf(DemoSpeedSegment(0, 120_000, 60)))
        val player = ScenarioDemoPlayer(clock, demoTestRepository(scenario), backgroundScope, false)
        val vehicleTimes = mutableListOf<Long>()
        val naviTimes = mutableListOf<Long>()
        val powerTimes = mutableListOf<Long>()
        var simulateDeliveryCost = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.samples.collect { if (it.speed != null) vehicleTimes += it.tMs }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.states.collect { if (it.lat != null) naviTimes += it.tMs }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect {
                if (it.state == PowerState.ON) {
                    powerTimes += it.tMs
                    if (simulateDeliveryCost) real.realMs += 10 // 기기 실측이 아닌 표본당 가상 처리 시간이다.
                }
            }
        }
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        simulateDeliveryCost = true
        real.realMs = 100

        player.pump()

        // 기술 예산25ms는 세 채널을 마친 경계에서 확인하므로10ms 표본3개 뒤 반환한다.
        assertEquals(130L, real.realMs)
        assertEquals(DemoPhase.PLAYING, player.playbackState.value.phase)
        assertEquals(7_800L, player.playbackState.value.scenarioTimeMs)
        assertEquals(4L, player.playbackState.value.deliveredSamples)
        assertEquals((0L..300L step 100).toList(), vehicleTimes)
        assertEquals(vehicleTimes, naviTimes)
        assertEquals(vehicleTimes, powerTimes)

        player.pump()

        assertEquals(160L, real.realMs)
        assertEquals(9_600L, player.playbackState.value.scenarioTimeMs)
        assertEquals(7L, player.playbackState.value.deliveredSamples)
        assertEquals(600L, player.playbackState.value.lastOriginalSampleMs)
        assertEquals((0L..600L step 100).toList(), vehicleTimes)
        assertEquals(vehicleTimes, naviTimes)
        assertEquals(vehicleTimes, powerTimes)
        player.execute(DemoCommand.Stop(id("stop")))
    }

    @Test
    fun pausedPumpsDrainRemainingSamplesAtTheFrozenTimeWithoutResumingTheClock() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(120_000, listOf(DemoSpeedSegment(0, 120_000, 60)))
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        val originalTimes = mutableListOf<Long>()
        var simulateDeliveryCost = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.samples.collect { if (it.speed != null) originalTimes += it.tMs }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect {
                if (simulateDeliveryCost && it.state == PowerState.ON) real.realMs += 10
            }
        }
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        simulateDeliveryCost = true
        real.realMs = 100

        player.execute(DemoCommand.Pause(id("pause")))

        assertEquals(DemoPhase.PAUSED, player.playbackState.value.phase)
        assertEquals(6_000L, player.playbackState.value.scenarioTimeMs)
        assertEquals(0, player.playbackState.value.speedMultiplier)
        assertEquals(4L, player.playbackState.value.deliveredSamples)
        assertEquals((0L..300L step 100).toList(), originalTimes)

        // 추가 실제 시간이 흘러도 운행 시각은 동결한 채 남은57개 원본을19회에 걸쳐 전달한다.
        repeat(19) { player.pump() }

        assertEquals(700L, real.realMs)
        assertEquals(DemoPhase.PAUSED, player.playbackState.value.phase)
        assertEquals(6_000L, player.playbackState.value.scenarioTimeMs)
        assertEquals(61L, player.playbackState.value.deliveredSamples)
        assertEquals(6_000L, player.playbackState.value.lastOriginalSampleMs)
        assertEquals((0L..6_000L step 100).toList(), originalTimes)
        player.pump()
        assertEquals(61L, player.playbackState.value.deliveredSamples)
        assertEquals(700L, real.realMs)
        player.execute(DemoCommand.Stop(id("stop")))
    }

    @Test
    fun reachingTheEndDuringDeliveryCompletesOnlyAfterAllRemainingSamplesAreDelivered() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(1_000, listOf(DemoSpeedSegment(0, 1_000, 60)))
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        val vehicle = mutableListOf<VehicleSignalInput>()
        val navi = mutableListOf<NaviState>()
        val power = mutableListOf<PowerEvent>()
        var simulateDeliveryCost = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { vehicle += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.states.collect { navi += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect {
                power += it
                if (simulateDeliveryCost && it.state == PowerState.ON) real.realMs += 10
            }
        }
        player.execute(DemoCommand.Load(id("load"), scenario.id))
        player.execute(DemoCommand.Start(id("start")))
        simulateDeliveryCost = true
        real.realMs = 10

        player.pump()

        assertEquals(1_000L, player.playbackState.value.scenarioTimeMs)
        assertEquals(0, player.playbackState.value.speedMultiplier)
        assertEquals(DemoPhase.PLAYING, player.playbackState.value.phase)
        assertEquals(4L, player.playbackState.value.deliveredSamples)
        assertEquals((0L..300L step 100).toList(), vehicle.map { it.tMs })
        assertTrue(vehicle.all { it.speed != null })

        player.pump()
        assertEquals(DemoPhase.PLAYING, player.playbackState.value.phase)
        assertEquals(7L, player.playbackState.value.deliveredSamples)
        player.pump()

        assertEquals(DemoPhase.COMPLETED, player.playbackState.value.phase)
        assertEquals(10L, player.playbackState.value.deliveredSamples)
        assertEquals(900L, player.playbackState.value.lastOriginalSampleMs)
        assertEquals((0L..900L step 100).toList(), vehicle.filter { it.speed != null }.map { it.tMs })
        assertEquals((0L..900L step 100).toList(), navi.filter { it.lat != null }.map { it.tMs })
        assertEquals((0L..900L step 100).toList(), power.filter { it.state == PowerState.ON }.map { it.tMs })
        assertEquals(11, vehicle.size)
        assertEquals(11, navi.size)
        assertEquals(11, power.size)
        assertEquals(1_000L, vehicle.last().tMs)
        assertTrue(vehicle.last().quality.values.all { it.availability == SignalAvailability.UNAVAILABLE })
        assertNull(vehicle.last().speed)
        assertNull(navi.last().lat)
        assertEquals(PowerState.UNKNOWN, power.last().state)
        player.pump()
        assertEquals(10L, player.playbackState.value.deliveredSamples)
        assertEquals(11, vehicle.size)
        assertEquals(11, navi.size)
        assertEquals(11, power.size)
    }
    @Test
    fun shortPumpBudgetOffersPublicationBetweenCompleteSamplesWithoutDroppingAnyChannel() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(1_000, listOf(DemoSpeedSegment(0, 1_000, 60)))
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        val vehicle = mutableListOf<Long>()
        val navi = mutableListOf<Long>()
        val power = mutableListOf<Long>()
        val boundaries = mutableListOf<Triple<Int, Int, Int>>()
        var costly = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { if (it.speed != null) vehicle += it.tMs } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.states.collect { if (it.lat != null) navi += it.tMs } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.events.collect { if (it.state == PowerState.ON) { power += it.tMs; if (costly) real.realMs += 1 } }
        }
        player.execute(DemoCommand.Load(id("short-load"), scenario.id))
        player.execute(DemoCommand.Start(id("short-start")))
        costly = true
        real.realMs = 10
        player.pump(workBudgetMs = 2) {
            boundaries += Triple(vehicle.size, navi.size, power.size)
        }
        assertEquals(12L, real.realMs)
        assertEquals(3L, player.playbackState.value.deliveredSamples)
        assertEquals(listOf(Triple(2, 2, 2), Triple(3, 3, 3)), boundaries)
        while (player.playbackState.value.phase == DemoPhase.PLAYING) player.pump(workBudgetMs = 2) {}
        assertEquals((0L..900L step 100).toList(), vehicle)
        assertEquals(vehicle, navi)
        assertEquals(vehicle, power)
        assertEquals(DemoPhase.COMPLETED, player.playbackState.value.phase)
    }

    @Test
    fun callbackSeesCaughtUpOnlyAfterAllDueOriginalChannelsHaveBeenHandedOver() = runTest {
        val real = DemoTestClock()
        val scenario = demoTestScenario(1_000, listOf(DemoSpeedSegment(0, 1_000, 60)))
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        player.execute(DemoCommand.Load(id("boundary-load"), scenario.id))
        player.execute(DemoCommand.Start(id("boundary-start")))
        real.realMs = 5 // 시나리오300ms까지 원본100·200·300을 전달한다.
        val caughtUp = mutableListOf<Boolean>()
        val originalTimes = mutableListOf<Long?>()
        player.pump(workBudgetMs = 2) {
            caughtUp += it
            originalTimes += player.playbackSnapshot().lastOriginalSampleMs
        }
        assertEquals(listOf(false, false, true), caughtUp)
        assertEquals(listOf(100L, 200L, 300L), originalTimes)
        player.execute(DemoCommand.Stop(id("boundary-stop")))
    }

}
