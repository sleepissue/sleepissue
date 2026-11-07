package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.rest.RestTestFixtures.north
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

// 기획서 7-3 보상 대상·악용 방지 규칙.
class RewardRulesTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val rules = RewardRules(zone = zone)
    private val hour = 3_600_000L

    private fun at(h: Int, m: Int = 0, day: Int = 6) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun eval(
        wallMs: Long,
        drive: Long? = 7_200,
        stage: InterventionStage = InterventionStage.NORMAL,
        areaId: String = "X",
        atM: Double = 0.0,
        history: List<RewardHistoryEntry> = emptyList(),
    ) = rules.evaluate(areaId, north(atM), wallMs, drive, stage, history)

    private fun awarded(areaId: String, wallMs: Long, atM: Double) = RewardHistoryEntry(areaId, north(atM), wallMs, rewarded = true)

    @Test
    fun `2시간 운전 뒤 휴식은 보상, 1시간 59분은 대상 아님`() {
        assertTrue(eval(at(10), drive = 7_200).awarded)
        val d = eval(at(10), drive = 7_199)
        assertEquals(RewardOutcome.NOT_ELIGIBLE, d.outcome)
        assertFalse(d.eligibleByDrive)
    }

    @Test
    fun `2단계 이상 알림 뒤 휴식은 운전 시간이 짧아도 보상, 1단계는 아님`() {
        assertTrue(eval(at(10), drive = 600, stage = InterventionStage.CHECK).awarded)
        assertEquals(RewardOutcome.NOT_ELIGIBLE, eval(at(10), drive = 600, stage = InterventionStage.CAUTION).outcome)
        assertEquals(RewardOutcome.NOT_ELIGIBLE, eval(at(10), drive = null).outcome)
    }

    @Test
    fun `하루 최대 4회, 다음 날은 다시`() {
        val four = (0 until 4).map { awarded("A$it", at(6 + it * 3), it * 40_000.0) }
        assertEquals(RewardOutcome.DAILY_LIMIT, eval(at(20), atM = 200_000.0, history = four).outcome)
        assertTrue(eval(at(1, day = 7), atM = 200_000.0, history = four).awarded)
    }

    @Test
    fun `같은 쉼터는 2시간 안에 다시 인정하지 않음`() {
        val history = listOf(awarded("X", at(10), 0.0))
        assertEquals(RewardOutcome.SAME_AREA_COOLDOWN, eval(at(11, 59), history = history).outcome)
    }

    @Test
    fun `직전 인정 쉼터에서 30 km 안이면 같은 날에는 인정하지 않음`() {
        val history = listOf(awarded("A", at(8), 0.0))
        assertEquals(RewardOutcome.TOO_CLOSE_TO_LAST, eval(at(12), atM = 29_000.0, history = history).outcome)
        assertTrue(eval(at(12), atM = 31_000.0, history = history).awarded)
        // 다음 날 같은 쉼터는 인정 [가정]
        assertTrue(eval(at(12, day = 7), areaId = "A", atM = 0.0, history = history).awarded)
    }

    @Test
    fun `보상 없이 인증만 된 기록은 악용 방지 횟수에 세지 않음`() {
        val history = (0 until 4).map { RewardHistoryEntry("A$it", north(it * 40_000.0), at(6 + it), rewarded = false) }
        assertTrue(eval(at(20), atM = 200_000.0, history = history).awarded)
    }

    @Test
    fun `직전 인정이 2시간 넘었어도 같은 쉼터 30 km 규칙은 같은 날이면 적용`() {
        val history = listOf(awarded("X", at(8), 0.0))
        assertEquals(RewardOutcome.TOO_CLOSE_TO_LAST, eval(at(8) + 3 * hour, history = history).outcome)
    }
}
