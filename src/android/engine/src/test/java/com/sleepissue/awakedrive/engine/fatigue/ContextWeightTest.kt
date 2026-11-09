package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.ContextWeightConfig
import com.sleepissue.awakedrive.engine.model.AgeBand
import org.junit.Assert.assertEquals
import org.junit.Test

/** 맥락 가중 K 시험. 기대값은 SRS v0.2 REQ_FAT_013~018 합격기준. */
class ContextWeightTest {

    /** 1분(초 단위). 운전 시간은 초로 받는다 */
    private val minute = 60L

    private fun k(input: ContextInput) = ContextWeight.kOf(input)

    /** 시·분 → 자정부터 센 분 */
    private fun clock(hour: Int, min: Int) = hour * 60 + min

    /** 맥락 가중 5개가 모두 켜지는 입력 */
    private val allFive = ContextInput(
        continuousDriveSec = 120 * minute,
        last24hDriveSec = 240 * minute,
        minuteOfDay = clock(3, 0),
        passengerCount = 0,
        cabinTempC = 26f,
    )

    @Test
    fun kStartsAtOneAndIsCappedAtOnePointThreeFive() {
        // 가중 없음 → 1.00, 연속 2 h+혼자 → 1.15, 5개 전부 → 1.35, 합이 1.35 초과 시 1.35 (REQ_FAT_013)
        assertEquals(1.00f, k(ContextInput()), 1e-6f)
        assertEquals(1.15f, k(ContextInput(continuousDriveSec = 120 * minute, passengerCount = 0)), 1e-6f)
        assertEquals(1.35f, k(allFive), 1e-6f)
        // 연속 운전 가중을 0.30으로 바꾸면 합이 1.55가 되지만 1.35로 자른다
        val heavier = ContextWeightConfig(longDriveWeight = 0.30f)
        assertEquals(1.35f, ContextWeight.kOf(allFive, heavier), 1e-6f)
    }

    @Test
    fun longDriveCountsFromTwoHours() {
        // 연속 운전 119 min → +0, 120 min → +0.10 (REQ_FAT_014)
        assertEquals(1.00f, k(ContextInput(continuousDriveSec = 119 * minute)), 1e-6f)
        assertEquals(1.10f, k(ContextInput(continuousDriveSec = 120 * minute)), 1e-6f)
    }

    @Test
    fun vulnerableTimeIncludesStartAndExcludesEnd() {
        // 기본: 01:59 → +0, 02:00 → +0.10, 06:59 → +0.10, 07:00 → +0, 13:59 → +0, 14:00 → +0.10 (REQ_FAT_015)
        val cases = listOf(
            clock(1, 59) to 1.00f,
            clock(2, 0) to 1.10f,
            clock(6, 59) to 1.10f,
            clock(7, 0) to 1.00f,
            clock(13, 59) to 1.00f,
            clock(14, 0) to 1.10f,
        )
        for ((minuteOfDay, expected) in cases) {
            assertEquals("minuteOfDay=$minuteOfDay", expected, k(ContextInput(minuteOfDay = minuteOfDay)), 1e-6f)
        }
        // 30대 설정 시 22:00 → +0.10. 연령대를 설정하지 않으면 22:00은 취약 시간대가 아님
        assertEquals(1.10f, k(ContextInput(minuteOfDay = clock(22, 0), ageBand = AgeBand.THIRTIES_FORTIES)), 1e-6f)
        assertEquals(1.00f, k(ContextInput(minuteOfDay = clock(22, 0))), 1e-6f)
    }

    @Test
    fun ageBandRangesAddToBaseRanges() {
        // 연령대 설정 시: 20대 04~08시, 30·40대 22~02시, 50대 이상 14~18시를 기본 시간대에 더한다 (REQ_FAT_015)
        val cases = listOf(
            Triple(AgeBand.TWENTIES, clock(7, 30), 1.10f),
            Triple(AgeBand.TWENTIES, clock(8, 0), 1.00f),
            Triple(AgeBand.THIRTIES_FORTIES, clock(21, 59), 1.00f),
            Triple(AgeBand.THIRTIES_FORTIES, clock(1, 59), 1.10f),
            Triple(AgeBand.FIFTIES_PLUS, clock(17, 30), 1.10f),
            Triple(AgeBand.FIFTIES_PLUS, clock(18, 0), 1.00f),
        )
        for ((band, minuteOfDay, expected) in cases) {
            assertEquals("$band $minuteOfDay", expected, k(ContextInput(minuteOfDay = minuteOfDay, ageBand = band)), 1e-6f)
        }
        // 연령대를 설정하지 않으면(null) 07:30·17:30은 취약 시간대가 아님
        assertEquals(1.00f, k(ContextInput(minuteOfDay = clock(7, 30))), 1e-6f)
        assertEquals(1.00f, k(ContextInput(minuteOfDay = clock(17, 30))), 1e-6f)
    }

    @Test
    fun soloDriveNeedsKnownEmptySeats() {
        // 동승자 0명 → +0.05, 1명 이상 → +0, 착석 null → +0 (REQ_FAT_016)
        assertEquals(1.05f, k(ContextInput(passengerCount = 0)), 1e-6f)
        assertEquals(1.00f, k(ContextInput(passengerCount = 1)), 1e-6f)
        assertEquals(1.00f, k(ContextInput(passengerCount = 3)), 1e-6f)
        assertEquals(1.00f, k(ContextInput(passengerCount = null)), 1e-6f)
    }

    @Test
    fun warmCabinCountsFromTwentyFiveDegrees() {
        // 24.9 ℃ → +0, 25.0 ℃ → +0.05, null → +0 (REQ_FAT_017)
        assertEquals(1.00f, k(ContextInput(cabinTempC = 24.9f)), 1e-6f)
        assertEquals(1.05f, k(ContextInput(cabinTempC = 25.0f)), 1e-6f)
        assertEquals(1.00f, k(ContextInput(cabinTempC = null)), 1e-6f)
    }

    @Test
    fun longDayDriveCountsFromFourHours() {
        // 최근 24시간 누적 239 min → +0, 240 min → +0.05 (REQ_FAT_018)
        assertEquals(1.00f, k(ContextInput(last24hDriveSec = 239 * minute)), 1e-6f)
        assertEquals(1.05f, k(ContextInput(last24hDriveSec = 240 * minute)), 1e-6f)
    }

    @Test
    fun factorsShowWhichWeightsAreOn() {
        // 연속 2시간 10분, 오후 3시 30분, 동승자 1명 → 연속 운전·취약 시간대만 켜짐
        val factors = ContextWeight.factorsOf(
            ContextInput(continuousDriveSec = 130 * minute, minuteOfDay = clock(15, 30), passengerCount = 1),
        )
        assertEquals(ContextFactors(longDrive = true, vulnerableTime = true), factors)
    }

    @Test(expected = IllegalArgumentException::class)
    fun minuteOfDayMustBeWithinOneDay() {
        ContextInput(minuteOfDay = 24 * 60)
    }
}
