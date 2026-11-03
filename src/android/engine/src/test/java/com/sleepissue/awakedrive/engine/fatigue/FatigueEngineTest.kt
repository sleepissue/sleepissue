package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.model.BaselineRecord
import com.sleepissue.awakedrive.engine.model.BaselineSource
import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.InvalidReason
import com.sleepissue.awakedrive.engine.model.PatternState
import com.sleepissue.awakedrive.engine.model.PatternType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * FatigueEngine 전체 흐름 시험: 게이트 → 지표 → 기준선 → f값 → B·K·R → 3분 지수평균.
 * 기대값은 SRS v0.2 REQ_FAT_012·019·020, REQ_BSL_004~007, REQ_AST_004, REQ_RES_011 합격기준.
 */
class FatigueEngineTest {

    private val minute = 60_000L

    /** 깨어 있던 운행 기록 3건 → 평소의 나가 생긴다 (REQ_BSL_003) */
    private fun awakeTrips(microPerMin: Float, largePerMin: Float = 4f) = (1L..3L).map {
        BaselineRecord(tripId = it, microPerMin = microPerMin, largePerMin = largePerMin, validAwake = true)
    }

    /** 맥락 가중 5개가 모두 켜진 맥락: K = 1.35 */
    private val riskyContext = ContextInput(
        continuousDriveMs = 150 * minute,
        last24hDriveMs = 300 * minute,
        minuteOfDay = 3 * 60,
        passengerCount = 0,
        cabinTempC = 26f,
    )

    /** 기획서 시나리오 1의 23:10 맥락: 연속 2시간 10분 + 혼자 → K = 1.15 */
    private val scenarioOneContext = ContextInput(
        continuousDriveMs = 130 * minute,
        minuteOfDay = 23 * 60 + 10,
        passengerCount = 0,
    )

    /** 깨어 있는 운전: ±1° 삼각파, 1초마다 방향 전환 → 2° 미세 수정 분당 약 59회, 큰 수정 0 */
    private fun awakeDeg(tMs: Long) = triangleDeg(tMs, halfPeriodMs = 1_000L, amplitudeDeg = 1.0)

    /** 졸린 운전: ±3° 삼각파, 6초마다 방향 전환 → 6° 큰 수정 분당 약 9회, 미세 수정 0 */
    private fun drowsyDeg(tMs: Long) = triangleDeg(tMs, halfPeriodMs = 6_000L, amplitudeDeg = 3.0)

    /**
     * 딴짓: [startsMs]마다 35초 동안 운전대를 그대로 두다가(휴대폰 보기) 0.4초 만에 7° 꺾는다(멈춤 뒤 급조향).
     * 그 밖에는 깨어 있는 운전. 급조향은 시작 35.4초 뒤에 확인된다.
     */
    private fun distractedDeg(tMs: Long, vararg startsMs: Long): Float {
        val start = startsMs.firstOrNull { tMs in it until it + 35_500L } ?: return awakeDeg(tMs)
        val held = awakeDeg(start)
        val sinceHold = tMs - start - 35_000L
        return if (sinceHold < 0) held else held + 7f * minOf(sinceHold + 100L, 500L) / 500f
    }

    /** 고속도로 직선 주행 프레임: D단, 방향지시등·브레이크 없음, 다음 회전 지점 1 km */
    private fun frame(tMs: Long, steeringDeg: Float, speedKph: Float = 80f, assist: Boolean = false) =
        GateInput(
            tMs = tMs,
            steeringDeg = steeringDeg,
            speedKph = speedKph,
            inDrive = true,
            turnSignalOn = false,
            brakeOn = false,
            distToNextTurnM = 1_000f,
            assistMode = assist,
        )

    /** [fromMs]~[toMs]를 100 ms마다 넣고 나온 FatigueState를 모은다 */
    private fun FatigueEngine.drive(
        fromMs: Long,
        toMs: Long,
        speedKph: Float = 80f,
        assist: (Long) -> Boolean = { false },
        steering: (Long) -> Float,
    ): List<FatigueState> = (fromMs..toMs step 100L).flatMap { t ->
        push(frame(t, steering(t), speedKph, assist(t)))
    }

    @Test
    fun emitsEveryThirtySecondsTenSecondsBehindInput() {
        // 60초 창, 30초 갱신 (REQ_FAT_005, MSG-02 주기 30000 ms).
        // 게이트가 10초 늦게 확정하므로 60초 시점 값은 70초 프레임을 넣을 때 나온다
        val engine = FatigueEngine()
        val emitted = ArrayList<Pair<Long, Long>>() // (넣은 프레임 시각, FatigueState.tMs)
        for (t in 0L..305_000L step 100L) {
            engine.push(frame(t, awakeDeg(t))).forEach { emitted += t to it.tMs }
        }
        assertEquals((60_000L..270_000L step 30_000L).toList(), emitted.map { it.second })
        assertTrue(emitted.all { (frameMs, stateMs) -> frameMs - stateMs == 10_000L })
        // 운행이 끝나 flush하면 아직 확정하지 않은 300초 갱신까지 나온다
        assertEquals(listOf(300_000L), engine.flush().map { it.tMs })
    }

    @Test
    fun awakeSteeringScoresZeroEvenInRiskyContext() {
        // 조향이 기준선(미세 20/분, 큰 4/분)보다 나쁘지 않으면 B=0 → K=1.35여도 R=0 (REQ_FAT_019)
        val engine = FatigueEngine().apply { context = riskyContext }
        val states = engine.drive(0L, 310_000L, steering = ::awakeDeg)
        assertEquals(9, states.size)
        for (s in states) {
            assertTrue(s.valid)
            assertEquals(InvalidReason.NONE, s.invalidReason)
            assertEquals(59f, s.microPerMin, 1f)
            assertEquals(1.35f, s.contextK, 1e-6f)
            assertEquals(0f, s.behaviorB, 0f)
            assertEquals(0f, s.riskR, 0f)
            assertEquals(0f, s.riskSmoothed, 0f)
        }
        assertEquals("이상 분 없음", PatternState(), engine.patternState)
    }

    @Test
    fun gateFailingInputGivesZeroRiskInRiskyContext() {
        // 게이트 불통과 입력(시속 50 km라 조향 지표 없음) + K=1.35 → R=0 (TC_FAT_019)
        val engine = FatigueEngine().apply { context = riskyContext }
        val states = engine.drive(0L, 310_000L, speedKph = 50f, steering = ::drowsyDeg)
        assertEquals(9, states.size)
        for (s in states) {
            assertFalse(s.valid)
            assertEquals(InvalidReason.GATE_WAIT, s.invalidReason)
            assertEquals(1.35f, s.contextK, 1e-6f)
            assertEquals(0f, s.behaviorB, 0f)
            assertEquals(0f, s.riskR, 0f)
            assertEquals(0f, s.riskSmoothed, 0f)
        }
    }

    @Test
    fun drowsySteeringRaisesRiskThroughThreeMinuteAverage() {
        // 미세 수정 0(기준 20) → f미세 1, 큰 수정 9/분(기준 4) → f큰 1, 급조향 0 → B = 100 × (0.35 + 0.25) = 60.
        // 시나리오 1 맥락 K = 1.15 → R = 69. 평활값은 69 × (1 − e^(−n/6))로 올라감 (REQ_FAT_012·020)
        val engine = FatigueEngine().apply { context = scenarioOneContext }
        val states = engine.drive(0L, 310_000L, steering = ::drowsyDeg)
        assertEquals(9, states.size)
        states.forEachIndexed { i, s ->
            assertTrue(s.valid)
            assertEquals(9f, s.largePerMin, 0.5f)
            assertEquals(1f, s.fMicro, 0f)
            assertEquals(1f, s.fLarge, 0f)
            assertEquals(0f, s.fSudden, 0f)
            assertEquals(60f, s.behaviorB, 1e-3f)
            assertEquals(1.15f, s.contextK, 1e-6f)
            assertEquals(69f, s.riskR, 1e-3f)
            val expected = 69.0 * (1.0 - exp(-(i + 1) / 6.0))
            assertEquals("${i + 1}번째 갱신", expected, s.riskSmoothed.toDouble(), 0.01)
        }
    }

    @Test
    fun assistModeKeepsSmoothedScoreUnchanged() {
        // 모드 ON 300 s 동안 FatigueState.valid=false, 위험 점수 갱신 0회 (REQ_AST_004)
        val assistStartMs = 180_000L
        val assistEndMs = 480_000L
        val engine = FatigueEngine()
        val states = engine.drive(0L, 610_000L, assist = { it in assistStartMs until assistEndMs }, steering = ::drowsyDeg)

        val before = states.last { it.tMs < assistStartMs }
        assertTrue(before.valid)
        assertTrue(before.riskSmoothed > 0f)

        val during = states.filter { it.tMs in assistStartMs until assistEndMs }
        assertEquals(10, during.size)
        for (s in during) {
            assertFalse(s.valid)
            assertEquals(InvalidReason.ASSIST, s.invalidReason)
            assertEquals(0f, s.riskR, 0f)
            assertEquals(before.riskSmoothed, s.riskSmoothed, 0f)
        }

        // 끝난 직후에는 창에 게이트 통과가 30초 쌓일 때까지 측정 대기, 그다음부터 다시 갱신
        val after = states.filter { it.tMs >= assistEndMs }
        assertEquals(InvalidReason.GATE_WAIT, after.first().invalidReason)
        assertTrue(after.drop(1).all { it.valid })
        assertTrue(after.last().riskSmoothed > before.riskSmoothed)
    }

    @Test
    fun usualBaselineDecidesWhatCountsAsTooFewCorrections() {
        // 같은 운전(미세 수정 분당 약 59회)도 평소 분당 240회인 사람에게는 크게 줄어든 것 → f미세 1, B = 35 (REQ_FAT_009)
        val engine = FatigueEngine()
        engine.baselineStore.loadHistory(awakeTrips(microPerMin = 240f))
        assertEquals(BaselineSource.USUAL, engine.baselineState.source)
        val first = engine.drive(0L, 70_000L, steering = ::awakeDeg).single()
        assertEquals(1f, first.fMicro, 0f)
        assertEquals(35f, first.behaviorB, 1e-3f)

        // 평소가 분당 20회인 기록으로 바꾸면 다음 갱신부터 f미세 0
        engine.baselineStore.loadHistory(awakeTrips(microPerMin = 20f))
        val next = engine.drive(70_100L, 100_000L, steering = ::awakeDeg).single()
        assertEquals(0f, next.fMicro, 0f)
        assertEquals(0f, next.behaviorB, 0f)
    }

    @Test
    fun todayBaselineIsConfirmedAtFifteenGatedMinutes() {
        // 유효 누적 14 min 59.9 s → 미확정(기본값 사용), 15 min 0 s → 확정 (REQ_BSL_004)
        // 처음부터 게이트를 통과하므로 900 s 판정이 확정되는 910 s 프레임에서 오늘의 나가 생긴다
        val engine = FatigueEngine()
        engine.drive(0L, 909_900L, steering = ::awakeDeg)
        assertNull(engine.baselineState.today)
        assertEquals(BaselineSource.DEFAULT, engine.baselineState.source)

        engine.drive(910_000L, 910_000L, steering = ::awakeDeg)
        val state = engine.baselineState
        val today = state.today
        assertNotNull(today)
        assertEquals(60f, today!!.microPerMin, 1f)
        assertEquals(0f, today.largePerMin, 0f)
        assertEquals(BaselineSource.TODAY, state.source)
        assertFalse(state.departureFatigue)
        assertFalse(state.sensitive)
    }

    @Test
    fun drowsyFirstFifteenMinutesMeansDepartureFatigue() {
        // 처음 15분이 미세 수정 0(기본값 20의 −100%), 큰 수정 분당 약 10회(기본값 4의 2.5배) → 조건 2개 → 출발 피로 (REQ_BSL_005)
        // 민감 모드가 되고 비교 기준은 기본값으로 고정, 첫 음성 확인은 15분 뒤 (REQ_BSL_006, REQ_DMO_006)
        val engine = FatigueEngine()
        val turnedOnAt = ArrayList<Long>()
        var before = false
        val states = (0L..1_200_000L step 100L).flatMap { t ->
            val out = engine.push(frame(t, drowsyDeg(t)))
            val now = engine.baselineState.departureFatigue
            if (now && !before) turnedOnAt += t
            before = now
            out
        }
        assertEquals("출발 피로 판정은 한 번 (예보 재계산 1회)", listOf(910_000L), turnedOnAt)
        val state = engine.baselineState
        assertTrue(state.sensitive)
        assertEquals(BaselineSource.DEFAULT, state.source)
        assertEquals(910_000L, state.sensitiveSinceMs)
        assertEquals(910_000L + 15 * minute, state.firstCheckDueMs)

        // 졸린 15분을 오늘의 나로 삼았다면 B가 0이 됐을 것. 기본값으로 고정했으므로 판정 뒤에도 B = 60
        val afterJudgement = states.filter { it.tMs > 900_000L }
        assertTrue(afterJudgement.isNotEmpty())
        for (s in afterJudgement) assertEquals(60f, s.behaviorB, 1e-3f)
    }

    @Test
    fun sleepyConditionCountsFirstCheckFromDeparture() {
        // '졸려요' → 바로 민감 모드 (REQ_BSL_007). 첫 음성 확인은 출발(D단)부터 15분 뒤 [해석]
        val engine = FatigueEngine()
        engine.baselineStore.reportCondition(sleepy = true, tMs = 0L)
        assertTrue(engine.baselineState.sensitive)
        assertNull("아직 출발 전", engine.baselineState.firstCheckDueMs)

        engine.drive(60_000L, 70_000L, steering = ::awakeDeg)
        assertEquals(60_000L + 15 * minute, engine.baselineState.firstCheckDueMs)
    }

    @Test
    fun restResetsOnlyTheSmoothedScore() {
        // 휴식 인증 뒤: 위험 점수 평활값은 0부터 다시, 오늘의 나는 유지 (REQ_RES_011)
        val engine = FatigueEngine()
        engine.drive(0L, 910_000L, steering = ::awakeDeg)
        val today = engine.baselineState.today
        assertNotNull(today)
        assertTrue(engine.drive(910_100L, 1_100_000L, steering = ::drowsyDeg).last().riskSmoothed > 0f)

        engine.resetRiskAfterRest()
        val next = engine.drive(1_100_100L, 1_130_000L, steering = ::drowsyDeg).single()
        assertTrue(next.valid)
        assertEquals("0에서 한 번 섞은 값 = R × α", next.riskR * 0.1535f, next.riskSmoothed, 0.01f)
        assertEquals(today, engine.baselineState.today)
        assertEquals(BaselineSource.TODAY, engine.baselineState.source)
    }

    @Test
    fun drowsySteeringBecomesTrendAfterSixAbnormalMinutes() {
        // 미세 수정 0(기준 20의 70% 이하) + 큰 수정 분당 9회(기준 4의 1.5배 이상) → 매분 이상 분 (REQ_PAT_001)
        // 6분째(360 s 창, 370 s 프레임에서 확정)에 추세형 (REQ_PAT_002). 그 전에는 복귀 없이 이어져 애매
        val engine = FatigueEngine()
        engine.drive(0L, 369_900L, steering = ::drowsyDeg)
        assertEquals(5, engine.patternState.abnormalMinutes10m)
        assertEquals(PatternType.AMBIGUOUS, engine.patternState.patternType)

        engine.drive(370_000L, 370_000L, steering = ::drowsyDeg)
        assertEquals(6, engine.patternState.abnormalMinutes10m)
        assertEquals(PatternType.TREND, engine.patternState.patternType)
        assertFalse(engine.patternState.safetyBypass)
    }

    @Test
    fun oneDistractionBecomesSpikeOnceSteeringRecovers() {
        // 평소 미세 60/분인 운전자가 35초 동안 운전대를 그대로 두다가 급조향 1회 → 그 1분은 이상 분 (REQ_PAT_001)
        // 다음 1분에 평소대로 돌아오면 튐형(딴짓 쪽) (REQ_PAT_003). 복귀를 확인하기 전 1분 동안은 애매
        val engine = FatigueEngine()
        engine.baselineStore.loadHistory(awakeTrips(microPerMin = 60f))
        engine.drive(0L, 370_000L) { distractedDeg(it, 310_000L) }
        assertEquals(1, engine.patternState.abnormalMinutes10m)
        assertEquals(PatternType.AMBIGUOUS, engine.patternState.patternType)

        engine.drive(370_100L, 430_000L) { distractedDeg(it, 310_000L) }
        assertEquals(1, engine.patternState.abnormalMinutes10m)
        assertEquals(PatternType.SPIKE, engine.patternState.patternType)
    }

    @Test
    fun threeSuddenSteersWithinFiveMinutesTriggerSafetyBypass() {
        // 급조향 105.4 s·205.4 s·305.4 s → 3회째에서 안전 우회 신호 1건 (REQ_PAT_005).
        // 게이트가 10초 늦게 확정하므로 315.4 s 프레임에서 켜지고, 5분 동안 유지한다 [제안값]
        val engine = FatigueEngine()
        val starts = longArrayOf(70_000L, 170_000L, 270_000L)
        val turnedOnAt = ArrayList<Long>()
        var before = false
        for (t in 0L..640_000L step 100L) {
            engine.push(frame(t, distractedDeg(t, *starts)))
            val now = engine.patternState.safetyBypass
            if (now && !before) turnedOnAt += t
            before = now
        }
        assertEquals(listOf(315_400L), turnedOnAt)
        assertFalse("605.4 s에 유지가 끝나 630 s 갱신에서 꺼짐", engine.patternState.safetyBypass)
    }

    @Test
    fun resetStartsANewTrip() {
        // 새 운행: 첫 갱신은 다시 60초 뒤, 평활값 0부터, 출발 전 컨디션도 지움 (REQ_TRP_008)
        val engine = FatigueEngine()
        engine.baselineStore.reportCondition(sleepy = true, tMs = 0L)
        assertTrue(engine.drive(0L, 310_000L, steering = ::drowsyDeg).last().riskSmoothed > 0f)

        engine.reset()
        assertFalse(engine.baselineState.sensitive)
        val restarted = engine.drive(1_000_000L, 1_070_000L, steering = ::awakeDeg)
        assertEquals(listOf(1_060_000L), restarted.map { it.tMs })
        assertEquals(0f, restarted.single().riskSmoothed, 0f)
    }
}
