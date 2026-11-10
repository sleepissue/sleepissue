package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.time.Clock
import org.junit.Assert.*
import org.junit.Test

/** SDK 수집 실패의 상태 보호와 비활성화 기록을 검증한다. 실제 SDK·다른 담당 기능은 실행하지 않는다. */
class SdkSignalFailureTest {
    private class Time(var now: Long = 0) : Clock {
        override fun scenarioTimeMs() = now
        override fun elapsedRealtimeMs() = now
        override fun wallTimeMs() = now
    }

    @Test fun permissionFailureDuringCollectionCannotBeUndoneByLateCallbackQueryOrCapability() {
        val time = Time()
        val store = SdkSignalStore(time, SdkProbePolicy(3_000, 1_000))
        val key = "SPEED_KPH"
        store.declare(key, "android.car.permission.CAR_SPEED", true)
        store.capability(key, true, "READ")
        store.receive(key, 22.222f, 80f, SdkDelivery.CALLBACK, nativeMs = 0)
        assertEquals(SignalAvailability.AVAILABLE, store.quality(key).availability)
        assertEquals(80f, store.vehicleInput(key)!!.speed)

        time.now = 100
        store.failed(key, SignalAvailability.PERMISSION_DENIED, "수집 중 권한 거부")
        assertDenied(store, key)
        time.now = 200
        store.receive(key, 25f, 90f, SdkDelivery.CALLBACK, nativeMs = 200)
        assertDenied(store, key)
        time.now = 300
        store.capability(key, true, "READ")
        assertDenied(store, key)
        store.receive(key, 25f, 90f, SdkDelivery.QUERY, nativeMs = 300)
        assertDenied(store, key)
        time.now = 10_000
        assertNull("권한을 잃은 신호의 콜백 Hz를 정상 수집처럼 표시하지 않는다.", store.callbackHz(key))
    }

    @Test fun unavailableUnsupportedPermissionDeniedAndErrorEachRecordOneDisableTransition() {
        data class Case(val key: String, val state: SignalAvailability, val permitted: Boolean,
            val repeatStatus: (SdkSignalStore, String) -> Unit)
        val cases = listOf(
            Case("STEERING_DEG", SignalAvailability.UNSUPPORTED, true) { store, key ->
                store.capability(key, false, "NONE")
            },
            Case("GEAR", SignalAvailability.PERMISSION_DENIED, false) { store, key ->
                store.capability(key, true, "READ")
            },
            Case("BRAKE_ON", SignalAvailability.ERROR, true) { store, key ->
                store.failed(key, SignalAvailability.ERROR, "SDK 구독 실패")
            },
            Case("VENTILATION_LEVEL", SignalAvailability.UNAVAILABLE, true) { store, key ->
                store.capability(key, true, "READ · 아직 수신 없음")
            },
        )
        for (case in cases) {
            val store = SdkSignalStore(Time(), SdkProbePolicy(3_000, 1_000))
            store.declare(case.key, "test.permission", case.permitted)
            case.repeatStatus(store, case.key)
            val once = store.history()
            assertEquals("${case.key} ${case.state} 비활성화 전이 기록", 1,
                once.count { it.contains(case.key) && it.contains(case.state.name) })
            assertEquals(case.state, store.quality(case.key).availability)
            assertNull(store.readings().single().value)
            assertNull("미수신/실패 기록이 센서 수신 시각을 만들면 안 된다.", store.quality(case.key).receivedAtElapsedMs)
            repeat(3) { case.repeatStatus(store, case.key) }
            assertEquals("${case.key} 동일 상태 반복은 기록을 중복 추가하지 않는다.", once, store.history())
            assertEquals(0L, store.readings().single().callbacks)
            assertEquals(0L, store.readings().single().queries)
        }
    }

    @Test fun oneSharedUnsupportedSeatCapabilityLogsOnceWithoutLosingIndividualStates() {
        val store = SdkSignalStore(Time(), SdkProbePolicy(3_000, 1_000))
        val keys = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT").map { "SEAT:$it" }
        keys.forEach { store.declare(it, "pleos.car.permission.CAR_SEATS", true) }
        repeat(3) {
            keys.forEach { key -> store.capability(key, false, "NONE · 공통 착석 capability", disabledLogKey = "SEATS") }
        }
        assertEquals(1, store.history().size)
        assertTrue(store.history().single().contains(" · STATE · SEATS · UNSUPPORTED ·"))
        assertEquals(5, store.readings().size)
        store.readings().forEach {
            assertEquals(false, it.apiReadable)
            assertEquals(SignalAvailability.UNSUPPORTED, it.availability)
            assertEquals(SdkNormalization.UNASSESSED, it.normalization)
            assertNull(it.receivedMs)
        }
        keys.forEach { assertNull(store.vehicleInput(it)!!.seats) }
        // 읽기 가능으로 전환된 뒤 다시 미지원이 되면 새 전이 한 행을 남긴다.
        keys.forEach { store.capability(it, true, "READ") }
        keys.forEach { store.capability(it, false, "NONE", disabledLogKey = "SEATS") }
        assertEquals(2, store.history().count { it.contains(" · STATE · SEATS · UNSUPPORTED ·") })
    }

    private fun assertDenied(store: SdkSignalStore, key: String) {
        assertEquals(SignalAvailability.PERMISSION_DENIED, store.quality(key).availability)
        assertNull(store.vehicleInput(key)!!.speed)
        assertNull(store.readings().single().value)
    }
}
