package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.performance.WorkProbe
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 로드 준비는 원본 metadata 변환 시점만 옮기며 값·실제 수신 시각·감사 의미를 바꾸지 않는다. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreparedDemoScenarioTest {
    private fun id(value: String) = RequestId("prepared-$value")
    private fun policy(): HubPolicy {
        val ttl = SignalFreshnessPolicy(1_000, 1_000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
        return HubPolicy(SignalField.entries.associateWith { ttl }, NaviField.entries.associateWith { ttl }, ttl,
            SignalFallbackPolicy(true, 0, ObservationTimeBasis.ELAPSED_REALTIME), ObservationTimeBasis.SCENARIO, HubTickDriver.MANUAL)
    }
    private fun hub(player: ScenarioDemoPlayer) = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, player, player, player,
        ObservationTimeBasis.SCENARIO, complete = player.takeIf { player.transportMode == DemoTransportMode.COMPLETE_OBSERVATION })),
        player.clock, policy())

    /** 재생 중 사용하던 stamp와 같은 기대값이다. 누락한 quality 키를 새로 만들지 않는다. */
    private fun legacyStamp(sample: DemoScenarioSample): DemoScenarioSample {
        fun quality(q: SignalQuality) = q.copy(source = Source.DEMO, observedAtMs = sample.tMs,
            observedTimeBasis = ObservationTimeBasis.SCENARIO, receivedAtElapsedMs = null,
            sourceRateHz = null, sourceRateInfo = null, observationSequence = null, selectionReason = null)
        return sample.copy(vehicle = sample.vehicle.copy(tMs = sample.tMs, source = Source.DEMO,
            quality = sample.vehicle.quality.mapValues { quality(it.value) }),
            navi = sample.navi.copy(tMs = sample.tMs, quality = sample.navi.quality.mapValues { quality(it.value) }),
            power = sample.power.copy(tMs = sample.tMs, quality = quality(sample.power.quality)))
    }

    @Test fun customRepositoryMetadataIsPreparedOnceWithExactlyTheLegacyStampSemantics() = runTest {
        val base = demoTestScenario(200)
        val dirty = SignalQuality(SignalAvailability.UNSUPPORTED, Source.REAL, 77, 88, 12f,
            ObservationTimeBasis.WALL_TIME, 99, SignalSelectionReason.REAL_FRESH,
            SourceRate(RateStatus.MEASURED, ObservationTimeBasis.ELAPSED_REALTIME, 12f))
        val original = base.samples.first().copy(
            vehicle = base.samples.first().vehicle.copy(tMs = 999, source = Source.REAL, speed = 22.22f,
                speedUnit = SpeedUnit.METERS_PER_SECOND, gear = null,
                quality = mapOf(SignalField.SPEED_KPH to dirty, SignalField.STEERING_DEG to dirty.copy(availability = SignalAvailability.ERROR)),
                updatedFields = setOf(SignalField.SPEED_KPH, SignalField.STEERING_DEG)),
            navi = base.samples.first().navi.copy(tMs = 888, quality = mapOf(
                NaviField.LAT to dirty.copy(availability = SignalAvailability.AVAILABLE), NaviField.LON to dirty)),
            power = base.samples.first().power.copy(tMs = 777, state = PowerState.OFF, quality = dirty.copy(availability = SignalAvailability.STALE)),
        )
        val scenario = base.copy(samples = listOf(original, base.samples[1]))
        var nanos = 0L
        val probe = WorkProbe(nanoTime = { ++nanos })
        val real = DemoTestClock()
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false, probe = probe)
        val vehicles = mutableListOf<VehicleSignalInput>(); val navis = mutableListOf<NaviState>(); val powers = mutableListOf<PowerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { vehicles += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.states.collect { navis += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { powers += it } }
        player.execute(DemoCommand.Load(id("metadata-load"), scenario.id))
        assertEquals(1L, probe.snapshot().getValue("DEMO_PREPARE").getValue("calls").toLong())
        player.execute(DemoCommand.Start(id("metadata-start")))
        val expected = legacyStamp(original)
        assertEquals(expected.vehicle, vehicles.single()); assertEquals(expected.navi, navis.single()); assertEquals(expected.power, powers.single())
        assertEquals(original.vehicle.quality.keys, vehicles.single().quality.keys)
        assertEquals(original.navi.quality.keys, navis.single().quality.keys)
        assertEquals(SpeedUnit.METERS_PER_SECOND, vehicles.single().speedUnit)
        assertEquals(original.vehicle.updatedFields, vehicles.single().updatedFields)
        assertEquals(Source.REAL, original.vehicle.source)
        assertEquals(88L, original.vehicle.quality.getValue(SignalField.SPEED_KPH).receivedAtElapsedMs)
        real.realMs = 100; player.pump()
        assertEquals(1L, probe.snapshot().getValue("DEMO_PREPARE").getValue("calls").toLong())
        player.execute(DemoCommand.Stop(id("metadata-stop")))
    }

    @Test fun delayedStartReusesCanonicalQualityWhileOnlyTheHubInjectsTheActualReceiptTime() = runTest {
        val scenario = demoTestScenario(1_000)
        val real = DemoTestClock()
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        val originals = mutableListOf<VehicleSignalInput>(); val navis = mutableListOf<NaviState>(); val powers = mutableListOf<PowerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { originals += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.states.collect { navis += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.events.collect { powers += it } }
        player.execute(DemoCommand.Load(id("receipt-load"), scenario.id))
        real.realMs = 1_000
        val hub = hub(player); hub.start(backgroundScope)
        player.execute(DemoCommand.Start(id("receipt-start")))
        real.realMs = 1_100; assertTrue(hub.publishDue())
        val sourceQuality = originals.single().quality.getValue(SignalField.STEERING_DEG)
        assertSame(scenario.samples.first().vehicle.quality.getValue(SignalField.STEERING_DEG), sourceQuality)
        assertSame(scenario.samples.first().navi.quality.getValue(NaviField.LAT), navis.single().quality.getValue(NaviField.LAT))
        assertSame(scenario.samples.first().power.quality, powers.single().quality)
        assertSame(scenario.samples.first().power, powers.single())
        assertNull(sourceQuality.receivedAtElapsedMs); assertEquals(0L, sourceQuality.observedAtMs)
        assertEquals(1_000L, hub.latestFrame.value!!.qualityOf(SignalField.STEERING_DEG).receivedAtElapsedMs)
        assertEquals(1_000L, hub.latestNavi.value!!.qualityOf(NaviField.LAT).receivedAtElapsedMs)
        assertEquals(1_000L, hub.latestPower.value!!.quality.receivedAtElapsedMs)
        assertEquals(100L, hub.latestFrame.value!!.tMs)
        player.execute(DemoCommand.Stop(id("receipt-stop"))); hub.stop()
    }

    @Test fun reloadingTheSameRepositoryKeepsSourceTimestampsAndCreatesNewHubReceiptTimes() = runTest {
        val scenario = demoTestScenario(1_000)
        val real = DemoTestClock()
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        val originals = mutableListOf<VehicleSignalInput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { if (it.speed != null) originals += it } }
        val hub = hub(player)
        player.execute(DemoCommand.Load(id("reload-first"), scenario.id)); hub.start(backgroundScope)
        player.execute(DemoCommand.Start(id("reload-first-start")))
        real.realMs = 100; hub.publishDue()
        val previous = hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH)
        assertEquals(0L, previous.receivedAtElapsedMs)
        player.execute(DemoCommand.Stop(id("reload-first-stop"))); hub.stop()
        real.realMs = 1_000
        player.execute(DemoCommand.Load(id("reload-second"), scenario.id)); hub.start(backgroundScope)
        player.execute(DemoCommand.Start(id("reload-second-start")))
        real.realMs = 1_100; hub.publishDue()
        val current = hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH)
        assertEquals(0L, current.observedAtMs); assertEquals(1_000L, current.receivedAtElapsedMs)
        assertEquals(0L, previous.receivedAtElapsedMs)
        assertEquals(listOf(0L, 0L), originals.map { it.tMs })
        assertTrue(originals.all { it.quality.values.all { q -> q.receivedAtElapsedMs == null } })
        assertNull(scenario.samples.first().vehicle.quality.getValue(SignalField.SPEED_KPH).receivedAtElapsedMs)
        player.execute(DemoCommand.Stop(id("reload-second-stop"))); hub.stop()
    }

    @Test fun repositoryAliasesAndPublicFlowMutationCannotChangeTheFrozenPlayback() = runTest {
        val base = demoTestScenario(200)
        val vehicleQuality = base.samples.first().vehicle.quality.toMutableMap()
        val naviQuality = base.samples.first().navi.quality.toMutableMap()
        val fields = mutableSetOf(SignalField.SPEED_KPH)
        val samples = base.samples.map { sample -> sample.copy(
            vehicle = sample.vehicle.copy(quality = vehicleQuality, updatedFields = fields),
            navi = sample.navi.copy(quality = naviQuality)) }.toMutableList()
        val segments = base.speedSegments.toMutableList()
        val scenario = base.copy(samples = samples, speedSegments = segments)
        val real = DemoTestClock()
        val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false)
        val vehicles = mutableListOf<VehicleSignalInput>(); val navis = mutableListOf<NaviState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.samples.collect { vehicles += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.states.collect { navis += it } }
        player.execute(DemoCommand.Load(id("freeze-load"), scenario.id))
        vehicleQuality.clear(); naviQuality.clear(); fields.clear(); fields += SignalField.GEAR
        samples[0] = samples[0].copy(vehicle = samples[0].vehicle.copy(speed = 99f))
        samples.clear(); segments[0] = DemoSpeedSegment(0, 200, 60); segments.clear()
        player.execute(DemoCommand.Start(id("freeze-start")))
        assertEquals(40f, vehicles.single().speed!!, 0f)
        assertEquals(1, player.playbackState.value.speedMultiplier)
        assertEquals(setOf(SignalField.SPEED_KPH), vehicles.single().updatedFields)
        assertEquals(SignalField.entries.toSet(), vehicles.single().quality.keys)
        assertEquals(NaviField.entries.toSet(), navis.single().quality.keys)
        try {
            @Suppress("UNCHECKED_CAST")
            (vehicles.single().quality as MutableMap<SignalField, SignalQuality>).clear()
            fail("공개 차량 품질은 변경할 수 없어야 한다.")
        } catch (_: UnsupportedOperationException) { }
        try {
            @Suppress("UNCHECKED_CAST")
            (navis.single().quality as MutableMap<NaviField, SignalQuality>).clear()
            fail("공개 내비 품질은 변경할 수 없어야 한다.")
        } catch (_: UnsupportedOperationException) { }
        try {
            @Suppress("UNCHECKED_CAST")
            (vehicles.single().updatedFields as MutableSet<SignalField>).clear()
            fail("공개 부분 갱신 필드는 변경할 수 없어야 한다.")
        } catch (_: UnsupportedOperationException) { }
        real.realMs = 100; player.pump()
        assertEquals(listOf(0L, 100L), vehicles.map { it.tMs })
        assertEquals(100L, vehicles.last().quality.getValue(SignalField.SPEED_KPH).observedAtMs)
        assertEquals(40f, vehicles.last().speed!!, 0f)
        player.execute(DemoCommand.Stop(id("freeze-stop")))
    }

    @Test fun bothTransportsKeepNullOriginalsAndExplicitInvalidationSeparateInChannelAudits() = runTest {
        val base = demoTestScenario(200)
        val unavailable = SignalQuality(SignalAvailability.UNAVAILABLE, Source.DEMO, 100, observedTimeBasis = ObservationTimeBasis.SCENARIO)
        val nullOriginal = base.samples.last().copy(
            vehicle = VehicleSignalInput(100, Source.DEMO, quality = SignalField.entries.associateWith { unavailable }),
            navi = NaviState(100, quality = NaviField.entries.associateWith { unavailable }),
            power = PowerEvent(100, PowerState.UNKNOWN, unavailable))
        val scenario = base.copy(samples = listOf(base.samples.first(), nullOriginal))
        val frames = mutableListOf<SignalFrame>()
        val audits = mutableListOf<DemoDeliveryAuditSnapshot>()
        for (mode in DemoTransportMode.entries) {
            val real = DemoTestClock(); val audit = DemoDeliveryAudit()
            val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false,
                deliveryAudit = audit, transportMode = mode)
            val hub = hub(player)
            player.execute(DemoCommand.Load(id("null-load-$mode"), scenario.id)); hub.start(backgroundScope)
            player.execute(DemoCommand.Start(id("null-start-$mode")))
            real.realMs = 100; player.pump(); hub.publishDue()
            assertNull(hub.latestFrame.value!!.speedKph); assertNull(hub.latestNavi.value!!.lat)
            assertEquals(2L, hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleCallbacks)
            frames += hub.latestFrame.value!!
            real.realMs = 200; player.pump(); hub.publishDue()
            assertEquals(DemoPhase.COMPLETED, player.playbackState.value.phase)
            assertEquals(2L, player.playbackState.value.deliveredSamples)
            assertEquals(3L, hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleCallbacks)
            val result = audit.snapshot()
            result.channels.values.forEach { channel ->
                assertEquals(2L, channel.originalCount); assertEquals(1L, channel.invalidationCount)
                assertEquals(0L, channel.missingSamples); assertEquals(0L, channel.remainingExpectedSamples)
                assertEquals(0L, channel.duplicates); assertEquals(0L, channel.outOfOrder)
                assertEquals(result.expectedHash, channel.hash)
            }
            audits += result; hub.stop()
        }
        assertEquals(frames.first(), frames.last()); assertEquals(audits.first(), audits.last())
    }

    @Test fun aJustReceivedPreparedOriginalStillBecomesStaleWhenItsScenarioAgeExceedsTheTtl() = runTest {
        for (mode in DemoTransportMode.entries) {
            val real = DemoTestClock(); val scenario = demoTestScenario(12_000)
            val player = ScenarioDemoPlayer(DemoClock(real), demoTestRepository(scenario), backgroundScope, false, transportMode = mode)
            val hub = hub(player)
            player.execute(DemoCommand.Load(id("stale-load-$mode"), scenario.id)); hub.start(backgroundScope)
            player.execute(DemoCommand.Start(id("stale-start-$mode")))
            real.realMs = 2_117
            player.pump(maxSamples = 10)
            assertEquals(11L, player.playbackState.value.deliveredSamples)
            assertEquals(1_000L, player.playbackState.value.lastOriginalSampleMs)
            real.realMs = 2_119; assertTrue(hub.publishDue())
            val q = hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH)
            assertEquals(1_000L, q.observedAtMs); assertEquals(2_117L, q.receivedAtElapsedMs)
            assertEquals(1_119L, player.clock.scenarioTimeMs() - q.observedAtMs!!)
            assertEquals(2L, player.clock.elapsedRealtimeMs() - q.receivedAtElapsedMs!!)
            assertEquals(SignalAvailability.STALE, q.availability)
            assertNull(hub.latestFrame.value!!.speedKph); assertNull(hub.latestNavi.value!!.lat)
            assertNull(scenario.samples[10].vehicle.quality.getValue(SignalField.SPEED_KPH).receivedAtElapsedMs)
            player.execute(DemoCommand.Stop(id("stale-stop-$mode"))); hub.stop()
        }
    }
}
