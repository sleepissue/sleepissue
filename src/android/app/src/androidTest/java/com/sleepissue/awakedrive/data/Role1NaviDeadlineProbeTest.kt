package com.sleepissue.awakedrive.data

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.os.SystemClock
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.pleos.PlaygroundSdkOwnership
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/** 제품 SDK/Hub를 그대로 구독한다. 성능 assertion 대신 모든 시도·시한 미달도 원본에 남긴다. */
@RunWith(AndroidJUnit4::class)
class Role1NaviDeadlineProbeTest {
    @Test fun explicitFiveRouteCyclesRecordInputSdkAdapterHubAndConsumer() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("명시한 SIG016 기기 시험만 실행", args.getString("role1Sig016") == "true")
        val runId = requireNotNull(args.getString("runId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val context = instrumentation.targetContext
        val output = File(context.cacheDir, "role1-sig016-$runId.json")
        require(!output.exists()) { "기존 진단을 덮지 않는 새 runId 필요" }
        val stage = File(context.cacheDir, "role1-sig016-$runId-stage.txt")
        require(!stage.exists())
        val lease = requireNotNull(PlaygroundSdkOwnership.acquire("SIG016:$runId")) { "기존 SDK 사용자 보존" }
        val helper = NaviHelper(context)
        val timing = Collections.synchronizedList(mutableListOf<com.sleepissue.awakedrive.pleos.SdkNaviTimingPoint>())
        val boundaries = args.getString("role1Sig016Boundaries") == "true"
        val live = Role1LiveSdkSession(context, naviTimingObserver = if (boundaries) ({ point -> timing.add(point); Unit }) else null)
        val events = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        val result = linkedMapOf<String, Any?>("runId" to runId, "clock" to "SystemClock.elapsedRealtime/ms", "plannedAttempts" to if (boundaries) 3 else 5, "boundariesEnabled" to boundaries)
        val route = AtomicReference<RouteStateInfo?>()
        val destination = AtomicReference<DestinationInfo?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var ownRoute = false
        fun record(kind: String, value: Any?) {
            events += mapOf("atMs" to SystemClock.elapsedRealtime(), "kind" to kind, "value" to value)
        }
        fun guiding() = route.get()?.naviMode in listOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)
        fun empty(n: NaviState?) = n != null && n.remainTimeSec == null && n.remainDistM == null
        fun active(n: NaviState?) = n?.remainTimeSec == 60L && n.remainDistM == 50f
        suspend fun waitFor(predicate: () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(20) }
        fun uiTexts(node: AccessibilityNodeInfo?, found: MutableList<String>, buttons: MutableList<AccessibilityNodeInfo>) {
            if (node == null) return
            val label = node.text?.toString() ?: node.contentDescription?.toString()
            if (!label.isNullOrBlank()) found += label
            if (node.packageName?.toString() == "ai.umos.maps.android.navigation.app" && label == "안내 시작") buttons += node
            repeat(node.childCount) { uiTexts(node.getChild(it), found, buttons) }
        }
        val listener = object : NaviHelperEventListener {
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { route.set(routeStateInfo); record("sdkRoute", routeStateInfo) }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) { destination.set(destinationInfo); record("sdkDestination", destinationInfo) }
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) { record("sdkRouteStarted", routeStartInfo) }
            override fun onRouteCancelled() { record("sdkCancelled", true) }
            override fun onError(errorCode: NaviErrorCode) { record("sdkError", errorCode.toString()) }
        }
        helper.addListener(listener)
        try {
            stage.writeText("PREFLIGHT")
            val connected = CompletableDeferred<Unit>()
            helper.initialize({ connected.complete(Unit) }, { connected.completeExceptionally(it) })
            withTimeout(5_000) { connected.await() }
            helper.getRouteStateInfo(); helper.getDestinationInfo()
            waitFor { route.get() != null && destination.get() != null }
            result["initialRoute"] = route.get(); result["initialDestination"] = destination.get()
            // 명시적 안내/탐색/남은값이 하나라도 있으면 사용자 경로를 덮지 않고 종료한다.
            assumeTrue("기존 사용자 목적지 보존", !guiding() && route.get()?.isRouting == false &&
                destination.get()?.remainDistance == 0 && destination.get()?.remainTime == 0)
            // SDK는 미리보기 중에도 SAFE/0을 반환할 수 있다. 사용자가 고른 미리보기도 덮지 않는다.
            val initialLabels = mutableListOf<String>()
            val initialButtons = mutableListOf<AccessibilityNodeInfo>()
            uiTexts(instrumentation.uiAutomation.rootInActiveWindow, initialLabels, initialButtons)
            result["initialUi"] = initialLabels.toList()
            assumeTrue("사용자 미리보기/다른 화면 보존: 경로 없는 내비 지도를 먼저 열어야 함",
                "어디로 갈까요?" in initialLabels && initialButtons.isEmpty())
            if (args.getString("role1Sig016PreflightOnly") == "true") {
                result["preflightOnly"] = true
                result["completed"] = true
                return@runBlocking
            }
            scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                live.adapter.naviSource.states.collect { n ->
                    record("adapter", mapOf("state" to n, "readings" to live.adapter.store.readings().filter {
                        it.key in listOf("REMAIN_TIME_SEC", "REMAIN_DIST_M") }))
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { live.hub.latestNavi.collect { record("hubLatest", it) } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { live.hub.naviSamples.collect { record("consumer1Hz", it) } }
            live.start()
            waitFor { live.hub.latestFrame.value?.gear == Gear.P && live.hub.latestFrame.value?.speedKph == 0f && empty(live.hub.latestNavi.value) }
            result["stationaryFrame"] = live.hub.latestFrame.value
            // 연결 이후에는 제품 어댑터의 기존 1초 조회/자발 알림만 관찰한다. 원시 관찰자의 추가 조회는 없다.
            val attempts = mutableListOf<Map<String, Any?>>()
            result["attempts"] = attempts
            repeat(if (boundaries) 3 else 5) { index ->
                val attempt = linkedMapOf<String, Any?>("attempt" to index + 1)
                attempts += attempt
                delay(1_200L + index * 173L)
                check(!guiding() && empty(live.hub.latestNavi.value)) { "새 시도 전 비활성/null 조건 필요" }
                val labels = mutableListOf<String>(); val buttons = mutableListOf<AccessibilityNodeInfo>()
                uiTexts(instrumentation.uiAutomation.rootInActiveWindow, labels, buttons)
                attempt["beforeUi"] = labels.toList()
                stage.writeText("REQUEST_${index + 1}")
                val before = SystemClock.elapsedRealtime()
                ownRoute = true
                helper.requestRoute(RouteInfo(latitude = 37.48544722, longitude = 127.03636666,
                    poiId = "36khp37hh8gqcm5nqhe5", poiName = "42dot", poiSubId = "0",
                    address = "서울시 강남구 남부순환로 2621"))
                val after = SystemClock.elapsedRealtime()
                attempt["requestBeforeMs"] = before; attempt["requestAfterMs"] = after
                record("requestBracket", mapOf("attempt" to index + 1, "beforeMs" to before, "afterMs" to after))
                // 미리보기의 공개 UI만 조작한다. 카운트다운과 겹치면 인과 확정 대신 원시 bracket을 보고한다.
                withTimeoutOrNull(3_000) {
                    while (!guiding()) {
                        labels.clear(); buttons.clear()
                        uiTexts(instrumentation.uiAutomation.rootInActiveWindow, labels, buttons)
                        if (buttons.isNotEmpty()) {
                            attempt["previewUi"] = labels.toList()
                            val tapBefore = SystemClock.elapsedRealtime()
                            val button = buttons.first()
                            check(button.isVisibleToUser && live.hub.latestFrame.value?.gear == Gear.P &&
                                live.hub.latestFrame.value?.speedKph == 0f) { "정차 중 보이는 내비 버튼만 조작" }
                            val bounds = Rect().also { button.getBoundsInScreen(it) }
                            check(!bounds.isEmpty)
                            val downTime = SystemClock.uptimeMillis()
                            fun pointer(action: Int): Boolean {
                                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                                event.source = InputDevice.SOURCE_TOUCHSCREEN
                                return try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
                            }
                            val down = pointer(MotionEvent.ACTION_DOWN)
                            val up = pointer(MotionEvent.ACTION_UP)
                            val accepted = down && up
                            attempt["startUiBounds"] = bounds.flattenToString()
                            val tapAfter = SystemClock.elapsedRealtime()
                            attempt["startUiBeforeMs"] = tapBefore; attempt["startUiAfterMs"] = tapAfter
                            attempt["startUiActionAccepted"] = accepted
                            record("startUiBracket", mapOf("attempt" to index + 1, "beforeMs" to tapBefore,
                                "afterMs" to tapAfter, "accepted" to accepted))
                            break
                        }
                        delay(50)
                    }
                }
                try {
                    waitFor { guiding() && destination.get()?.poiId == "36khp37hh8gqcm5nqhe5" && active(live.hub.latestNavi.value) }
                    waitFor { synchronized(events) { events.any { it["kind"] == "consumer1Hz" && (it["atMs"] as Long) >= before && active(it["value"] as? NaviState) } } }
                    attempt["activeReached"] = true
                    attempt["activeRoute"] = route.get(); attempt["activeDestination"] = destination.get()
                    attempt["activeHub"] = live.hub.latestNavi.value
                } catch (error: TimeoutCancellationException) {
                    attempt["activeReached"] = false; attempt["timeout"] = error.message
                }
                stage.writeText("CANCEL_${index + 1}")
                check(destination.get()?.poiId == "36khp37hh8gqcm5nqhe5") { "시험 경로 식별 불가: 임의 취소 금지" }
                val cancelBefore = SystemClock.elapsedRealtime(); helper.cancelRoute(); val cancelAfter = SystemClock.elapsedRealtime()
                attempt["cancelBeforeMs"] = cancelBefore; attempt["cancelAfterMs"] = cancelAfter
                record("cancelBracket", mapOf("attempt" to index + 1, "beforeMs" to cancelBefore, "afterMs" to cancelAfter))
                waitFor { !guiding() && empty(live.hub.latestNavi.value) }
                waitFor { synchronized(events) { events.any { it["kind"] == "consumer1Hz" && (it["atMs"] as Long) >= cancelBefore && empty(it["value"] as? NaviState) } } }
                attempt["afterCancelRoute"] = route.get(); attempt["afterCancelHub"] = live.hub.latestNavi.value
                ownRoute = false
                assertTrue(empty(live.hub.latestNavi.value))
            }
            result["completed"] = true
        } catch (error: Throwable) {
            result["error"] = "${error.javaClass.simpleName}: ${error.message}"
            throw error
        } finally {
            if (ownRoute && destination.get()?.poiId in listOf(null, "", "36khp37hh8gqcm5nqhe5")) {
                helper.cancelRoute(); delay(1_200)
            }
            live.close(); scope.cancel(); scope.coroutineContext[Job]?.join()
            helper.removeListener(listener); helper.release()
            result["ownerBeforeRelease"] = PlaygroundSdkOwnership.currentOwner
            result["leaseReleased"] = PlaygroundSdkOwnership.release(lease)
            result["ownerAfterRelease"] = PlaygroundSdkOwnership.currentOwner
            result["hubAfterClose"] = live.hub.latestNavi.value
            result["events"] = synchronized(events) { events.toList() }
            result["naviTiming"] = synchronized(timing) { timing.toList() }
            output.writeText(GsonBuilder().serializeNulls().create().toJson(result))
            stage.writeText("DONE")
        }
    }
}
