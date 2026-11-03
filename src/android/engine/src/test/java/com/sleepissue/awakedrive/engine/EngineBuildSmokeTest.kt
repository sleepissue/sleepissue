package com.sleepissue.awakedrive.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * :engine 모듈이 Kotlin 컴파일과 JUnit 실행까지 되는지만 확인하는 테스트.
 * FatigueEngine 등 실제 테스트가 생기면 지워도 된다.
 */
class EngineBuildSmokeTest {
    @Test
    fun kotlinCompilesAndJUnitRuns() {
        assertEquals(4, 2 + 2)
    }
}
