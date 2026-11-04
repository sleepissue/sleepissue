package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.RateStatus
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugHubProbeControllerTest {
    @Test fun defaultFakeInputsExposeTwoHzSourceAndTenHzPublicationAndDemoCoordinates() = runTest {
        val probe = DebugHubProbeController(backgroundScope); probe.initialize()
        probe.setInputEnabled(Source.REAL, true); probe.setInputEnabled(Source.DEMO, true)
        probe.advance(10_000)
        val s = probe.state.value
        assertEquals(100L, s.diagnostics.emittedFrames)
        assertEquals(10f, s.diagnostics.publicationRate.hz!!, 0f)
        assertEquals(21L, s.diagnostics.inputs.getValue(Source.REAL).vehicleCallbacks)
        assertEquals(2f, s.diagnostics.inputs.getValue(Source.REAL).steeringRate.hz!!, 0f)
        assertEquals(true, s.diagnostics.inputs.getValue(Source.REAL).steeringRate.belowMinimum)
        assertEquals(80f, s.frame!!.speedKph!!, 0f)
        assertEquals(Source.REAL, s.frame.qualityOf(SignalField.SPEED_KPH).source)
        assertEquals(35.5, s.navi!!.lat!!, 0f.toDouble())
        assertEquals(Source.DEMO, s.navi.qualityOf(NaviField.LAT).source)
        probe.close()
    }

    @Test fun freezeExpiryFallbackAndDisconnectedSourcesHaveDifferentStates() = runTest {
        val probe = DebugHubProbeController(backgroundScope); probe.initialize()
        probe.setInputEnabled(Source.REAL, true); probe.setInputEnabled(Source.DEMO, true)
        probe.advance(10_000); probe.pauseRealObservations(); probe.advance(1_100)
        val s = probe.state.value
        assertEquals(SignalAvailability.STALE, s.diagnostics.inputs.getValue(Source.REAL).vehicleQuality.getValue(SignalField.SPEED_KPH).availability)
        assertEquals(40f, s.frame!!.speedKph!!, 0f)
        assertEquals(SignalSelectionReason.DEMO_FALLBACK, s.frame.qualityOf(SignalField.SPEED_KPH).selectionReason)
        probe.advance(10_000)
        assertEquals(0f, probe.state.value.diagnostics.inputs.getValue(Source.REAL).steeringRate.hz!!, 0f)
        probe.setInputEnabled(Source.REAL, false); probe.setInputEnabled(Source.DEMO, false); probe.advance(100)
        assertNull(probe.state.value.frame!!.speedKph)
        assertEquals(RateStatus.INTERRUPTED, probe.state.value.diagnostics.inputs.getValue(Source.REAL).steeringRate.status)
        probe.close()
    }

    @Test fun fiveHzBoundaryNullAvailabilityAndNewSessionResetAreDirectlyOperable() = runTest {
        val probe = DebugHubProbeController(backgroundScope); probe.initialize()
        probe.setRealRate(5); probe.setInputEnabled(Source.REAL, true); probe.advance(10_000)
        assertEquals(5f, probe.state.value.diagnostics.inputs.getValue(Source.REAL).steeringRate.hz!!, 0f)
        assertEquals(false, probe.state.value.diagnostics.inputs.getValue(Source.REAL).steeringRate.belowMinimum)
        probe.setValues(Source.REAL, 15f, 95f); probe.advance(1_000)
        assertEquals(15f, probe.state.value.frame!!.steeringDeg!!, 0f)
        probe.setRealAvailability(SignalAvailability.PERMISSION_DENIED); probe.advance(100)
        assertNull(probe.state.value.frame!!.steeringDeg)
        assertEquals(SignalAvailability.PERMISSION_DENIED, probe.state.value.frame!!.qualityOf(SignalField.STEERING_DEG).availability)
        val session = probe.state.value.diagnostics.sessionId
        probe.stopHub(); val count = probe.state.value.diagnostics.emittedFrames; probe.advance(1_000)
        assertEquals(count, probe.state.value.diagnostics.emittedFrames)
        assertNull(probe.state.value.frame)
        probe.restartHub(); probe.advance(100)
        assertNotEquals(session, probe.state.value.diagnostics.sessionId)
        assertNull(probe.state.value.frame!!.speedKph)
        probe.state.value.diagnostics.inputs.values.forEach {
            assertEquals(0L, it.vehicleCallbacks)
            assertEquals(0L, it.steeringRate.acceptedObservations)
            assertEquals(RateStatus.NOT_STARTED, it.steeringRate.status)
        }
        probe.close()
    }

    @Test fun missingDemoLocationFallsBackToTheWholeRealPair() = runTest {
        val probe = DebugHubProbeController(backgroundScope); probe.initialize()
        probe.setInputEnabled(Source.REAL, true); probe.setInputEnabled(Source.DEMO, true); probe.advance(100)
        probe.setDemoLocation(false); probe.advance(100)
        val navi = probe.state.value.navi!!
        assertEquals(37.5, navi.lat!!, 0.0); assertEquals(127.0, navi.lon!!, 0.0)
        assertEquals(Source.REAL, navi.qualityOf(NaviField.LAT).source)
        assertEquals(Source.REAL, navi.qualityOf(NaviField.LON).source)
        probe.close()
    }

    @Test fun probeTimeAndFakeRealNeverModifyManualTripPolicyOrSavedSnapshot() = runTest {
        val repository = object : TripRepository {
            var saved: TripSnapshot? = null
            override suspend fun save(snapshot: TripSnapshot): RepositoryResult { saved = snapshot; return RepositoryResult.Succeeded }
            override suspend fun load() = saved?.let { TripLoadResult.Loaded(it) } ?: TripLoadResult.Empty
            override suspend fun delete(): RepositoryResult { saved = null; return RepositoryResult.Succeeded }
        }
        val manual = DebugHarnessController(backgroundScope, repository, FakeClock())
        manual.initialize(); manual.setGear(Gear.D); manual.advanceTime(120_000); manual.setGear(Gear.P)
        manual.advanceTime(180_000); manual.saveNow()
        val before = manual.state.value; val stored = repository.saved
        val probe = DebugHubProbeController(backgroundScope); probe.initialize()
        probe.setInputEnabled(Source.REAL, true); probe.setInputEnabled(Source.DEMO, true); probe.advance(10_000)
        probe.close(); runCurrent()
        assertEquals(before, manual.state.value)
        assertEquals(stored, repository.saved)
        manual.close()
        val restored = DebugHarnessController(backgroundScope, repository, FakeClock())
        restored.initialize()
        assertEquals(300_000L, restored.state.value.timeMs)
        restored.advanceTime(120_000)
        assertEquals(120L, restored.state.value.trip.continuousDriveSec)
        restored.close()
    }
}
