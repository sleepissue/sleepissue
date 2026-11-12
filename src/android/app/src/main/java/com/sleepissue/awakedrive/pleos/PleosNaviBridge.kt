package com.sleepissue.awakedrive.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.rest.NaviWaypoint
import com.sleepissue.awakedrive.engine.rest.RestArea
import com.sleepissue.awakedrive.engine.rest.WaypointMatcher
import com.sleepissue.awakedrive.navi.NaviBridge
import com.sleepissue.awakedrive.navi.WaypointOutcome
import com.sleepissue.awakedrive.navi.confirmRestWaypoint
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [4번] NaviHelper 3.0.5로 쉼터를 경유지에 넣는다 (REQ_RST_004). 실패는 1번이 거리 안내로 바꾼다 (REQ_RST_006).
 *
 * 순서: ① `addWaypoint` ② `onWayPointInfo`/`getWaypointInfo`로 목록에 들어갔는지 확인
 *       ③ 내비가 경로 변경을 알리지 않았으면 `requestReRoute` ④ `requestNavigationScreen(Foreground)`
 * 최종 목적지는 건드리지 않는다(`requestRouteWithWaypoints`는 목적지를 다시 지정해야 하므로 쓰지 않는다).
 *
 * 시그니처 근거: NaviHelper-3.0.5.aar·navi-data-3.0.4.aar의 javap 결과(2026-11-06).
 * 권한: addWaypoint·requestReRoute = NAVI_ROUTE_SEARCH, getWaypointInfo = NAVI_ROUTE,
 *       requestNavigationScreen = NAVI_CUSTOM_MAP (각 메서드의 @RequiresPermission).
 *
 * 에뮬레이터에서 확인할 것 (TC_RST_004):
 *  - [가정] 경로 안내 중이 아니면 addWaypoint가 onError 또는 무반응 → 시간 초과 FAILED
 *  - [가정] WaypointIndex.FIRST = 가장 먼저 들르는 경유지 자리 (기존 경유지가 있을 때 덮어쓰는지 확인)
 *  - [가정] addWaypoint 자체가 재탐색까지 하고 onWaypointChanged(ADD)를 보낸다. 안 보내면 requestReRoute를 부른다
 *  - 전체 소요 시간 → [EngineConfig.waypointConfirmTimeoutMs] 조정
 *
 * 콜백 스레드는 문서에 없으므로 [가정] 아무 스레드에서 와도 되도록 Flow로만 넘긴다.
 */
class PleosNaviBridge(
    private val context: Context,
    private val config: EngineConfig = EngineConfig(),
) : NaviBridge, AutoCloseable {

    private val matcher = WaypointMatcher(config)
    private val lock = Mutex()
    private var navi: NaviHelper? = null
    @Volatile private var connected = false

    // 콜백 → 코루틴 다리. 늦게 온 값은 최신 것만 있으면 되므로 오래된 것부터 버린다.
    private val waypointLists = MutableSharedFlow<List<NaviWaypoint>>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val waypointAdded = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val errors = MutableSharedFlow<NaviErrorCode>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val listener = object : NaviHelperEventListener {
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            Log.d(TAG, "경유지 목록 수신: ${waypointInfoList.size}개")
            waypointLists.tryEmit(waypointInfoList.map { it.toNaviWaypoint() })
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            Log.d(TAG, "경유지 변경 통지: ${waypointChangedInfo.cmdType}")
            if (waypointChangedInfo.cmdType == WaypointCmdType.ADD) waypointAdded.tryEmit(Unit)
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.d(TAG, "내비 오류 통지: $errorCode")
            errors.tryEmit(errorCode)
        }
    }

    override suspend fun addRestWaypoint(area: RestArea): WaypointOutcome = lock.withLock {
        if (!granted(PERMISSION_ROUTE_SEARCH)) {
            return@withLock WaypointOutcome.Failed(ExecutionFailure.PERMISSION_DENIED, "$PERMISSION_ROUTE_SEARCH 없음")
        }
        // 명시적 오류는 즉시 반환하고, 연결·반영 확인은 설정된 한도까지만 기다린다.
        val routeChanged = AtomicBoolean(false)
        confirmRestWaypoint(config, confirm = { addAndConfirm(area, routeChanged) }, afterAdded = {
            navi?.let { afterAdded(it, routeChanged.get()) }
        })
    }

    private suspend fun addAndConfirm(area: RestArea, routeChanged: AtomicBoolean): WaypointOutcome = coroutineScope {
        Log.d(TAG, "경유지 추가: 연결 시작")
        val helper = connect() ?: return@coroutineScope WaypointOutcome.Failed(ExecutionFailure.UNAVAILABLE, "NaviHelper 연결 실패")
        Log.d(TAG, "경유지 추가: 연결 완료")

        // 목록·오류·변경 알림을 addWaypoint 전에 듣기 시작해야 빠른 콜백을 놓치지 않는다.
        val confirmed = CompletableDeferred<WaypointOutcome>()
        val listening = listOf(
            launch(start = CoroutineStart.UNDISPATCHED) { waypointLists.first { matcher.contains(it, area) }; confirmed.complete(WaypointOutcome.Added) },
            launch(start = CoroutineStart.UNDISPATCHED) { waypointAdded.first(); routeChanged.set(true) },
            launch(start = CoroutineStart.UNDISPATCHED) {
                val code = errors.first()
                confirmed.complete(WaypointOutcome.Failed(ExecutionFailure.UNAVAILABLE, "NaviErrorCode.$code"))
            },
        )
        val polling = launch {
            // 결과를 주지 않는 API라 목록을 다시 물어 확인한다.
            while (true) {
                delay(config.waypointPollIntervalMs)
                if (granted(PERMISSION_ROUTE)) runCatching { helper.getWaypointInfo() }
            }
        }
        try {
            Log.d(TAG, "경유지 추가: 호출 시작")
            helper.addWaypoint(area.toRequest())
            Log.d(TAG, "경유지 추가: 호출 복귀")
            confirmed.await().also { Log.d(TAG, "경유지 추가: 반영 확인 종료") }
        } catch (e: SecurityException) {
            WaypointOutcome.Failed(ExecutionFailure.PERMISSION_DENIED, e.message ?: "SecurityException")
        } finally {
            polling.cancel()
            listening.forEach { it.cancel() }
        }
    }

    /** 경유지는 이미 들어갔으므로 여기서의 실패는 결과를 바꾸지 않고 로그만 남긴다. */
    private fun afterAdded(helper: NaviHelper, routeChanged: Boolean) {
        if (!routeChanged) runCatching { helper.requestReRoute() }.onFailure { Log.w(TAG, "requestReRoute 실패", it) }
        if (granted(PERMISSION_CUSTOM_MAP)) {
            runCatching { helper.requestNavigationScreen(NavigationScreenRequest.Foreground) }
                .onFailure { Log.w(TAG, "requestNavigationScreen 실패", it) }
        } else {
            Log.w(TAG, "$PERMISSION_CUSTOM_MAP 없음 → 내비 화면을 열지 않음")
        }
    }

    /** NaviHelper는 한 번만 만들고 다시 쓴다. 연결 실패면 다음 요청 때 다시 시도한다. */
    private suspend fun connect(): NaviHelper? {
        navi?.let { helper -> if (connected) return helper }
        val result = CompletableDeferred<Boolean>()
        val helper = navi ?: NaviHelper(context.applicationContext).also {
            it.addListener(listener)
            navi = it
        }
        try {
            helper.initialize(
                { result.complete(true) },
                { e -> Log.w(TAG, "NaviHelper 연결 실패", e); result.complete(false) },
            )
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper.initialize 예외", e)
            result.complete(false)
        }
        connected = result.await()
        return if (connected) helper else null
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun close() {
        navi?.let { runCatching { it.removeListener(listener) }; runCatching { it.release() } }
        navi = null
        connected = false
    }

    private fun RestArea.toRequest() = RequestWaypointInfo(
        location.lat,
        location.lon,
        name,                // poiName (nullable)
        WaypointIndex.FIRST, // [가정] 가장 먼저 들르는 경유지
        id,                  // poiId: 우리 쉼터 id를 실어 보내 확인 때 쓴다
        "",                  // address (내장 표에 없음)
        "",                  // poiSubId
        "",                  // poiProvider
    )

    private fun WaypointInfo.toNaviWaypoint(): NaviWaypoint {
        val valid = latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0
        return NaviWaypoint(poiId = poiId, location = if (valid) GeoPoint(latitude, longitude) else null)
    }

    private companion object {
        const val TAG = "PleosNaviBridge"
        const val PERMISSION_ROUTE_SEARCH = "pleos.car.permission.NAVI_ROUTE_SEARCH"
        const val PERMISSION_ROUTE = "pleos.car.permission.NAVI_ROUTE"
        const val PERMISSION_CUSTOM_MAP = "pleos.car.permission.NAVI_CUSTOM_MAP"
    }
}
