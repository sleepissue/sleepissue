package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.InvalidReason
import com.sleepissue.awakedrive.engine.model.SteeringBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * FatigueEngine 전체 흐름 시험: 게이트 → 지표 → f값 → B·K·R → 3분 지수평균.
 * 기대값은 SRS v0.2 REQ_FAT_012·019·020, REQ_AST_004 합격기준.
 */
class FatigueEngineTest {

    private val minute = 60_000L

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
    fun baselineDecidesWhatCountsAsTooFewCorrections() {
        // 같은 운전(미세 수정 분당 약 59회)도 기준선이 분당 240회인 사람에게는 크게 줄어든 것 → f미세 1, B = 35 (REQ_FAT_009)
        val engine = FatigueEngine(initialBaseline = SteeringBaseline(microPerMin = 240f, largePerMin = 4f))
        val first = engine.drive(0L, 70_000L, steering = ::awakeDeg).single()
        assertEquals(1f, first.fMicro, 0f)
        assertEquals(35f, first.behaviorB, 1e-3f)

        // 기준선을 분당 20회로 바꾸면 다음 갱신부터 f미세 0
        engine.baseline = SteeringBaseline(microPerMin = 20f, largePerMin = 4f)
        val next = engine.drive(70_100L, 100_000L, steering = ::awakeDeg).single()
        assertEquals(0f, next.fMicro, 0f)
        assertEquals(0f, next.behaviorB, 0f)
    }

    @Test
    fun resetStartsOverFromZero() {
        // 새 운행: 첫 갱신은 다시 60초 뒤, 평활값은 0부터
        val engine = FatigueEngine()
        assertTrue(engine.drive(0L, 310_000L, steering = ::drowsyDeg).last().riskSmoothed > 0f)
        engine.reset()
        val restarted = engine.drive(1_000_000L, 1_070_000L, steering = ::awakeDeg)
        assertEquals(listOf(1_060_000L), restarted.map { it.tMs })
        assertEquals(0f, restarted.single().riskSmoothed, 0f)
    }
}
