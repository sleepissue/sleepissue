package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.*
import org.junit.Assert.*
import org.junit.Test

class SdkSignalStoreTest {
    private class Time(var now: Long = 0) : Clock {
        override fun scenarioTimeMs() = now
        override fun elapsedRealtimeMs() = now
        override fun wallTimeMs() = now
    }
    private val time = Time()
    private fun store() = SdkSignalStore(time, SdkProbePolicy(3_000, 1_000)).also { s ->
        (SignalField.entries.map { it.name } + NaviField.entries.map { it.name } +
            listOf("SEAT:ROW_1_LEFT", "SEAT:ROW_1_RIGHT")).forEach { s.declare(it, "permission", true) }
    }
    @Test fun missingNativeTimeStaysUnknownWhileEqualValuesCountAsCallbacks() {
        val s = store()
        for (i in 0..100) { time.now = i * 100L; s.receive("SPEED_KPH", 22.222f, 80f, SdkDelivery.CALLBACK) }
        assertEquals(101L, s.readings().first { it.key == "SPEED_KPH" }.callbacks)
        assertEquals(10f, s.callbackHz("SPEED_KPH")!!, 0f)
        assertNull(s.nativeHz("SPEED_KPH")); assertNull(s.quality("SPEED_KPH").observedAtMs)
        assertEquals(ObservationTimeBasis.UNKNOWN, s.quality("SPEED_KPH").observedTimeBasis)
        assertEquals(80f, s.vehicleInput("SPEED_KPH")!!.speed)
    }
    @Test fun initialQueryDoesNotBecomeCallbackAndLateQueryCannotReplaceCallback() {
        val s = store(); s.receive("GEAR", "P", Gear.P, SdkDelivery.QUERY)
        assertEquals(0L, s.readings().first { it.key == "GEAR" }.callbacks)
        time.now = 100; s.receive("GEAR", "D", Gear.D, SdkDelivery.CALLBACK)
        time.now = 200; s.receive("GEAR", "P", Gear.P, SdkDelivery.QUERY)
        assertEquals(Gear.D, s.vehicleInput("GEAR")!!.gear)
        assertEquals(100L, s.quality("GEAR").receivedAtElapsedMs)
    }
    @Test fun duplicateNativeTimestampIsNotNewSensorObservationAndReversalInvalidatesRate() {
        val s = store()
        for (i in 0..100) { time.now = i * 100L; s.receive("SPEED_KPH", 1f, 3.6f, SdkDelivery.CALLBACK, 0) }
        assertEquals(10f, s.callbackHz("SPEED_KPH")!!, 0f)
        assertEquals(0f, s.nativeHz("SPEED_KPH")!!, 0f)
        s.receive("SPEED_KPH", 1f, 3.6f, SdkDelivery.CALLBACK, 100)
        s.receive("SPEED_KPH", 1f, 3.6f, SdkDelivery.CALLBACK, 99)
        assertNull(s.nativeHz("SPEED_KPH"))
    }
    @Test fun permissionUnsupportedFailureAndNoReceptionRemainDistinctNulls() {
        val s = store(); s.declare("GEAR", "powertrain", false)
        s.capability("SPEED_KPH", false, "NONE")
        s.failed("BRAKE_ON", SignalAvailability.ERROR, "bind failed")
        assertEquals(SignalAvailability.PERMISSION_DENIED, s.quality("GEAR").availability)
        assertEquals(SignalAvailability.UNSUPPORTED, s.quality("SPEED_KPH").availability)
        assertEquals(SignalAvailability.ERROR, s.quality("BRAKE_ON").availability)
        assertEquals(SignalAvailability.UNAVAILABLE, s.quality("TURN_SIGNAL").availability)
        assertNull(s.vehicleInput("GEAR")!!.gear)
        assertNull(s.vehicleInput("BRAKE_ON")!!.brakeOn)
    }
    @Test fun frontWheelAngleRemainsRawAndNeverEntersEngineSteering() {
        val s = store(); s.receive("STEERING_DEG", -12f, null, SdkDelivery.CALLBACK,
            note = "전륜각", availability = SignalAvailability.UNSUPPORTED)
        assertEquals("-12.0", s.readings().first { it.key == "STEERING_DEG" }.raw)
        assertNull(s.vehicleInput("STEERING_DEG")!!.steeringDeg)
        assertEquals(SignalAvailability.UNSUPPORTED, s.quality("STEERING_DEG").availability)
    }
    @Test fun destinationResponseDoesNotRefreshLocationReceipt() {
        val s = store(); s.receive("LAT", 37.5, 37.5, SdkDelivery.CALLBACK)
        s.receive("LON", 127.0, 127.0, SdkDelivery.CALLBACK)
        time.now = 5_000; s.receive("REMAIN_DIST_M", 500, 500f, SdkDelivery.CALLBACK)
        val n = s.naviState()
        assertEquals(0L, n.qualityOf(NaviField.LAT).receivedAtElapsedMs)
        assertEquals(5_000L, n.qualityOf(NaviField.REMAIN_DIST_M).receivedAtElapsedMs)
    }
    @Test fun freshSeatDoesNotExtendOldSeatsAndUnknownSeatIsNotEmptySeat() {
        val s = store(); s.receive("SEAT:ROW_1_LEFT", true, true, SdkDelivery.CALLBACK)
        time.now = 4_000; s.receive("SEAT:ROW_1_RIGHT", false, false, SdkDelivery.CALLBACK)
        val input = s.vehicleInput("SEAT:ROW_1_RIGHT")!!
        assertNull(input.seats!!.driver); assertEquals(false, input.seats!!.frontPassenger)
        assertNull(input.seats!!.rearLeft)
        assertEquals(0L, input.quality.getValue(SignalField.SEATS).receivedAtElapsedMs)
    }
    @Test fun headingNeedsPositionChangeAndUsesLastMinuteWindow() {
        val s = store(); assertNull(s.locationHeading(37.5, 127.0))
        time.now = 1_000; assertNull(s.locationHeading(37.5, 127.0))
        time.now = 2_000; assertEquals(90f, s.locationHeading(37.5, 127.01)!!, .1f)
        time.now = 63_000; assertNull(s.locationHeading(38.0, 128.0))
    }
    @Test fun normalizerPreservesAdapterReceiptForFullNaviSnapshotsAndVehiclePatches() {
        val s = store(); s.receive("LAT", 37.5, 37.5, SdkDelivery.CALLBACK)
        s.receive("GEAR", "P", Gear.P, SdkDelivery.CALLBACK)
        time.now = 5_000
        val normalizer = SignalNormalizer(time)
        assertEquals(0L, normalizer.navi(s.naviState()).qualityOf(NaviField.LAT).receivedAtElapsedMs)
        assertEquals(0L, normalizer.vehicle(s.vehicleInput("GEAR")!!).qualityOf(SignalField.GEAR).receivedAtElapsedMs)
    }
    @Test fun newStoreHasNoPreviousSessionReadingsOrRateHistory() {
        val old = store(); old.receive("GEAR", "D", Gear.D, SdkDelivery.CALLBACK)
        val fresh = store(); assertNull(fresh.vehicleInput("GEAR")!!.gear)
        assertEquals(0L, fresh.readings().first { it.key == "GEAR" }.callbacks)
        assertNull(fresh.quality("GEAR").receivedAtElapsedMs)
    }
    @Test fun callbackReceiptIsCapturedBeforeMainThreadProcessing() {
        val s = store(); time.now = 500
        s.receive("BRAKE_ON", true, true, SdkDelivery.CALLBACK, receivedMs = 100)
        assertEquals(100L, SignalNormalizer(time).vehicle(s.vehicleInput("BRAKE_ON")!!)
            .qualityOf(SignalField.BRAKE_ON).receivedAtElapsedMs)
    }
    @Test fun invalidationDoesNotCountAsDestinationCallbackOrExtendReceipt() {
        val s = store(); s.receive("REMAIN_DIST_M", 0, null, SdkDelivery.CALLBACK)
        time.now = 1_000; s.failed("REMAIN_DIST_M", SignalAvailability.UNAVAILABLE, "활성 경로 없음")
        assertEquals(1L, s.readings().first { it.key == "REMAIN_DIST_M" }.callbacks)
        assertEquals(0L, s.quality("REMAIN_DIST_M").receivedAtElapsedMs)
        assertNull(s.naviState().remainDistM)
    }
    @Test fun routeMustBePresentFreshAndExplicitlyRevalidatedAfterCancellation() {
        val gate = SdkRouteGate(time, SdkProbePolicy(3_000, 1_000))
        assertFalse(gate.acceptsRouteValues())
        gate.update(false); assertFalse(gate.acceptsRouteValues())
        gate.update(true); time.now = 3_000; assertTrue(gate.acceptsRouteValues())
        time.now = 3_001; assertFalse(gate.acceptsRouteValues())
        gate.update(true); assertTrue(gate.acceptsRouteValues())
        gate.clear(); assertFalse(gate.acceptsRouteValues())
    }
    @Test fun realSdkHistoryPreservesDrivingObservationAfterReturningToPark() {
        val s = store(); s.receive("SPEED_KPH", 22.222f, 80f, SdkDelivery.CALLBACK, 0)
        time.now = 1_000; s.receive("SPEED_KPH", 0f, 0f, SdkDelivery.CALLBACK, 1_000)
        assertEquals(2, s.history().size)
        assertTrue(s.history().first().contains("22.222 → 80.0"))
        assertEquals(0f, s.vehicleInput("SPEED_KPH")!!.speed)
    }
    @Test fun repeatedCallbacksCountWithoutDuplicatingChangesAndNewSessionHasNoOldHistory() {
        val s = store()
        repeat(200) { time.now++; s.receive("BRAKE_ON", true, true, SdkDelivery.CALLBACK) }
        assertEquals(200L, s.readings().first { it.key == "BRAKE_ON" }.callbacks)
        assertEquals(1, s.history().size)
        assertTrue(store().history().isEmpty())
    }
    @Test fun sdkSeatBooleanCannotBecomeKnownOccupancyOrEmptySeat() {
        val s = store()
        for (raw in listOf(true, false, null)) {
            s.receiveSeatSdkValue("SEAT:ROW_1_RIGHT", raw, SdkDelivery.CALLBACK, time.now++)
            assertNull(s.vehicleInput("SEAT:ROW_1_RIGHT")!!.seats)
            assertEquals(SignalAvailability.UNSUPPORTED, s.quality("SEAT:ROW_1_RIGHT").availability)
        }
        assertEquals(3L, s.readings().first { it.key == "SEAT:ROW_1_RIGHT" }.callbacks)
    }
    @Test fun readableCapabilityAndLateCallbackCannotOverrideDeniedPermission() {
        val s = store(); s.declare("GEAR", "powertrain", false)
        s.capability("GEAR", true, "READ")
        s.receive("GEAR", 8, Gear.D, SdkDelivery.CALLBACK)
        assertNull(s.vehicleInput("GEAR")!!.gear)
        assertNull(s.quality("GEAR").receivedAtElapsedMs)
        s.failed("GEAR", SignalAvailability.ERROR, "binder error")
        assertEquals(SignalAvailability.PERMISSION_DENIED, s.quality("GEAR").availability)
    }
    @Test fun delayedRouteStateUsesCallbackReceiptInsteadOfProcessingTime() {
        val gate = SdkRouteGate(time, SdkProbePolicy(3_000, 1_000))
        time.now = 5_000; gate.update(true, receivedAtMs = 0)
        assertFalse(gate.acceptsRouteValues())
    }
    @Test fun delayedLocationCannotExtendTheHeadingWindow() {
        val s = store()
        time.now = 10_000; assertNull(s.locationHeading(37.5, 127.0, receivedAtMs = 0))
        time.now = 65_000
        assertNull(s.locationHeading(37.5, 127.01, receivedAtMs = 61_000))
    }
}
