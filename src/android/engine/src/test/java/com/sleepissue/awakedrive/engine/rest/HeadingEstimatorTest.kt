package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.area
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.north
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// REQ_RST_002 "최근 1분 이동 방향" — 내비가 방향을 주지 않을 때 위치 이력으로 채운다.
class HeadingEstimatorTest {
    private val estimator = HeadingEstimator()

    private fun navi(tMs: Long, at: GeoPoint, heading: Float? = null) =
        NaviState(tMs, lat = at.lat, lon = at.lon, headingDeg = heading)

    @Test
    fun `남쪽으로 1분 달리면 방향은 180도 근처`() {
        for (s in 0..60) estimator.observe(navi(s * 1_000L, north(-27.0 * s))) // 약 97 km/h
        assertEquals(180f, estimator.headingDeg()!!, 0.5f)
    }

    @Test
    fun `위치가 고정이면 방향을 정하지 않는다 (에뮬레이터 고정 위치)`() {
        for (s in 0..60) estimator.observe(navi(s * 1_000L, origin))
        assertNull(estimator.headingDeg())
        val filled = estimator.fill(navi(61_000, origin))
        assertNull(filled.headingDeg)
    }

    @Test
    fun `최소 이동 거리보다 덜 움직이면 방향 없음`() {
        estimator.observe(navi(0, origin))
        estimator.observe(navi(1_000, north(20.0)))
        assertNull(estimator.headingDeg())
        estimator.observe(navi(2_000, north(40.0)))
        assertEquals(0f, estimator.headingDeg()!!, 0.5f)
    }

    @Test
    fun `1분보다 오래된 궤적은 버려 최근 방향을 따른다`() {
        // 처음 2분은 북쪽, 다음 2분은 동쪽
        var p = origin
        for (s in 0..120) { p = north(27.0 * s); estimator.observe(navi(s * 1_000L, p)) }
        val turn = p
        for (s in 1..120) estimator.observe(navi((120 + s) * 1_000L, GeoPoint(turn.lat, turn.lon + 0.0003 * s)))
        assertEquals(90f, estimator.headingDeg()!!, 1f)
    }

    @Test
    fun `내비가 준 방향은 그대로 둔다`() {
        estimator.observe(navi(0, origin))
        estimator.observe(navi(60_000, north(-1_000.0)))
        val given = navi(61_000, north(-1_010.0), heading = 10f)
        assertSame(given, estimator.fill(given))
    }

    @Test
    fun `시간이 거꾸로 가거나 위치 없는 관측은 버린다`() {
        estimator.observe(navi(10_000, origin))
        estimator.observe(navi(5_000, north(-500.0)))
        estimator.observe(NaviState(20_000))
        assertNull(estimator.headingDeg())
    }

    @Test
    fun `채운 방향으로 RestAreaSelector가 앞쪽 쉼터를 고른다`() {
        // 남쪽(180°)으로 달리는 중, 남쪽 5 km에 부산방향(차로 180°) 쉼터
        val ahead = area("S", north(-5_000.0))
        for (s in 0..60) estimator.observe(navi(s * 1_000L, north(-27.0 * s)))
        val now = navi(60_000, north(-27.0 * 60))
        val selector = RestAreaSelector()
        assertTrue(selector.select(now, listOf(ahead)) is RestAreaSelection.NotFound)
        val selected = selector.select(estimator.fill(now), listOf(ahead))
        assertEquals("S", (selected as RestAreaSelection.Selected).area.id)
    }

    @Test
    fun `reset 뒤에는 이전 궤적을 쓰지 않는다`() {
        for (s in 0..60) estimator.observe(navi(s * 1_000L, north(-27.0 * s)))
        estimator.reset()
        estimator.observe(navi(61_000, north(-27.0 * 61)))
        assertNull(estimator.headingDeg())
    }
}
