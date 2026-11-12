package com.sleepissue.awakedrive.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.sleepissue.awakedrive.navi.RestAreaRepository
import com.sleepissue.awakedrive.ui.AndroidUiClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 실제 기기 메모리·실제 경과 시간에서 제품 재생 경로 6종의 결과를 보존한다. SDK 실행 시험은 별도다. */
@RunWith(AndroidJUnit4::class)
class ProductScenariosDeviceTest {
    @Test fun normal() = verify("normal")
    @Test fun distraction() = verify("distraction")
    @Test fun drowsy() = verify("drowsy")
    @Test fun talk() = verify("talk")
    @Test fun assist() = verify("assist")
    @Test fun departure() = verify("departure")

    private fun verify(name: String) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clock = AndroidUiClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val session = ProductDemoSession(scope, clock, RestAreaRepository(context).areas)
        val rows = mutableListOf<Map<String, Any?>>()
        val started = clock.elapsedRealtimeMs()
        var lastBucket = -1L
        var maxStage = 0
        var maxRisk: Float? = null
        fun record() {
            val state = session.state.value
            maxStage = maxOf(maxStage, state.intervention?.stage?.level ?: 0)
            state.fatigue?.fatigue?.riskSmoothed?.let { maxRisk = maxOf(maxRisk ?: it, it) }
            rows += linkedMapOf("tMs" to state.tMs, "phase" to session.playback.value.phase.name,
                "stage" to state.intervention?.stage?.level, "risk" to state.fatigue?.fatigue?.riskSmoothed,
                "pattern" to state.fatigue?.pattern?.patternType?.name,
                "assist" to state.fatigue?.assist?.assistMode, "gear" to state.frame?.gear?.name,
                "restArea" to state.restAreaName, "restCertified" to state.restCertified,
                "stampCount" to state.stampCount, "restCount" to state.rests.size,
                "nextRestArea" to state.nextRestArea.toString(), "warnings" to state.warnings,
                "waypoint" to state.waypointMessage, "message" to state.message)
        }
        try {
            val json = context.assets.open("scenarios/$name.json").bufferedReader().use { it.readText() }
            session.load(json, 60)
            assertEquals("$name 로드", DemoPhase.READY, session.playback.value.phase)
            session.start()
            while (session.playback.value.phase == DemoPhase.PLAYING && clock.elapsedRealtimeMs() - started < 180_000) {
                val bucket = session.state.value.tMs / 60_000
                if (bucket != lastBucket) { lastBucket = bucket; record() }
                maxStage = maxOf(maxStage, session.state.value.intervention?.stage?.level ?: 0)
                delay(100)
            }
            record()
        } finally {
            val report = linkedMapOf("scenario" to name, "requestedSpeed" to 60,
                "phase" to session.playback.value.phase.name, "error" to session.playback.value.error,
                "elapsedMs" to clock.elapsedRealtimeMs() - started, "maxStage" to maxStage,
                "maxRisk" to maxRisk,
                "firstRestGuidance" to session.state.value.warnings.firstOrNull { it.stage == "3단계" }?.time,
                "confirmationPolicy" to "SDK 실행·사람의 응답 없음: 18:55 느림 2회 가정과 구분",
                "deliveredSamples" to session.playback.value.deliveredSamples, "observations" to rows)
            File(context.cacheDir, "product-scenario-$name.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
            session.close()
            scope.cancel()
        }
        assertEquals("$name 종료: 보고서 참조", "COMPLETED", rows.last()["phase"])
        assertEquals("$name 최종 시각", 2_710_000L, rows.last()["tMs"])
        assertEquals("$name 휴식 인증", true, rows.last()["restCertified"])
        assertTrue("$name 주행 중 앞쪽 쉼터 미검색", rows.any { it["nextRestArea"].toString().startsWith("Found") })
        if (name == "normal") assertEquals("$name 오경고", 0, maxStage)
        if (name == "distraction") {
            assertEquals("딴짓 급조향 확인 단계 누락 또는 잘못된 휴식 유도", 2, maxStage)
            assertTrue("딴짓 튐형 기록 없음", rows.any { it["pattern"] == "SPIKE" })
        }
        if (name == "drowsy") assertEquals("졸음 휴식 유도", 3, maxStage)
    }
}
