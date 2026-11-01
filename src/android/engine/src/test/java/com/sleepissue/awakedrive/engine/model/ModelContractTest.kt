package com.sleepissue.awakedrive.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelContractTest {
    @Test
    fun receivingAnObservationPreservesItsOriginalTimeBasis() {
        val observed = SignalQuality(
            availability = SignalAvailability.AVAILABLE,
            source = Source.REAL,
            observedAtMs = 10_000,
            observedTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME,
        )
        val received = observed.copy(receivedAtElapsedMs = 10_100)

        assertEquals(10_000L, received.observedAtMs)
        assertEquals(10_100L, received.receivedAtElapsedMs)
        assertEquals(ObservationTimeBasis.ELAPSED_REALTIME, received.observedTimeBasis)
        assertEquals(ObservationTimeBasis.UNKNOWN, SignalQuality(observedAtMs = 10_000).observedTimeBasis)
    }

    @Test
    fun actualZeroAndFalseRemainDistinctFromUnavailableValues() {
        val actualZero = SignalQuality(SignalAvailability.AVAILABLE, Source.REAL, 50, 1_000)
        val demoBrake = SignalQuality(SignalAvailability.AVAILABLE, Source.DEMO, 50, 1_000)
        val frame = SignalFrame(
            tMs = 50,
            speedKph = 0f,
            brakeOn = false,
            quality = mapOf(SignalField.SPEED_KPH to actualZero, SignalField.BRAKE_ON to demoBrake),
        )

        assertEquals(0f, frame.speedKph!!, 0f)
        assertFalse(frame.brakeOn!!)
        assertNull(frame.steeringDeg)
        assertEquals(Source.REAL, frame.qualityOf(SignalField.SPEED_KPH).source)
        assertEquals(Source.DEMO, frame.qualityOf(SignalField.BRAKE_ON).source)
        assertEquals(SignalAvailability.UNAVAILABLE, frame.qualityOf(SignalField.STEERING_DEG).availability)
        assertNull(SeatOccupancy().frontPassenger)
    }

    @Test
    fun missingDestinationRemainsDistinctFromZeroDistanceAtTurn() {
        val noDestination = NaviState(tMs = 0, lat = 37.0, lon = 127.0)
        val atTurn = noDestination.copy(distToNextTurnM = 0f, remainTimeSec = 0)

        assertNull(noDestination.distToNextTurnM)
        assertNull(noDestination.remainTimeSec)
        assertEquals(0f, atTurn.distToNextTurnM!!, 0f)
        assertEquals(0L, atTurn.remainTimeSec)
        GeoPoint(-90.0, -180.0)
        GeoPoint(90.0, 180.0)
    }

    @Test
    fun invalidSensorValuesAndCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SignalFrame(0, speedKph = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SignalFrame(0, speedKph = -1f) }
        assertThrows(IllegalArgumentException::class.java) { SignalFrame(0, steeringDeg = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { SignalQuality(sourceRateHz = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { NaviState(0, lat = 90.01) }
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(0.0, -180.01) }
        assertThrows(IllegalArgumentException::class.java) { NaviState(0, headingDeg = 360f) }
        assertThrows(IllegalArgumentException::class.java) { NaviState(0, distToNextTurnM = -1f) }
    }

    @Test
    fun defaultNormalStageRemainsUnevaluated() {
        val state = InterventionState()

        assertEquals(InterventionStage.NORMAL, state.stage)
        assertEquals(EvaluationStatus.NOT_EVALUATED, state.evaluationStatus)
        assertNull(state.reason)
        assertNull(state.pendingRequestId)
        assertThrows(IllegalArgumentException::class.java) { RequestId(" ") }
    }

    @Test
    fun humanNoResponseAndUnavailableInputRemainDistinct() {
        val id = RequestId("check-1")
        val noResponse = CheckResult(id, CheckContext.DRIVER_ALERTNESS, CheckOutcome.NO_RESPONSE)
        val unavailable = noResponse.copy(outcome = CheckOutcome.UNAVAILABLE)

        assertEquals(CheckOutcome.NO_RESPONSE, noResponse.outcome)
        assertEquals(CheckOutcome.UNAVAILABLE, unavailable.outcome)
        assertNull(unavailable.responseTimeMs)
        assertNull(unavailable.intent)
        assertThrows(IllegalArgumentException::class.java) { noResponse.copy(responseTimeMs = -1) }
    }
}
