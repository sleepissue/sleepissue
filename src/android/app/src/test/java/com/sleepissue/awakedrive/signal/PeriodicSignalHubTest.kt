package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.performance.WorkProbe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeriodicSignalHubTest {
    private fun policy(driver: HubTickDriver = HubTickDriver.MANUAL, ttl: Long = 1_000, allowDemo: Boolean = true) =
        SignalFreshnessPolicy(ttl, ttl, MissingObservationPolicy.REQUIRE_TIMESTAMP).let { limits -> HubPolicy(
            SignalField.entries.associateWith { limits }, NaviField.entries.associateWith { limits }, limits,
            SignalFallbackPolicy(allowDemo, 0, ObservationTimeBasis.SCENARIO), ObservationTimeBasis.SCENARIO, driver) }
    private fun q(source: Source, at: Long, basis: ObservationTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME,
        availability: SignalAvailability = SignalAvailability.AVAILABLE) =
        SignalQuality(availability, source, at, observedTimeBasis = basis)
    private fun input(source: Source, at: Long, speed: Float? = 80f, steering: Float? = 1f,
        basis: ObservationTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME) = VehicleSignalInput(
        at, source, steeringDeg = steering, speed = speed, gear = Gear.D,
        quality = SignalField.entries.associateWith { q(source, at, basis) })
    private inner class Rig(val scope: TestScope, val rules: HubPolicy = policy()) {
        val time = FakeClock()
        val real = FakeSignalSource(); val demo = FakeSignalSource()
        val realNavi = FakeNaviSource(); val demoNavi = FakeNaviSource()
        val realPower = FakePowerSource()
        val hub = VehicleSignalHub(listOf(
            HubSourceSet(Source.REAL, real, realNavi, realPower, ObservationTimeBasis.ELAPSED_REALTIME),
            HubSourceSet(Source.DEMO, demo, demoNavi, rateTimeBasis = ObservationTimeBasis.SCENARIO)), time, rules)
        suspend fun open() { hub.start(scope.backgroundScope); scope.runCurrent() }
        fun at(ms: Long) { time.scenarioMs = ms; time.elapsedMs = ms }
        suspend fun send(source: Source, ms: Long, speed: Float = if (source == Source.REAL) 80f else 40f) {
            at(ms)
            val value = input(source, ms, speed, basis = if (source == Source.REAL) ObservationTimeBasis.ELAPSED_REALTIME else ObservationTimeBasis.SCENARIO)
            (if (source == Source.REAL) real else demo).emit(value); scope.runCurrent()
        }
        suspend fun tick(ms: Long): SignalFrame { at(ms); assertTrue(hub.publishDue()); scope.runCurrent(); return hub.latestFrame.value!! }
        suspend fun navi(source: Source, lat: Double?, lon: Double?) {
            val basis = if (source == Source.REAL) ObservationTimeBasis.ELAPSED_REALTIME else ObservationTimeBasis.SCENARIO
            (if (source == Source.REAL) realNavi else demoNavi).emit(NaviState(time.scenarioMs, lat, lon,
                distToNextTurnM = if (source == Source.REAL) 500f else 800f,
                quality = NaviField.entries.associateWith { q(source, time.scenarioMs, basis) })); scope.runCurrent()
        }
    }

    @Test fun sixtySecondsOfTenHzFramesDoNotInventMoreThanTwoHzSourceObservations() = runTest {
        val rig = Rig(this); val frames = mutableListOf<SignalFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.hub.samples.collect { frames += it } }
        rig.open(); rig.send(Source.REAL, 0)
        for (at in 100L..60_000 step 100) {
            if (at % 500L == 0L) rig.send(Source.REAL, at)
            rig.tick(at)
        }
        assertEquals((100L..60_000 step 100).toList(), frames.map { it.tMs })
        assertEquals((1L..600).toList(), frames.map { it.publication!!.sequence })
        val diagnostics = rig.hub.diagnostics.value
        assertEquals(121L, diagnostics.inputs.getValue(Source.REAL).vehicleCallbacks)
        assertEquals(121L, diagnostics.inputs.getValue(Source.REAL).steeringRate.acceptedObservations)
        assertEquals(2f, diagnostics.inputs.getValue(Source.REAL).steeringRate.hz!!, 0f)
        assertEquals(10f, diagnostics.publicationRate.hz!!, 0f)
        assertEquals(diagnostics.inputs.getValue(Source.REAL).steeringRate,
            frames.last().qualityOf(SignalField.STEERING_DEG).sourceRateInfo)
        assertEquals(true, frames.last().qualityOf(SignalField.STEERING_DEG).sourceRateInfo!!.belowMinimum)
        assertEquals(0L, diagnostics.skippedDeadlines)
        rig.hub.stop()
    }

    @Test fun automaticDriverUsesInjectedClockAndPublishesAtOneHundredMilliseconds() = runTest {
        val source = FakeSignalSource()
        val clock = object : Clock {
            override fun scenarioTimeMs() = testScheduler.currentTime
            override fun elapsedRealtimeMs() = testScheduler.currentTime
            override fun wallTimeMs() = 0L
        }
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, source, rateTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME)), clock,
            policy(HubTickDriver.AUTOMATIC))
        val seen = mutableListOf<Long>()
        val navigation = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.samples.collect { seen += it.tMs } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.naviSamples.collect { navigation += it.tMs } }
        hub.start(backgroundScope); source.emit(input(Source.REAL, 0)); runCurrent()
        advanceTimeBy(99); runCurrent(); assertTrue(seen.isEmpty())
        advanceTimeBy(901); runCurrent()
        assertEquals((100L..1_000 step 100).toList(), seen)
        assertEquals(listOf(1_000L), navigation)
        hub.stop(); advanceTimeBy(1_000); runCurrent()
        assertEquals(10, seen.size)
        assertEquals(RateStatus.INTERRUPTED, hub.diagnostics.value.publicationRate.status)
    }

    @Test fun replayDoesNotRefreshSourceOrReceiptTimeAndDuplicateCallbacksDoNotExtendFreshness() = runTest {
        val rig = Rig(this, policy(allowDemo = false)); rig.open(); rig.send(Source.REAL, 0)
        val first = rig.tick(100).qualityOf(SignalField.STEERING_DEG)
        rig.at(500); rig.real.emit(input(Source.REAL, 0)); runCurrent()
        val repeated = rig.tick(1_000).qualityOf(SignalField.STEERING_DEG)
        assertEquals(first.observedAtMs, repeated.observedAtMs)
        assertEquals(first.receivedAtElapsedMs, repeated.receivedAtElapsedMs)
        assertEquals(first.observationSequence, repeated.observationSequence)
        val stale = rig.tick(1_100)
        assertNull(stale.steeringDeg)
        assertEquals(SignalAvailability.STALE, stale.qualityOf(SignalField.STEERING_DEG).availability)
        assertEquals(1L, rig.hub.diagnostics.value.inputs.getValue(Source.REAL).steeringRate.duplicateObservations)
        rig.hub.stop()
    }

    @Test fun realIsPreferredThenExpiryAndExplicitDisconnectFallBackToFreshDemo() = runTest {
        val rig = Rig(this); rig.open(); rig.send(Source.REAL, 0); rig.send(Source.DEMO, 0)
        assertEquals(80f, rig.tick(100).speedKph!!, 0f)
        rig.send(Source.DEMO, 1_000)
        val fallback = rig.tick(1_100)
        assertEquals(40f, fallback.speedKph!!, 0f)
        assertEquals(SignalSelectionReason.DEMO_FALLBACK, fallback.qualityOf(SignalField.SPEED_KPH).selectionReason)
        rig.send(Source.REAL, 1_200); assertEquals(80f, rig.tick(1_300).speedKph!!, 0f)
        rig.hub.stopInput(Source.REAL)
        assertEquals(40f, rig.tick(1_400).speedKph!!, 0f)
        rig.hub.stopInput(Source.DEMO)
        assertNull(rig.tick(1_500).speedKph)
        assertEquals(RateStatus.INTERRUPTED, rig.hub.diagnostics.value.inputs.getValue(Source.REAL).steeringRate.status)
        rig.hub.startInput(Source.REAL)
        assertNull(rig.tick(1_600).speedKph)
        rig.send(Source.REAL, 1_700, 90f)
        assertEquals(90f, rig.tick(1_800).speedKph!!, 0f)
        rig.hub.stop()
    }

    @Test fun partialUpdatesRetainOnlyUntouchedFieldsAndCompleteNullClearsPreviousValue() = runTest {
        val rig = Rig(this); rig.open(); rig.send(Source.REAL, 0)
        rig.at(100)
        rig.real.emit(VehicleSignalInput(100, Source.REAL, gear = null, updatedFields = setOf(SignalField.GEAR),
            quality = mapOf(SignalField.GEAR to q(Source.REAL, 100))))
        runCurrent()
        val patched = rig.tick(200)
        assertEquals(80f, patched.speedKph!!, 0f); assertNull(patched.gear)
        assertEquals(0L, patched.qualityOf(SignalField.SPEED_KPH).receivedAtElapsedMs)
        rig.at(300); rig.real.emit(VehicleSignalInput(300, Source.REAL, steeringDeg = 1f)); runCurrent()
        assertNull(rig.tick(400).speedKph)
        rig.hub.stop()
    }

    @Test fun demoLocationIsAtomicAndIndependentOfRealVehicleAndRealNaviFields() = runTest {
        val rig = Rig(this); rig.open(); rig.send(Source.REAL, 0); rig.send(Source.DEMO, 0)
        rig.navi(Source.REAL, 37.0, 127.0); rig.navi(Source.DEMO, 35.0, 129.0)
        val frame = rig.tick(100); val navi = rig.hub.latestNavi.value!!
        assertEquals(Source.REAL, frame.qualityOf(SignalField.SPEED_KPH).source)
        assertEquals(35.0, navi.lat!!, 0.0); assertEquals(129.0, navi.lon!!, 0.0)
        assertEquals(Source.DEMO, navi.qualityOf(NaviField.LAT).source)
        assertEquals(SignalSelectionReason.DEMO_LOCATION, navi.qualityOf(NaviField.LON).selectionReason)
        assertEquals(Source.REAL, navi.qualityOf(NaviField.DIST_TO_NEXT_TURN_M).source)
        rig.at(200); rig.navi(Source.DEMO, 36.0, null); rig.tick(300)
        assertEquals(37.0, rig.hub.latestNavi.value!!.lat!!, 0.0)
        assertEquals(127.0, rig.hub.latestNavi.value!!.lon!!, 0.0)
        rig.hub.stopInput(Source.REAL); rig.tick(400)
        assertNull(rig.hub.latestNavi.value!!.lat); assertNull(rig.hub.latestNavi.value!!.lon)
        rig.hub.stop()
    }

    @Test fun unsupportedPermissionDeniedAndErrorStayNullWhileRealZeroAndFalseSurvive() = runTest {
        val rig = Rig(this, policy(allowDemo = false)); rig.open(); rig.at(0)
        rig.real.emit(input(Source.REAL, 0, speed = 0f).copy(brakeOn = false, cruiseActive = true, ventilationLevel = 2,
            quality = SignalField.entries.associateWith { field -> q(Source.REAL, 0, availability = when (field) {
                SignalField.STEERING_DEG -> SignalAvailability.UNSUPPORTED
                SignalField.CRUISE_ACTIVE -> SignalAvailability.PERMISSION_DENIED
                SignalField.VENTILATION_LEVEL -> SignalAvailability.ERROR
                else -> SignalAvailability.AVAILABLE
            }) }))
        runCurrent(); val f = rig.tick(100)
        assertEquals(0f, f.speedKph!!, 0f); assertEquals(false, f.brakeOn)
        assertNull(f.steeringDeg); assertNull(f.cruiseActive); assertNull(f.ventilationLevel)
        assertEquals(SignalAvailability.UNSUPPORTED, f.qualityOf(SignalField.STEERING_DEG).availability)
        assertEquals(SignalAvailability.PERMISSION_DENIED, f.qualityOf(SignalField.CRUISE_ACTIVE).availability)
        assertEquals(RateStatus.UNMEASURABLE, rig.hub.diagnostics.value.inputs.getValue(Source.REAL).steeringRate.status)
        rig.hub.stop()
    }

    @Test fun slowConsumerDoesNotThrottleSourceOrPublisherAndOldSessionBufferCannotLeak() = runTest {
        val rig = Rig(this); val seen = mutableListOf<FramePublication>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.hub.samples.collect { seen += it.publication!!; delay(1_000) }
        }
        rig.open()
        for (at in 100L..1_000 step 100) { rig.send(Source.REAL, at); rig.tick(at) }
        assertEquals(10L, rig.hub.diagnostics.value.emittedFrames)
        assertEquals(10L, rig.hub.diagnostics.value.inputs.getValue(Source.REAL).vehicleCallbacks)
        assertEquals(listOf(1L), seen.map { it.sequence })
        val oldSession = seen.single().sessionId
        rig.hub.stop(); rig.hub.start(backgroundScope)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(1, seen.size)
        assertNull(rig.hub.latestFrame.value)
        rig.tick(1_100)
        assertEquals(2, seen.size)
        assertNotEquals(oldSession, seen.last().sessionId)
        assertNull(rig.hub.latestFrame.value!!.speedKph)
        rig.hub.stop()
    }

    @Test fun delayedTickRecordsSkippedDeadlinesWithoutFabricatingOldFramesAndReversalStopsPublishing() = runTest {
        val rig = Rig(this); rig.open(); rig.send(Source.REAL, 0)
        assertEquals(1L, rig.tick(1_000).publication!!.sequence)
        assertEquals(9L, rig.hub.diagnostics.value.skippedDeadlines)
        assertFalse(rig.hub.publishDue())
        rig.at(999); assertFalse(rig.hub.publishDue())
        assertTrue(rig.hub.diagnostics.value.clockFailure)
        rig.at(1_100); assertFalse(rig.hub.publishDue())
        rig.hub.stop()
    }

    @Test fun collectionCompletionAndGetterFailureRemainUnavailableWhileOtherChannelsContinue() = runTest {
        val real = object : VehicleSignalSource { override val samples: Flow<VehicleSignalInput> get() = throw SecurityException() }
        val demo = object : VehicleSignalSource { override val samples = emptyFlow<VehicleSignalInput>() }
        val navi = FakeNaviSource(); val clock = FakeClock()
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, real, navi, rateTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME),
            HubSourceSet(Source.DEMO, demo, rateTimeBasis = ObservationTimeBasis.SCENARIO)), clock, policy())
        hub.start(backgroundScope)
        assertEquals(InputCollectionStatus.FAILED, hub.diagnostics.value.inputs.getValue(Source.REAL).collection.vehicle)
        assertEquals(InputCollectionStatus.COMPLETED, hub.diagnostics.value.inputs.getValue(Source.DEMO).collection.vehicle)
        navi.emit(NaviState(0, 37.0, 127.0, quality = NaviField.entries.associateWith { q(Source.REAL, 0) })); runCurrent()
        clock.scenarioMs = 100; clock.elapsedMs = 100; hub.publishDue()
        assertNull(hub.latestFrame.value!!.steeringDeg)
        assertEquals(SignalAvailability.PERMISSION_DENIED, hub.latestFrame.value!!.qualityOf(SignalField.STEERING_DEG).availability)
        assertEquals(37.0, hub.latestNavi.value!!.lat!!, 0.0)
        hub.stop()
    }

    @Test fun parentCancellationStopsAllSourcesAndClearsPublishedValues() = runTest {
        val rig = Rig(this); val parent = Job(backgroundScope.coroutineContext[Job])
        rig.hub.start(CoroutineScope(backgroundScope.coroutineContext + parent))
        rig.send(Source.REAL, 0); rig.tick(100)
        parent.cancelAndJoin()
        assertEquals(0, rig.real.activeSubscriptions)
        assertNull(rig.hub.latestFrame.value)
        assertFalse(rig.hub.publishDue())
        assertEquals(InputCollectionStatus.STOPPED, rig.hub.diagnostics.value.inputs.getValue(Source.REAL).collection.vehicle)
        assertEquals(RateStatus.INTERRUPTED, rig.hub.diagnostics.value.inputs.getValue(Source.REAL).steeringRate.status)
        assertEquals(RateStatus.INTERRUPTED, rig.hub.diagnostics.value.publicationRate.status)
        rig.hub.stop()
    }

    @Test fun externalUnitsAreConvertedOnceAtTheAppBoundary() {
        assertEquals(79.992f, UnitConverter.speedKph(22.22f, SpeedUnit.METERS_PER_SECOND), 0.001f)
        assertEquals(80f, UnitConverter.speedKph(80f, SpeedUnit.KILOMETERS_PER_HOUR), 0f)
        assertEquals(40_000f, UnitConverter.distanceMeters(40f, DistanceUnit.KILOMETERS), 0f)
        assertEquals(40_000f, UnitConverter.distanceMeters(40_000f, DistanceUnit.METERS), 0f)
    }

    @Test fun acceleratedScenarioDoesNotMakeRealSensorWarmupOrHzAdvance() = runTest {
        val rig = Rig(this, policy(ttl = 20_000)); rig.open(); rig.send(Source.REAL, 0)
        for (scenario in 0L..10_000 step 100) {
            rig.time.scenarioMs = scenario; rig.time.elapsedMs = scenario / 60
            rig.demo.emit(input(Source.DEMO, scenario, basis = ObservationTimeBasis.SCENARIO)); runCurrent()
            if (scenario > 0) rig.hub.publishDue()
        }
        val inputs = rig.hub.diagnostics.value.inputs
        assertEquals(10f, inputs.getValue(Source.DEMO).steeringRate.hz!!, 0f)
        assertEquals(RateStatus.WARMING_UP, inputs.getValue(Source.REAL).steeringRate.status)
        assertNull(inputs.getValue(Source.REAL).steeringRate.hz)
        assertEquals(1L, inputs.getValue(Source.REAL).steeringRate.acceptedObservations)
        rig.hub.stop()
    }

    @Test fun mismatchedCoordinateObservationTimesCannotCreateAMixedPair() = runTest {
        val rig = Rig(this); rig.open(); rig.navi(Source.REAL, 37.0, 127.0)
        rig.at(100)
        rig.demoNavi.emit(NaviState(100, 35.0, 129.0, quality = mapOf(
            NaviField.LAT to q(Source.DEMO, 100, ObservationTimeBasis.SCENARIO),
            NaviField.LON to q(Source.DEMO, 0, ObservationTimeBasis.SCENARIO))))
        runCurrent(); rig.tick(200)
        val navi = rig.hub.latestNavi.value!!
        assertEquals(37.0, navi.lat!!, 0.0); assertEquals(127.0, navi.lon!!, 0.0)
        assertEquals(SignalSelectionReason.REAL_LOCATION, navi.qualityOf(NaviField.LAT).selectionReason)
        rig.hub.stop()
    }

    @Test fun realSubscriptionFailureSelectsDemoAndKeepsFailureQualityForDiagnostics() = runTest {
        val rig = Rig(this); rig.open(); rig.send(Source.REAL, 0); rig.send(Source.DEMO, 0)
        rig.tick(100); rig.real.fail(); runCurrent()
        val f = rig.tick(200)
        assertEquals(40f, f.speedKph!!, 0f)
        val failed = rig.hub.diagnostics.value.inputs.getValue(Source.REAL)
        assertEquals(InputCollectionStatus.FAILED, failed.collection.vehicle)
        assertEquals(SignalAvailability.ERROR, failed.vehicleQuality.getValue(SignalField.SPEED_KPH).availability)
        assertEquals(InputCollectionStatus.COLLECTING, failed.collection.navi)
        rig.hub.stop()
    }

    @Test fun elapsedPublicationClockCanAdvanceWhileScenarioRemainsFrozen() = runTest {
        val time = FakeClock(); val real = FakeSignalSource()
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, real, rateTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME)),
            time, policy().copy(publicationTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME,
                fallback = SignalFallbackPolicy(true, 0, ObservationTimeBasis.ELAPSED_REALTIME)))
        hub.start(backgroundScope); real.emit(input(Source.REAL, 0)); runCurrent()
        time.elapsedMs = 100; assertTrue(hub.publishDue())
        assertEquals(0L, hub.latestFrame.value!!.tMs)
        assertEquals(100L, hub.latestFrame.value!!.publication!!.publishedAtElapsedMs)
        assertEquals(0L, hub.latestFrame.value!!.qualityOf(SignalField.SPEED_KPH).receivedAtElapsedMs)
        hub.stop()
    }

    @Test fun aBurstOfSourceCallbacksKeepsAllCountsAndPublishesDiagnosticsOnlyAtTheFrameBoundary() = runTest {
        val time = FakeClock(); val source = FakeSignalSource()
        var nanos = 0L
        val probe = WorkProbe(nanoTime = { ++nanos })
        val hub = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, source, rateTimeBasis = ObservationTimeBasis.SCENARIO)),
            time, policy(), probe = probe)
        hub.start(backgroundScope)
        val diagnosticsBefore = probe.snapshot().getValue("HUB_DIAGNOSTICS").getValue("calls").toLong()
        for (at in 0L until 60_000L step 100) {
            time.scenarioMs = at; time.elapsedMs = at / 60
            source.emit(input(Source.DEMO, at, 40f, basis = ObservationTimeBasis.SCENARIO)); runCurrent()
        }
        assertEquals(diagnosticsBefore, probe.snapshot().getValue("HUB_DIAGNOSTICS").getValue("calls").toLong())
        time.scenarioMs = 60_000; time.elapsedMs = 1_000
        assertTrue(hub.publishDue())
        val diagnostics = hub.diagnostics.value
        assertEquals(600L, diagnostics.inputs.getValue(Source.DEMO).vehicleCallbacks)
        assertEquals(600L, diagnostics.inputs.getValue(Source.DEMO).steeringRate.acceptedObservations)
        assertEquals(9.9f, diagnostics.inputs.getValue(Source.DEMO).steeringRate.hz!!, 0.0001f)
        assertEquals(1L, diagnostics.emittedFrames)
        assertEquals(599L, diagnostics.skippedDeadlines)
        assertEquals(40f, hub.latestFrame.value!!.speedKph!!, 0f)
        assertEquals(diagnostics.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.STEERING_DEG),
            hub.latestFrame.value!!.qualityOf(SignalField.STEERING_DEG).copy(selectionReason = null))
        hub.stop()
    }

    @Test fun youngReceiptDoesNotMakeAnOldScenarioObservationFresh() = runTest {
        val rig = Rig(this); rig.open()
        rig.time.scenarioMs = 2_000; rig.time.elapsedMs = 200
        rig.demo.emit(input(Source.DEMO, 1_000, 40f, basis = ObservationTimeBasis.SCENARIO)); runCurrent()
        rig.time.scenarioMs = 2_119; rig.time.elapsedMs = 202
        assertTrue(rig.hub.publishDue())
        val quality = rig.hub.diagnostics.value.inputs.getValue(Source.DEMO).vehicleQuality.getValue(SignalField.SPEED_KPH)
        assertEquals(1_000L, quality.observedAtMs)
        assertEquals(200L, quality.receivedAtElapsedMs)
        assertEquals(SignalAvailability.STALE, quality.availability)
        assertNull(rig.hub.latestFrame.value!!.speedKph)
        rig.hub.stop()
    }

    @Test fun activeRawObservationSubscriberKeepsPartialFieldsAndDuplicateReceiptIdentity() = runTest {
        val rig = Rig(this)
        val received = mutableListOf<VehicleObservation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.hub.rawVehicleObservations.collect { received += it } }
        rig.open(); rig.send(Source.DEMO, 0)
        rig.time.scenarioMs = 100; rig.time.elapsedMs = 100
        rig.demo.emit(input(Source.DEMO, 0, 40f, basis = ObservationTimeBasis.SCENARIO).copy(
            updatedFields = setOf(SignalField.SPEED_KPH))); runCurrent()
        assertEquals(2, received.size)
        assertEquals(SignalField.entries.toSet(), received.first().updatedFields)
        assertEquals(setOf(SignalField.SPEED_KPH), received.last().updatedFields)
        assertEquals(setOf(SignalField.SPEED_KPH), received.last().frame.quality.keys)
        assertEquals(received.first().frame.qualityOf(SignalField.SPEED_KPH), received.last().frame.qualityOf(SignalField.SPEED_KPH))
        rig.hub.stop()
    }

}
