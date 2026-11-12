package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProductDemoSessionTest {
    private fun json() = """{"schemaVersion":1,"id":"product-test","source":"DEMO","startEpochMs":1794013200000,
        "speedSegments":[{"fromSec":0,"toSec":1,"factor":1}],"samples":[${(0..9).joinToString(",") { index ->
            """{"t":${index / 10.0},"steeringDeg":1,"speedKph":98,"gear":"D","turnSignal":"OFF","brake":false,"seats":null,"cruise":null,"distToNextTurnM":5000,"cabinTempC":23.5,"power":"ON"}"""
        }}]}"""

    @Test fun `제품 데모의 로드 재생 정지 재로드가 Hub 신호와 UI 상태에 반영된다`() = runTest {
        val clock = DemoTestClock()
        val session = ProductDemoSession(backgroundScope, clock, emptyList())
        try {
            session.load(json(), 10)
            assertEquals(DemoPhase.READY, session.playback.value.phase)
            session.start()
            clock.realMs = 50
            advanceTimeBy(20); runCurrent()
            assertNotNull(session.state.value.frame)
            assertTrue(session.state.value.demoPlaying)
            assertTrue(session.state.value.tMs in 100..500)
            assertEquals(Source.DEMO, session.state.value.frame?.qualityOf(SignalField.SPEED_KPH)?.source)
            assertEquals(98f, session.state.value.frame?.speedKph)
            session.pause()
            val pausedAt = session.state.value.tMs
            clock.realMs = 100
            advanceTimeBy(20); runCurrent()
            assertEquals(pausedAt, session.state.value.tMs)
            assertFalse(session.state.value.demoPlaying)
            session.resume()
            clock.realMs = 200
            advanceTimeBy(20); runCurrent()
            assertEquals(DemoPhase.COMPLETED, session.playback.value.phase)
            assertEquals(1000L, session.state.value.tMs)
            assertFalse(session.state.value.demoPlaying)
            session.stop()
            session.load(json(), 60)
            assertEquals(DemoPhase.READY, session.playback.value.phase)
            assertNull(session.state.value.frame)
            assertEquals(0, session.state.value.stampCount)
        } finally { session.close() }
    }

    @Test fun `정상 원본의 종료 무효화는 새 확인 경고를 만들지 않는다`() = runTest {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
            .first { java.io.File(it, "data/scenarios/normal.json").exists() }
        val clock = DemoTestClock()
        val session = ProductDemoSession(backgroundScope, clock, emptyList())
        try {
            session.load(java.io.File(root, "data/scenarios/normal.json").readText(), 60)
            session.start()
            repeat(12_000) {
                if (session.playback.value.phase == DemoPhase.PLAYING) {
                    clock.realMs += 10
                    advanceTimeBy(10); runCurrent()
                }
            }
            assertEquals(DemoPhase.COMPLETED, session.playback.value.phase)
            val state = session.state.value
            assertEquals("확인=${state.intervention} 보조=${state.fatigue?.assist} 신호=${state.frame}",
                InterventionStage.NORMAL, state.intervention?.stage)
        } finally { session.close() }
    }

    @Test fun `잘못된 시나리오는 이전 세션을 재사용하지 않는다`() = runTest {
        val session = ProductDemoSession(backgroundScope, DemoTestClock(), emptyList())
        try {
            session.load(json(), 1)
            session.load("{}", 1)
            assertFalse(session.state.value.actionsConnected)
            assertNull(session.state.value.frame)
            assertTrue(session.state.value.message.contains("불러오지 못했습니다"))
        } finally { session.close() }
    }
}
