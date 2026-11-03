package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.model.AssistReason
import com.sleepissue.awakedrive.engine.model.BaselineRecord
import com.sleepissue.awakedrive.engine.model.BaselineSource
import com.sleepissue.awakedrive.engine.model.DriveFrame
import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.SteeringEvent
import com.sleepissue.awakedrive.engine.model.SteeringEventType
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
        continuousDriveSec = 150 * 60L,
        last24hDriveSec = 300 * 60L,
        minuteOfDay = 3 * 60,
        passengerCount = 0,
        cabinTempC = 26f,
    )

    /** 기획서 시나리오 1의 23:10 맥락: 연속 2시간 10분 + 혼자 → K = 1.15 */
    private val scenarioOneContext = ContextInput(
        continuousDriveSec = 130 * 60L,
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
        DriveFrame(
            tMs = tMs,
            steeringDeg = steeringDeg,
            speedKph = speedKph,
            inDrive = true,
            turnSignalOn = false,
            brakeOn = false,
            distToNextTurnM = 1_000f,
            cruiseActive = assist,
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
            assertTrue("원천 10 Hz면 미세 수정 사용 (REQ_SIG_009)", s.microEnabled)
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
    fun safetyBypassStillWorksDuringAssistMode() {
        // 보조 주행 중에도 5분 안 급조향 3회면 안전 우회 (기획서 5-4 "안전 우회는 그대로 씁니다", REQ_PAT_005)
        // 급조향 1회짜리 사건(2단계용)은 보조 주행 중에는 내지 않음 — 조향을 졸음 판단에서 뺌 (REQ_AST_004)
        val engine = FatigueEngine()
        val starts = longArrayOf(70_000L, 170_000L, 270_000L)
        val states = engine.drive(0L, 320_000L, assist = { true }) { distractedDeg(it, *starts) }
        assertEquals(AssistReason.CRUISE, engine.assistState.reason)
        assertTrue(states.all { it.invalidReason == InvalidReason.ASSIST })
        assertTrue(engine.patternState.safetyBypass)
        assertEquals(
            listOf(SteeringEvent(305_400L, SteeringEventType.REPEATED_RAPID_STEERING)),
            engine.drainEvents(),
        )
        assertTrue("꺼낸 사건은 지워짐", engine.drainEvents().isEmpty())
    }

    @Test
    fun suddenSteerWithinThirtySecondsAfterCruiseOffIsUnsafeHandover() {
        // 크루즈를 200 s에 끄고 29 s 뒤 급조향 → 인계 품질 이상 + 급조향 1회 (REQ_AST_007, REQ_INT_002)
        // 31 s 뒤면 인계 감시 밖이라 급조향 1회 사건만 (해제 전부터 운전대는 가만히 있었음)
        assertEquals(
            listOf(
                SteeringEvent(229_000L, SteeringEventType.RAPID_STEERING),
                SteeringEvent(229_000L, SteeringEventType.HANDOVER_UNSAFE),
            ),
            handoverEvents(jumpAfterReleaseMs = 28_600L),
        )
        assertEquals(
            listOf(SteeringEvent(231_000L, SteeringEventType.RAPID_STEERING)),
            handoverEvents(jumpAfterReleaseMs = 30_600L),
        )
    }

    /** 0~200 s 크루즈, 그 뒤 해제. 해제 [jumpAfterReleaseMs] 뒤부터 0.4초 만에 0° → 7° 꺾는다. 나온 사건을 돌려준다 */
    private fun handoverEvents(jumpAfterReleaseMs: Long): List<SteeringEvent> {
        val releaseMs = 200_000L
        val jumpMs = releaseMs + jumpAfterReleaseMs
        val engine = FatigueEngine()
        engine.drive(0L, 260_000L, assist = { it < releaseMs }) { t ->
            if (t < jumpMs) 0f else 7f * minOf(t - jumpMs + 100L, 500L) / 500f
        }
        assertFalse(engine.assistState.assistMode)
        return engine.drainEvents()
    }

    /** 원천 2 Hz 조향을 1번 Hub처럼 10 Hz로 다시 낸 값: 0.5초마다만 바뀌고 그 사이는 마지막 값 유지 */
    private fun heldAt2Hz(tMs: Long, steering: (Long) -> Float) = steering(tMs - tMs % 500L)

    @Test
    fun slowSteeringSourceTurnsMicroOffAndRenormalizesB() {
        // 원천 2 Hz(5 Hz 미만) → 미세 수정 지표 비활성, B는 큰 수정·급조향 가중치만 재정규화 (TC_SIG_009)
        // 졸린 조향: 큰 수정 분당 약 9회(기준 4의 2배 이상) → f큰 1 → B = 100 × 0.25 ÷ 0.65 = 38.46
        val engine = FatigueEngine()
        val states = (0L..310_000L step 100L).flatMap { t ->
            engine.push(frame(t, heldAt2Hz(t, ::drowsyDeg)).copy(steeringRateOk = false))
        }
        assertEquals(9, states.size)
        for (s in states) {
            assertTrue(s.valid)
            assertFalse(s.microEnabled)
            assertEquals(0f, s.fMicro, 0f)
            assertEquals(1f, s.fLarge, 0f)
            assertEquals(38.46f, s.behaviorB, 0.01f)
        }
    }

    @Test
    fun microComesBackOnceSlowFramesLeaveTheWindow() {
        // 0~99.9 s만 원천이 느림 → 그 프레임이 60초 창 안에 있는 동안만 미세 수정을 끈다 [해석]
        val engine = FatigueEngine()
        val states = (0L..250_000L step 100L).flatMap { t ->
            engine.push(frame(t, awakeDeg(t)).copy(steeringRateOk = t >= 100_000L))
        }.associateBy { it.tMs }
        assertFalse("창 (90, 150]에 99.9 s가 있음", states.getValue(150_000L).microEnabled)
        assertTrue("창 (120, 180]", states.getValue(180_000L).microEnabled)
    }

    @Test
    fun rateMeterWarmUpDoesNotSpoilDepartureFatigue() {
        // 1번 RateMeter는 시작 뒤 10초를 '측정 중'(→ 느림으로 넣음)으로 준다. 데모 '출발 피로'(REQ_DMO_006)가
        // 이 몇 초 때문에 미세 수정 조건을 잃으면 안 된다: 15분 중 1%만 느렸으므로 출발 피로 판정은 그대로 [제안값 10%]
        val engine = FatigueEngine()
        val states = (0L..910_000L step 100L).flatMap { t ->
            engine.push(frame(t, drowsyDeg(t)).copy(steeringRateOk = t >= 10_000L))
        }.associateBy { it.tMs }
        assertFalse("첫 창 (0, 60]에는 느린 프레임이 있음", states.getValue(60_000L).microEnabled)
        assertTrue("창 (30, 90]부터 미세 수정 사용", states.getValue(90_000L).microEnabled)
        assertTrue(engine.baselineState.departureFatigue)
        assertTrue(engine.baselineState.sensitive)
    }

    @Test
    fun slowFramesThatDoNotPassTheGateDoNotTurnMicroOff() {
        // 주차 중(시속 0)에 조향 원천이 느렸던 것은 지표와 상관없음 → 출발 뒤 창에서는 미세 수정을 씀 [해석]
        val engine = FatigueEngine()
        val states = (0L..160_000L step 100L).flatMap { t ->
            val parked = t < 100_000L
            engine.push(frame(t, awakeDeg(t), speedKph = if (parked) 0f else 80f).copy(steeringRateOk = !parked))
        }.associateBy { it.tMs }
        val window = states.getValue(150_000L)
        assertTrue(window.valid)
        assertTrue(window.microEnabled)
    }

    @Test
    fun slowSteeringSourceDoesNotMakeDrowsyStartTheNormal() {
        // 처음 15분이 원천 2 Hz: 미세 수정 조건을 빼고 보면 큰 수정만 늘었으므로 출발 피로는 아님 [해석].
        // 대신 흐린 신호로 만든 오늘의 나를 기준으로 쓰지 않아, 졸린 조향이 '정상'이 되지 않는다 (판정 뒤에도 B = 38.46)
        val engine = FatigueEngine()
        val states = (0L..1_000_000L step 100L).flatMap { t ->
            engine.push(frame(t, heldAt2Hz(t, ::drowsyDeg)).copy(steeringRateOk = false))
        }
        assertNotNull(engine.baselineState.today)
        assertFalse(engine.baselineState.departureFatigue)
        assertEquals(BaselineSource.DEFAULT, engine.baselineState.source)
        for (s in states.filter { it.tMs > 900_000L }) assertEquals(38.46f, s.behaviorB, 0.01f)
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
