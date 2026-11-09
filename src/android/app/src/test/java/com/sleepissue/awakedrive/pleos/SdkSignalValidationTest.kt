package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.time.Clock
import org.junit.Assert.*
import org.junit.Test

/** 수신 원시값·SDK 읽기 지원·정규화 의미와 canonical 신호를 구분한다. */
class SdkSignalValidationTest {
    private class Time(var now: Long = 0) : Clock {
        override fun scenarioTimeMs() = now
        override fun elapsedRealtimeMs() = now
        override fun wallTimeMs() = now
    }

    @Test fun ventilationBoundsIncludeBothEndpointsAndOutOfRangeValuesRemainRawErrors() {
        val time = Time()
        val store = store(time, "VENTILATION_LEVEL")
        store.bounds("VENTILATION_LEVEL", 0, 3)
        for (raw in listOf(0, 3, -1, 4)) {
            time.now += 100
            store.receiveBoundedInt("VENTILATION_LEVEL", raw, SdkDelivery.CALLBACK, time.now)
            val reading = store.readings().single()
            assertEquals(raw.toString(), reading.raw)
            assertEquals(0, reading.minValue)
            assertEquals(3, reading.maxValue)
            assertEquals(time.now, reading.receivedMs)
            if (raw in 0..3) {
                assertEquals(SignalAvailability.AVAILABLE, reading.availability)
                assertEquals(raw, store.vehicleInput("VENTILATION_LEVEL")!!.ventilationLevel)
            } else {
                assertEquals(SignalAvailability.ERROR, reading.availability)
                assertNull(reading.value)
                assertNull(store.vehicleInput("VENTILATION_LEVEL")!!.ventilationLevel)
            }
        }
    }

    @Test fun negativeSeatTemperatureDiagnosticNeverBecomesVentilationOrRefreshesItsReceipt() {
        val time = Time()
        val temperatureKey = "SEAT_TEMPERATURE_LEVEL"
        val store = store(time, "VENTILATION_LEVEL", temperatureKey)
        store.bounds("VENTILATION_LEVEL", 0, 3)
        store.bounds(temperatureKey, -3, 3)
        store.receiveBoundedInt("VENTILATION_LEVEL", 0, SdkDelivery.CALLBACK, 0)
        time.now = 100
        store.receiveBoundedInt(temperatureKey, -3, SdkDelivery.CALLBACK, 100, diagnosticOnly = true)
        val diagnostic = store.readings().single { it.key == temperatureKey }
        assertEquals("-3", diagnostic.raw)
        assertEquals(-3, diagnostic.value)
        assertEquals(SignalAvailability.AVAILABLE, diagnostic.availability)
        assertEquals(SdkNormalization.DIAGNOSTIC_ONLY, diagnostic.normalization)
        assertNull("별도 좌석 온열/냉각 값은 SignalFrame 필드가 아니다.", store.vehicleInput(temperatureKey))
        val ventilation = store.vehicleInput("VENTILATION_LEVEL")!!
        assertEquals(0, ventilation.ventilationLevel)
        assertEquals(0L, ventilation.quality.getValue(SignalField.VENTILATION_LEVEL).receivedAtElapsedMs)
    }

    @Test fun readableSteeringAndSeatApisKeepTheirCanonicalMeaningUnconfirmed() {
        val time = Time(100)
        val seatKey = "SEAT:ROW_1_RIGHT"
        val store = store(time, "STEERING_DEG", seatKey)
        store.receiveSteeringSdkValue(-10f, SdkDelivery.CALLBACK, 100)
        store.receiveSeatSdkValue(seatKey, false, SdkDelivery.CALLBACK, 100)
        val steering = store.readings().single { it.key == "STEERING_DEG" }
        val seat = store.readings().single { it.key == seatKey }
        assertEquals(true, steering.apiReadable)
        assertEquals(true, seat.apiReadable)
        assertEquals("READ", steering.support)
        assertEquals("READ", seat.support)
        assertEquals("-10.0", steering.raw)
        assertEquals("false", seat.raw)
        assertEquals(SdkNormalization.SEMANTIC_UNCONFIRMED, steering.normalization)
        assertEquals(SdkNormalization.LOSSY_SDK_BOOLEAN, seat.normalization)
        assertEquals(SignalAvailability.UNSUPPORTED, steering.availability)
        assertEquals(SignalAvailability.UNSUPPORTED, seat.availability)
        assertNull(store.vehicleInput("STEERING_DEG")!!.steeringDeg)
        assertNull("SDK false를 빈 좌석으로 확정하지 않는다.", store.vehicleInput(seatKey)!!.seats)
    }

    @Test fun unsupportedCapabilityRejectsALateCallbackWithoutRestoringThePreviousValue() {
        val time = Time(100)
        val store = store(time, "GEAR")
        store.receive("GEAR", 8, Gear.D, SdkDelivery.CALLBACK, receivedMs = 100)
        time.now = 200
        store.capability("GEAR", false, "NONE")
        time.now = 300
        store.receive("GEAR", 4, Gear.P, SdkDelivery.CALLBACK, receivedMs = 300)
        val result = store.readings().single()
        assertEquals(false, result.apiReadable)
        assertEquals("NONE", result.support)
        assertEquals(SignalAvailability.UNSUPPORTED, result.availability)
        assertNull(store.vehicleInput("GEAR")!!.gear)
        assertEquals("8", result.raw)
        assertEquals(100L, result.receivedMs)
        assertEquals(1L, result.callbacks)
        assertEquals(1L, result.rejectedDeliveries)
    }

    @Test fun olderReceiptIsRejectedAndReadableRecheckPreservesTheAlreadyReceivedValueAndTime() {
        val time = Time(500)
        val store = store(time, "SPEED_KPH")
        store.receive("SPEED_KPH", 22.222f, 80f, SdkDelivery.CALLBACK, nativeMs = 400, receivedMs = 400)
        time.now = 600
        store.receive("SPEED_KPH", 25f, 90f, SdkDelivery.CALLBACK, nativeMs = 300, receivedMs = 300)
        assertEquals(80f, store.vehicleInput("SPEED_KPH")!!.speed)
        assertEquals(1L, store.readings().single().rejectedDeliveries)
        store.capability("SPEED_KPH", true, "READ 재확인")
        val result = store.readings().single()
        assertEquals(SignalAvailability.AVAILABLE, result.availability)
        assertEquals(80f, result.value)
        assertEquals("22.222", result.raw)
        assertEquals(400L, result.nativeMs)
        assertEquals(400L, result.receivedMs)
        assertEquals(1L, result.callbacks)
        assertEquals(0L, result.queries)
        assertEquals(1L, result.rejectedDeliveries)
        assertEquals(80f, store.vehicleInput("SPEED_KPH")!!.speed)
        assertEquals(400L, store.quality("SPEED_KPH").receivedAtElapsedMs)
    }

    private fun store(time: Time, vararg keys: String) = SdkSignalStore(time, SdkProbePolicy(3_000, 1_000)).also { store ->
        keys.forEach { key ->
            store.declare(key, "test.permission", true)
            store.capability(key, true, "READ")
        }
    }
}
