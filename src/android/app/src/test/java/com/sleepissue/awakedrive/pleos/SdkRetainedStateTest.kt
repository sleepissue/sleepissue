package com.sleepissue.awakedrive.pleos

import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.signal.FakeClock
import org.junit.Assert.*
import org.junit.Test

class SdkRetainedStateTest {
    private val clock = FakeClock()
    private val store = SdkSignalStore(clock, SdkProbePolicy(3_000, 1_000))
    private val values = mapOf<String, Any>("TURN_SIGNAL" to TurnSignal.OFF, "BRAKE_ON" to false,
        "CRUISE_ACTIVE" to false, "CRUISE_TARGET_KPH" to 0f)
    private fun begin(key: String, value: Any = values.getValue(key)) {
        store.declare(key, "permission", true); store.capability(key, true)
        store.beginCollection(key)
        store.receive(key, value, value, SdkDelivery.CALLBACK, receivedMs = 0)
    }
    private fun select(key: String) = SignalCandidate(store.readings().first { it.key == key }.value, store.quality(key))
        .freshness(SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE), clock)

    @Test fun receiveReportsAcceptanceForAllStateFieldsIncludingNullAndError() {
        values.forEach { (key, value) ->
            store.declare(key, "permission", true); store.capability(key, true)
            assertTrue(store.receive(key, value, value, SdkDelivery.QUERY,
                receivedMs = 10, deliverySequence = 1))
            // false/0도 수락된 값이다. 같은 값 재조회도 확인 시각을 갱신하므로 true다.
            assertTrue(store.receive(key, value, value, SdkDelivery.REQUERY,
                receivedMs = 20, queryStartedMs = 15, deliverySequence = 2))
            val confirmed = store.readings().first { it.key == key }
            assertFalse(store.receive(key, null, null, SdkDelivery.CALLBACK,
                receivedMs = 15, deliverySequence = 3))
            assertFalse(store.receive(key, null, null, SdkDelivery.CALLBACK,
                receivedMs = 20, deliverySequence = 1))
            assertEquals(confirmed.copy(rejectedDeliveries = confirmed.rejectedDeliveries + 2),
                store.readings().first { it.key == key })
            assertTrue(store.receive(key, null, null, SdkDelivery.CALLBACK,
                receivedMs = 30, deliverySequence = 4))
            assertEquals(SignalAvailability.UNAVAILABLE, store.quality(key).availability)
            assertTrue(store.receive(key, "오류", null, SdkDelivery.CALLBACK,
                availability = SignalAvailability.ERROR, receivedMs = 40, deliverySequence = 5))
            assertEquals(SignalAvailability.ERROR, store.quality(key).availability)
            store.block(key, SignalAvailability.PERMISSION_DENIED, "권한 상실")
            assertFalse(store.receive(key, value, value, SdkDelivery.CALLBACK,
                receivedMs = 50, deliverySequence = 6))
            assertEquals(SignalAvailability.PERMISSION_DENIED, store.quality(key).availability)
        }
    }

    @Test fun turnOffLeftRightStayAvailableForTwentySecondsWithReconfirmation() {
        for (value in listOf(TurnSignal.OFF, TurnSignal.LEFT, TurnSignal.RIGHT)) sustained("TURN_SIGNAL", value)
    }
    @Test fun brakeFalseAndTrueStayAvailableForTwentySecondsWithReconfirmation() {
        for (value in listOf(false, true)) sustained("BRAKE_ON", value)
    }
    @Test fun cruiseFalseAndTrueStayAvailableForTwentySecondsWithReconfirmation() {
        for (value in listOf(false, true)) sustained("CRUISE_ACTIVE", value)
    }
    @Test fun targetZeroAndNonzeroStayAvailableForTwentySecondsWithReconfirmation() {
        for (value in listOf(0f, 72f)) sustained("CRUISE_TARGET_KPH", value)
    }
    private fun sustained(key: String, value: Any) {
        clock.elapsedMs = 0; begin(key, value)
        for (second in 1..20) {
            clock.elapsedMs = second * 1_000L
            store.receive(key, value, value, SdkDelivery.REQUERY, receivedMs = clock.elapsedMs,
                queryStartedMs = clock.elapsedMs - 10)
            assertEquals(value, select(key).value)
            assertEquals(SignalAvailability.AVAILABLE, select(key).quality.availability)
        }
        val r = store.readings().first { it.key == key }
        assertEquals(0L, r.receivedMs); assertNull(r.nativeMs)
        assertEquals(20_000L, r.confirmedMs); assertEquals(1L, r.callbacks); assertEquals(1L, r.observations)
        assertEquals(20L, r.queries); assertEquals(0f, store.callbackHz(key)!!, 0f)
        assertNull(store.nativeHz(key))
    }

    @Test fun boundaryIncludesLeaseEndButExpiresImmediatelyAfterwards() {
        values.keys.forEach { key ->
            begin(key); clock.elapsedMs = 3_000; assertNotNull(select(key).value)
            clock.elapsedMs = 3_001; assertNull(select(key).value)
            assertEquals(SignalAvailability.STALE, select(key).quality.availability)
        }
    }
    @Test fun sameOrOlderReceiptCannotRollBackConfirmationOnAnyStateField() {
        values.forEach { (key, v) ->
            begin(key); store.receive(key, v, v, SdkDelivery.REQUERY, receivedMs = 2_000, queryStartedMs = 1_900)
            store.receive(key, v, v, SdkDelivery.REQUERY, receivedMs = 1_000, queryStartedMs = 900)
            store.receive(key, v, v, SdkDelivery.CALLBACK, receivedMs = 1_500)
            assertEquals(2_000L, store.quality(key).stateConfirmedAtElapsedMs)
            assertEquals(2L, store.readings().first { it.key == key }.rejectedDeliveries)
        }
    }
    @Test fun changedRequeryUpdatesValueWithoutInventingSensorCallbackOrNativeTime() {
        begin("BRAKE_ON"); clock.elapsedMs = 1_000
        store.receive("BRAKE_ON", true, true, SdkDelivery.REQUERY, queryStartedMs = 900)
        val r = store.readings().single()
        assertEquals(true, r.value); assertEquals(1L, r.callbacks); assertEquals(1L, r.observations)
        assertEquals(1_000L, r.receivedMs); assertEquals(1_000L, r.confirmedMs); assertNull(r.nativeMs)
    }
    @Test fun explicitNullClearsOnlyTheNamedFieldAndCannotBeReplacedByAnOlderQuery() {
        begin("BRAKE_ON"); begin("TURN_SIGNAL")
        store.receive("BRAKE_ON", null, null, SdkDelivery.CALLBACK, receivedMs = 1_000)
        store.receive("BRAKE_ON", false, false, SdkDelivery.REQUERY, receivedMs = 1_100, queryStartedMs = 900)
        assertNull(store.vehicleInput("BRAKE_ON")!!.brakeOn)
        assertEquals(SignalAvailability.UNAVAILABLE, store.quality("BRAKE_ON").availability)
        assertNull(store.quality("BRAKE_ON").stateLeaseUntilElapsedMs)
        assertEquals(TurnSignal.OFF, store.vehicleInput("TURN_SIGNAL")!!.turnSignal)
    }
    @Test fun failureDisconnectAndPermissionBlockOldResponsesAndCallbacks() {
        values.forEach { (key, v) ->
            for (status in listOf(SignalAvailability.ERROR, SignalAvailability.UNAVAILABLE, SignalAvailability.PERMISSION_DENIED)) {
                begin(key); store.block(key, status, "오류/해제/권한 상실")
                store.receive(key, v, v, SdkDelivery.REQUERY, receivedMs = 2_000, queryStartedMs = 500)
                store.receive(key, v, v, SdkDelivery.CALLBACK, receivedMs = 2_100)
                assertEquals(status, store.quality(key).availability); assertNull(select(key).value)
                assertNull(store.quality(key).stateLeaseUntilElapsedMs)
            }
        }
    }
    @Test fun receiptCapturedBeforeMainDelayIsNotRenewedAtApplicationTime() {
        values.forEach { (key, v) ->
            begin(key); clock.elapsedMs = 5_001
            store.receive(key, v, v, SdkDelivery.REQUERY, receivedMs = 1_000, queryStartedMs = 900)
            assertEquals(1_000L, store.quality(key).stateConfirmedAtElapsedMs)
            assertEquals(SignalAvailability.STALE, select(key).quality.availability)
        }
    }
    @Test fun deliveryOrderProtectsEqualMillisecondResponses() {
        begin("BRAKE_ON")
        store.receive("BRAKE_ON", true, true, SdkDelivery.CALLBACK, receivedMs = 1_000, deliverySequence = 3)
        store.receive("BRAKE_ON", false, false, SdkDelivery.REQUERY, receivedMs = 1_000,
            queryStartedMs = 1_000, deliverySequence = 2)
        assertEquals(true, store.vehicleInput("BRAKE_ON")!!.brakeOn)
    }
    @Test fun repeatedCallbacksRemainRealCallbacksWhileQueriesDoNotBecomeCallbacks() {
        begin("BRAKE_ON")
        store.receive("BRAKE_ON", false, false, SdkDelivery.CALLBACK, receivedMs = 100)
        assertEquals(2L, store.readings().single().callbacks)
        assertEquals(2L, store.readings().single().observations)
    }
    @Test fun unsupportedOrPermissionDeniedDoesNotBecomeAvailableThroughAQuery() {
        values.forEach { (key, v) ->
            store.declare(key, "permission", false); store.capability(key, true)
            store.receive(key, v, v, SdkDelivery.REQUERY)
            assertEquals(SignalAvailability.PERMISSION_DENIED, store.quality(key).availability)
            store.declare(key, "permission", true); store.capability(key, false)
            store.receive(key, v, v, SdkDelivery.REQUERY)
            assertEquals(SignalAvailability.UNSUPPORTED, store.quality(key).availability)
        }
    }
    @Test fun sdkEnumsRemainExplicitAndTargetUnitsAreConvertedOnce() {
        assertEquals(TurnSignal.OFF, SdkStateNormalization.turnSignal(TurnSignalValue.OFF))
        assertEquals(TurnSignal.LEFT, SdkStateNormalization.turnSignal(TurnSignalValue.LEFT))
        assertEquals(TurnSignal.RIGHT, SdkStateNormalization.turnSignal(TurnSignalValue.RIGHT))
        assertNull(SdkStateNormalization.turnSignal(null))
        CruiseControl.entries.forEach { value ->
            val expected = when (value) { CruiseControl.ACTIVATED -> true; CruiseControl.ENABLED, CruiseControl.SUSPENDED -> false; else -> null }
            assertEquals(expected, SdkStateNormalization.cruiseActive(value))
        }
        assertNull(SdkStateNormalization.cruiseActive(null))
        assertEquals(72f, SdkStateNormalization.targetKph(20f)!!, .001f)
        assertEquals(0f, SdkStateNormalization.targetKph(0f)!!, 0f)
        listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { assertNull(SdkStateNormalization.targetKph(it)) }
    }
}
