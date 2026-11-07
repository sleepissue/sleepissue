package com.sleepissue.awakedrive.navi

import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.rest.RestArea
import com.sleepissue.awakedrive.engine.rest.RestAreaKind
import com.sleepissue.awakedrive.engine.rest.RestAreaSelector
import com.sleepissue.awakedrive.signal.RestAction
import com.sleepissue.awakedrive.signal.RestRequest
import com.sleepissue.awakedrive.signal.RestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// TC_RST_002·004·005·006의 app 쪽 연결: 1번 RestPort 계약대로 REQUESTED → results를 보내는지.
@OptIn(ExperimentalCoroutinesApi::class)
class RestAreaRestPortTest {

    private val here = GeoPoint(36.5, 127.4)
    // 북쪽 약 10 km, 북쪽으로 가는 차로의 쉼터
    private val ahead = RestArea(
        id = "DS-001", name = "옥산졸음쉼터", kind = RestAreaKind.DROWSY_SHELTER,
        location = GeoPoint(36.59, 127.4), routeName = "경부선", directionLabel = "서울방향",
        carriagewayBearingDeg = 0f,
    )

    private class FakeBridge(var outcome: WaypointOutcome = WaypointOutcome.Added) : NaviBridge {
        val added = mutableListOf<RestArea>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun addRestWaypoint(area: RestArea): WaypointOutcome {
            added += area
            gate?.await()
            return outcome
        }
    }

    private val bridge = FakeBridge()

    private fun TestScope.port(areas: List<RestArea> = listOf(ahead)): Pair<RestAreaRestPort, MutableList<RestResult>> {
        val port = RestAreaRestPort({ areas }, { id -> areas.firstOrNull { it.id == id } }, RestAreaSelector(), bridge, workScope())
        val collected = mutableListOf<RestResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.results.toList(collected) }
        return port to collected
    }

    private fun id(v: String) = RequestId(v)
    private fun navi(heading: Float? = 0f) = NaviState(tMs = 0, lat = here.lat, lon = here.lon, headingDeg = heading)

    @Test
    fun `FIND_AHEAD - 앞쪽 쉼터를 찾으면 SUCCEEDED와 이름·직선 거리`() = runTest {
        val (port, results) = port()
        assertEquals(ExecutionResult(id("f1"), ExecutionStatus.REQUESTED), port.start(RestRequest(id("f1"), RestAction.FIND_AHEAD, navi())))
        advanceUntilIdle()
        val r = results.single()
        assertEquals(ExecutionStatus.SUCCEEDED, r.execution.status)
        assertEquals("옥산졸음쉼터", r.candidate?.name)
        assertEquals(10_000f, r.candidate!!.distanceM, 100f)
    }

    @Test
    fun `FIND_AHEAD - 반대 방향으로 가면 candidate null (REQ_RST_005 NO_REST_AREA)`() = runTest {
        val (port, results) = port()
        port.start(RestRequest(id("f2"), RestAction.FIND_AHEAD, navi(heading = 180f)))
        advanceUntilIdle()
        assertEquals(ExecutionStatus.SUCCEEDED, results.single().execution.status)
        assertNull(results.single().candidate)
    }

    @Test
    fun `FIND_AHEAD - 내비 상태가 없으면 candidate null`() = runTest {
        val (port, results) = port()
        port.start(RestRequest(id("f3"), RestAction.FIND_AHEAD, naviState = null))
        advanceUntilIdle()
        assertNull(results.single().candidate)
    }

    @Test
    fun `ADD_WAYPOINT - STARTED 뒤 SUCCEEDED, 경유지에 그 쉼터를 넣음`() = runTest {
        val (port, results) = port()
        assertEquals(ExecutionStatus.REQUESTED, port.start(RestRequest(id("w1"), RestAction.ADD_WAYPOINT, candidateId = "DS-001")).status)
        advanceUntilIdle()
        assertEquals(listOf(ExecutionStatus.STARTED, ExecutionStatus.SUCCEEDED), results.map { it.execution.status })
        assertEquals("DS-001", results.last().candidate?.id)
        assertEquals(listOf(ahead), bridge.added)
    }

    @Test
    fun `ADD_WAYPOINT - 내비 없음·확인 시간 초과는 FAILED로 1번에 넘김 (REQ_RST_006)`() = runTest {
        val (port, results) = port()
        bridge.outcome = WaypointOutcome.Failed(ExecutionFailure.TIMED_OUT, "onWayPointInfo에 안 보임")
        port.start(RestRequest(id("w2"), RestAction.ADD_WAYPOINT, candidateId = "DS-001"))
        advanceUntilIdle()
        assertEquals(ExecutionResult(id("w2"), ExecutionStatus.FAILED, ExecutionFailure.TIMED_OUT), results.last().execution)
    }

    @Test
    fun `ADD_WAYPOINT - 브리지 예외는 ERROR`() = runTest {
        val throwing = object : NaviBridge {
            override suspend fun addRestWaypoint(area: RestArea): WaypointOutcome = error("SDK 예외")
        }
        val port = RestAreaRestPort({ listOf(ahead) }, { ahead }, RestAreaSelector(), throwing, workScope())
        val results = mutableListOf<RestResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.results.toList(results) }
        port.start(RestRequest(id("w3"), RestAction.ADD_WAYPOINT, candidateId = "DS-001"))
        advanceUntilIdle()
        assertEquals(ExecutionFailure.ERROR, results.last().execution.failure)
    }

    @Test
    fun `ADD_WAYPOINT - 모르는 쉼터 id는 바로 FAILED`() = runTest {
        val (port, results) = port()
        val r = port.start(RestRequest(id("w4"), RestAction.ADD_WAYPOINT, candidateId = "없는-id"))
        assertEquals(ExecutionResult(id("w4"), ExecutionStatus.FAILED, ExecutionFailure.ERROR), r)
        advanceUntilIdle()
        assertEquals(emptyList<RestResult>(), results)
    }

    @Test
    fun `같은 requestId가 진행 중이면 두 번째는 FAILED`() = runTest {
        val (port, _) = port()
        bridge.gate = CompletableDeferred()
        port.start(RestRequest(id("w5"), RestAction.ADD_WAYPOINT, candidateId = "DS-001"))
        advanceUntilIdle()
        assertEquals(ExecutionStatus.FAILED, port.start(RestRequest(id("w5"), RestAction.ADD_WAYPOINT, candidateId = "DS-001")).status)
        bridge.gate!!.complete(Unit)
    }

    @Test
    fun `진행 중 취소는 CANCELLED, 끝난 요청 취소는 UNAVAILABLE`() = runTest {
        val (port, results) = port()
        bridge.gate = CompletableDeferred()
        port.start(RestRequest(id("w6"), RestAction.ADD_WAYPOINT, candidateId = "DS-001"))
        advanceUntilIdle()
        assertEquals(ExecutionStatus.CANCELLED, port.cancel(id("w6")).status)
        advanceUntilIdle()
        assertEquals(ExecutionStatus.CANCELLED, results.last().execution.status)
        assertEquals(ExecutionFailure.UNAVAILABLE, port.cancel(id("w6")).failure)
    }

    @Test
    fun `REMOVE_WAYPOINT는 지원하지 않음`() = runTest {
        val (port, _) = port()
        assertEquals(ExecutionFailure.NOT_SUPPORTED, port.start(RestRequest(id("r1"), RestAction.REMOVE_WAYPOINT)).failure)
    }

    // 같은 가상 시계를 쓰는 작업 scope. backgroundScope에 띄운 작업은 advanceUntilIdle이 돌려 주지 않는다.
    private fun TestScope.workScope() = CoroutineScope(StandardTestDispatcher(testScheduler))
}
