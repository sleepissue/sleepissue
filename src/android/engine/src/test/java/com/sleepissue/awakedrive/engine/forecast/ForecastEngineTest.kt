package com.sleepissue.awakedrive.engine.forecast

import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.time.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

// SRS v0.2 TC_FCS_001~008(007 삭제) 합격기준 수치를 그대로 옮긴 테스트.
class ForecastEngineTest {

    private val zone = ZoneId.of("Asia/Seoul")

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, 3, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun engineAt(hour: Int, minute: Int = 0) = ForecastEngine(FakeClock(scenarioMs = at(hour, minute)), zone = zone)

    private fun input(
        continuousDrivingMin: Int = 0,
        remainingTimeToDestinationMin: Int? = 300,
        last24hCumulativeDrivingMin: Int = 0,
        onsetHistoryMin: List<Int> = emptyList(),
        startCondition: StartCondition = StartCondition.NONE,
        startFatigueDetected: Boolean = false,
        ageBand: AgeBand? = null,
    ) = ForecastInput(
        continuousDrivingMin, remainingTimeToDestinationMin, last24hCumulativeDrivingMin,
        onsetHistoryMin, startCondition, startFatigueDetected, ageBand,
    )

    // 취약 시간대(02~07, 14~17)에 걸리지 않는 시각에서 규칙만 확인
    private fun calcAt20(i: ForecastInput) = engineAt(20).calculate(i, ForecastRecalcTrigger.TRIP_START)

    // ── TC_FCS_002 개인 졸음 시점 ──

    @Test fun `기록 0~2회면 기본 120분`() {
        val e = engineAt(20)
        assertEquals(120, e.personalOnsetMin(emptyList()))
        assertEquals(120, e.personalOnsetMin(listOf(80, 100)))
    }

    @Test fun `기록 3회 80·100·70이면 중앙값 80분`() {
        assertEquals(80, engineAt(20).personalOnsetMin(listOf(80, 100, 70)))
    }

    @Test fun `짝수 개면 가운데 두 값 평균, 최근 10회만 사용`() {
        val e = engineAt(20)
        assertEquals(85, e.personalOnsetMin(listOf(80, 90, 70, 100)))
        // 최근 10회는 전부 60, 그보다 오래된 기록 200은 무시
        assertEquals(60, e.personalOnsetMin(List(10) { 60 } + listOf(200, 200, 200)))
    }

    // ── TC_FCS_003 최근 24시간 누적 ──

    @Test fun `누적 239분이면 120분 그대로, 240분이면 90분`() {
        assertEquals(120, calcAt20(input(last24hCumulativeDrivingMin = 239)).remainingMin)
        val r = calcAt20(input(last24hCumulativeDrivingMin = 240))
        assertEquals(90, r.remainingMin)
        assertTrue(ForecastAdjustment.CUMULATIVE_ADVANCE in r.adjustments)
    }

    // ── TC_FCS_004 졸려요·출발 피로 ──

    @Test fun `졸려요면 120에서 60분`() {
        assertEquals(60, calcAt20(input(startCondition = StartCondition.DROWSY)).remainingMin)
    }

    @Test fun `출발 피로 감지도 같은 규칙`() {
        assertEquals(60, calcAt20(input(startFatigueDetected = true)).remainingMin)
    }

    @Test fun `50분이 절반이면 하한 30분`() {
        val r = calcAt20(input(onsetHistoryMin = listOf(50, 50, 50), startCondition = StartCondition.DROWSY))
        assertEquals(30, r.remainingMin)
        assertTrue(ForecastAdjustment.DROWSY_FLOOR in r.adjustments)
    }

    @Test fun `누적 4시간 뒤 90에서 절반이면 45분`() {
        val r = calcAt20(input(last24hCumulativeDrivingMin = 240, startCondition = StartCondition.DROWSY))
        assertEquals(45, r.remainingMin)
    }

    @Test fun `원래 30분보다 짧으면 하한 때문에 늦어지지 않음`() {
        val r = calcAt20(input(onsetHistoryMin = listOf(20, 20, 20), startCondition = StartCondition.DROWSY))
        assertEquals(20, r.remainingMin)
    }

    // ── 연속 운전 시간 반영 (REQ_FCS_001) ──

    @Test fun `연속 50분 운전했으면 남은 시간 70분`() {
        assertEquals(70, calcAt20(input(continuousDrivingMin = 50)).remainingMin)
    }

    @Test fun `이미 예상 시점을 지났으면 곧`() {
        val r = calcAt20(input(continuousDrivingMin = 130))
        assertEquals(0, r.remainingMin)
        assertEquals("곧", r.spokenTime)
    }

    // ── TC_FCS_005 취약 시간대 ──

    @Test fun `12시30분 출발, 14시 취약 진입이 더 이르면 14시`() {
        val r = engineAt(12, 30).calculate(input(), ForecastRecalcTrigger.TRIP_START)
        assertEquals(at(14), r.expectedDrowsyAtMs)
        assertEquals(90, r.remainingMin)
        assertTrue(ForecastAdjustment.VULNERABLE_EARLIER in r.adjustments)
    }

    @Test fun `21시 출발이면 취약 시간대 없음, 23시`() {
        val r = engineAt(21).calculate(input(), ForecastRecalcTrigger.TRIP_START)
        assertEquals(at(23), r.expectedDrowsyAtMs)
        assertFalse(ForecastAdjustment.VULNERABLE_EARLIER in r.adjustments)
    }

    @Test fun `이미 취약 시간대 14시30분이면 최소 30분`() {
        val r = engineAt(14, 30).calculate(input(), ForecastRecalcTrigger.TRIP_START)
        assertEquals(30, r.remainingMin)
        assertTrue(ForecastAdjustment.VULNERABLE_FLOOR in r.adjustments)
    }

    @Test fun `이미 취약 시간대여도 원래 예상이 더 빠르면 그대로`() {
        val r = engineAt(14, 30).calculate(input(continuousDrivingMin = 110), ForecastRecalcTrigger.DRIVING_RESUMED)
        assertEquals(10, r.remainingMin)
    }

    @Test fun `30·40대 설정이면 22시 진입, 자정 넘는 구간도 인식`() {
        val entering = engineAt(21, 30).calculate(input(ageBand = AgeBand.THIRTIES_FORTIES), ForecastRecalcTrigger.TRIP_START)
        assertEquals(at(22), entering.expectedDrowsyAtMs)

        val inside = engineAt(1).calculate(input(ageBand = AgeBand.THIRTIES_FORTIES), ForecastRecalcTrigger.TRIP_START)
        assertEquals(30, inside.remainingMin)
    }

    @Test fun `취약 시간대 끝 시각은 포함하지 않음`() {
        // 17:00 출발 → 14~17 구간 밖, 다음 취약 시간대는 02시라 120분 그대로
        assertEquals(120, engineAt(17).calculate(input(), ForecastRecalcTrigger.TRIP_START).remainingMin)
    }

    // ── TC_FCS_006 말투 ──

    @Test fun `1시간 미만은 10분 단위 n분쯤 뒤`() {
        assertEquals("40분쯤 뒤", calcAt20(input(continuousDrivingMin = 80)).spokenTime)
        assertEquals("50분쯤 뒤", calcAt20(input(continuousDrivingMin = 66)).spokenTime) // 54분
    }

    @Test fun `59분은 60분으로 반올림돼 시각으로 말함`() {
        val r = calcAt20(input(continuousDrivingMin = 61)) // 59분 뒤 = 20:59
        assertEquals("밤 9시쯤", r.spokenTime)
    }

    @Test fun `1시간 이상은 30분 단위 시각`() {
        assertEquals("밤 11시쯤", engineAt(20, 50).calculate(input(), ForecastRecalcTrigger.TRIP_START).spokenTime) // 22:50
        assertEquals("밤 10시 반쯤", engineAt(20, 20).calculate(input(), ForecastRecalcTrigger.TRIP_START).spokenTime) // 22:20
        assertEquals("오후 2시쯤", engineAt(12, 30).calculate(input(), ForecastRecalcTrigger.TRIP_START).spokenTime) // 14:00
    }

    @Test fun `자정은 밤 12시`() {
        assertEquals("밤 12시쯤", engineAt(22).calculate(input(), ForecastRecalcTrigger.TRIP_START).spokenTime)
    }

    // ── TC_FCS_008 목적지 없음 ──

    @Test fun `목적지 없으면 대체 경로로 예보 산출`() {
        val r = calcAt20(input(remainingTimeToDestinationMin = null))
        assertEquals(120, r.remainingMin)
        assertTrue(r.usedFallbackWithoutDestination)
        assertNull(r.arrivesBeforeExpected)
    }

    @Test fun `목적지 도착이 예상보다 빠르면 표시`() {
        assertEquals(true, calcAt20(input(remainingTimeToDestinationMin = 60)).arrivesBeforeExpected)
        assertEquals(false, calcAt20(input(remainingTimeToDestinationMin = 260)).arrivesBeforeExpected)
    }

    // ── REQ_PRD_001 시나리오 1 (E2E 기준) ──

    @Test fun `21시 출발, 남은 4시간20분, 누적 40분, 기록 3회 미만이면 밤 11시쯤`() {
        val r = engineAt(21).calculate(
            input(remainingTimeToDestinationMin = 260, last24hCumulativeDrivingMin = 40, onsetHistoryMin = listOf(90, 100)),
            ForecastRecalcTrigger.DESTINATION_SET,
        )
        assertEquals("밤 11시쯤 졸릴 수 있어요.", r.ttsMessage)
        assertEquals(ForecastRecalcTrigger.DESTINATION_SET, r.trigger)
    }
}
