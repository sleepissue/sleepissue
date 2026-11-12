package com.sleepissue.awakedrive.ui

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DriveUiActionRouterTest {
    private class Manager : InterventionManager {
        override val state = MutableStateFlow(InterventionState(stage = InterventionStage.REST_GUIDANCE, pendingRequestId = RequestId("pending")))
        override val requests = emptyFlow<ExecutionRequest>()
        var cancelled: RequestId? = null
        val inputs = mutableListOf<InterventionInput>()
        override suspend fun start(requestId: RequestId) = ExecutionResult(requestId, ExecutionStatus.SUCCEEDED)
        override suspend fun submit(requestId: RequestId, input: InterventionInput): ExecutionResult {
            inputs += input; return start(requestId)
        }
        override suspend fun onResult(result: ExecutionResult) = result
        override suspend fun cancel(requestId: RequestId): ExecutionResult {
            cancelled = requestId; return ExecutionResult(requestId, ExecutionStatus.CANCELLED)
        }
        override suspend fun stop(requestId: RequestId) = start(requestId)
    }
    private class Harness {
        val manager = Manager()
        var current = DriveSessionUiState(frame = SignalFrame(0, gear = Gear.P, speedKph = 0f))
        var message = ""
        var naps = 0
        var deletes = 0
        var settings: DriverUiSettings? = null
        val router = DriveUiActionRouter(manager, { current }, {}, { settings = it },
            { ExecutionResult(RequestId("passenger"), ExecutionStatus.SUCCEEDED) },
            { naps++ }, {}, {}, { deletes++; true }, { message = it }, { RequestId("ui") })
    }
    @Test fun `취소는 대기 요청 소유자에게 전달되고 위험 단계를 바꾸지 않는다`() = runTest {
        val h = Harness(); h.router.dispatch(DriveUiAction.CancelWaypoint)
        assertEquals(RequestId("pending"), h.manager.cancelled)
        assertEquals(InterventionStage.REST_GUIDANCE, h.manager.state.value.stage)
    }
    @Test fun `대기 요청이 없을 때 제거 성공을 꾸미지 않는다`() = runTest {
        val h = Harness(); h.manager.state.value = h.manager.state.value.copy(pendingRequestId = null)
        h.router.dispatch(DriveUiAction.CancelWaypoint)
        assertNull(h.manager.cancelled)
        assertTrue(h.message.contains("지원하지"))
    }
    @Test fun `운전 중과 정차 미확정 때 파워냅과 기록 삭제를 거절한다`() = runTest {
        val h = Harness()
        for (frame in listOf(SignalFrame(0, gear = Gear.D, speedKph = 80f), null)) {
            h.current = h.current.copy(frame = frame)
            h.router.dispatch(DriveUiAction.StartNap); h.router.dispatch(DriveUiAction.DeleteRecords)
        }
        assertEquals(0, h.naps); assertEquals(0, h.deletes)
    }
    @Test fun `주차 확인 뒤에만 실행 소유자를 부른다`() = runTest {
        val h = Harness(); h.router.dispatch(DriveUiAction.StartNap); h.router.dispatch(DriveUiAction.DeleteRecords)
        assertEquals(1, h.naps); assertEquals(1, h.deletes)
    }
    @Test fun `통풍 동의 변경이 실제 개입 입력으로 연결된다`() = runTest {
        val h = Harness(); val settings = DriverUiSettings(ventilationConsent = true)
        h.router.dispatch(DriveUiAction.Preferences(settings))
        assertTrue(h.manager.inputs.contains(InterventionInput.VentilationConsentChanged(true)))
        assertEquals(settings, h.settings)
    }
    @Test fun `리포트는 최근 24시간 합계를 이번 운행으로 표시하지 않는다`() {
        val state = DriveSessionUiState(currentTripDriveSec = 1_800,
            trip = TripState(last24hDriveSec = 18_000))
        assertEquals("0시간 30분", state.toReportUiState().totalDrivingTime)
        assertNull(state.copy(currentTripDriveSec = null).toReportUiState().totalDrivingTime)
    }
}
