package com.sleepissue.awakedrive.demo

import android.content.Intent
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.ResponseIntent
import com.sleepissue.awakedrive.engine.rest.NapState
import com.sleepissue.awakedrive.navi.RestAreaRepository
import com.sleepissue.awakedrive.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 실제 SDK 조립·버튼 대체를 검증한다. 음성 발화와 UI 터치 시험을 대신하지 않는다. */
class ProductConnectedDeviceTest {
    @Test fun normalConnected() = exercise("normal")
    @Test fun drowsyConnected() = exercise("drowsy")
    @Test fun drowsyCancelConnected() = exercise("drowsy", cancelWaypoint = true)

    @Test fun drowsyGuidedConnected() = runBlocking {
        assumeTrue("제품 SDK 기기 시험을 명시한 경우 실행", InstrumentationRegistry.getArguments().getString("role5ConnectedDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = NaviHelper(context)
        try {
            // 기존 4번 기기 시험과 같은 임시 경로다. 제품 앱의 목적지는 변경하지 않는다.
            val ready = CompletableDeferred<Unit>()
            helper.initialize({ ready.complete(Unit) }, { ready.completeExceptionally(it) })
            withTimeout(5000) { ready.await() }
            helper.requestRoute(RouteInfo(latitude = 37.48544722, longitude = 127.03636666,
                poiId = "36khp37hh8gqcm5nqhe5", poiName = "42dot", poiSubId = "0", address = "서울시 강남구 남부순환로 2621"))
            delay(8000)
            exercise("drowsy", guided = true)
        } finally {
            runCatching { helper.cancelRoute() }
            helper.release()
        }
    }

    private fun exercise(name: String, cancelWaypoint: Boolean = false, guided: Boolean = false) = runBlocking {
        assumeTrue("제품 SDK 기기 시험을 명시한 경우 실행", InstrumentationRegistry.getArguments().getString("role5ConnectedDevice") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, DrivingPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val settings = DriverUiSettingsStore(context)
        val originalConsent = settings.read().microphoneConsent
        // 인공 발화를 만들지 않고 운전자 버튼 대체만 사용한다. 다른 설정은 보존한다.
        settings.write(settings.read().copy(microphoneConsent = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val clock = AndroidUiClock()
        val session = ProductDemoSession(scope, clock, RestAreaRepository(context).areas, AndroidDemoExecutionFactory(context))
        val started = clock.elapsedRealtimeMs()
        var maxStage = 0
        val messages = linkedSetOf<String>()
        var seenQuestion = false
        var seenOneSpeed = false
        var seenButton = false
        var recovered = false
        var certified = false
        var stamps = 0
        var phase = "NOT_LOADED"
        var napStarted = false
        var alarmed = false
        var cancelled = false
        try {
            val json = context.assets.open("scenarios/$name.json").bufferedReader().use { it.readText() }
            session.load(json, 60)
            assertEquals(DemoPhase.READY, session.playback.value.phase)
            session.start()
            while (session.playback.value.phase == DemoPhase.PLAYING && clock.elapsedRealtimeMs() - started < 180_000) {
                val state = session.state.value
                maxStage = maxOf(maxStage, state.intervention?.stage?.level ?: 0)
                if (state.waypointMessage != "경유지 요청 없음") messages += state.waypointMessage
                if (session.playback.value.questionRequestId != null) {
                    seenQuestion = true
                    seenOneSpeed = seenOneSpeed || session.playback.value.speedMultiplier == 1
                }
                if (name == "normal" && state.parked && state.restAreaName != null && !napStarted) {
                    session.action(DriveUiAction.StartNap)
                    napStarted = session.state.value.nap is NapState.Running
                }
                if (state.nap is NapState.Alarming) {
                    alarmed = true
                    session.action(DriveUiAction.WakeNow)
                }
                state.voiceButtonContext?.let { context ->
                    seenButton = true
                    val intent = when (context) {
                        CheckContext.WAYPOINT_CONSENT -> ResponseIntent.YES
                        CheckContext.RECOVERY -> ResponseIntent.REFRESHED
                        else -> ResponseIntent.REST
                    }
                    if (cancelWaypoint && context == CheckContext.WAYPOINT_CONSENT) {
                        session.action(DriveUiAction.CancelWaypoint)
                        cancelled = true
                    } else session.action(DriveUiAction.VoiceResponse(intent))
                }
                recovered = recovered || state.recovery.contains("회복 확인 정상")
                delay(100)
            }
            if (session.state.value.nap is NapState.Alarming) {
                alarmed = true
                session.action(DriveUiAction.WakeNow)
            }
            phase = session.playback.value.phase.name
            certified = session.state.value.restCertified
            stamps = session.state.value.stampCount
            assertEquals(DemoPhase.COMPLETED, session.playback.value.phase)
            assertTrue(certified)
            if (name == "normal") {
                assertEquals(0, maxStage)
                assertTrue("파워냅 시작 미확인", napStarted)
                assertTrue("파워냅 알람 미확인", alarmed)
            }
            if (name == "drowsy") {
                assertEquals(3, maxStage)
                assertTrue(seenQuestion)
                assertTrue(seenOneSpeed)
                assertTrue(seenButton)
                if (cancelWaypoint) {
                    assertTrue(cancelled)
                    assertTrue("취소 결과 없음", messages.any { it.contains("취소") })
                    assertFalse("취소 후 경유지 실행", messages.any { it.startsWith("경유지 추가:") })
                } else assertTrue("경유지 요청 결과 없음", messages.any { it.startsWith("경유지 추가:") })
                if (guided) assertTrue("경로 안내 중에도 실제 경유지 반영 확인 실패: $messages",
                    messages.contains("경유지 추가: 내비게이션에서 반영을 확인했습니다"))
            }
            session.stop()
            assertNull(session.state.value.voiceButtonContext)
            assertNull(session.playback.value.questionRequestId)
        } finally {
            File(context.cacheDir, "connected-product-$name${if (cancelWaypoint) "-cancel" else if (guided) "-guided" else ""}.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(
                mapOf("scenario" to name, "phase" to phase, "elapsedMs" to clock.elapsedRealtimeMs() - started,
                    "maxStage" to maxStage, "restCertified" to certified, "stamps" to stamps,
                    "questionStarted" to seenQuestion, "questionOneSpeed" to seenOneSpeed,
                    "driverButtonUsed" to seenButton, "napStarted" to napStarted, "napAlarmed" to alarmed, "cancelled" to cancelled, "recoveryNormal" to recovered, "waypointResults" to messages)))
            session.close(); scope.cancel()
            settings.write(settings.read().copy(microphoneConsent = originalConsent))
        }
    }
}
