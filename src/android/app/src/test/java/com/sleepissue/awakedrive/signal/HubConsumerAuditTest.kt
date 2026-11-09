package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.MissingObservationPolicy
import com.sleepissue.awakedrive.engine.signal.SignalFallbackPolicy
import com.sleepissue.awakedrive.engine.signal.SignalFreshnessPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 원본 수신·Hub 발행·개별 분석 소비자의 누락을 따로 감사한다. 판단 엔진은 연결하지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class HubConsumerAuditTest {
    @Test fun fastAuditConsumersMatchProducerPublicationsAndOriginalCallbacks() = runTest {
        val rig = Rig(this)
        val publications = mutableListOf<Long>()
        val originals = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.hub.samples.collect { publications += it.publication!!.sequence }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.hub.rawVehicleObservations.collect { originals += it.frame.tMs }
        }
        rig.hub.start(backgroundScope)
        rig.produceTenOriginalsAndFrames()
        val producer = rig.hub.diagnostics.value
        val input = producer.inputs.getValue(Source.DEMO)
        assertEquals(10L, input.vehicleCallbacks)
        assertEquals(10L, input.steeringRate.acceptedObservations)
        assertEquals(10L, producer.emittedFrames)
        assertEquals(0L, producer.skippedDeadlines)
        assertEquals((1L..10L).toList(), publications)
        assertEquals((100L..1_000L step 100).toList(), originals)
        assertEquals(producer.emittedFrames, publications.size.toLong())
        assertEquals(input.vehicleCallbacks, originals.size.toLong())
        assertEquals(0L, publicationGaps(publications))
        assertEquals(0L, originalTimeGaps(originals))
        rig.hub.stop()
    }

    @Test fun slowConsumersCanHaveGapsWhileOriginalReceptionAndPublicationsRemainComplete() = runTest {
        val rig = Rig(this)
        val fastPublications = mutableListOf<Long>()
        val fastOriginals = mutableListOf<Long>()
        val slowPublications = mutableListOf<Long>()
        val slowOriginals = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.hub.samples.collect { fastPublications += it.publication!!.sequence }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.hub.rawVehicleObservations.collect { fastOriginals += it.frame.tMs }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.hub.samples.collect { slowPublications += it.publication!!.sequence; delay(1_000) }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.hub.rawVehicleObservations.collect { slowOriginals += it.frame.tMs; delay(1_000) }
        }
        rig.hub.start(backgroundScope)
        rig.produceTenOriginalsAndFrames()
        assertEquals(listOf(1L), slowPublications)
        assertEquals(listOf(100L), slowOriginals)
        // 소비자만 재개한다. 생산 시계·원본·발행 시한은 더 진행하지 않는다.
        advanceTimeBy(1_000); runCurrent()
        val producer = rig.hub.diagnostics.value
        val input = producer.inputs.getValue(Source.DEMO)
        assertEquals(10L, input.vehicleCallbacks)
        assertEquals(10L, input.steeringRate.acceptedObservations)
        assertEquals(10L, producer.emittedFrames)
        assertEquals(0L, producer.skippedDeadlines)
        assertEquals((1L..10L).toList(), fastPublications)
        assertEquals((100L..1_000L step 100).toList(), fastOriginals)
        assertEquals(listOf(1L, 10L), slowPublications)
        assertEquals(listOf(100L, 1_000L), slowOriginals)
        assertEquals(8L, publicationGaps(slowPublications))
        assertEquals(8L, originalTimeGaps(slowOriginals))
        assertEquals(producer.emittedFrames - slowPublications.size, publicationGaps(slowPublications))
        assertEquals(input.vehicleCallbacks - slowOriginals.size, originalTimeGaps(slowOriginals))
        // 원본 수신 누락0·발행 deadline 누락0이어도 느린 소비자의 누락8개는 남는다.
        assertEquals(40f, rig.hub.latestFrame.value!!.speedKph!!, 0f)
        rig.hub.stop()
    }

    private class Rig(private val scope: TestScope) {
        private val clock = FakeClock()
        private val source = FakeSignalSource()
        private val freshness = SignalFreshnessPolicy(1_000, 1_000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
        val hub = VehicleSignalHub(
            listOf(HubSourceSet(Source.DEMO, source, rateTimeBasis = ObservationTimeBasis.SCENARIO)),
            clock,
            HubPolicy(SignalField.entries.associateWith { freshness }, NaviField.entries.associateWith { freshness },
                freshness, SignalFallbackPolicy(true, 0, ObservationTimeBasis.ELAPSED_REALTIME),
                ObservationTimeBasis.SCENARIO, HubTickDriver.MANUAL),
        )

        suspend fun produceTenOriginalsAndFrames() {
            for (at in 100L..1_000L step 100) {
                clock.scenarioMs = at
                clock.elapsedMs = at / 60
                val quality = SignalQuality(SignalAvailability.AVAILABLE, Source.DEMO, at,
                    observedTimeBasis = ObservationTimeBasis.SCENARIO)
                source.emit(VehicleSignalInput(at, Source.DEMO, steeringDeg = 1f, speed = 40f, gear = Gear.D,
                    quality = SignalField.entries.associateWith { quality }))
                scope.runCurrent()
                assertTrue(hub.publishDue())
                scope.runCurrent()
            }
        }
    }

    private fun publicationGaps(sequences: List<Long>): Long =
        sequences.zipWithNext().sumOf { (before, after) -> after - before - 1 }

    private fun originalTimeGaps(times: List<Long>): Long =
        times.zipWithNext().sumOf { (before, after) -> (after - before) / 100 - 1 }
}
