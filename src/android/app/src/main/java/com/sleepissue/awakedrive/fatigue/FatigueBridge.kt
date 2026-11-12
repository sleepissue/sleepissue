package com.sleepissue.awakedrive.fatigue

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.config.resolvedFatigueConfig
import com.sleepissue.awakedrive.engine.fatigue.ContextWeight
import com.sleepissue.awakedrive.engine.fatigue.FatigueEngine
import com.sleepissue.awakedrive.engine.fatigue.contextInputOf
import com.sleepissue.awakedrive.engine.fatigue.toDriveFrame
import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.model.AssistState
import com.sleepissue.awakedrive.engine.model.BaselineState
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.CheckOutcome
import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.EvaluationStatus
import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.InvalidReason
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.PatternState
import com.sleepissue.awakedrive.engine.model.PatternType
import com.sleepissue.awakedrive.engine.model.ResponseIntent
import com.sleepissue.awakedrive.engine.model.SafetyEvent
import com.sleepissue.awakedrive.engine.model.SafetyEventType
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.model.SteeringEvent
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.signal.DrivingAssessment
import com.sleepissue.awakedrive.signal.DrivingPattern
import com.sleepissue.awakedrive.signal.InterventionInput
import java.time.Instant
import java.time.ZoneId

/**
 * 화면(5번)이 읽는 2번 엔진 상태 묶음. 프레임마다 새로 만든다.
 * [fatigue]는 마지막 30초 갱신 값이고, 첫 갱신 전에는 null이다.
 */
data class FatigueSnapshot(
    val fatigue: FatigueState?,
    val pattern: PatternState,
    val assist: AssistState,
    val baseline: BaselineState,
    /** 지금 취약 시간대인지. 시각을 모르면 null */
    val vulnerableTime: Boolean?,
)

/**
 * 2번 엔진 출력 연결기의 계산 부분: Hub 프레임 → FatigueEngine → 1번 단계 전환 입력.
 * 코루틴·Android에 의존하지 않아 JVM 시험으로 확인한다. 실제로 돌리는 쪽은 [FatigueAssessmentRunner].
 *
 * 하는 일 (프레임 하나마다)
 * 1. 맥락(연속 운전·24시간 누적·시각·착석·실내 온도·연령대)을 엔진에 넣는다.
 * 2. SignalFrame·NaviState를 엔진 입력으로 바꿔 넣는다 (SignalAdapters).
 * 3. 결과를 1번 InterventionInput으로 바꾼다. 대응표는 docs/fatigue-engine/08-team-handoff.md 4-1.
 *    - 조향 사건 → `Safety` (이름이 같은 SafetyEventType)
 *    - 30초 갱신 → `Assessment` (평활값, 패턴, 보조 주행, 조작 공백)
 *    - 민감 모드·취약 시간대가 바뀔 때만 → `SensitiveModeChanged`·`VulnerableTimeChanged`
 *    - 민감 모드 첫 음성 확인 시각이 되면 한 번 → `CheckDue` (REQ_BSL_006)
 * 4. 사람에게 물어본 주의 확인 결과([onCheckResult])를 엔진에 넣어 딴짓을 기록한다 (REQ_PAT_004).
 *
 * 시각: 프레임 tMs + [epochOffsetMs]를 epoch ms로 보고 [zone]의 하루 중 분으로 바꾼다. 4번 ForecastEngine과 같은 해석이다.
 * 데모 재생 중에는 DemoClock이 시나리오 상대 ms(0부터)를 주므로 [epochOffsetMs]에 시나리오 시작 시각을 넣어야 한다.
 * 한 스레드(코루틴 하나)에서만 부른다. 엔진이 그렇게 만들어져 있다.
 *
 * ROS로 치면 엔진 노드의 토픽을 1번 노드가 구독하는 메시지 형식으로 바꿔 다시 발행하는 브리지 노드다.
 */
class FatigueBridge(
    private val engineConfig: EngineConfig = EngineConfig(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    /** 2번 엔진. 앱을 켤 때 `engine.baselineStore.loadHistory(...)`로 운행 기록을 넣는다 */
    val engine = FatigueEngine(engineConfig)

    /** 설정의 연령대 (MSG-12). null이면 기본 취약 시간대만 쓴다 */
    var ageBand: AgeBand? = null

    /**
     * 프레임 시각을 실제 날짜 시각(epoch ms)으로 바꿀 때 더하는 값. 하루 중 시각(취약 시간대, REQ_FAT_015)에만 쓴다.
     * - 실제 주행: Hub 시각이 이미 epoch 기준(AnchoredTripClock)이라 0
     * - 데모 재생: DemoClock이 Hub에 시나리오 상대 ms(0부터)를 주므로 데모를 시작할 때 시나리오의 startEpochMs
     * 0으로 두면 데모의 0 ms를 1970-01-01 09:00(KST)으로 읽어 14:00 시나리오도 취약 시간대가 아니게 된다.
     */
    @Volatile
    var epochOffsetMs: Long = 0L

    /** 마지막으로 계산한 상태 (화면용) */
    var snapshot: FatigueSnapshot = snapshotOf(fatigue = null, vulnerable = null)
        private set

    /**
     * 출발 피로·'졸려요' 때 첫 음성 확인을 할 시각(프레임 시각, REQ_BSL_006·007). 해당 없으면 null.
     * 이 시각 뒤 첫 프레임에서 1번에 `CheckDue`를 한 번 보낸다.
     */
    val firstCheckDueMs: Long? get() = engine.baselineState.firstCheckDueMs

    private val contextConfig = engineConfig.resolvedFatigueConfig().context
    private var lastSensitive = false
    private var lastVulnerable: Boolean? = null
    private var lastFatigue: FatigueState? = null
    private var lastFrameMs: Long? = null
    /** `CheckDue`를 이미 보낸 첫 확인 시각. 같은 시각으로 두 번 보내지 않는다 */
    private var sentFirstCheckDueMs: Long? = null

    /** 프레임 하나를 넣고 1번에 보낼 입력을 돌려준다. 보낼 것이 없으면 빈 목록. */
    fun onFrame(frame: SignalFrame, navi: NaviState?, trip: TripState?): List<InterventionInput> {
        val out = ArrayList<InterventionInput>()
        val minute = minuteOfDay(frame.tMs + epochOffsetMs)
        engine.context = contextInputOf(trip, frame, minute, ageBand)

        val vulnerable = minute?.let { ContextWeight.isVulnerableTime(it, ageBand, contextConfig) }
        if (vulnerable != null && vulnerable != lastVulnerable) out += InterventionInput.VulnerableTimeChanged(vulnerable)
        lastVulnerable = vulnerable

        val states = engine.push(frame.toDriveFrame(navi))
        val source = sourceOf(frame)
        engine.drainEvents().forEach { out += InterventionInput.Safety(it.toSafetyEvent(source)) }
        for (state in states) {
            out += InterventionInput.Assessment(state.toDrivingAssessment(engine.patternState, engine.assistState))
            lastFatigue = state
        }
        sensitiveChange()?.let { out += it }
        firstCheckDue(frame.tMs, source)?.let { out += it }
        lastFrameMs = frame.tMs
        snapshot = snapshotOf(lastFatigue, vulnerable)
        return out
    }

    /**
     * 사람에게 물어본 주의 확인 결과 (1번 CheckResult). 엔진의 딴짓 기록에 쓴다 (REQ_PAT_004). 단계는 바꾸지 않는다
     * (1번 InterventionManager가 같은 결과를 CheckCompleted로 따로 받는다).
     * - 운전자·동승자 주의 확인만 본다(경유지 동의·회복 확인·출발 전 컨디션은 무시)
     * - 정상 대답 → 딴짓 후보. 단 '쉴게'·'잘게'·'졸려'처럼 쉬겠다는 대답은 딴짓이 아님
     * - 느림·무응답 → 후보를 지움. 장치 오류·취소·사용 불가는 사람의 대답이 아니므로 무시
     * @return 이번 호출로 딴짓을 바로 기록했으면 true (튐형 확정을 기다리면 false, 뒤 프레임에서 기록될 수 있음)
     */
    fun onCheckResult(result: CheckResult): Boolean {
        if (result.context != CheckContext.DRIVER_ALERTNESS && result.context != CheckContext.PASSENGER_ALERTNESS) return false
        val normal = when (result.outcome) {
            CheckOutcome.NORMAL -> result.intent !in restIntents
            CheckOutcome.SLOW, CheckOutcome.NO_RESPONSE -> false
            else -> return false
        }
        val tMs = lastFrameMs ?: return false
        val recorded = engine.onCheckResult(tMs, normal)
        snapshot = snapshot.copy(pattern = engine.patternState)
        return recorded
    }

    /** 출발 전 컨디션 (REQ_BSL_007). '졸려요'면 민감 모드가 켜지므로 그 변화를 돌려준다 */
    fun reportCondition(sleepy: Boolean, tMs: Long): List<InterventionInput> {
        engine.baselineStore.reportCondition(sleepy, tMs)
        snapshot = snapshot.copy(baseline = engine.baselineState)
        return listOfNotNull(sensitiveChange())
    }

    /** 휴식 인증 + 회복 확인 뒤 (REQ_RES_011): 위험 점수 평활값만 0부터 다시 센다 */
    fun onRestCertified() {
        engine.resetRiskAfterRest()
    }

    /** 새 운행 (REQ_TRP_008): 엔진을 처음 상태로. 평소의 나(운행 기록)는 그대로 둔다. 민감 모드가 꺼졌으면 그 변화를 돌려준다 */
    fun onNewTrip(): List<InterventionInput> {
        engine.reset()
        lastFatigue = null
        lastVulnerable = null
        sentFirstCheckDueMs = null
        snapshot = snapshotOf(fatigue = null, vulnerable = null)
        return listOfNotNull(sensitiveChange())
    }

    /** 민감 모드 첫 음성 확인 시각이 지났으면 그 시각마다 한 번 `CheckDue` (REQ_BSL_006) */
    private fun firstCheckDue(tMs: Long, source: Source): InterventionInput? {
        val due = engine.baselineState.firstCheckDueMs ?: return null
        if (tMs < due || due == sentFirstCheckDueMs) return null
        sentFirstCheckDueMs = due
        return InterventionInput.CheckDue(tMs, source)
    }

    private fun sensitiveChange(): InterventionInput? {
        val sensitive = engine.baselineState.sensitive
        if (sensitive == lastSensitive) return null
        lastSensitive = sensitive
        return InterventionInput.SensitiveModeChanged(sensitive)
    }

    private fun snapshotOf(fatigue: FatigueState?, vulnerable: Boolean?) = FatigueSnapshot(
        fatigue = fatigue,
        pattern = engine.patternState,
        assist = engine.assistState,
        baseline = engine.baselineState,
        vulnerableTime = vulnerable,
    )

    private fun minuteOfDay(tMs: Long): Int? {
        if (tMs < 0) return null
        val local = Instant.ofEpochMilli(tMs).atZone(zone)
        return local.hour * 60 + local.minute
    }

    /** 조향 사건의 출처: 조향 값의 출처, 모르면 차속의 출처, 그것도 모르면 실제 [해석] */
    private fun sourceOf(frame: SignalFrame): Source =
        frame.qualityOf(SignalField.STEERING_DEG).source
            ?: frame.qualityOf(SignalField.SPEED_KPH).source
            ?: Source.REAL

    private companion object {
        /** 대답이 빨라도 쉬겠다는 뜻이면 딴짓으로 보지 않는다 */
        val restIntents = setOf(ResponseIntent.REST, ResponseIntent.NAP, ResponseIntent.SLEEPY)
    }
}

/**
 * 30초 갱신 하나 → 1번 [DrivingAssessment]. 대응표(08 문서 4-1)대로 바꾼다.
 * - valid → EVALUATED + 평활값. 게이트 대기 → NOT_EVALUATED, 보조 주행 → UNAVAILABLE [해석]. 둘 다 점수는 null(0으로 바꾸지 않음)
 * - 패턴: 추세형 → TRENDING, 튐형 → TRANSIENT, 애매 → UNCERTAIN, 이상 분 없음 → null
 * - 보조 주행 여부·조작 공백(초)은 그 시점 AssistState 값
 */
fun FatigueState.toDrivingAssessment(pattern: PatternState, assist: AssistState): DrivingAssessment = DrivingAssessment(
    tMs = tMs,
    evaluationStatus = when {
        valid -> EvaluationStatus.EVALUATED
        invalidReason == InvalidReason.ASSIST -> EvaluationStatus.UNAVAILABLE
        else -> EvaluationStatus.NOT_EVALUATED
    },
    riskScore = if (valid) riskSmoothed else null,
    pattern = pattern.patternType.toDrivingPattern(),
    assistedDriving = assist.assistMode,
    operationGapSec = assist.opGapSec.toLong(),
)

/** 2번 패턴 → 1번 패턴. 이상 분이 없으면(NONE) null */
fun PatternType.toDrivingPattern(): DrivingPattern? = when (this) {
    PatternType.TREND -> DrivingPattern.TRENDING
    PatternType.SPIKE -> DrivingPattern.TRANSIENT
    PatternType.AMBIGUOUS -> DrivingPattern.UNCERTAIN
    PatternType.NONE -> null
}

/** 조향 사건 → 1번 안전 이벤트. 두 enum은 이름이 같다 (RAPID_STEERING, REPEATED_RAPID_STEERING, HANDOVER_UNSAFE) */
fun SteeringEvent.toSafetyEvent(source: Source): SafetyEvent =
    SafetyEvent(tMs = tMs, type = SafetyEventType.valueOf(type.name), source = source)
