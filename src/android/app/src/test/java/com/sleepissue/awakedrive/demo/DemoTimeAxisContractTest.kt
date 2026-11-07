package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckVerdict
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser
import com.sleepissue.awakedrive.signal.*
import com.sleepissue.awakedrive.voice.CheckManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * 시간축 교체의 반례를 만드는 시험 전용 조립이다. 생산 재생 경로·TTL·원본 시각을 바꾸지 않는다.
 * 부하와 수신 지연은 가짜 실제 경과 시계로 주입한다. 기기 처리 성능을 측정하는 시험이 아니다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoTimeAxisContractTest {
    private class ActualClock(var elapsedMs: Long = 0) : Clock {
        override fun scenarioTimeMs() = elapsedMs
        override fun elapsedRealtimeMs() = elapsedMs
        override fun wallTimeMs() = 1_800_000_000_000L + elapsedMs
    }

    /** 의도적으로 늦은 처리 시각을 노출하는 반례용 Clock이다. 실제 시간 두 축은 그대로 위임한다. */
    private class CompletedClock(private val actual: Clock) : Clock {
        var completedMs = 0L
            set(value) { require(value >= field); field = value }
        override fun scenarioTimeMs() = completedMs
        override fun elapsedRealtimeMs() = actual.elapsedRealtimeMs()
        override fun wallTimeMs() = actual.wallTimeMs()
    }

    private class CompleteSource : CompleteSignalSource {
        override val observations = MutableSharedFlow<CompleteSignalObservation>()
        suspend fun emit(value: CompleteSignalObservation) = observations.emit(value)
    }

    private fun targetClock(actual: ActualClock, multiplier: Int) = DemoClock(actual).apply {
        configure(120_000, listOf(DemoSpeedSegment(0, 120_000, multiplier)))
        start()
    }

    private fun policy(publicationBasis: ObservationTimeBasis = ObservationTimeBasis.SCENARIO): HubPolicy {
        val ttl = SignalFreshnessPolicy(1_000, 1_000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
        return HubPolicy(SignalField.entries.associateWith { ttl }, NaviField.entries.associateWith { ttl }, ttl,
            SignalFallbackPolicy(true, 0, ObservationTimeBasis.ELAPSED_REALTIME), publicationBasis, HubTickDriver.MANUAL)
    }

    private fun sourceSet(source: CompleteSource) = HubSourceSet(Source.DEMO,
        rateTimeBasis = ObservationTimeBasis.SCENARIO, complete = source)

    private fun observation(at: Long, source: Source = Source.DEMO, steering: Float? = 1f,
        speed: Float = if (source == Source.REAL) 80f else 40f): CompleteSignalObservation {
        val q = SignalQuality(SignalAvailability.AVAILABLE, source, at,
            observedTimeBasis = if (source == Source.DEMO) ObservationTimeBasis.SCENARIO else ObservationTimeBasis.ELAPSED_REALTIME)
        return CompleteSignalObservation(
            VehicleSignalInput(at, source, steeringDeg = steering, speed = speed, gear = Gear.D,
                turnSignal = TurnSignal.OFF, brakeOn = false, cruiseActive = false,
                quality = SignalField.entries.associateWith { field ->
                    if (field == SignalField.STEERING_DEG && steering == null) q.copy(availability = SignalAvailability.UNAVAILABLE) else q
                }),
            NaviState(at, if (source == Source.REAL) 37.5 else 35.5, if (source == Source.REAL) 127.0 else 129.0,
                quality = NaviField.entries.associateWith { q }),
            PowerEvent(at, PowerState.ON, q),
        )
    }

    @Test fun theSameOriginalBecomesStaleAtOneTenAndSixtyTimesWhenTargetTimeAdvancesDuringDelivery() = runTest {
        val original = observation(0)
        for (multiplier in listOf(1, 10, 60)) {
            for (delayed in listOf(false, true)) {
                val actual = ActualClock(); val target = targetClock(actual, multiplier)
                val source = CompleteSource(); val hub = VehicleSignalHub(listOf(sourceSet(source)), target, policy())
                hub.start(backgroundScope)
                // 원본0이 대기하는 동안 처리 부하가 흐른다. 60배의 실제20ms는 시나리오1,200ms다.
                val scenarioDelay = if (delayed) 1_200L else 100L
                actual.elapsedMs = (scenarioDelay + multiplier - 1) / multiplier
                source.emit(original); runCurrent(); assertTrue(hub.publishDue())
                val q = hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH)
                assertEquals(0L, q.observedAtMs)
                assertEquals(actual.elapsedMs, q.receivedAtElapsedMs)
                assertEquals(0L, actual.elapsedRealtimeMs() - q.receivedAtElapsedMs!!)
                assertEquals(actual.elapsedMs * multiplier, target.scenarioTimeMs())
                assertEquals(actual.elapsedMs, target.elapsedRealtimeMs())
                assertEquals(actual.wallTimeMs(), target.wallTimeMs())
                assertEquals(actual.elapsedMs, hub.latestFrame.value!!.publication!!.publishedAtElapsedMs)
                if (delayed) {
                    assertEquals(1_200L, target.scenarioTimeMs() - q.observedAtMs!!)
                    assertEquals(SignalAvailability.STALE, q.availability)
                    assertNull(hub.latestFrame.value!!.speedKph)
                } else {
                    assertTrue(target.scenarioTimeMs() - q.observedAtMs!! <= 120)
                    assertEquals(SignalAvailability.AVAILABLE, q.availability)
                    assertEquals(40f, hub.latestFrame.value!!.speedKph!!, 0f)
                }
                hub.stop()
            }
        }
        assertEquals(0L, original.vehicle.tMs)
        assertNull(original.vehicle.quality.getValue(SignalField.SPEED_KPH).receivedAtElapsedMs)
    }

    @Test fun movingOnlyTheHubScenarioClockBehindTheTargetMakesAStaleValueAppearAvailable() = runTest {
        val actual = ActualClock(); val target = targetClock(actual, 60); val completed = CompletedClock(actual)
        val targetSource = CompleteSource(); val completedSource = CompleteSource()
        val targetHub = VehicleSignalHub(listOf(sourceSet(targetSource)), target, policy())
        val completedHub = VehicleSignalHub(listOf(sourceSet(completedSource)), completed, policy())
        targetHub.start(backgroundScope); completedHub.start(backgroundScope)
        actual.elapsedMs = 20
        // 이 시험은 속도 최신성만 분리한다. 조향 원천 Hz의 미래 시각 문제는 다음 시험에서 확인한다.
        val original = observation(100, steering = null)
        targetSource.emit(original); completedSource.emit(original); runCurrent()
        // 시험 스케줄러로 세 채널 수신을 끝낸 뒤에만 완료 watermark를 올린다. 실제 ACK 구현은 추가하지 않는다.
        completed.completedMs = 100
        assertTrue(targetHub.publishDue()); assertTrue(completedHub.publishDue())
        val targetQ = targetHub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH)
        val completedQ = completedHub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH)
        val targetLagMs = target.scenarioTimeMs() - completed.scenarioTimeMs()
        assertEquals(1_100L, targetLagMs)
        assertEquals(100L, targetQ.observedAtMs); assertEquals(targetQ.observedAtMs, completedQ.observedAtMs)
        assertEquals(20L, targetQ.receivedAtElapsedMs); assertEquals(targetQ.receivedAtElapsedMs, completedQ.receivedAtElapsedMs)
        assertEquals(SignalAvailability.STALE, targetQ.availability)
        assertEquals(SignalAvailability.AVAILABLE, completedQ.availability)
        assertNull(targetHub.latestFrame.value!!.speedKph)
        assertEquals(40f, completedHub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(1_200L, targetHub.latestFrame.value!!.tMs)
        assertEquals(100L, completedHub.latestFrame.value!!.tMs)
        assertEquals(12L, targetHub.diagnostics.value.emittedFrames + targetHub.diagnostics.value.skippedDeadlines)
        assertEquals(1L, completedHub.diagnostics.value.emittedFrames + completedHub.diagnostics.value.skippedDeadlines)
        assertEquals(20L, completed.elapsedRealtimeMs())
        assertEquals(actual.wallTimeMs(), completed.wallTimeMs())
        targetHub.stop(); completedHub.stop()
    }

    @Test fun anObservationAheadOfTheCompletedClockBreaksSourceRateAndMixingRateClocksAlsoReversesTime() = runTest {
        val actual = ActualClock(); val target = targetClock(actual, 60); val completed = CompletedClock(actual)
        val source = CompleteSource(); val hub = VehicleSignalHub(listOf(sourceSet(source)), completed, policy())
        hub.start(backgroundScope)
        actual.elapsedMs = 20
        val original = observation(100)
        source.emit(original); runCurrent()
        completed.completedMs = 100
        assertTrue(hub.publishDue())
        val rate = hub.diagnostics.value.inputs.getValue(Source.DEMO).steeringRate
        assertEquals(RateStatus.UNMEASURABLE, rate.status)
        assertEquals(0L, rate.acceptedObservations)
        assertEquals(1L, rate.reversedObservations)
        assertNull(rate.hz)
        assertEquals(100L, hub.latestFrame.value!!.qualityOf(SignalField.STEERING_DEG).observedAtMs)
        assertEquals(1_100L, target.scenarioTimeMs() - completed.scenarioTimeMs())

        // observe만 목표 시각으로 바꿔도 sample이 논리 시각이면 같은 RateMeter의 now가 역행한다.
        val mixedMeter = RateMeter().apply { start(0, ObservationTimeBasis.SCENARIO) }
        assertEquals(RateObservationResult.ACCEPTED, mixedMeter.observe(100, ObservationTimeBasis.SCENARIO, target.scenarioTimeMs()))
        val reversed = mixedMeter.sample(completed.scenarioTimeMs())
        assertEquals(RateStatus.UNMEASURABLE, reversed.status)
        assertEquals(1L, reversed.acceptedObservations)
        assertEquals(1L, reversed.reversedObservations)
        assertNull(reversed.hz)
        hub.stop()
    }

    @Test fun pausedScenarioTimeNeverFreezesReceiptTtlRealFreshnessOrVoiceResponseElapsedTime() = runTest {
        val actual = ActualClock(); val target = targetClock(actual, 60); val completed = CompletedClock(actual)
        val source = CompleteSource(); val real = FakeSignalSource()
        // 논리 시각을 멈춘 채 실제 TTL을 검증할 수 있도록 기존 ELAPSED 발행 정책을 명시한다.
        val hub = VehicleSignalHub(listOf(
            HubSourceSet(Source.REAL, real, rateTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME), sourceSet(source)),
            completed, policy(ObservationTimeBasis.ELAPSED_REALTIME))
        hub.start(backgroundScope)
        actual.elapsedMs = 2
        real.emit(observation(2, Source.REAL).vehicle)
        source.emit(observation(100, steering = null)); runCurrent()
        completed.completedMs = 100
        target.pause()
        assertEquals(120L, target.scenarioTimeMs())
        val config = EngineConfig()
        val check = CheckManager(completed, CheckPolicy(config), SpeechIntentParser())
        check.onBeepEnded()
        actual.elapsedMs = 100; assertTrue(hub.publishDue())
        assertEquals(80f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(Source.REAL, hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).source)
        actual.elapsedMs = 1_002; assertTrue(hub.publishDue())
        assertEquals(80f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(SignalAvailability.AVAILABLE,
            hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH).availability)
        actual.elapsedMs = 1_100; assertTrue(hub.publishDue())
        val expired = hub.diagnostics.value.inputs
        assertEquals(SignalAvailability.STALE, expired.getValue(Source.REAL).vehicleQuality.getValue(SignalField.SPEED_KPH).availability)
        val demoQ = expired.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH)
        assertEquals(SignalAvailability.STALE, demoQ.availability)
        assertEquals(0L, completed.scenarioTimeMs() - demoQ.observedAtMs!!)
        assertEquals(1_098L, completed.elapsedRealtimeMs() - demoQ.receivedAtElapsedMs!!)
        assertNull(hub.latestFrame.value!!.speedKph)

        real.emit(observation(1_100, Source.REAL, speed = 90f).vehicle); runCurrent()
        actual.elapsedMs = 1_200; assertTrue(hub.publishDue())
        assertEquals(90f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(Source.REAL, hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).source)
        assertEquals(1_100L, hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).observedAtMs)
        assertEquals(100L, hub.latestFrame.value!!.tMs)
        actual.elapsedMs = 2_302
        check.onVoiceStarted()
        val response = check.finish(config.defaultVoiceBaselineMs, QuestionType.DRIVER_CHECK, "네")
        assertEquals(2_300, response.latencyMs)
        assertEquals(CheckVerdict.SLOW, response.verdict)
        assertEquals(100L, completed.scenarioTimeMs())
        assertEquals(120L, target.scenarioTimeMs())
        assertEquals(2_302L, completed.elapsedRealtimeMs())
        assertEquals(actual.wallTimeMs(), completed.wallTimeMs())
        hub.stop()
    }
}
