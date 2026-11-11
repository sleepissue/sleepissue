package com.sleepissue.awakedrive.data

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.rest.GeoMath
import com.sleepissue.awakedrive.navi.RestAreaRepository
import com.sleepissue.awakedrive.pleos.PleosNaviBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [4번] TC_RST_004: 경로 안내 중 실제 NaviHelper.addWaypoint가 쉼터를 경유지로 넣는지, 얼마나 걸리는지 잰다.
 * 내비 경로를 바꾸므로 `-e rest4Device true`를 준 명시적 기기 시험에서만 돈다. 결과는 cache/rest4-waypoint.txt.
 */
@RunWith(AndroidJUnit4::class)
class Rest4WaypointDeviceTest {
    @Test fun addWaypointDuringGuidance() = runBlocking {
        assumeTrue("명시한 기기 시험에서만 실행", InstrumentationRegistry.getArguments().getString("rest4Device") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = GeoPoint(37.48544722, 127.03636666) // 1번 probe와 같은 목적지(42dot)
        // 가까운 두 곳: 첫 호출은 NaviHelper 연결 시간까지, 두 번째는 연결 재사용 시간만 잰다.
        val (area, second) = RestAreaRepository(context).areas.sortedBy { GeoMath.distanceM(it.location, destination) }.take(2)
        val helper = NaviHelper(context)
        // 실측용: 확인 한도를 넉넉히 줘서 실제 걸린 시간을 본다. 제품 한도(EngineConfig 기본 1.5 s)와 비교한다.
        val bridge = PleosNaviBridge(context, EngineConfig(waypointConfirmTimeoutMs = 10_000L))
        val log = StringBuilder()
        try {
            val connected = CompletableDeferred<Unit>()
            helper.initialize({ connected.complete(Unit) }, { connected.completeExceptionally(it) })
            withTimeout(5_000) { connected.await() }
            helper.requestRoute(RouteInfo(latitude = destination.lat, longitude = destination.lon,
                poiId = "36khp37hh8gqcm5nqhe5", poiName = "42dot", poiSubId = "0", address = "서울시 강남구 남부순환로 2621"))
            delay(8_000) // 경로 탐색·안내 시작 대기
            val started = SystemClock.elapsedRealtime()
            val outcome = bridge.addRestWaypoint(area)
            val tookMs = SystemClock.elapsedRealtime() - started
            log.append("area=${area.id} ${area.name}\noutcome=$outcome\ntookMs=$tookMs\n")
            val again = SystemClock.elapsedRealtime()
            val secondOutcome = bridge.addRestWaypoint(second)
            log.append("second=${second.id} outcome=$secondOutcome tookMs=${SystemClock.elapsedRealtime() - again}\n")
            Log.i(TAG, log.toString())
            assertNotNull(outcome)
        } catch (e: Exception) {
            log.append("error=${e.javaClass.simpleName}: ${e.message}\n")
            throw e
        } finally {
            runCatching { helper.cancelRoute() }
            delay(1_000)
            bridge.close(); helper.release()
            File(context.cacheDir, "rest4-waypoint.txt").writeText(log.toString())
        }
    }

    private companion object { const val TAG = "Rest4WaypointDeviceTest" }
}
