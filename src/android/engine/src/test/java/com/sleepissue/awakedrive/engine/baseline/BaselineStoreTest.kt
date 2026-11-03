package com.sleepissue.awakedrive.engine.baseline

import com.sleepissue.awakedrive.engine.model.BaselineRecord
import com.sleepissue.awakedrive.engine.model.BaselineSource
import com.sleepissue.awakedrive.engine.model.SteeringBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

/** 기준선·출발 피로·민감 모드 시험. 기대값은 SRS v0.2 REQ_BSL_001·003~007 합격기준. */
class BaselineStoreTest {

    private val minute = 60_000L

    /** 기록 3회 전 기본값 (BaselineConfig 기본) */
    private val default = SteeringBaseline(microPerMin = 20f, largePerMin = 4f)

    /** 깨어 있던 운행 기록 */
    private fun awake(tripId: Long, micro: Float, large: Float = 4f) =
        BaselineRecord(tripId = tripId, microPerMin = micro, largePerMin = large, validAwake = true)

    /** 유효 구간 15분 동안 분당 [microPerMin]·[largePerMin]회였던 것으로 오늘의 나를 확정한다 */
    private fun BaselineStore.confirmToday(nowMs: Long, microPerMin: Float, largePerMin: Float, sudden: Int = 0) =
        updateToday(
            nowMs = nowMs,
            gatedMs = 15 * minute,
            microCount = (microPerMin * 15).roundToInt(),
            largeCount = (largePerMin * 15).roundToInt(),
            suddenCount = sudden,
        )

    private fun assertBaseline(expected: SteeringBaseline, actual: SteeringBaseline?) {
        assertNotNull(actual)
        assertEquals(expected.microPerMin, actual!!.microPerMin, 1e-4f)
        assertEquals(expected.largePerMin, actual.largePerMin, 1e-4f)
    }

    @Test
    fun usesDefaultUntilThreeAwakeTripsThenUsual() {
        // 운행 0·1·2회 → 기본값, 3회 → 평소의 나 (REQ_BSL_003)
        val trips = listOf(awake(1, 30f, 2f), awake(2, 50f, 4f), awake(3, 40f, 3f))
        for (n in 0..2) {
            val store = BaselineStore().apply { loadHistory(trips.take(n)) }
            assertEquals("운행 ${n}회", BaselineSource.DEFAULT, store.state.source)
            assertEquals("운행 ${n}회", default, store.state.reference)
        }
        val store = BaselineStore().apply { loadHistory(trips) }
        assertEquals(BaselineSource.USUAL, store.state.source)
        assertEquals(SteeringBaseline(40f, 3f), store.state.reference)
    }

    @Test
    fun usualIsMedianOfLastTenAwakeTrips() {
        // 운행 12회 입력 시 최근 10회만 사용, 중앙값 오차 0, 깨어 있던 구간이 없는 운행은 제외 (REQ_BSL_001)
        // BSL_001은 2차 범위지만 BSL_003의 '3회 → 평소의 나'에 필요해 중앙값까지는 지금 만든다
        val trips = (1L..12L).map { awake(it, micro = it * 10f, large = it.toFloat()) } +
            BaselineRecord(tripId = 13, microPerMin = 999f, largePerMin = 999f, validAwake = false)
        // 최근 10회 = 3~12회 → 미세 30, 40, …, 120의 중앙값 75, 큰 3, …, 12의 중앙값 7.5. 넣는 순서와 상관없음
        assertEquals(SteeringBaseline(75f, 7.5f), BaselineStore.usualOf(trips.shuffled(Random(1))))
    }

    @Test
    fun todayIsConfirmedAtFifteenGatedMinutes() {
        // 유효 누적 14 min 59 s → 미확정(기본값·평소의 나 사용), 15 min 0 s → 확정 (REQ_BSL_004)
        val store = BaselineStore()
        store.updateToday(nowMs = 1_000_000L, gatedMs = 899_000L, microCount = 330, largeCount = 45, suddenCount = 0)
        assertNull(store.state.today)
        assertEquals(BaselineSource.DEFAULT, store.state.source)

        // 15분 동안 미세 330회·큰 45회 → 분당 22·3. 기본값(20·4)보다 나쁘지 않으므로 오늘의 나를 씀
        store.updateToday(nowMs = 1_001_000L, gatedMs = 900_000L, microCount = 330, largeCount = 45, suddenCount = 0)
        assertBaseline(SteeringBaseline(22f, 3f), store.state.today)
        assertEquals(BaselineSource.TODAY, store.state.source)
        assertBaseline(SteeringBaseline(22f, 3f), store.state.reference)

        // 한 번 확정하면 운행 중에는 바뀌지 않음
        store.updateToday(nowMs = 2_000_000L, gatedMs = 1_800_000L, microCount = 0, largeCount = 0, suddenCount = 5)
        assertBaseline(SteeringBaseline(22f, 3f), store.state.today)
        assertFalse(store.state.departureFatigue)
    }

    @Test
    fun departureFatigueNeedsTwoOfThreeCriteria() {
        // 미세 −30.0%·큰 1.50배 → 출발 피로(2개), 미세 −29.9%·큰 1.50배 → 아님(1개), 3개 충족 → 출발 피로 (REQ_BSL_005)
        // 기준: 기본값(미세 20, 큰 4). 미세 14.0 = −30.0%, 14.02 = −29.9%, 큰 6.0 = 1.50배, 5.96 = 1.49배
        fun count(micro: Float, large: Float, sudden: Int) =
            BaselineStore.departureCriteriaCount(SteeringBaseline(micro, large), sudden, default)
        assertEquals(2, count(14.0f, 6.0f, 0))
        assertEquals(1, count(14.02f, 6.0f, 0))
        assertEquals(3, count(14.0f, 6.0f, 1))
        assertEquals(1, count(20f, 5.96f, 1))
        assertTrue(BaselineStore.isDepartureFatigue(SteeringBaseline(14.0f, 6.0f), 0, default))
        assertFalse(BaselineStore.isDepartureFatigue(SteeringBaseline(14.02f, 6.0f), 0, default))
        assertTrue(BaselineStore.isDepartureFatigue(SteeringBaseline(14.0f, 6.0f), 1, default))
        assertFalse(BaselineStore.isDepartureFatigue(SteeringBaseline(20f, 5.96f), 1, default))
    }

    @Test
    fun slowSteeringSourceLeavesMicroOutOfDepartureFatigue() {
        // 처음 15분 동안 조향 원천이 5 Hz 미만이면 미세 수정 조건은 세지 않음 → 남은 두 조건을 모두 만족해야 출발 피로 [해석]
        fun count(micro: Float, large: Float, sudden: Int) =
            BaselineStore.departureCriteriaCount(SteeringBaseline(micro, large), sudden, default, microReliable = false)
        assertEquals(1, count(14.0f, 6.0f, 0))
        assertEquals(2, count(14.0f, 6.0f, 1))
        assertFalse(BaselineStore.isDepartureFatigue(SteeringBaseline(14.0f, 6.0f), 0, default, microReliable = false))
        assertTrue(BaselineStore.isDepartureFatigue(SteeringBaseline(14.0f, 6.0f), 1, default, microReliable = false))

        // 흐린 신호로 만든 오늘의 나는 비교 기준으로 쓰지 않음 (기본값·평소의 나 유지)
        val store = BaselineStore()
        store.updateToday(nowMs = 910_000L, gatedMs = 15 * minute, microCount = 330, largeCount = 45, suddenCount = 0,
            microReliable = false)
        assertNotNull(store.state.today)
        assertEquals(BaselineSource.DEFAULT, store.state.source)
    }

    @Test
    fun departureFatigueKeepsUsualBaselineAndSchedulesFirstCheck() {
        // 민감 모드 진입 후 기준선 소스 = 평소의 나(없으면 기본값), 첫 음성 확인 15 min (REQ_BSL_006)
        val store = BaselineStore().apply { loadHistory(listOf(awake(1, 20f), awake(2, 20f), awake(3, 20f))) }
        store.onDriveFrame(0L)
        store.confirmToday(nowMs = 910_000L, microPerMin = 12f, largePerMin = 7f) // 미세 −40%, 큰 1.75배
        val state = store.state
        assertTrue(state.departureFatigue)
        assertTrue(state.sensitive)
        assertEquals(BaselineSource.USUAL, state.source)
        assertEquals(SteeringBaseline(20f, 4f), state.reference)
        assertEquals(910_000L, state.sensitiveSinceMs)
        assertEquals(910_000L + 15 * minute, state.firstCheckDueMs)

        // 기록이 없으면 기본값으로 고정
        val fresh = BaselineStore().apply {
            onDriveFrame(0L)
            confirmToday(nowMs = 910_000L, microPerMin = 12f, largePerMin = 7f)
        }
        assertTrue(fresh.state.departureFatigue)
        assertEquals(BaselineSource.DEFAULT, fresh.state.source)
    }

    @Test
    fun sleepyConditionTurnsOnSensitiveModeAndKeepsBaselineFixed() {
        // '졸려요' → 민감 모드 ON, '개운해요'·'보통' → OFF (REQ_BSL_007)
        val store = BaselineStore()
        store.reportCondition(sleepy = true, tMs = 0L)
        assertTrue(store.state.sensitive)
        assertNull("출발 전이라 첫 확인 시각이 아직 없음", store.state.firstCheckDueMs)
        store.reportCondition(sleepy = false, tMs = 30_000L)
        assertFalse(store.state.sensitive)
        assertNull(store.state.sensitiveSinceMs)

        // 다시 '졸려요'를 고르고 2분에 출발 → 첫 음성 확인은 출발부터 15분 [해석]
        store.reportCondition(sleepy = true, tMs = 60_000L)
        store.onDriveFrame(120_000L)
        store.onDriveFrame(130_000L) // 첫 D단만 출발로 봄
        assertEquals(120_000L + 15 * minute, store.state.firstCheckDueMs)

        // 오늘의 나가 평소와 비슷하게 확정돼도 민감 모드라 기본값으로 고정 (기획서 5-1 "비교 기준은 평소의 나로 고정")
        store.confirmToday(nowMs = 1_030_000L, microPerMin = 22f, largePerMin = 3f)
        assertFalse(store.state.departureFatigue)
        assertEquals(BaselineSource.DEFAULT, store.state.source)
        assertEquals(120_000L + 15 * minute, store.state.firstCheckDueMs)
    }

    @Test
    fun newTripStartsTodayOverButKeepsHistory() {
        // 꺼져 있던 시간 2시간 초과 → 새 운행, 오늘의 나 초기화 (REQ_TRP_008). 평소의 나는 유지
        val store = BaselineStore().apply { loadHistory(listOf(awake(1, 30f), awake(2, 30f), awake(3, 30f))) }
        store.onDriveFrame(0L)
        store.confirmToday(nowMs = 910_000L, microPerMin = 15f, largePerMin = 7f) // 출발 피로
        assertTrue(store.state.sensitive)

        store.resetTrip()
        assertNull(store.state.today)
        assertFalse(store.state.departureFatigue)
        assertFalse(store.state.sensitive)
        assertNull(store.state.firstCheckDueMs)
        assertEquals(BaselineSource.USUAL, store.state.source)
        assertEquals(0L, store.todayGatedMs)
    }

    @Test
    fun usualHandoverCountIsMedianOfAwakeRecords() {
        // 평소 해제 직후 30초 큰 수정 횟수 (REQ_AST_007 '평소 해제 때'). 값이 있는 기록이 없으면 null → '3회 이상' 기준
        assertNull(BaselineStore.usualHandoverLargeCountOf(emptyList()))
        val trips = listOf(
            BaselineRecord(tripId = 1L, microPerMin = 20f, largePerMin = 4f, handoverLargeCount = 2, validAwake = true),
            BaselineRecord(tripId = 2L, microPerMin = 20f, largePerMin = 4f, handoverLargeCount = 4, validAwake = true),
            BaselineRecord(tripId = 3L, microPerMin = 20f, largePerMin = 4f, handoverLargeCount = null, validAwake = true),
            BaselineRecord(tripId = 4L, microPerMin = 20f, largePerMin = 4f, handoverLargeCount = 9, validAwake = false),
        )
        assertEquals(3f, BaselineStore.usualHandoverLargeCountOf(trips)!!, 0f)
        val store = BaselineStore().apply { loadHistory(trips) }
        assertEquals(3f, store.usualHandoverLargeCount!!, 0f)
    }

    @Test
    fun stateObjectStaysTheSameWhenNothingChanges() {
        // 값이 그대로면 같은 객체를 유지 → StateFlow에 넣어도 바뀔 때만 알림이 감
        val store = BaselineStore()
        val before = store.state
        store.updateToday(nowMs = 1_000L, gatedMs = 100L, microCount = 0, largeCount = 0, suddenCount = 0)
        store.loadHistory(emptyList())
        assertSame(before, store.state)
    }
}
