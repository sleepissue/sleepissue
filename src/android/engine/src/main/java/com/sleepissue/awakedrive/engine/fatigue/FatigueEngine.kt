package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.assist.AssistModeDetector
import com.sleepissue.awakedrive.engine.baseline.BaselineStore
import com.sleepissue.awakedrive.engine.config.AssistConfig
import com.sleepissue.awakedrive.engine.config.BaselineConfig
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.config.FatigueConfig
import com.sleepissue.awakedrive.engine.config.PatternConfig
import com.sleepissue.awakedrive.engine.config.resolvedFatigueConfig
import com.sleepissue.awakedrive.engine.model.AssistState
import com.sleepissue.awakedrive.engine.model.BaselineState
import com.sleepissue.awakedrive.engine.model.DriveFrame
import com.sleepissue.awakedrive.engine.model.FatigueState
import com.sleepissue.awakedrive.engine.model.InvalidReason
import com.sleepissue.awakedrive.engine.model.PatternState
import com.sleepissue.awakedrive.engine.model.SteeringEvent
import com.sleepissue.awakedrive.engine.model.SteeringEventType
import com.sleepissue.awakedrive.engine.pattern.PatternClassifier

/**
 * 졸음 판단 엔진(2번 전체). 30초마다 [FatigueState]를 낸다.
 * (REQ_FAT_001~020, REQ_BSL_003~007, REQ_PAT_001~005, REQ_AST_001·003·004·006·007, REQ_SIG_009의 엔진 쪽)
 *
 * 흐름: 보조 주행 판정 → 조향 게이트 → 조향 지표 3개 → 기준선과 비교한 f값 → 행동 점수 B × 맥락 가중 K = 위험 점수 R
 * → 3분 지수평균. 옆으로 패턴(추세형·튐형)·안전 우회·인계 품질을 함께 본다.
 *
 * 쓰는 법
 * - [push]에 10 Hz 프레임을 넣는다. 30초 갱신 시각이 된 호출에서만 FatigueState가 하나 나오고, 나머지는 빈 목록이다.
 * - [push] 뒤에 [drainEvents]로 급조향·안전 우회·인계 품질 이상 사건을 꺼낸다 (1번이 바로 단계를 올림).
 * - 맥락(연속 운전·시각·착석·실내 온도)이 바뀌면 [context]를 바꾼다. K는 FatigueState를 낼 때의 맥락으로 계산한다.
 * - 기준선은 [baselineStore]가 정한다. 앱을 켤 때 운행 기록을 `baselineStore.loadHistory(...)`로,
 *   출발 전 컨디션을 `baselineStore.reportCondition(...)`으로 넣는다. 상태는 [baselineState]로 읽는다.
 * - 딴짓·졸음 패턴과 안전 우회는 [patternState](MSG-03), 보조 주행은 [assistState](MSG-04)로 읽는다.
 * - 새 운행이면 [reset], 휴식 인증 뒤면 [resetRiskAfterRest]를 부른다.
 *
 * 게이트가 방향지시등을 켜기 10초 전 프레임까지 빼느라 판정을 10초 늦게 확정하므로,
 * FatigueState·사건의 시각은 넣은 프레임 시각보다 10초 늦다. 60초 시점 값은 70초 프레임을 넣을 때 나온다.
 * 보조 주행 판정([assistState])만은 지연 없이 그 프레임에서 바로 바뀐다.
 *
 * 유효하지 않은 갱신 (valid = false)
 * - 보조 주행 중(그 시점 판정이 ASSIST) → invalidReason = ASSIST. 위험 점수를 갱신하지 않는다 (REQ_AST_004)
 * - 60초 창에서 게이트 통과가 30초 미만 → invalidReason = GATE_WAIT (REQ_FAT_005)
 * - 이때 f값·B·R은 0이고(이번 창은 계산하지 않음), 평활값은 마지막 값 그대로 둔다 [해석].
 *   지표(분당 횟수·급조향 횟수)는 화면이 회색으로 보여 줄 수 있게 그대로 담는다.
 *
 * 게이트를 통과한 프레임의 조향 원천 갱신이 5 Hz 미만이면(DriveFrame.steeringRateOk = false) 그 프레임이 든 60초 창은
 * 미세 수정을 끄고(FatigueState.microEnabled = false) B를 큰 수정·급조향 가중치로만 다시 나눈다 (REQ_SIG_009).
 * 이상 분도 미세 수정 조건을 빼고 보고, 처음 15분 가운데 10% 이상이 느렸으면 출발 피로에서도 뺀다 [해석].
 *
 * 한 스레드(코루틴 하나)에서만 부른다. Android에 의존하지 않는다.
 * ROS로 치면 신호 토픽을 구독해 졸음 점수·패턴·보조 주행 토픽을 발행하는 노드다.
 */
class FatigueEngine(
    private val config: FatigueConfig = FatigueConfig(),
    private val baselineConfig: BaselineConfig = BaselineConfig(),
    patternConfig: PatternConfig = PatternConfig(),
    assistConfig: AssistConfig = AssistConfig(),
) {
    /**
     * 앱에서 쓰는 생성자: 팀 [EngineConfig] 한 곳에서 2번 설정 4묶음을 꺼낸다 (CLAUDE.md 4장).
     * 취약 시간대는 EngineConfig.vulnerableBaseRanges·vulnerableAgeRanges를 쓴다.
     */
    constructor(engineConfig: EngineConfig) : this(
        engineConfig.resolvedFatigueConfig(),
        engineConfig.baseline,
        engineConfig.pattern,
        engineConfig.assist,
    )

    /** 맥락 가중 K의 입력. 1번 TripState·SignalFrame에서 만들어 바뀔 때마다 넣는다. */
    var context: ContextInput = ContextInput()

    /** 기본값·평소의 나·오늘의 나 가운데 비교 기준을 고르고, 출발 피로·민감 모드를 정한다. */
    val baselineStore = BaselineStore(baselineConfig)

    /** 지금 기준선·민감 모드 상태 (1번·4번·5번이 읽음) */
    val baselineState: BaselineState get() = baselineStore.state

    /** 지금 딴짓·졸음 패턴과 안전 우회 신호 (1번 단계 전환·5번 화면이 읽음, MSG-03) */
    val patternState: PatternState get() = pattern.state

    /** 지금 보조 주행 모드·인계 품질·조작 공백 (1번 단계 전환·5번 화면이 읽음, MSG-04) */
    val assistState: AssistState get() = assist.state

    private val assist = AssistModeDetector(assistConfig)
    private val gate = SteeringGate(config)
    private val metrics = SteeringMetrics(config)
    private val safety = SafetySteering(config)
    private val smoother = RiskSmoother(config)
    private val pattern = PatternClassifier(patternConfig)
    private val events = ArrayList<SteeringEvent>()

    /** 마지막으로 넣은 프레임 시각. 민감 모드 시작·첫 음성 확인 시각을 이 시계로 잡는다 */
    private var lastInputMs: Long? = null

    /** 지난 판정까지 센 급조향 수(졸음 점수용 조향). 늘어나면 그 판정에서 급조향이 확인된 것 */
    private var lastSuddenTotal = 0

    /** 게이트 확정을 기다리는 프레임마다 (시각, 조향 원천 5 Hz 이상인지). 게이트와 같은 순서로 꺼낸다 */
    private val pendingRateOk = ArrayDeque<Pair<Long, Boolean>>()

    /** 게이트를 통과했는데 조향 원천이 5 Hz 미만이었던 마지막 판정 시각 (REQ_SIG_009) */
    private var lastSlowGatedMs: Long? = null

    /** 오늘의 나를 확정하기 전까지 게이트를 통과한 판정 수와, 그 가운데 조향 원천이 느렸던 수 */
    private var todayGatedDecisions = 0
    private var todaySlowGatedDecisions = 0

    /**
     * 프레임 하나를 넣는다. 30초 갱신 시각이 되면 FatigueState가 들어 있는 목록을 돌려준다.
     * 앞 프레임보다 이른 프레임은 버린다.
     */
    fun push(frame: DriveFrame): List<FatigueState> {
        val last = lastInputMs
        if (last != null && frame.tMs < last) return emptyList()
        lastInputMs = frame.tMs
        if (frame.inDrive == true) baselineStore.onDriveFrame(frame.tMs)
        pendingRateOk.addLast(frame.tMs to frame.steeringRateOk)

        val assistMode = assist.update(frame).assistMode
        val input = GateInput(
            tMs = frame.tMs,
            steeringDeg = frame.steeringDeg,
            speedKph = frame.speedKph,
            inDrive = frame.inDrive,
            turnSignalOn = frame.turnSignalOn,
            brakeOn = frame.brakeOn,
            distToNextTurnM = frame.distToNextTurnM,
            assistMode = assistMode,
        )
        return gate.push(input).mapNotNull { onDecision(it, frame.tMs) }
    }

    /**
     * 운전자·동승자 주의 확인 결과 (REQ_PAT_004). 정상 대답 뒤 패턴이 튐형으로 확인되면 딴짓 1건으로 기록한다.
     * 기록은 [patternState]의 distractionCount·lastDistractionMs로 읽는다. 단계는 바꾸지 않는다(1번 담당).
     * @param normal 정상 대답이면 true, 느림·무응답이면 false. 장치 오류·취소는 넣지 않는다
     * @return 이번 호출로 바로 기록했으면 true (튐형 확정을 기다리면 false, 뒤 갱신 때 기록될 수 있음)
     */
    fun onCheckResult(tMs: Long, normal: Boolean): Boolean = pattern.onCheckResult(tMs, normal)

    /** 지난번 이후 생긴 조향 사건을 꺼낸다(꺼낸 사건은 지워짐). 1번이 push 뒤마다 부른다. */
    fun drainEvents(): List<SteeringEvent> {
        if (events.isEmpty()) return emptyList()
        val out = events.toList()
        events.clear()
        return out
    }

    /** 게이트에 남은 프레임을 모두 확정해 남은 갱신을 낸다. 운행이 끝날 때만 쓴다. */
    fun flush(): List<FatigueState> = gate.flush().mapNotNull { onDecision(it, lastInputMs ?: it.tMs) }

    /**
     * 새 운행(꺼져 있던 시간 2시간 초과, REQ_TRP_008): 지표·평활값·패턴·보조 주행·오늘의 나·출발 피로·컨디션을 지운다.
     * 평소의 나(운행 기록)와 맥락은 그대로 둔다. 시동을 켠 직후, 출발 전 컨디션을 받기 전에 부른다.
     */
    fun reset() {
        assist.reset()
        gate.reset()
        metrics.reset()
        safety.reset()
        smoother.reset()
        baselineStore.resetTrip()
        pattern.reset()
        events.clear()
        lastInputMs = null
        lastSuddenTotal = 0
        pendingRateOk.clear()
        lastSlowGatedMs = null
        todayGatedDecisions = 0
        todaySlowGatedDecisions = 0
    }

    /**
     * 휴식 인증 + 회복 확인 뒤(REQ_RES_011): 위험 점수 평활값만 0부터 다시 센다.
     * 오늘의 나는 그대로 둔다. 쉬고 난 직후로 다시 잡으면 덜 깬 상태가 기준이 될 수 있어서다.
     */
    fun resetRiskAfterRest() {
        smoother.reset()
    }

    private fun onDecision(decision: GateDecision, nowMs: Long): FatigueState? {
        // 조향 원천 속도는 게이트를 통과한(지표에 들어가는) 프레임만 따진다. 주차 중 느린 신호는 지표와 상관없음
        val rateOk = takeRateOk(decision.tMs)
        if (decision.passed) {
            if (!rateOk) lastSlowGatedMs = decision.tMs
            if (baselineState.today == null) {
                todayGatedDecisions++
                if (!rateOk) todaySlowGatedDecisions++
            }
        }
        val indicators = metrics.accept(decision)

        // 급조향 1회 → 2단계 확인. 졸음 점수용 조향에서만 센다(보조 주행 중에는 조향을 판단에서 뺌)
        if (metrics.suddenTotal > lastSuddenTotal) {
            lastSuddenTotal = metrics.suddenTotal
            addEvent(decision.tMs, SteeringEventType.RAPID_STEERING)
            // 이상 분은 그 급조향까지의 60초로도 본다: 멈춘 구간과 급조향이 1분 칸 경계로 갈려도 딴짓 1분을 잡게 (REQ_PAT_001)
            val upToSudden = metrics.windowEndingAt(decision.tMs)
            pattern.onSuddenWindow(
                tMs = decision.tMs,
                microPerMin = upToSudden.microPerMin,
                largePerMin = upToSudden.largePerMin,
                measured = upToSudden.valid,
                reference = baselineState.reference,
                microEnabled = microEnabledAt(decision.tMs),
            )
        }
        // 안전 우회·인계 품질은 보조 주행 중에도 본다
        safety.accept(decision)?.let { signal ->
            signal.suddenAtMs?.let { t ->
                if (pattern.onSudden(t)) addEvent(t, SteeringEventType.REPEATED_RAPID_STEERING)
                if (assist.onSafetySudden(t, nowMs)) addEvent(t, SteeringEventType.HANDOVER_UNSAFE)
            }
            signal.largeSwingEndMs?.let { end ->
                if (assist.onSafetyLargeSwing(end, nowMs, baselineStore.usualHandoverLargeCount)) {
                    addEvent(decision.tMs, SteeringEventType.HANDOVER_UNSAFE)
                }
            }
        }

        if (baselineState.today == null) {
            baselineStore.updateToday(
                nowMs = nowMs,
                gatedMs = metrics.gatedMsTotal,
                microCount = metrics.microTotal,
                largeCount = metrics.largeTotal,
                suddenCount = metrics.suddenTotal,
                microReliable = todayMicroReliable(),
            )
        }
        if (indicators == null) return null
        val state = evaluate(indicators, decision.reason)
        pattern.onWindow(
            tMs = indicators.tMs,
            microPerMin = indicators.microPerMin,
            largePerMin = indicators.largePerMin,
            suddenInWindow = indicators.suddenInWindow,
            measured = state.valid,
            reference = baselineState.reference,
            microEnabled = state.microEnabled,
        )
        return state
    }

    /** 창 (끝 − 60초, 끝] 안에 게이트를 통과한 느린 프레임이 없으면 미세 수정을 쓴다 (REQ_SIG_009) [해석] */
    private fun microEnabledAt(windowEndMs: Long): Boolean {
        val slow = lastSlowGatedMs ?: return true
        return windowEndMs - slow >= config.windowMs
    }

    /**
     * 처음 15분 동안 게이트를 통과한 프레임 가운데 조향 원천이 느렸던 비율이 10% 미만이면 미세 수정을 믿는다 [제안값].
     * 1번 RateMeter는 시작 뒤 10초를 '측정 중'으로 주므로, 그 몇 초 때문에 운행 전체의 미세 수정을 버리지 않게 한다.
     */
    private fun todayMicroReliable(): Boolean {
        if (todayGatedDecisions == 0) return true
        return todaySlowGatedDecisions.toDouble() / todayGatedDecisions < baselineConfig.todayMaxSlowSteeringRatio
    }

    /** 이 판정 프레임의 조향 원천 속도를 꺼낸다. 게이트는 넣은 순서대로 확정하므로 맨 앞이 그 프레임이다 */
    private fun takeRateOk(tMs: Long): Boolean {
        while (pendingRateOk.isNotEmpty() && pendingRateOk.first().first < tMs) pendingRateOk.removeFirst()
        val head = pendingRateOk.firstOrNull()
        return if (head != null && head.first == tMs) pendingRateOk.removeFirst().second else true
    }

    private fun addEvent(tMs: Long, type: SteeringEventType) {
        events += SteeringEvent(tMs, type)
        // 아무도 꺼내 가지 않아도 메모리가 계속 늘지 않게 오래된 것부터 버린다
        while (events.size > MAX_PENDING_EVENTS) events.removeAt(0)
    }

    private fun evaluate(indicators: SteeringIndicators, reason: GateReason): FatigueState {
        val contextK = ContextWeight.kOf(context, config.context)
        val microEnabled = microEnabledAt(indicators.tMs)
        val invalidReason = when {
            // 창 안에 통과 구간이 남아 있어도 보조 주행이 켜진 동안은 점수를 갱신하지 않는다 (REQ_AST_004)
            reason == GateReason.ASSIST -> InvalidReason.ASSIST
            !indicators.valid -> InvalidReason.GATE_WAIT
            else -> InvalidReason.NONE
        }
        if (invalidReason != InvalidReason.NONE) {
            return FatigueState(
                tMs = indicators.tMs,
                microPerMin = indicators.microPerMin,
                largePerMin = indicators.largePerMin,
                suddenCount5m = indicators.suddenCount5m,
                contextK = contextK,
                riskSmoothed = smoother.value,
                valid = false,
                invalidReason = invalidReason,
                microEnabled = microEnabled,
            )
        }

        val reference = baselineState.reference
        // 조향 원천 갱신이 느리면 미세 수정은 계산하지 않고 B를 큰 수정·급조향으로만 다시 나눈다 (REQ_SIG_009)
        val fMicro = if (microEnabled) FatigueFactors.fMicro(indicators.microPerMin, reference.microPerMin, config) else 0f
        val fLarge = FatigueFactors.fLarge(indicators.largePerMin, reference.largePerMin, config)
        val fSudden = FatigueFactors.fSudden(indicators.suddenCount5m, config)
        val behaviorB = if (microEnabled) {
            RiskScore.behaviorScore(fMicro, fLarge, fSudden, config)
        } else {
            RiskScore.behaviorScoreWithoutMicro(fLarge, fSudden, config)
        }
        val riskR = RiskScore.riskScore(behaviorB, contextK, config)
        return FatigueState(
            tMs = indicators.tMs,
            microPerMin = indicators.microPerMin,
            largePerMin = indicators.largePerMin,
            suddenCount5m = indicators.suddenCount5m,
            fMicro = fMicro,
            fLarge = fLarge,
            fSudden = fSudden,
            behaviorB = behaviorB,
            contextK = contextK,
            riskR = riskR,
            riskSmoothed = smoother.update(riskR),
            valid = true,
            invalidReason = InvalidReason.NONE,
            microEnabled = microEnabled,
        )
    }

    private companion object {
        /** 꺼내 가지 않은 사건을 이만큼만 쌓아 둔다 */
        const val MAX_PENDING_EVENTS = 100
    }
}
