package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.FakeClock
import org.junit.Assert.*
import org.junit.Test

class SdkNaviDeliveryTest {
    private val clock = FakeClock()
    private val store = SdkSignalStore(clock, SdkProbePolicy(3_000, 1_000)).apply {
        NaviField.entries.forEach { declare(it.name, "permission", true); capability(it.name, true) }
    }
    private val reports = SdkNaviDelivery(store, SdkRouteGate(clock, store.policy)) { }
    private fun at(ms: Long) { clock.elapsedMs = ms; clock.scenarioMs = ms }

    @Test fun onlyNewOrExpiredActiveRouteRequestsRefreshAndCannotReviveOldDestination() {
        reports.destination(60, 50, 0)
        assertNull(store.naviState().remainTimeSec)
        assertTrue(reports.route(naviRouteState(), 0))
        // 조회 전 기본 0/오래된 목적지를 활성 값으로 재사용하지 않는다.
        assertNull(store.naviState().remainTimeSec)
        assertFalse(reports.route(naviRouteState(), 0))
        reports.destination(60, 50, 0)
        assertEquals(60L, store.naviState().remainTimeSec)
        at(3_001)
        assertTrue(reports.route(naviRouteState(), 3_001))
        reports.cancel("취소")
        assertNull(store.naviState().remainTimeSec)
        assertTrue(reports.route(naviRouteState(), 3_001))
    }

    @Test fun activeRouteUnitsUpcomingTurnApproachPassAndCancelRemainExplicit() {
        reports.destination(600, 5_000, 0); reports.turns(listOf(400.0), 0)
        assertNull(store.naviState().remainTimeSec)
        reports.route(naviRouteState(), 0)
        reports.destination(600, 5_000, 0); reports.turns(listOf(400.0, 800.0, -1.0, Double.NaN), 0)
        assertEquals(600L, store.naviState().remainTimeSec)
        assertEquals(5_000f, store.naviState().remainDistM!!, 0f)
        assertEquals(400f, store.naviState().distToNextTurnM!!, 0f)
        at(1_000); reports.turns(listOf(50.0, 450.0), 1_000)
        assertEquals(50f, store.naviState().distToNextTurnM!!, 0f)
        reports.turns(emptyList(), 1_000); assertNull(store.naviState().distToNextTurnM)
        reports.cancel("경로 해제"); reports.destination(0, 0, 1_000)
        assertNull(store.naviState().remainTimeSec)
        assertTrue(store.readings().all { it.callbacks == 0L && it.observations == 0L })
    }

    @Test fun expiredRouteAndErrorRequireNewRouteEvidenceRatherThanLateDestinationRecovery() {
        reports.route(naviRouteState(), 0); at(3_001); reports.destination(5, 10, 3_001)
        assertNull(store.naviState().remainDistM)
        reports.route(naviRouteState(), 3_001); reports.destination(5, 10, 3_001)
        reports.failed(IllegalStateException("연결 오류"))
        assertEquals(SignalAvailability.ERROR, store.quality("REMAIN_TIME_SEC").availability)
        reports.destination(5, 10, 3_001); assertNull(store.naviState().remainTimeSec)
        reports.route(naviRouteState(), 3_001); reports.destination(5, 10, 3_001)
        assertEquals(5L, store.naviState().remainTimeSec)
    }

    @Test fun headingUsesMovingLocationReportsAndCannotBridgeInvalidOrExpiredLocations() {
        reports.location(37.0, 127.0, 9_999.0, 0)
        assertNull(store.naviState().headingDeg)
        at(1_000); reports.location(37.0, 127.001, 9_999.0, 1_000)
        assertEquals(90f, store.naviState().headingDeg!!, .01f)
        at(5_000); reports.location(37.0, 127.002, 9_999.0, 5_000)
        assertNull(store.naviState().headingDeg)
        reports.location(Double.NaN, 127.0, 0.0, 5_000)
        assertNull(store.naviState().lat); assertNull(store.naviState().headingDeg)
        at(6_000); reports.location(37.001, 127.0, 0.0, 6_000)
        assertNull(store.naviState().headingDeg)
        at(7_000); reports.location(37.002, 127.0, 0.0, 7_000)
        assertEquals(0f, store.naviState().headingDeg!!, .01f)
        reports.location(30.0, 120.0, 0.0, 6_500)
        assertEquals(37.002, store.naviState().lat!!, 0.0)
        reports.failed(IllegalStateException("위치 오류"))
        at(8_000); reports.location(38.0, 128.0, 0.0, 8_000)
        assertNull(store.naviState().headingDeg)
        at(9_000); reports.location(38.001, 128.0, 0.0, 9_000)
        assertEquals(0f, store.naviState().headingDeg!!, .01f)
        assertTrue(store.readings().all { it.callbacks == 0L && it.observations == 0L })
    }
}
