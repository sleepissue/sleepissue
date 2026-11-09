package com.sleepissue.awakedrive.engine.time

import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment
import com.sleepissue.awakedrive.engine.forecast.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DemoClockTest {
    private class RealClock(var now: Long = 1_000, var wall: Long = 99_000) : Clock {
        override fun scenarioTimeMs() = error("실제 시계의 운행 시각은 데모에 합산하지 않습니다.")
        override fun elapsedRealtimeMs() = now
        override fun wallTimeMs() = wall
    }
    private val real = RealClock()
    private val clock = DemoClock(real)
    private val segments = listOf(DemoSpeedSegment(0, 1_000, 1), DemoSpeedSegment(1_000, 11_000, 10),
        DemoSpeedSegment(11_000, 71_000, 60))
    private fun start() { clock.configure(71_000, segments); clock.start() }

    @Test fun `같은 실제 시각에서 읽기는 시간을 늘리지 않는다`() {
        start()
        repeat(10) { assertEquals(0L, clock.scenarioTimeMs()) }
        assertEquals(1_000L, clock.elapsedRealtimeMs())
        assertEquals(99_000L, clock.wallTimeMs())
    }

    @Test fun `경계 직전과 직후는 새 구간 속도를 정확히 적용한다`() {
        start()
        real.now += 999
        assertEquals(999L, clock.scenarioTimeMs())
        assertEquals(1, clock.snapshot().segmentMultiplier)
        real.now++
        assertEquals(1_000L, clock.scenarioTimeMs())
        assertEquals(10, clock.snapshot().speedMultiplier)
        real.now += 999
        assertEquals(10_990L, clock.scenarioTimeMs())
        real.now++
        assertEquals(11_000L, clock.scenarioTimeMs())
        assertEquals(60, clock.snapshot().speedMultiplier)
        real.now++
        assertEquals(11_060L, clock.scenarioTimeMs())
    }

    @Test fun `한 번의 실제 경과가 여러 배속 구간을 건너도 남은 시간을 적분한다`() {
        start()
        real.now += 2_500
        assertEquals(41_000L, clock.scenarioTimeMs())
        assertEquals(2_500L, clock.snapshot().realElapsedMs)
    }

    @Test fun `정수 ms로 나누어떨어지지 않는 경계에서도 남은 실제 시간을 잃지 않는다`() {
        clock.configure(100_000, listOf(DemoSpeedSegment(0, 101, 60), DemoSpeedSegment(101, 100_000, 10)))
        clock.start()
        real.now += 2
        assertEquals(104L, clock.scenarioTimeMs())
        real.now += 1
        assertEquals(114L, clock.scenarioTimeMs())
    }

    @Test fun `일시정지한 실제 시간은 운행에 더하지 않고 실제 경과에는 보인다`() {
        start(); real.now += 1_050; clock.pause()
        assertEquals(1_500L, clock.scenarioTimeMs())
        real.now += 10_000
        assertEquals(1_500L, clock.scenarioTimeMs())
        assertEquals(0, clock.snapshot().speedMultiplier)
        assertEquals(11_050L, clock.snapshot().realElapsedMs)
        clock.resume(); real.now += 100
        assertEquals(2_500L, clock.scenarioTimeMs())
    }

    @Test fun `질문 시작 전까지 기존 배속을 적용하고 질문 구간만 실제 1배다`() {
        start(); real.now += 1_050
        clock.setQuestionActive(true)
        assertEquals(1_500L, clock.scenarioTimeMs())
        real.now += 2_300
        assertEquals(3_800L, clock.scenarioTimeMs())
        assertEquals(1, clock.snapshot().speedMultiplier)
        assertEquals(4_350L, clock.elapsedRealtimeMs())
        clock.setQuestionActive(false)
        assertEquals(10, clock.snapshot().speedMultiplier)
        real.now += 100
        assertEquals(4_800L, clock.scenarioTimeMs())
    }

    @Test fun `질문 중 구간 경계를 넘으면 종료 즉시 현재 구간 배속으로 돌아간다`() {
        start(); real.now += 1_900
        clock.setQuestionActive(true)
        assertEquals(10_000L, clock.scenarioTimeMs())
        real.now += 1_500
        assertEquals(11_500L, clock.scenarioTimeMs())
        assertEquals(60, clock.snapshot().segmentMultiplier)
        assertEquals(1, clock.snapshot().speedMultiplier)
        clock.setQuestionActive(false)
        assertEquals(60, clock.snapshot().speedMultiplier)
        real.now += 10
        assertEquals(12_100L, clock.scenarioTimeMs())
    }

    @Test fun `질문 중 일시정지와 재개 후에도 잠금을 유지한다`() {
        start(); clock.setQuestionActive(true); real.now += 100; clock.pause()
        real.now += 500; clock.resume(); real.now += 100
        assertEquals(200L, clock.scenarioTimeMs())
        assertEquals(1, clock.snapshot().speedMultiplier)
        clock.setQuestionActive(false)
        assertEquals(1, clock.snapshot().speedMultiplier)
    }

    @Test fun `끝과 중지 이후 시각은 고정되고 reset은 새 기준으로 비운다`() {
        start(); real.now += 5_000
        assertEquals(71_000L, clock.scenarioTimeMs())
        assertEquals(3_000L, clock.snapshot().realElapsedMs)
        assertEquals(0, clock.snapshot().speedMultiplier)
        clock.stop(); real.now += 5_000
        assertEquals(71_000L, clock.scenarioTimeMs())
        assertEquals(3_000L, clock.snapshot().realElapsedMs)
        clock.reset()
        assertEquals(0L, clock.scenarioTimeMs())
        assertEquals(0L, clock.snapshot().realElapsedMs)
    }

    @Test(expected = IllegalStateException::class) fun `실제 단조 시각 역행을 조용히 더하지 않는다`() {
        start(); real.now--
        clock.scenarioTimeMs()
    }

    @Test(expected = IllegalArgumentException::class) fun `빈틈 있는 구간은 거부한다`() {
        clock.configure(300, listOf(DemoSpeedSegment(0, 100, 1), DemoSpeedSegment(200, 300, 10)))
    }

    @Test(expected = IllegalArgumentException::class) fun `지원하지 않는 배속은 거부한다`() {
        DemoSpeedSegment(0, 100, 2)
    }

    @Test fun `epoch 변환을 예보에만 적용하여 날짜와 상대 마감 시각을 왕복한다`() {
        start(); real.now += 1_050
        val epoch = Instant.parse("2026-11-05T03:30:00Z").toEpochMilli() // 서울 12:30
        val mapping = ScenarioEpochMapping(epoch)
        val forecastClock = EpochScenarioClock(clock, mapping)
        assertEquals(1_500L, clock.scenarioTimeMs())
        assertEquals(epoch + 1_500, forecastClock.scenarioTimeMs())
        assertEquals(clock.elapsedRealtimeMs(), forecastClock.elapsedRealtimeMs())
        assertEquals(real.wall, forecastClock.wallTimeMs())
        val forecast = ForecastEngine(forecastClock).calculate(ForecastInput(0, null, 0, emptyList()), ForecastRecalcTrigger.TRIP_START)
        val fourteen = Instant.parse("2026-11-05T05:00:00Z").toEpochMilli()
        // 기존 ForecastEngine의 분 단위 내림 정책을 그대로 사용한다.
        assertTrue(forecast.expectedDrowsyAtMs in (fourteen - 60_000)..fourteen)
        assertTrue(mapping.toRelativeMs(forecast.expectedDrowsyAtMs) > 60 * 60_000)
        assertEquals(forecast.expectedDrowsyAtMs, mapping.toEpochMs(mapping.toRelativeMs(forecast.expectedDrowsyAtMs)))
        assertEquals(1_500L, clock.scenarioTimeMs())
    }

    @Test(expected = ArithmeticException::class) fun `epoch 덧셈 오버플로를 숨기지 않는다`() {
        ScenarioEpochMapping(Long.MAX_VALUE).toEpochMs(1)
    }
}
