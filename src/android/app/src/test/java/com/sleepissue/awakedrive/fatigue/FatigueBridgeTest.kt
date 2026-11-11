package com.sleepissue.awakedrive.fatigue

import com.sleepissue.awakedrive.engine.model.AssistState
import com.sleepissue.awakedrive.engine.model.EvaluationStatus
import com.sleepissue.awakedrive.engine.model.ExecutionRequest
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.InterventionState
import com.sleepissue.awakedrive.engine.model.InvalidReason
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.PatternState
import com.sleepissue.awakedrive.engine.model.PatternType
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.SafetyEventType
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.SignalQuality
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.model.SteeringEvent
import com.sleepissue.awakedrive.engine.model.SteeringEventType
import com.sleepissue.awakedrive.engine.model.TurnSignal
import com.sleepissue.awakedrive.engine.signal.RateStatus
import com.sleepissue.awakedrive.engine.signal.SourceRate
import com.sleepissue.awakedrive.signal.DrivingPattern
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.signal.InterventionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 2번 엔진 출력 연결기 시험: 대응표(docs/fatigue-engine/08-team-handoff.md 4-1)와 1번 입력 보내기. */
class FatigueBridgeTest {

    private val zone = ZoneId.of("Asia/Seoul")

    private fun at(hour: Int, minute: Int, second: Int = 0): Long =
        ZonedDateTime.of(2026, 11, 7, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    /** 밤 11시 (취약 시간대 아님) */
    private val night = at(23, 0)

    /** 고속도로 직선 주행 프레임: D단, 시속 80 km, 조향 원천 10 Hz로 잼 */
    private fun frame(tMs: Long, deg: Float, cruise: Boolean = false, source: Source = Source.DEMO) = SignalFrame(
        tMs = tMs,
        steeringDeg = deg,
        speedKph = 80f,
        gear = Gear.D,
        turnSignal = TurnSignal.OFF,
        brakeOn = false,
        cruiseActive = cruise,
        quality = mapOf(
            SignalField.STEERING_DEG to SignalQuality(
                source = source,
                sourceRateInfo = SourceRate(status = RateStatus.MEASURED, belowMinimum = false),
            ),
        ),
    )

    /** 다음 회전 지점 1 km */
    private val navi = NaviState(tMs = 0L, distToNextTurnM = 1_000f)

    /** 깨어 있는 운전: ±1° 삼각파, 1초마다 방향 전환 */
    private fun awake(tMs: Long): Float {
        val phase = (tMs % 2_000L) / 1_000.0
        return (if (phase < 1.0) -1.0 + 2.0 * phase else 3.0 - 2.0 * phase).toFloat()
    }

    private fun FatigueBridge.drive(from: Long, to: Long, cruise: Boolean = false, deg: (Long) -> Float): List<InterventionInput> =
        (from..to step 100L).flatMap { onFrame(frame(it, deg(it), cruise), navi, trip = null) }

    @Test
    fun mappingFollowsTheAgreedTable() {
        val pattern = PatternState(patternType = PatternType.TREND)
        val assist = AssistState(assistMode = false, opGapSec = 42)
        val valid = FatigueState(tMs = 60_000L, riskSmoothed = 61f, valid = true, invalidReason = InvalidReason.NONE)
        valid.toDrivingAssessment(pattern, assist).let {
            assertEquals(60_000L, it.tMs)
            assertEquals(EvaluationStatus.EVALUATED, it.evaluationStatus)
            assertEquals(61f, it.riskScore!!, 0f)
            assertEquals(DrivingPattern.TRENDING, it.pattern)
            assertEquals(false, it.assistedDriving)
            assertEquals(42L, it.operationGapSec)
        }
        // 게이트 대기 → 아직 평가 안 함, 보조 주행 → 계산 불가. 둘 다 점수를 0으로 바꾸지 않고 null
        val waiting = FatigueState(tMs = 90_000L, riskSmoothed = 30f, valid = false, invalidReason = InvalidReason.GATE_WAIT)
        assertEquals(EvaluationStatus.NOT_EVALUATED, waiting.toDrivingAssessment(pattern, assist).evaluationStatus)
        assertNull(waiting.toDrivingAssessment(pattern, assist).riskScore)
        val assisted = waiting.copy(invalidReason = InvalidReason.ASSIST)
        assertEquals(EvaluationStatus.UNAVAILABLE, assisted.toDrivingAssessment(pattern, assist).evaluationStatus)
        assertNull(assisted.toDrivingAssessment(pattern, assist).riskScore)
        // 패턴
        assertEquals(DrivingPattern.TRANSIENT, PatternType.SPIKE.toDrivingPattern())
        assertEquals(DrivingPattern.UNCERTAIN, PatternType.AMBIGUOUS.toDrivingPattern())
        assertNull(PatternType.NONE.toDrivingPattern())
    }

    @Test
    fun steeringEventsBecomeSafetyEventsWithTheSameName() {
        for (type in SteeringEventType.values()) {
            val safety = SteeringEvent(1_234L, type).toSafetyEvent(Source.DEMO)
            assertEquals(type.name, safety.type.name)
            assertEquals(1_234L, safety.tMs)
            assertEquals(Source.DEMO, safety.source)
        }
        assertEquals(SteeringEventType.values().map { it.name }, SafetyEventType.values().map { it.name })
    }

    @Test
    fun firstAssessmentComesWithTheEngineUpdate() {
        // 엔진은 60초 창·30초 갱신이고 10초 늦게 확정한다 → 70초째 프레임에서 60초 시점 판단 하나
        val bridge = FatigueBridge()
        val inputs = bridge.drive(night, night + 70_000L, deg = ::awake)
        val assessments = inputs.filterIsInstance<InterventionInput.Assessment>().map { it.value }
        assertEquals(1, assessments.size)
        val first = assessments.single()
        assertEquals(night + 60_000L, first.tMs)
        assertEquals(EvaluationStatus.EVALUATED, first.evaluationStatus)
        assertEquals(0f, first.riskScore!!, 0f)
        assertNull("이상 분 없음", first.pattern)
        assertEquals(false, first.assistedDriving)
        // 밤 11시는 취약 시간대가 아님 → 처음 한 번만 알림
        assertEquals(listOf(false), inputs.filterIsInstance<InterventionInput.VulnerableTimeChanged>().map { it.enabled })
        assertNotNull(bridge.snapshot.fatigue)
        assertEquals(false, bridge.snapshot.vulnerableTime)
    }

    @Test
    fun vulnerableTimeIsSentOnlyWhenItChanges() {
        // 01:59:50 → 02:00:10: 취약 시간대(02~07시) 진입을 한 번 알린다 (1번 1단계 안내 조건, REQ_FAT_015와 같은 정의)
        val bridge = FatigueBridge()
        val inputs = bridge.drive(at(1, 59, 50), at(2, 0, 10), deg = ::awake)
        assertEquals(listOf(false, true), inputs.filterIsInstance<InterventionInput.VulnerableTimeChanged>().map { it.enabled })
    }

    @Test
    fun demoRelativeTimeUsesScenarioStartForTimeOfDay() {
        // DemoClock은 0부터 시작하는 상대 ms를 준다. 시작 시각을 넣지 않으면 0 ms를 1970-01-01 09:00으로 읽어
        // 14:00 시나리오도 취약 시간대가 아니다. 시작 시각(14:00)을 넣으면 취약 시간대(14~17시)로 본다
        val plain = FatigueBridge().drive(0L, 1_000L, deg = ::awake)
        val demo = FatigueBridge().apply { epochOffsetMs = at(14, 0) }.drive(0L, 1_000L, deg = ::awake)
        assertEquals(listOf(false), plain.filterIsInstance<InterventionInput.VulnerableTimeChanged>().map { it.enabled })
        assertEquals(listOf(true), demo.filterIsInstance<InterventionInput.VulnerableTimeChanged>().map { it.enabled })
    }

    @Test
    fun sleepyConditionTurnsOnSensitiveModeOnce() {
        val bridge = FatigueBridge()
        assertEquals(listOf(InterventionInput.SensitiveModeChanged(true)), bridge.reportCondition(sleepy = true, tMs = night))
        assertTrue(bridge.reportCondition(sleepy = true, tMs = night + 1_000L).isEmpty())
        assertEquals(listOf(InterventionInput.SensitiveModeChanged(false)), bridge.reportCondition(sleepy = false, tMs = night + 2_000L))
        // 출발(D단) 전이면 첫 확인 시각은 아직 없음
        assertNull(bridge.firstCheckDueMs)
    }

    @Test
    fun suddenSteerIsSentAsSafetyEventFromDemo() {
        // 60초 깨어 있는 운전 → 5초 그대로 → 0.4초 만에 7° (멈춤 뒤 급조향) → RAPID_STEERING, 출처는 조향 값의 출처
        val holdFrom = night + 60_000L
        val jumpAt = holdFrom + 5_000L
        val held = awake(holdFrom)
        fun steering(t: Long): Float = when {
            t < holdFrom -> awake(t)
            t < jumpAt -> held
            else -> held + 7f * minOf(t - jumpAt + 100L, 400L) / 400f
        }
        val bridge = FatigueBridge()
        val safety = bridge.drive(night, jumpAt + 15_000L, deg = ::steering)
            .filterIsInstance<InterventionInput.Safety>().map { it.value }
        assertEquals(listOf(SafetyEventType.RAPID_STEERING), safety.map { it.type })
        assertEquals(Source.DEMO, safety.single().source)
        assertTrue("급조향 시각", safety.single().tMs in jumpAt..jumpAt + 1_000L)
    }

    @Test
    fun cruiseGivesUnavailableAssessment() {
        // 크루즈 작동 → 보조 주행: 조향을 판단에서 빼므로 '계산 불가', 점수 없음 (REQ_AST_004)
        val bridge = FatigueBridge()
        val assessment = bridge.drive(night, night + 70_000L, cruise = true, deg = ::awake)
            .filterIsInstance<InterventionInput.Assessment>().single().value
        assertEquals(EvaluationStatus.UNAVAILABLE, assessment.evaluationStatus)
        assertNull(assessment.riskScore)
        assertEquals(true, assessment.assistedDriving)
    }

    @Test
    fun runnerSubmitsEveryInputWithDistinctIds() = runBlocking {
        val manager = RecordingInterventionManager()
        val runner = FatigueAssessmentRunner(FatigueBridge())
        val frames = (night..night + 100_000L step 100L).map { frame(it, awake(it)) }
        runner.run(frames.asFlow(), navi = { navi }, trip = { null }, manager = manager)
        runner.reportCondition(sleepy = true, tMs = night + 100_000L, manager = manager)

        val ids = manager.submitted.map { it.first.value }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("fatigue-") })
        val assessments = manager.submitted.map { it.second }.filterIsInstance<InterventionInput.Assessment>()
        assertEquals(listOf(night + 60_000L, night + 90_000L), assessments.map { it.value.tMs })
        assertEquals(assessments.last().value, runner.assessments.replayCache.single())
        assertEquals(InterventionInput.SensitiveModeChanged(true), manager.submitted.last().second)
        assertTrue(runner.snapshots.value.baseline.sensitive)
        assertFalse(runner.snapshots.value.assist.assistMode)
    }

    /** 받은 입력만 적어 두는 1번 InterventionManager 대역 */
    private class RecordingInterventionManager : InterventionManager {
        val submitted = ArrayList<Pair<RequestId, InterventionInput>>()
        override val state: StateFlow<InterventionState> = MutableStateFlow(InterventionState())
        override val requests: Flow<ExecutionRequest> = emptyFlow()
        override suspend fun start(requestId: RequestId) = ok(requestId)
        override suspend fun submit(requestId: RequestId, input: InterventionInput): ExecutionResult {
            submitted += requestId to input
            return ok(requestId)
        }
        override suspend fun onResult(result: ExecutionResult) = ok(result.requestId)
        override suspend fun cancel(requestId: RequestId) = ok(requestId)
        override suspend fun stop(requestId: RequestId) = ok(requestId)
        private fun ok(requestId: RequestId) = ExecutionResult(requestId = requestId, status = ExecutionStatus.REQUESTED)
    }
}
