package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.demo.DemoDeliveryAudit
import com.sleepissue.awakedrive.demo.DemoDeliveryChannel
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.performance.WorkProbe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 같은 원본의 전달 단위만 바꿨을 때 값·시간축·선택·수집 수명이 같아야 한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompleteSignalHubTest {
    private fun policy(): HubPolicy {
        val limits = SignalFreshnessPolicy(1_000, 1_000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
        return HubPolicy(SignalField.entries.associateWith { limits }, NaviField.entries.associateWith { limits }, limits,
            SignalFallbackPolicy(true, 0, ObservationTimeBasis.ELAPSED_REALTIME), ObservationTimeBasis.SCENARIO, HubTickDriver.MANUAL)
    }

    private fun observation(at: Long, source: Source = Source.DEMO, speed: Float? = if (source == Source.REAL) 80f else 40f): CompleteSignalObservation {
        val quality = SignalQuality(SignalAvailability.AVAILABLE, source, at,
            observedTimeBasis = if (source == Source.DEMO) ObservationTimeBasis.SCENARIO else ObservationTimeBasis.ELAPSED_REALTIME)
        return CompleteSignalObservation(
            VehicleSignalInput(at, source, steeringDeg = 1f, speed = speed, gear = Gear.D,
                turnSignal = TurnSignal.OFF, brakeOn = false, cruiseActive = false,
                quality = SignalField.entries.associateWith { quality }),
            NaviState(at, if (source == Source.REAL) 37.5 else 35.5, if (source == Source.REAL) 127.0 else 129.0,
                distToNextTurnM = 500f, quality = NaviField.entries.associateWith { quality }),
            PowerEvent(at, PowerState.ON, quality),
        )
    }

    private class CompleteSource(private val audit: DemoDeliveryAudit? = null) : CompleteSignalSource {
        private sealed interface Event {
            data class Sample(val value: CompleteSignalObservation, val invalidation: Boolean) : Event
            data class Failure(val error: Throwable) : Event
            data object Finished : Event
        }
        private val bus = MutableSharedFlow<Event>()
        var activeSubscriptions = 0
            private set
        var totalSubscriptions = 0
            private set
        override val observations: Flow<CompleteSignalObservation> = flow {
            activeSubscriptions++; totalSubscriptions++
            try {
                bus.takeWhile { it != Event.Finished }.collect { event ->
                    when (event) {
                        is Event.Sample -> {
                            audit?.observe(DemoDeliveryChannel.VEHICLE, event.value.vehicle.tMs, event.invalidation)
                            audit?.observe(DemoDeliveryChannel.NAVI, event.value.navi.tMs, event.invalidation)
                            audit?.observe(DemoDeliveryChannel.POWER, event.value.power.tMs, event.invalidation)
                            emit(event.value)
                        }
                        is Event.Failure -> throw event.error
                        Event.Finished -> error("완료 이벤트는 수집 전에 걸러진다.")
                    }
                }
            } finally { activeSubscriptions-- }
        }
        suspend fun emit(value: CompleteSignalObservation, invalidation: Boolean = false) = bus.emit(Event.Sample(value, invalidation))
        suspend fun fail(error: Throwable = IllegalStateException("시험용 완전 관측 실패")) = bus.emit(Event.Failure(error))
        suspend fun finish() = bus.emit(Event.Finished)
    }

    private inner class Rig(val scope: TestScope, val bundled: Boolean, val time: FakeClock = FakeClock(),
        probe: WorkProbe = WorkProbe.NONE, val complete: CompleteSource = CompleteSource()) {
        val vehicle = FakeSignalSource(); val navi = FakeNaviSource(); val power = FakePowerSource()
        val real = FakeSignalSource(); val realNavi = FakeNaviSource()
        val hub = VehicleSignalHub(listOf(
            HubSourceSet(Source.REAL, real, realNavi, rateTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME),
            HubSourceSet(Source.DEMO, vehicle, navi, power, ObservationTimeBasis.SCENARIO, complete = complete.takeIf { bundled }),
        ), time, policy(), probe = probe)
        suspend fun open(owner: CoroutineScope = scope.backgroundScope) { hub.start(owner); scope.runCurrent() }
        fun at(scenario: Long, elapsed: Long = scenario / 60) { time.scenarioMs = scenario; time.elapsedMs = elapsed }
        suspend fun send(value: CompleteSignalObservation) {
            if (bundled) complete.emit(value)
            else { vehicle.emit(value.vehicle); navi.emit(value.navi); power.emit(value.power) }
            scope.runCurrent()
        }
        suspend fun tick(scenario: Long, elapsed: Long = scenario / 60) {
            at(scenario, elapsed); assertTrue(hub.publishDue()); scope.runCurrent()
        }
    }

    @Test fun individualAndCompleteDeliveryProduceIdenticalValuesQualitiesRatesAndRealPriority() = runTest {
        val separate = Rig(this, false); val bundled = Rig(this, true)
        separate.open(); bundled.open()
        for (at in 0L..12_000L step 100) {
            listOf(separate, bundled).forEach { rig ->
                rig.at(at)
                if (at == 0L || at == 6_000L) {
                    val real = observation(at / 60, Source.REAL)
                    rig.real.emit(real.vehicle); rig.realNavi.emit(real.navi); runCurrent()
                }
                rig.send(observation(at))
                if (at > 0) rig.tick(at)
            }
            assertEquals(separate.hub.latestFrame.value, bundled.hub.latestFrame.value)
            assertEquals(separate.hub.latestNavi.value, bundled.hub.latestNavi.value)
            assertEquals(separate.hub.latestPower.value, bundled.hub.latestPower.value)
            assertEquals(separate.hub.diagnostics.value, bundled.hub.diagnostics.value)
        }
        val frame = bundled.hub.latestFrame.value!!
        assertEquals(Source.REAL, frame.qualityOf(SignalField.SPEED_KPH).source)
        assertEquals(80f, frame.speedKph!!, 0f)
        assertEquals(35.5, bundled.hub.latestNavi.value!!.lat!!, 0.0)
        assertEquals(SignalSelectionReason.DEMO_LOCATION, bundled.hub.latestNavi.value!!.qualityOf(NaviField.LAT).selectionReason)
        val demo = bundled.hub.diagnostics.value.inputs.getValue(Source.DEMO)
        assertEquals(121L, demo.vehicleCallbacks)
        assertEquals(10f, demo.steeringRate.hz!!, 0f)
        assertEquals(ObservationTimeBasis.SCENARIO, demo.steeringRate.timeBasis)
        assertEquals(RateStatus.WARMING_UP, bundled.hub.diagnostics.value.inputs.getValue(Source.REAL).steeringRate.status)
        assertEquals(0, bundled.vehicle.totalSubscriptions)
        assertEquals(1, bundled.complete.totalSubscriptions)
        separate.hub.stop(); bundled.hub.stop()
    }

    @Test fun completeDeliveryUsesOneStateLockAndOneVehicleCallbackForEachOriginal() = runTest {
        var nanos = 0L
        val probe = WorkProbe(nanoTime = { ++nanos })
        val rig = Rig(this, true, probe = probe); rig.open()
        val before = probe.snapshot().getValue("HUB_LOCK_WAIT").getValue("calls").toLong()
        for (at in 0L..900L step 100) { rig.at(at); rig.send(observation(at)) }
        val after = probe.snapshot()
        assertEquals(10L, after.getValue("HUB_LOCK_WAIT").getValue("calls").toLong() - before)
        assertEquals(10L, after.getValue("HUB_RECEIVE_COMPLETE").getValue("calls").toLong())
        listOf("HUB_RECEIVE_VEHICLE", "HUB_RECEIVE_NAVI", "HUB_RECEIVE_POWER").forEach { stage ->
            assertEquals(10L, after.getValue(stage).getValue("calls").toLong())
        }
        rig.tick(1_000)
        assertEquals(10L, rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleCallbacks)
        assertEquals(10L, rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).steeringRate.acceptedObservations)
        assertEquals(1L, rig.hub.diagnostics.value.emittedFrames)
        assertEquals(9L, rig.hub.diagnostics.value.skippedDeadlines)
        rig.hub.stop()
    }

    @Test fun completeSourceDoesNotReadOrSubscribeToTheThreeUnselectedFlowGetters() = runTest {
        var reads = 0
        val vehicle = object : VehicleSignalSource { override val samples: Flow<VehicleSignalInput> get() { reads++; return emptyFlow() } }
        val navi = object : NaviSignalSource { override val states: Flow<NaviState> get() { reads++; return emptyFlow() } }
        val power = object : PowerSignalSource { override val events: Flow<PowerEvent> get() { reads++; return emptyFlow() } }
        val complete = CompleteSource(); val time = FakeClock()
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, vehicle, navi, power, ObservationTimeBasis.SCENARIO,
            complete = complete)), time, policy())
        hub.start(backgroundScope); complete.emit(observation(0)); runCurrent()
        time.scenarioMs = 100; time.elapsedMs = 1; hub.publishDue()
        assertEquals(0, reads)
        assertEquals(40f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(35.5, hub.latestNavi.value!!.lat!!, 0.0)
        assertEquals(PowerState.ON, hub.latestPower.value!!.state)
        hub.stop(); assertEquals(0, complete.activeSubscriptions)
    }

    @Test fun explicitNullAndUnavailableClearEveryChannelInEitherDeliveryPath() = runTest {
        for (bundled in listOf(false, true)) {
            val rig = Rig(this, bundled); rig.open(); rig.send(observation(0)); rig.tick(100)
            val original = observation(100)
            val unavailable = SignalQuality(SignalAvailability.UNAVAILABLE, Source.DEMO, 100,
                observedTimeBasis = ObservationTimeBasis.SCENARIO)
            rig.send(CompleteSignalObservation(
                original.vehicle.copy(steeringDeg = null, speed = null, gear = null),
                original.navi.copy(lat = null, lon = null),
                original.power.copy(state = PowerState.UNKNOWN, quality = unavailable),
            ))
            rig.tick(200)
            assertNull(rig.hub.latestFrame.value!!.speedKph); assertNull(rig.hub.latestFrame.value!!.steeringDeg)
            assertNull(rig.hub.latestFrame.value!!.gear); assertNull(rig.hub.latestNavi.value!!.lat)
            assertNull(rig.hub.latestNavi.value!!.lon); assertEquals(PowerState.UNKNOWN, rig.hub.latestPower.value!!.state)
            assertEquals(2L, rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleCallbacks)
            rig.hub.stop()
        }
    }

    @Test fun sourceAndReceiptTtlBoundariesAndYoungReceiptOldObservationRemainIdentical() = runTest {
        for (bundled in listOf(false, true)) {
            val rig = Rig(this, bundled); rig.open(); rig.send(observation(0))
            rig.tick(1_000, 1_000)
            assertEquals(40f, rig.hub.latestFrame.value!!.speedKph!!, 0f)
            rig.tick(1_100, 1_001)
            assertNull(rig.hub.latestFrame.value!!.speedKph)
            assertEquals(SignalAvailability.STALE,
                rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH).availability)
            rig.hub.stop()

            val delayed = Rig(this, bundled); delayed.open(); delayed.at(2_000, 200)
            delayed.send(observation(1_000)); delayed.tick(2_119, 202)
            val q = delayed.hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH)
            assertEquals(1_000L, q.observedAtMs); assertEquals(200L, q.receivedAtElapsedMs)
            assertEquals(SignalAvailability.STALE, q.availability)
            assertNull(delayed.hub.latestFrame.value!!.speedKph)
            assertNull(delayed.hub.latestNavi.value!!.lat)
            delayed.hub.stop()
        }
    }

    @Test fun completeStopRestartAndNewSessionCannotReuseThePreviousCacheOrEpoch() = runTest {
        val rig = Rig(this, true); rig.open(); rig.send(observation(0)); rig.tick(100)
        val oldSession = rig.hub.latestFrame.value!!.publication!!.sessionId
        assertTrue(rig.hub.stopInput(Source.DEMO))
        assertEquals(0, rig.complete.activeSubscriptions)
        rig.at(200); rig.complete.emit(observation(200, speed = 99f)); rig.tick(200)
        assertNull(rig.hub.latestFrame.value!!.speedKph)
        assertEquals(RateStatus.INTERRUPTED, rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).steeringRate.status)
        assertTrue(rig.hub.startInput(Source.DEMO)); rig.tick(300)
        assertNull(rig.hub.latestFrame.value!!.speedKph)
        rig.send(observation(300, speed = 60f)); rig.tick(400)
        assertEquals(60f, rig.hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(1L, rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleCallbacks)
        rig.hub.stop(); rig.open(); assertNull(rig.hub.latestFrame.value)
        rig.tick(500); assertNull(rig.hub.latestFrame.value!!.speedKph)
        rig.send(observation(500, speed = 70f)); rig.tick(600)
        assertEquals(70f, rig.hub.latestFrame.value!!.speedKph!!, 0f)
        assertNotEquals(oldSession, rig.hub.latestFrame.value!!.publication!!.sessionId)
        assertEquals(3, rig.complete.totalSubscriptions)
        rig.hub.stop()
    }

    @Test fun completeFailureAndCompletionApplyToAllThreeChannelsAndPreserveFailureQuality() = runTest {
        for (permissionDenied in listOf(false, true)) {
            val rig = Rig(this, true); rig.open(); rig.send(observation(0)); rig.tick(100)
            rig.complete.fail(if (permissionDenied) SecurityException("시험용 권한 거부") else IllegalStateException("시험용 오류")); runCurrent()
            val state = rig.hub.diagnostics.value.inputs.getValue(Source.DEMO)
            assertEquals(HubCollectionState(InputCollectionStatus.FAILED, InputCollectionStatus.FAILED, InputCollectionStatus.FAILED), state.collection)
            assertEquals(RateStatus.INTERRUPTED, state.steeringRate.status)
            val availability = if (permissionDenied) SignalAvailability.PERMISSION_DENIED else SignalAvailability.ERROR
            assertEquals(availability, state.vehicleQuality.getValue(SignalField.SPEED_KPH).availability)
            assertEquals(availability, state.naviQuality.getValue(NaviField.LAT).availability)
            assertEquals(availability, state.powerQuality!!.availability)
            rig.tick(200); assertNull(rig.hub.latestFrame.value!!.speedKph); assertNull(rig.hub.latestNavi.value!!.lat)
            rig.hub.stop()
        }
        val completed = Rig(this, true); completed.open(); completed.send(observation(0)); completed.tick(100)
        completed.complete.finish(); runCurrent(); completed.tick(200)
        val state = completed.hub.diagnostics.value.inputs.getValue(Source.DEMO)
        assertEquals(HubCollectionState(InputCollectionStatus.COMPLETED, InputCollectionStatus.COMPLETED, InputCollectionStatus.COMPLETED), state.collection)
        assertNull(completed.hub.latestFrame.value!!.speedKph)
        assertNull(completed.hub.latestNavi.value!!.lat)
        completed.hub.stop()
    }

    @Test fun completeFlowGetterFailureAndOwnerCancellationNeverLeaveCollectingChannels() = runTest {
        val source = object : CompleteSignalSource { override val observations: Flow<CompleteSignalObservation> get() = throw SecurityException("시험용 getter 거부") }
        val time = FakeClock()
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, rateTimeBasis = ObservationTimeBasis.SCENARIO, complete = source)), time, policy())
        hub.start(backgroundScope)
        val failed = hub.diagnostics.value.inputs.getValue(Source.DEMO)
        assertEquals(HubCollectionState(InputCollectionStatus.FAILED, InputCollectionStatus.FAILED, InputCollectionStatus.FAILED), failed.collection)
        assertEquals(SignalAvailability.PERMISSION_DENIED, failed.powerQuality!!.availability)
        hub.stop()

        val rig = Rig(this, true); val parent = Job(backgroundScope.coroutineContext[Job])
        rig.open(CoroutineScope(backgroundScope.coroutineContext + parent)); rig.send(observation(0)); rig.tick(100)
        parent.cancelAndJoin()
        assertEquals(0, rig.complete.activeSubscriptions)
        assertNull(rig.hub.latestFrame.value); assertFalse(rig.hub.publishDue())
        assertEquals(HubCollectionState(InputCollectionStatus.STOPPED, InputCollectionStatus.STOPPED, InputCollectionStatus.STOPPED),
            rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).collection)
        rig.hub.stop()
    }

    @Test fun everyOriginalAndTheSeparateInvalidationMatchChannelAuditsAndVehicleCallbacks() = runTest {
        val expected = (0 until 1_200).map { it * 100L }
        val audit = DemoDeliveryAudit().apply { reset(expected) }
        val rig = Rig(this, true, complete = CompleteSource(audit)); rig.open()
        expected.forEach { at -> rig.at(at); rig.send(observation(at)) }
        rig.tick(120_000)
        val original = observation(120_000)
        val unavailable = SignalQuality(SignalAvailability.UNAVAILABLE, Source.DEMO, 120_000,
            observedTimeBasis = ObservationTimeBasis.SCENARIO)
        rig.complete.emit(CompleteSignalObservation(
            VehicleSignalInput(120_000, Source.DEMO, quality = SignalField.entries.associateWith { unavailable }),
            NaviState(120_000, quality = NaviField.entries.associateWith { unavailable }),
            PowerEvent(120_000, PowerState.UNKNOWN, unavailable),
        ), invalidation = true)
        runCurrent(); rig.tick(120_100)
        val received = audit.snapshot()
        received.channels.values.forEach { channel ->
            assertEquals(1_200L, channel.originalCount); assertEquals(1_200L, channel.uniqueExpectedCount)
            assertEquals(0L, channel.missingSamples); assertEquals(0L, channel.remainingExpectedSamples)
            assertEquals(0L, channel.duplicates); assertEquals(0L, channel.outOfOrder)
            assertEquals(0L, channel.unknownTimestamps); assertEquals(1L, channel.invalidationCount)
            assertEquals(received.expectedHash, channel.hash)
        }
        assertEquals(1_201L, rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleCallbacks)
        assertEquals(1_200L, rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).steeringRate.acceptedObservations)
        assertEquals(1L, rig.hub.diagnostics.value.emittedFrames - 1)
        assertNull(rig.hub.latestFrame.value!!.speedKph)
        assertEquals(original.vehicle.tMs, rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH).observedAtMs)
        rig.hub.stop()
    }

    @Test fun delayedPublicationReportsTheOriginalScenarioDeadlineWithoutInventingPastFrames() = runTest {
        val rig = Rig(this, true)
        val frames = mutableListOf<SignalFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.hub.samples.collect { frames += it } }
        assertNull(rig.hub.diagnostics.value.nextPublicationAtMs)
        assertEquals(ObservationTimeBasis.UNKNOWN, rig.hub.diagnostics.value.publicationTimeBasis)
        rig.open(); rig.send(observation(0))
        assertEquals(100L, rig.hub.diagnostics.value.nextPublicationAtMs)
        assertEquals(ObservationTimeBasis.SCENARIO, rig.hub.diagnostics.value.publicationTimeBasis)
        rig.tick(305)
        assertEquals(listOf(305L), frames.map { it.tMs })
        assertEquals(1L, rig.hub.diagnostics.value.emittedFrames)
        assertEquals(2L, rig.hub.diagnostics.value.skippedDeadlines)
        assertEquals(400L, rig.hub.diagnostics.value.nextPublicationAtMs)
        rig.at(399); assertFalse(rig.hub.publishDue())
        assertEquals(400L, rig.hub.diagnostics.value.nextPublicationAtMs)
        rig.tick(400)
        assertEquals(listOf(305L, 400L), frames.map { it.tMs })
        assertEquals(listOf(1L, 2L), frames.map { it.publication!!.sequence })
        assertEquals(500L, rig.hub.diagnostics.value.nextPublicationAtMs)
        rig.hub.stop()
        assertFalse(rig.hub.diagnostics.value.running)
        assertNull(rig.hub.diagnostics.value.nextPublicationAtMs)
        assertEquals(ObservationTimeBasis.SCENARIO, rig.hub.diagnostics.value.publicationTimeBasis)
    }

    @Test fun elapsedDeadlineRemainsIndependentOfFrameTimeAndIsInvalidOnlyWhenTheHubStopsOrItsClockFails() = runTest {
        val time = FakeClock(scenarioMs = 7_000, elapsedMs = 5_000)
        val complete = CompleteSource()
        val rules = policy().copy(publicationTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME)
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, rateTimeBasis = ObservationTimeBasis.SCENARIO,
            complete = complete)), time, rules)
        hub.start(backgroundScope); complete.emit(observation(7_000)); runCurrent()
        assertEquals(5_100L, hub.diagnostics.value.nextPublicationAtMs)
        assertEquals(ObservationTimeBasis.ELAPSED_REALTIME, hub.diagnostics.value.publicationTimeBasis)
        time.elapsedMs = 5_305; assertTrue(hub.publishDue())
        assertEquals(7_000L, hub.latestFrame.value!!.tMs)
        assertEquals(5_305L, hub.latestFrame.value!!.publication!!.publishedAtElapsedMs)
        assertEquals(5_400L, hub.diagnostics.value.nextPublicationAtMs)
        assertEquals(2L, hub.diagnostics.value.skippedDeadlines)
        hub.stopInput(Source.DEMO)
        assertTrue(hub.diagnostics.value.running)
        assertEquals(5_400L, hub.diagnostics.value.nextPublicationAtMs)
        time.elapsedMs = 5_400; assertTrue(hub.publishDue())
        assertNull(hub.latestFrame.value!!.speedKph)
        assertEquals(5_500L, hub.diagnostics.value.nextPublicationAtMs)
        hub.stop()
        assertNull(hub.diagnostics.value.nextPublicationAtMs)
        assertEquals(ObservationTimeBasis.ELAPSED_REALTIME, hub.diagnostics.value.publicationTimeBasis)
        time.elapsedMs = 6_000; hub.start(backgroundScope)
        assertEquals(6_100L, hub.diagnostics.value.nextPublicationAtMs)
        time.elapsedMs = 5_999; assertFalse(hub.publishDue())
        assertTrue(hub.diagnostics.value.clockFailure)
        assertNull(hub.diagnostics.value.nextPublicationAtMs)
        assertEquals(ObservationTimeBasis.ELAPSED_REALTIME, hub.diagnostics.value.publicationTimeBasis)
        hub.stop()
    }

}
