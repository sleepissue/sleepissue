package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.area
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.north
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.origin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// TC_RST_004 일부: 내비 경유지 목록에서 넣은 쉼터를 찾아낸다.
class WaypointMatcherTest {

    private val matcher = WaypointMatcher()
    private val shelter = area("DS-001", origin)

    @Test
    fun `poiId가 같으면 좌표 없이도 일치`() {
        assertTrue(matcher.contains(listOf(NaviWaypoint("DS-001", null)), shelter))
    }

    @Test
    fun `poiId가 바뀌어도 200 m 안이면 일치, 밖이면 불일치`() {
        assertTrue(matcher.contains(listOf(NaviWaypoint("navi-9", north(199.0))), shelter))
        assertFalse(matcher.contains(listOf(NaviWaypoint("navi-9", north(201.0))), shelter))
    }

    @Test
    fun `빈 목록·다른 경유지만 있으면 불일치`() {
        assertFalse(matcher.contains(emptyList(), shelter))
        assertFalse(matcher.contains(listOf(NaviWaypoint(null, north(5_000.0))), shelter))
    }
}
