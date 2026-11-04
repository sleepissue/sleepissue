package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.NaviState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// SRS v0.2 TC_RST_002·003·005를 옮긴 테스트. 가짜 쉼터는 현재 위치에서 (거리, 방위각)으로 정확히 찍는다.
class RestAreaSelectorTest {

    // 경부고속도로 부근의 임의 위치 [가정] — 값 자체는 의미 없고 위도에 따른 거리 왜곡만 실제와 비슷하면 된다.
    private val here = GeoPoint(36.5, 127.4)
    private val selector = RestAreaSelector()

    private fun navi(headingDeg: Float? = 0f, position: GeoPoint? = here) =
        NaviState(tMs = 0, lat = position?.lat, lon = position?.lon, headingDeg = headingDeg)

    /** here에서 bearingDeg 방향으로 distanceM 떨어진 점 (구면 직접 문제). */
    private fun pointAt(distanceM: Double, bearingDeg: Double, from: GeoPoint = here): GeoPoint {
        val r = 6_371_008.8
        val d = distanceM / r
        val b = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun area(
        id: String,
        distanceKm: Double,
        bearingDeg: Double = 0.0,
        direction: RouteDirection = RouteDirection.DOWN,
    ) = RestArea(
        id = id,
        name = "${id}졸음쉼터",
        kind = RestAreaKind.DROWSY_SHELTER,
        location = pointAt(distanceKm * 1_000, bearingDeg),
        routeName = "경부선",
        direction = direction,
    )

    private fun selectedId(result: RestAreaSelection): String? = (result as? RestAreaSelection.Selected)?.area?.id

    // ── 계산 확인 ──────────────────────────────────────

    @Test
    fun `테스트용 좌표 생성과 거리·방위각 계산이 서로 맞다`() {
        val p = pointAt(12_000.0, 30.0)
        assertEquals(12_000.0, GeoMath.distanceM(here, p), 0.01)
        assertEquals(30.0, GeoMath.initialBearingDeg(here, p), 1e-6)
    }

    @Test
    fun `방위각 차는 360도를 넘어가도 짧은 쪽으로 잰다`() {
        assertEquals(20.0, GeoMath.angleDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, GeoMath.angleDiffDeg(0.0, 180.0), 1e-9)
        assertEquals(0.0, GeoMath.angleDiffDeg(0.0, 360.0), 1e-9)
    }

    // ── TC_RST_002: 거리 40 km, 방위각 ±45° ──────────────

    @Test
    fun `TC_RST_002 거리 39_9 km는 후보, 40_1 km는 제외`() {
        val result = selector.candidates(here, 0f, listOf(area("in", 39.9), area("out", 40.1)))
        assertEquals(listOf("in"), result.map { it.area.id })
    }

    @Test
    fun `TC_RST_002 방위각 차 44_9도는 후보, 45_1도는 제외`() {
        val areas = listOf(
            area("left-in", 10.0, bearingDeg = -44.9),
            area("right-in", 10.0, bearingDeg = 44.9),
            area("left-out", 10.0, bearingDeg = -45.1),
            area("right-out", 10.0, bearingDeg = 45.1),
        )
        val result = selector.candidates(here, 0f, areas)
        assertEquals(setOf("left-in", "right-in"), result.map { it.area.id }.toSet())
    }

    @Test
    fun `진행 방향이 북쪽 근처여도 360도 경계를 넘어 비교한다`() {
        // 진행 방향 350°, 쉼터 방위 20° → 차 30° → 후보
        val result = selector.candidates(here, 350f, listOf(area("wrap", 10.0, bearingDeg = 20.0)))
        assertEquals(listOf("wrap"), result.map { it.area.id })
    }

    @Test
    fun `지나친 쉼터(뒤쪽)는 가까워도 후보가 아니다`() {
        val result = selector.candidates(here, 0f, listOf(area("behind", 1.0, bearingDeg = 180.0)))
        assertTrue(result.isEmpty())
    }

    // ── TC_RST_003: 노선 방향 일치 + 가장 가까운 한 곳 ──────

    @Test
    fun `TC_RST_003 방향 불일치 2곳을 빼고 일치 3곳 중 12_0 km를 고른다`() {
        val areas = listOf(
            area("up-5", 5.0, direction = RouteDirection.UP), // 더 가깝지만 반대 방향
            area("up-8", 8.0, direction = RouteDirection.UP),
            area("down-31", 31.0),
            area("down-12", 12.0),
            area("down-18", 18.5),
        )
        val result = selector.select(navi(), areas, travelDirection = RouteDirection.DOWN)

        result as RestAreaSelection.Selected
        assertEquals("down-12", result.area.id)
        assertEquals(12_000f, result.distanceM, 1f)
    }

    @Test
    fun `양방향 쉼터는 진행 노선 방향과 상관없이 후보다`() {
        val areas = listOf(area("both", 20.0, direction = RouteDirection.BOTH), area("down", 10.0))
        assertEquals("both", selectedId(selector.select(navi(), areas, travelDirection = RouteDirection.UP)))
    }

    @Test
    fun `노선 방향을 모르면 기본값으로는 빼고, 설정을 켜면 넣는다`() {
        val areas = listOf(area("unknown", 10.0, direction = RouteDirection.UNKNOWN))
        assertEquals(
            RestAreaSelection.NotFound(NoRestAreaReason.NONE_IN_RANGE),
            selector.select(navi(), areas, travelDirection = RouteDirection.DOWN),
        )
        val lenient = RestAreaSelector(EngineConfig(restAreaIncludeUnknownDirection = true))
        assertEquals("unknown", selectedId(lenient.select(navi(), areas, travelDirection = RouteDirection.DOWN)))
    }

    @Test
    fun `진행 노선 방향 공급원이 없으면(null) 양방향 쉼터만 남는다`() {
        val areas = listOf(area("down", 5.0), area("both", 15.0, direction = RouteDirection.BOTH))
        assertEquals("both", selectedId(selector.select(navi(), areas, travelDirection = null)))
    }

    @Test
    fun `거리가 같으면 id 순으로 골라 결과가 매번 같다`() {
        val areas = listOf(area("b", 10.0, bearingDeg = 10.0), area("a", 10.0, bearingDeg = -10.0))
        repeat(3) { assertEquals("a", selectedId(selector.select(navi(), areas.shuffled(), RouteDirection.DOWN))) }
    }

    // ── TC_RST_005: 후보 0곳 ─────────────────────────────

    @Test
    fun `TC_RST_005 후보가 0곳이면 예외 없이 NotFound`() {
        assertEquals(
            RestAreaSelection.NotFound(NoRestAreaReason.NONE_IN_RANGE),
            selector.select(navi(), emptyList(), RouteDirection.DOWN),
        )
        assertEquals(
            RestAreaSelection.NotFound(NoRestAreaReason.NONE_IN_RANGE),
            selector.select(navi(), listOf(area("far", 60.0)), RouteDirection.DOWN),
        )
    }

    @Test
    fun `위치나 진행 방향을 못 받으면 이유를 붙여 NotFound`() {
        val areas = listOf(area("down", 10.0))
        assertEquals(
            RestAreaSelection.NotFound(NoRestAreaReason.NO_POSITION),
            selector.select(navi(position = null), areas, RouteDirection.DOWN),
        )
        assertEquals(
            RestAreaSelection.NotFound(NoRestAreaReason.NO_HEADING),
            selector.select(navi(headingDeg = null), areas, RouteDirection.DOWN),
        )
    }
}
