package com.sleepissue.awakedrive.session

import com.sleepissue.awakedrive.engine.forecast.ForecastAdjustment
import com.sleepissue.awakedrive.engine.forecast.ForecastEngine
import com.sleepissue.awakedrive.engine.forecast.ForecastInput
import com.sleepissue.awakedrive.engine.forecast.ForecastRecalcTrigger
import com.sleepissue.awakedrive.engine.model.EvaluationStatus
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.signal.FakeClock
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.signal.InterventionManager
import com.sleepissue.awakedrive.signal.RestRecoveryResetRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

private fun unused(): InterventionManager = java.lang.reflect.Proxy.newProxyInstance(
    InterventionManager::class.java.classLoader, arrayOf(InterventionManager::class.java),
) { _, method, _ -> error("시험에서 쓰지 않는 멤버: ${method.name}") } as InterventionManager

/** 4번 예보 재계산 연결 (FCS_001·004, RES_011). 계산 규칙 자체는 :engine ForecastEngineTest가 본다. */
class ForecastCoordinatorTest {

    private val zone = ZoneId.of("Asia/Seoul")
    // 10:00 — 취약 시간대(14~16시, 02~06시) 밖이라 절반 규칙만 보인다
    private val clock = FakeClock(scenarioMs = LocalDateTime.of(2026, 11, 9, 10, 0).atZone(zone).toInstant().toEpochMilli())
    private val min = 60_000L

    /** submit만 기록한다. 나머지 멤버는 이 시험에서 부르지 않는다. */
    private class SubmitRecorder : InterventionManager by unused() {
        val inputs = mutableListOf<InterventionInput>()
        override suspend fun submit(requestId: RequestId, input: InterventionInput): ExecutionResult {
            inputs += input
            return ExecutionResult(requestId, ExecutionStatus.REQUESTED)
        }
    }

    private var n = 0
    private val manager = SubmitRecorder()
    private val coordinator = ForecastCoordinator(
        ForecastEngine(clock, zone = zone), clock, manager, { ForecastInput(0, null, 0, emptyList()) }, { RequestId("fc-${++n}") },
    )

    private fun forecasts() = manager.inputs.filterIsInstance<InterventionInput.Forecast>()

    @Test
    fun `출발 피로가 false에서 true로 바뀔 때 한 번만 재계산, 120분 → 60분 (FCS_004)`() = runTest {
        coordinator.watchDepartureFatigue(flowOf(false, false, true, true, true))

        val result = coordinator.latest.value!!
        assertEquals(ForecastRecalcTrigger.START_FATIGUE_DETECTED, result.trigger)
        assertEquals(60, result.onsetMin)
        assertTrue(ForecastAdjustment.DROWSY_HALVED in result.adjustments)
        val sent = forecasts().single().value
        assertEquals(EvaluationStatus.EVALUATED, sent.evaluationStatus)
        assertEquals(clock.scenarioMs + 60 * min, sent.dueAtMs)
        assertEquals(clock.scenarioMs, sent.tMs)
    }

    @Test
    fun `출발 피로 뒤 다른 재계산에도 절반 규칙 유지, 새 운행에서 해제`() = runTest {
        coordinator.watchDepartureFatigue(flowOf(true))
        assertEquals(60, coordinator.recalculate(ForecastRecalcTrigger.DRIVING_RESUMED).onsetMin)

        coordinator.onNewTrip()
        assertEquals(120, coordinator.recalculate(ForecastRecalcTrigger.TRIP_START).onsetMin)
    }

    @Test
    fun `출발 피로가 한 번도 없으면 재계산 안 함`() = runTest {
        coordinator.watchDepartureFatigue(flowOf(false, false))
        assertNull(coordinator.latest.value)
        assertTrue(forecasts().isEmpty())
    }

    @Test
    fun `운행·내비 상태에서 입력 만들기`() {
        val input = ForecastCoordinator.inputFrom(
            TripState(tripId = "t1", continuousDriveSec = 3_659, last24hDriveSec = 14_400),
            NaviState(tMs = 0, remainTimeSec = 15_600),
        )
        assertEquals(60, input.continuousDrivingMin)
        assertEquals(240, input.last24hCumulativeDrivingMin)
        assertEquals(260, input.remainingTimeToDestinationMin)
        assertNull(ForecastCoordinator.inputFrom(null, null).remainingTimeToDestinationMin)
    }

    @Test
    fun `초기화 실패는 FAILED, 예보는 보내지 않음`() = runTest {
        val reset = RestRecoveryReset({ error("엔진 초기화 실패") }, coordinator)
        val result = reset(RestRecoveryResetRequest(RequestId("r1"), "rest-1"))
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertFalse(forecasts().isNotEmpty())
    }
}
