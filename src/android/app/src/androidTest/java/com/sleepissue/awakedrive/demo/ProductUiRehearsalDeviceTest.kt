package com.sleepissue.awakedrive.demo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.helper.NaviHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.rest.NapState
import com.sleepissue.awakedrive.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 실제 제품 Activity의 버튼을 눌러 시연한다. 직접 발화 없이 마이크 미동의·버튼 대체 경로만 쓴다. */
class ProductUiRehearsalDeviceTest {
    @Test fun visibleProductScenarios() = runBlocking {
        assumeTrue("화면을 점유하는 리허설을 명시한 경우 실행", InstrumentationRegistry.getArguments().getString("role5UiRehearsal") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val settings = DriverUiSettingsStore(context)
        val original = settings.read()
        val info = automation.serviceInfo
        val oldFlags = info.flags
        info.flags = oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        val arguments = InstrumentationRegistry.getArguments()
        val guided = arguments.getString("guided") == "true"
        val cancelWaypoint = arguments.getString("cancelWaypoint") == "true"
        val speed = (arguments.getString("speed") ?: "60").toInt().also { require(it in listOf(1, 10, 60)) }
        val names = (arguments.getString("scenarios") ?: "normal,distraction,drowsy,talk,assist,departure").split(',')
        val labels = mapOf("normal" to "정상", "distraction" to "딴짓", "drowsy" to "졸음", "talk" to "대화", "assist" to "보조 주행", "departure" to "출발 피로")
        val runLabel = arguments.getString("runLabel")?.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val output = File(context.cacheDir, "product-ui-rehearsal" + (runLabel?.let { "-$it" } ?: "")).also { it.mkdirs() }
        val reports = mutableListOf<Map<String, Any?>>()
        fun nodes(): List<AccessibilityNodeInfo> {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(queue::add)
            automation.windows.forEach { it.root?.let(queue::add) }
            val result = mutableListOf<AccessibilityNodeInfo>()
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst(); result += node
                repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
            }
            return result.filter { it.packageName?.toString() == context.packageName }
        }
        suspend fun click(text: String) = withTimeout(15_000) {
            while (true) {
                var target = nodes().firstOrNull { it.text?.toString() == text }
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && !target.isVisibleToUser) {
                    target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                } else if (target?.isVisibleToUser == true && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
                delay(100)
            }
        }
        fun screenshot(name: String) {
            automation.takeScreenshot()?.let { bitmap ->
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        try {
            settings.write(original.copy(microphoneConsent = false, passengerChecks = true))
            for (name in names) {
                val report = linkedMapOf<String, Any?>("scenario" to name, "microphoneConsent" to false,
                    "scope" to "ACTUAL_PRODUCT_ACTIVITY_UI_BUTTONS", "requestedSpeed" to speed, "guided" to guided, "cancelWaypoint" to cancelWaypoint)
                val seen = mutableSetOf<String>()
                val responses = mutableListOf<String>()
                val answered = mutableSetOf<RequestId>()
                var routeHelper: NaviHelper? = null
                var activity: LiveDriveActivity? = null
                var session: ProductDemoSession? = null
                var napStarted = false
                var alarmed = false
                var napStoppedByButton = false
                val naviEvents = java.util.Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
                var passengerUsed = false
                var maxStage = 0
                var assistedSeen = false
                val waypointResults = linkedSetOf<String>()
                try {
                    if (guided) {
                        val helper = NaviHelper(context)
                        routeHelper = helper
                        helper.addListener(object : NaviHelperEventListener {
                            private fun record(kind: String, value: Any?) {
                                naviEvents += mapOf("elapsedMs" to android.os.SystemClock.elapsedRealtime(), "kind" to kind, "value" to value)
                            }
                            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { record("route", routeStateInfo) }
                            override fun onDestinationInfo(destinationInfo: DestinationInfo) { record("destination", destinationInfo) }
                            override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) { record("waypoints", waypointInfoList) }
                            override fun onError(errorCode: NaviErrorCode) { record("error", errorCode.toString()) }
                        })
                        val ready = CompletableDeferred<Unit>()
                        helper.initialize({ ready.complete(Unit) }, { ready.completeExceptionally(it) })
                        withTimeout(5000) { ready.await() }
                        // 기존 내비 기기시험에서 확인한 임시 경로. 제품 동작 자체에 목적지를 만들지는 않는다.
                        helper.requestRoute(RouteInfo(latitude = 37.48544722, longitude = 127.03636666,
                            poiId = "36khp37hh8gqcm5nqhe5", poiName = "42dot", poiSubId = "0", address = "서울시 강남구 남부순환로 2621"))
                        repeat(8) {
                            helper.getRouteStateInfo(); helper.getDestinationInfo(); helper.getWaypointInfo()
                            delay(1000)
                        }
                    }
                    activity = instrumentation.startActivitySync(Intent(context, DrivingPreviewActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as LiveDriveActivity
                    // 관측만 실제 Activity의 세션을 읽는다. 로드·재생·응답은 모두 화면 버튼으로 수행한다.
                    val field = LiveDriveActivity::class.java.getDeclaredField("demo").apply { isAccessible = true }
                    session = field.get(activity) as ProductDemoSession
                    delay(750)
                    if (name == names.first()) screenshot("00-home")
                    click("데모"); click(requireNotNull(labels[name])); click("${speed}배"); click("불러오기")
                    withTimeout(30_000) { while (session.playback.value.phase != DemoPhase.READY) delay(100) }
                    screenshot("$name-ready")
                    click("재생")
                    val started = android.os.SystemClock.elapsedRealtime()
                    var lastNaviPoll = started
                    while (session.playback.value.phase in setOf(DemoPhase.PLAYING, DemoPhase.PAUSED) && android.os.SystemClock.elapsedRealtime() - started < maxOf(180_000L, 2_710_000L / speed + 60_000L)) {
                        if (guided && session.playback.value.phase == DemoPhase.PAUSED && nodes().isEmpty()) {
                            screenshot("$name-navigation")
                            report["navigationScreenOpened"] = true
                            context.startActivity(Intent(context, DrivingPreviewActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                            delay(750)
                        }
                        if (guided && android.os.SystemClock.elapsedRealtime() - lastNaviPoll >= 1000) {
                            routeHelper?.let { it.getRouteStateInfo(); it.getDestinationInfo(); it.getWaypointInfo() }
                            lastNaviPoll = android.os.SystemClock.elapsedRealtime()
                        }
                        val state = session.state.value
                        assistedSeen = assistedSeen || state.fatigue?.assist?.assistMode == true
                        if (state.waypointMessage != "경유지 요청 없음") waypointResults += state.waypointMessage
                        val level = state.intervention?.stage?.level ?: 0
                        maxStage = maxOf(maxStage, level)
                        val marker = when {
                            state.nap is NapState.Alarming -> "alarm"
                            state.restCertified -> "certified"
                            state.parked && state.restAreaName != null -> "rest"
                            state.tMs > 15_000 && !state.parked -> "stage-$level"
                            else -> null
                        }
                        // 정식 정상 시나리오는 휴식 15분 직후 끝난다. 캡처보다 기상 조작을 먼저 한다.
                        if (name == "normal" && state.parked && state.restAreaName != null && !napStarted) {
                            click("잠깐 잘게 · 15분"); napStarted = true
                        }
                        if (state.nap is NapState.Alarming && !napStoppedByButton) {
                            alarmed = true; click("지금 깨우기")
                            withTimeout(5000) { while (session.state.value.nap !is NapState.Idle) delay(50) }
                            assertTrue(session.state.value.recovery.contains("기상 완료"))
                            napStoppedByButton = true
                            screenshot("$name-woken")
                        }
                        if (marker != null && seen.add(marker) && marker != "alarm") { screenshot("$name-$marker") }
                        state.voiceButtonContext?.let { question ->
                            val requestId = session.playback.value.questionRequestId
                            if (requestId != null && answered.add(requestId)) when {
                                question == CheckContext.WAYPOINT_CONSENT -> {
                                    if (cancelWaypoint) { click("취소"); responses += "경유지 제안 취소" }
                                    else { click("추가해 주세요"); responses += "운전자 버튼: 경유지 동의" }
                                }
                                question == CheckContext.RECOVERY -> { click("괜찮아요"); responses += "운전자 버튼: 회복 확인" }
                                name == "distraction" -> { click("동승자: 괜찮아요"); passengerUsed = true; responses += "동승자 버튼: 괜찮아요" }
                                name == "talk" -> { click("동승자: 쉬어 가요"); passengerUsed = true; responses += "동승자 버튼: 쉬어 가요" }
                                name == "assist" -> { click("괜찮아요"); responses += "운전자 버튼: 괜찮아요" }
                                else -> { click("쉴게요"); responses += "운전자 버튼: 쉴게요" }
                            }
                        }
                        delay(100)
                    }
                    // 마지막 정차 프레임 뒤 늦게 도착한 회복 질문도 화면 버튼으로 답한다.
                    val settleUntil = android.os.SystemClock.elapsedRealtime() + 8_000L
                    while (android.os.SystemClock.elapsedRealtime() < settleUntil) {
                        if (session.state.value.voiceButtonContext == CheckContext.RECOVERY) {
                            click("괜찮아요"); responses += "운전자 버튼: 회복 확인"; delay(500); break
                        }
                        if (session.state.value.recovery.contains("회복 확인 정상")) break
                        delay(100)
                    }
                    delay(300)
                    screenshot("$name-final")
                    val state = session.state.value
                    report.putAll(mapOf("phase" to session.playback.value.phase.name, "maxStage" to maxStage,
                        "restCertified" to state.restCertified, "stamps" to state.stampCount,
                        "napStarted" to napStarted, "napAlarmed" to alarmed, "napStoppedByButton" to napStoppedByButton, "napRemainingMs" to state.napRemainingMs,
                        "assistedSeen" to assistedSeen, "departureFatigue" to state.fatigue?.baseline?.departureFatigue,
                        "recoveryNormal" to state.recovery.contains("회복 확인 정상"),
                        "passengerButtonUsed" to passengerUsed, "waypointResults" to waypointResults,
                        "warnings" to state.warnings, "waypoint" to state.waypointMessage,
                        "responses" to responses, "message" to state.message,
                        "visibleTexts" to nodes().mapNotNull { it.text?.toString() }.distinct()))
                    assertEquals("제품 화면 재생 중단: $name", DemoPhase.COMPLETED, session.playback.value.phase)
                    assertTrue("휴식 인증 없음: $name", state.restCertified)
                    if (guided && !cancelWaypoint) assertTrue("경로 안내 중 실제 경유지 반영 없음: $waypointResults",
                        waypointResults.contains("경유지 추가: 내비게이션에서 반영을 확인했습니다"))
                    if (cancelWaypoint) assertTrue("제안 취소 결과 없음: $waypointResults",
                        waypointResults.any { it.contains("취소했습니다") })
                    if (name == "normal") { assertEquals(0, maxStage); assertTrue(napStarted); assertTrue(alarmed); assertTrue("기상 버튼으로 파워냅 종료 확인", napStoppedByButton) }
                    if (name == "distraction") { assertEquals(2, maxStage); assertTrue(passengerUsed) }
                    if (name == "talk") { assertEquals(3, maxStage); assertTrue(passengerUsed) }
                } catch (error: Throwable) {
                    report["error"] = "${error.javaClass.simpleName}: ${error.message}"
                    report["responses"] = responses
                    report["visibleTexts"] = nodes().mapNotNull { it.text?.toString() }.distinct()
                    screenshot("$name-error")
                } finally {
                    report["playback"] = session?.playback?.value
                    report["naviEvents"] = synchronized(naviEvents) { naviEvents.toList() }
                    reports += report
                    File(output, "$name.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
                    File(output, "summary.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(reports))
                    withContext(NonCancellable + Dispatchers.Main) { session?.close(); activity?.finish() }
                    routeHelper?.let { helper -> runCatching { helper.cancelRoute() }; helper.release() }
                }
            }
        } finally {
            settings.write(original)
            info.flags = oldFlags; automation.serviceInfo = info
        }
        assertTrue("리허설 실패 항목: ${reports.filter { it.containsKey("error") }}", reports.none { it.containsKey("error") })
    }
}
