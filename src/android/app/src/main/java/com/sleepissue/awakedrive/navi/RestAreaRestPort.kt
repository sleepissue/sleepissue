package com.sleepissue.awakedrive.navi

import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.rest.RestArea
import com.sleepissue.awakedrive.engine.rest.RestAreaSelection
import com.sleepissue.awakedrive.engine.rest.RestAreaSelector
import com.sleepissue.awakedrive.signal.RestAction
import com.sleepissue.awakedrive.signal.RestAreaCandidate
import com.sleepissue.awakedrive.signal.RestPort
import com.sleepissue.awakedrive.signal.RestRequest
import com.sleepissue.awakedrive.signal.RestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [4번] 1번 [RestPort] 계약의 실제 구현 — 쉼터 찾기와 경유지 추가 (REQ_RST_002~006).
 *
 * ROS로 치면 액션 서버다. [start]는 "요청 받았음(REQUESTED)"만 바로 돌려주고,
 * 실제 결과는 [results] 토픽으로 나중에 보낸다. 1번 매니저는 같은 requestId로 둘을 잇는다.
 *
 * | 요청 | 하는 일 | results |
 * |---|---|---|
 * | FIND_AHEAD | [RestAreaSelector]로 앞쪽 쉼터 1곳 | SUCCEEDED + candidate (없으면 candidate = null → 1번이 NO_REST_AREA 안내, REQ_RST_005) |
 * | ADD_WAYPOINT | [NaviBridge]로 경유지 추가·확인·재탐색·화면 | STARTED → SUCCEEDED / FAILED |
 * | REMOVE_WAYPOINT | 지원 안 함 (1번 정책도 이미 추가된 경유지 제거는 NOT_SUPPORTED) | — |
 *
 * 연결(1번 담당): FIND_AHEAD 결과는 `InterventionInput.RestAreaResolved(result)`로,
 * ADD_WAYPOINT 결과는 `InterventionManager.onResult(result.execution)`로 넘긴다.
 *
 * @param scope 요청 작업을 돌릴 코루틴 범위. 서비스·화면 수명에 맞춰 호출자가 넘긴다.
 *   (코루틴: 스레드를 막지 않고 기다리는 가벼운 작업 단위. 범위가 취소되면 안의 작업도 같이 멈춘다.)
 */
class RestAreaRestPort(
    private val areas: () -> List<RestArea>,
    private val findArea: (String) -> RestArea?,
    private val selector: RestAreaSelector,
    private val bridge: NaviBridge,
    private val scope: CoroutineScope,
) : RestPort {

    constructor(repository: RestAreaRepository, selector: RestAreaSelector, bridge: NaviBridge, scope: CoroutineScope) :
        this({ repository.areas }, repository::find, selector, bridge, scope)

    // 수집자가 잠깐 늦어도 결과를 잃지 않도록 버퍼를 둔다.
    private val mutableResults = MutableSharedFlow<RestResult>(extraBufferCapacity = 32)
    override val results: SharedFlow<RestResult> = mutableResults.asSharedFlow()

    private val lock = Mutex()
    private val running = mutableMapOf<RequestId, Job>()

    override suspend fun start(request: RestRequest): ExecutionResult = lock.withLock {
        val id = request.requestId
        if (id in running) return@withLock result(id, ExecutionStatus.FAILED, ExecutionFailure.ERROR)
        when (request.action) {
            RestAction.FIND_AHEAD -> launchFor(id) { emit(findAhead(request)) }
            RestAction.ADD_WAYPOINT -> {
                val area = request.candidateId?.let(findArea)
                    ?: return@withLock result(id, ExecutionStatus.FAILED, ExecutionFailure.ERROR) // 모르는 쉼터 id
                launchFor(id) {
                    emit(RestResult(result(id, ExecutionStatus.STARTED), area.toCandidate(null)))
                    val execution = when (val outcome = bridge.addRestWaypoint(area)) {
                        WaypointOutcome.Added -> result(id, ExecutionStatus.SUCCEEDED)
                        is WaypointOutcome.Failed -> result(id, ExecutionStatus.FAILED, outcome.failure)
                    }
                    emit(RestResult(execution, area.toCandidate(null)))
                }
            }
            RestAction.REMOVE_WAYPOINT -> return@withLock result(id, ExecutionStatus.FAILED, ExecutionFailure.NOT_SUPPORTED)
        }
        result(id, ExecutionStatus.REQUESTED)
    }

    /**
     * 진행 중인 요청을 멈춘다. 이미 내비에 들어간 경유지를 빼 주지는 않는다(1번 계약: 취소 ≠ 제거).
     * 끝난 요청이나 모르는 id는 UNAVAILABLE.
     */
    override suspend fun cancel(requestId: RequestId): ExecutionResult {
        val job = lock.withLock { running.remove(requestId) }
            ?: return result(requestId, ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)
        job.cancel()
        val cancelled = result(requestId, ExecutionStatus.CANCELLED)
        mutableResults.emit(RestResult(cancelled))
        return cancelled
    }

    private fun findAhead(request: RestRequest): RestResult {
        val id = request.requestId
        val navi = request.naviState
            // 내비 상태가 아예 없으면 쉼터를 못 고른다 → 이름 없이 "가까운 곳에서 쉬어 가요" (REQ_RST_005)
            ?: return RestResult(result(id, ExecutionStatus.SUCCEEDED), candidate = null)
        val table = try {
            areas()
        } catch (e: IllegalArgumentException) {
            // 내장 표가 깨짐 — RestAreaRepositoryTest가 출시 전에 막아야 하는 경우
            return RestResult(result(id, ExecutionStatus.FAILED, ExecutionFailure.ERROR))
        }
        return when (val selection = selector.select(navi, table)) {
            is RestAreaSelection.Selected ->
                RestResult(result(id, ExecutionStatus.SUCCEEDED), selection.area.toCandidate(selection.distanceM))
            is RestAreaSelection.NotFound -> RestResult(result(id, ExecutionStatus.SUCCEEDED), candidate = null)
        }
    }

    private fun launchFor(id: RequestId, block: suspend RestAreaRestPort.() -> Unit) {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e // cancel()이 CANCELLED를 보낸다
            } catch (e: Exception) {
                emit(RestResult(result(id, ExecutionStatus.FAILED, ExecutionFailure.ERROR)))
            } finally {
                lock.withLock { if (running[id] === coroutineContext[Job]) running.remove(id) }
            }
        }
        running[id] = job
        job.start()
    }

    private suspend fun emit(value: RestResult) = mutableResults.emit(value)

    private fun result(id: RequestId, status: ExecutionStatus, failure: ExecutionFailure? = null) =
        ExecutionResult(id, status, failure)
}

/** 경유지 요청 결과에는 거리를 새로 재지 않으므로 0으로 둔다 — 1번은 ADD_WAYPOINT의 candidate 거리를 쓰지 않는다. */
internal fun RestArea.toCandidate(distanceM: Float?) = RestAreaCandidate(
    id = id,
    name = name,
    location = location,
    distanceM = distanceM ?: 0f,
)
