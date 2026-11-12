package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.*
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 질문 재시도·경유지 대기의 시간축은 조립자가 명시한다. 단계 계산은 항상 운행 논리 시각이다. */
enum class RequestTimerBasis { SCENARIO, ELAPSED_REALTIME }

/** 취소는 기존 요청을 무효화하는 명령이며 실제 경유지 제거 성공을 뜻하지 않는다. */
data class CancelExecutionRequest(override val requestId: RequestId) : ExecutionRequest

/** 정상 회복 뒤 각 담당의 시간·평활값·예보 초기화를 연결할 요청이다. 완료로 위장하지 않는다. */
data class RestRecoveryResetRequest(override val requestId: RequestId, val restSessionId: String) : ExecutionRequest

/**
 * 단계 계산과 외부 실행 요청을 연결한다. 실제 음성·SDK·Room을 실행하지 않는다.
 * requests는 단일 실행 담당자가 소비한다. 버퍼에 남은 취소된 시작 요청은 전달하지 않는다.
 * Tick은 소유자가 주입하며 내부 타이머·코루틴을 자동 시작하지 않는다.
 */
class PolicyInterventionManager(
    private val policy: InterventionPolicy,
    private val config: EngineConfig,
    private val clock: Clock,
    private val sessionSource: Source,
    private val timerBasis: RequestTimerBasis,
    private val newRequestId: () -> RequestId,
) : InterventionManager {
    private data class Entry(val request: ExecutionRequest, val stage: InterventionStage,
        var status: ExecutionStatus = ExecutionStatus.REQUESTED, var delivered: Boolean = false,
        var started: Boolean = false, var payloadHandled: Boolean = false, val restSessionId: String? = null,
        val firstCheck: Boolean = false, var startedAtMs: Long? = null)

    private val mutex = Mutex()
    private val outbound = Channel<ExecutionRequest>(Channel.UNLIMITED)
    private val collecting = AtomicBoolean()
    private val entries = linkedMapOf<RequestId, Entry>()
    private val commands = mutableMapOf<RequestId, InterventionInput>()
    private val mutableState = MutableStateFlow(InterventionState())
    private val mutableTransitions = MutableStateFlow<List<StageTransition>>(emptyList())
    private var policyState = InterventionPolicyState()
    private var active = false
    private var assessment = DrivingAssessment(0)
    private var trip: TripState? = null
    private var forecast: ForecastAssessment? = null
    private var navi: NaviState? = null
    private var sensitive = false
    private var vulnerable = false
    private var frequentChecks = false
    private var ventilationConsent = false
    private var alertnessContext = CheckContext.DRIVER_ALERTNESS
    private var restSession: String? = null
    private val seenRestSessions = mutableSetOf<String>()
    private var latestRestCertificationMs: Long? = null
    private val seenSafetyEvents = mutableSetOf<Pair<Long, SafetyEventType>>()
    private var recoveryCompletedAtMs: Long? = null
    private var candidate: RestAreaCandidate? = null
    private var lastAreaDistanceM: Float? = null
    private var distanceReminderPending = false
    private var distanceReminderSent = false
    private var routeAdded = false
    private var autoAddAt: Long? = null
    private var autoAddOwner: RequestId? = null
    private var retryAt: Long? = null
    private var retryOwner: RequestId? = null
    private var assistedAnchor: Long? = null
    private var firstCheckDueMs: Long? = null
    private var firstCheckRequested = false
    private var firstCheckInterrupted = false
    private var lastNoticeAt: Long? = null
    private var lastTimerMs: Long? = null
    private val enteredAt = mutableMapOf<InterventionStage, Long>()
    private val mutableInterruptedChecks = MutableStateFlow<Map<CheckContext, Long>>(emptyMap())
    val interruptedChecks: StateFlow<Map<CheckContext, Long>> = mutableInterruptedChecks.asStateFlow()

    override val state: StateFlow<InterventionState> = mutableState.asStateFlow()
    val transitions: StateFlow<List<StageTransition>> = mutableTransitions.asStateFlow()

    suspend fun checkpoint(): InterventionCheckpoint = mutex.withLock {
        check(active) { "중지된 개입 세션은 저장할 수 없습니다." }
        val checks = mutableInterruptedChecks.value.toMutableMap()
        var firstInterrupted = firstCheckInterrupted
        val firstRetry = retryOwner?.let { entries[it]?.firstCheck } == true
        if ((state.value.stage == InterventionStage.CHECK || firstRetry) && retryAt != null && timerBasis == RequestTimerBasis.SCENARIO) {
            checks.putIfAbsent(alertnessContext, requireNotNull(retryAt))
            if (firstRetry) firstInterrupted = true
        }
        entries.values.forEach { entry ->
            val context = (entry.request as? CheckRequest)?.context
            if (context != null && context != CheckContext.WAYPOINT_CONSENT && !entry.payloadHandled && entry.status !in cancelledStatuses) {
                val now = clock.scenarioTimeMs()
                checks[context] = minOf(checks[context] ?: now, now)
                if (entry.firstCheck) firstInterrupted = true
            }
        }
        InterventionCheckpoint(policyState.copy(publicState = state.value.copy(pendingRequestId = null)),
            assessment, trip != null, forecast, sensitive, vulnerable, ventilationConsent, alertnessContext,
            restSession, seenRestSessions.toSet(), latestRestCertificationMs, recoveryCompletedAtMs,
            routeAdded, checks, enteredAt.toMap(), assistedAnchor, firstCheckDueMs, firstCheckRequested, firstInterrupted)
    }

    /** 새 인스턴스에만 복원한다. 평가·음성·검색·경유지 요청을 자동 발행하지 않는다. */
    suspend fun restore(checkpoint: InterventionCheckpoint, restoredTrip: TripState) = mutex.withLock {
        check(!active && entries.isEmpty()) { "새 개입 세션에서만 복원할 수 있습니다." }
        require(timerBasis == RequestTimerBasis.SCENARIO) { "실제 경과 시각 요청 타이머 복원은 아직 지원하지 않습니다." }
        checkpoint.requireValid(clock.scenarioTimeMs(), restoredTrip)
        policyState = checkpoint.policy
        mutableState.value = checkpoint.policy.publicState
        assessment = checkpoint.assessment
        trip = restoredTrip.takeIf { checkpoint.tripContextAvailable }
        forecast = checkpoint.forecast
        sensitive = checkpoint.sensitive; vulnerable = checkpoint.vulnerable
        ventilationConsent = checkpoint.ventilationConsent; alertnessContext = checkpoint.alertnessContext
        restSession = checkpoint.restSessionId
        seenRestSessions.addAll(checkpoint.seenRestSessions)
        latestRestCertificationMs = checkpoint.latestRestCertificationMs
        recoveryCompletedAtMs = checkpoint.recoveryCompletedAtMs
        routeAdded = checkpoint.routeAdded
        enteredAt.putAll(checkpoint.enteredAt)
        assistedAnchor = checkpoint.assistedAnchor
        firstCheckDueMs = checkpoint.firstCheckDueMs
        firstCheckRequested = checkpoint.firstCheckRequested
        firstCheckInterrupted = checkpoint.firstCheckInterrupted
        mutableInterruptedChecks.value = checkpoint.interruptedChecks
        clearTimers()
        // 과거 외부 실행·후보·예약을 되살리지 않는다. 이후 새 입력에 의한 재안내의 기준이다.
        lastNoticeAt = timerNow()
        active = true
    }

    /** 중단된 확인은 사람이 이 동작을 요청했을 때 새 ID로 다시 시작한다. */
    suspend fun resumeInterruptedChecks(): Boolean = mutex.withLock {
        if (!active || mutableInterruptedChecks.value.isEmpty()) return@withLock false
        val contexts = mutableInterruptedChecks.value.filterValues { clock.scenarioTimeMs() >= it }
        if (contexts.isEmpty()) return@withLock false
        mutableInterruptedChecks.value = mutableInterruptedChecks.value - contexts.keys
        contexts.keys.forEach { enqueue(CheckRequest(freshId(), it), firstCheck = firstCheckInterrupted && it == alertnessContext) }
        if (alertnessContext in contexts) firstCheckInterrupted = false
        refreshPending()
        true
    }
    override val requests: Flow<ExecutionRequest> = flow {
        check(collecting.compareAndSet(false, true)) { "외부 실행 요청은 담당자 한 곳에서 소비해야 합니다." }
        try {
            for (request in outbound) {
                val deliver = mutex.withLock {
                    if (request is CancelExecutionRequest) true else entries[request.requestId]?.let {
                        if (it.status == ExecutionStatus.REQUESTED && !it.delivered) {
                            it.delivered = true; true
                        } else false
                    } ?: false
                }
                if (deliver) emit(request)
            }
        } finally { collecting.set(false) }
    }

    override suspend fun start(requestId: RequestId): ExecutionResult = mutex.withLock {
        if (!active) {
            require(clock.scenarioTimeMs() >= (policyState.lastEvaluatedMs ?: 0)) {
                "시나리오 시계를 되돌리려면 새로운 개입 세션을 구성해야 합니다."
            }
            active = true
            policyState = InterventionPolicyState()
            assessment = DrivingAssessment(clock.scenarioTimeMs())
            trip = null; forecast = null; navi = null; candidate = null; restSession = null
            alertnessContext = CheckContext.DRIVER_ALERTNESS
            clearTimers(); assistedAnchor = null; routeAdded = false
            clearFirstCheck()
            lastAreaDistanceM = null; distanceReminderPending = false; distanceReminderSent = false
            enteredAt.clear(); lastNoticeAt = null; lastTimerMs = null; commands.clear()
            mutableInterruptedChecks.value = emptyMap()
            evaluate()
        }
        result(requestId, ExecutionStatus.STARTED)
    }

    override suspend fun submit(requestId: RequestId, input: InterventionInput): ExecutionResult = mutex.withLock {
        if (!active) return@withLock unavailable(requestId)
        commands[requestId]?.let { previous ->
            return@withLock if (previous == input) result(requestId, ExecutionStatus.REQUESTED)
                else result(requestId, ExecutionStatus.FAILED, ExecutionFailure.ERROR)
        }
        require(clock.scenarioTimeMs() >= (policyState.lastEvaluatedMs ?: 0)) { "개입 논리 시각이 역행했습니다." }
        timerNow() // 시각 역행을 먼저 확인해 입력 일부만 변경되는 것을 막는다.
        var confirmation: CheckResult? = null
        var bypass = false
        var forceCheck = false
        var recovered = false
        var certified = false
        when (input) {
            is InterventionInput.Assessment -> {
                require(input.value.tMs <= clock.scenarioTimeMs()) { "미래의 판단을 현재 판단으로 사용할 수 없습니다." }
                if (input.value.tMs < assessment.tMs) return@withLock unavailable(requestId)
                val hadGap = assessment.assistedDriving == true && (assessment.operationGapSec ?: 0) >= config.assistedOperationGapSec
                forceCheck = input.value.assistedDriving == true && (input.value.operationGapSec ?: 0) >= config.assistedOperationGapSec && !hadGap
                if (input.value.assistedDriving == true && assessment.assistedDriving != true) assistedAnchor = clock.scenarioTimeMs()
                if (input.value.assistedDriving != true) assistedAnchor = null
                assessment = input.value
            }
            is InterventionInput.TripChanged -> {
                if (trip?.tripId != input.value.tripId) clearFirstCheck()
                trip = input.value
            }
            is InterventionInput.Forecast -> {
                require(input.value.tMs <= clock.scenarioTimeMs()) { "미래의 예보 입력을 사용할 수 없습니다." }
                if (forecast?.let { input.value.tMs < it.tMs } == true) return@withLock unavailable(requestId)
                forecast = input.value
            }
            is InterventionInput.SensitiveModeChanged -> {
                sensitive = input.enabled
                if (!sensitive) clearFirstCheck()
            }
            is InterventionInput.FirstCheckScheduleChanged -> {
                require(input.tripId.isNotBlank())
                require(input.dueAtMs == null || input.dueAtMs >= 0)
                if (trip?.tripId != input.tripId) return@withLock unavailable(requestId)
                val anchor = input.dueAtMs?.minus(config.baseline.sensitiveFirstCheckDelayMs)
                if (anchor != null && (anchor < (trip?.runStartMs ?: 0) ||
                    latestRestCertificationMs?.let { anchor <= it } == true)) return@withLock unavailable(requestId)
                if (firstCheckDueMs != input.dueAtMs) {
                    clearFirstCheck()
                    firstCheckDueMs = input.dueAtMs
                    // 이 민감 모드 구간에 이미 실제 시작한 주의 확인이면 중복하지 않는다.
                    firstCheckRequested = anchor != null && entries.values.any {
                        (it.request as? CheckRequest)?.context in alertnessContexts &&
                            it.startedAtMs?.let { started -> started >= anchor } == true
                    }
                }
            }
            is InterventionInput.FrequentChecksChanged -> frequentChecks = input.enabled
            is InterventionInput.VulnerableTimeChanged -> vulnerable = input.enabled
            is InterventionInput.VentilationConsentChanged -> ventilationConsent = input.enabled
            is InterventionInput.AlertnessTargetChanged -> {
                require(input.context in setOf(CheckContext.DRIVER_ALERTNESS, CheckContext.PASSENGER_ALERTNESS)) {
                    "주의 확인 대상은 운전자 또는 동승자여야 합니다."
                }
                if (alertnessContext != input.context) {
                    val interruptedFirst = firstCheckInterrupted
                    val activeFirst = entries.values.any { it.firstCheck && !it.payloadHandled && it.status !in cancelledStatuses }
                    if (interruptedFirst) mutableInterruptedChecks.value = mutableInterruptedChecks.value - alertnessContext
                    firstCheckInterrupted = false
                    if (interruptedFirst || activeFirst) firstCheckRequested = false
                    alertnessContext = input.context
                    val pending = entries.values.filter {
                        it.request is CheckRequest && it.request.context in setOf(CheckContext.DRIVER_ALERTNESS, CheckContext.PASSENGER_ALERTNESS) &&
                            !it.payloadHandled && it.status !in cancelledStatuses
                    }
                    pending.forEach(::cancelEntry)
                    if (state.value.stage == InterventionStage.CHECK) forceCheck = true
                }
            }
            is InterventionInput.NavigationChanged -> navi = input.value
            is InterventionInput.Safety -> {
                if (input.value.source != sessionSource) return@withLock unavailable(requestId)
                require(input.value.tMs <= clock.scenarioTimeMs()) { "미래의 안전 이벤트는 처리할 수 없습니다." }
                if (recoveryCompletedAtMs?.let { input.value.tMs < it } == true) return@withLock unavailable(requestId)
                if (!seenSafetyEvents.add(input.value.tMs to input.value.type)) return@withLock result(requestId, ExecutionStatus.REQUESTED)
                bypass = input.value.type == SafetyEventType.REPEATED_RAPID_STEERING
                forceCheck = !bypass
            }
            is InterventionInput.CheckDue -> {
                if (input.source != sessionSource) return@withLock unavailable(requestId)
                require(input.tMs <= clock.scenarioTimeMs()) { "미래의 확인 요청은 처리할 수 없습니다." }
                forceCheck = true
            }
            is InterventionInput.RestAreaResolved -> if (!handleArea(input.value)) return@withLock unavailable(requestId)
            is InterventionInput.RestAreaDistance -> {
                if (candidate?.id != input.candidateId) return@withLock unavailable(requestId)
                require(input.distanceM == null || input.distanceM.isFinite() && input.distanceM >= 0) { "쉼터 거리가 유효하지 않습니다." }
                if (!distanceReminderSent && lastAreaDistanceM?.let { it > config.restDistanceReminderM } == true &&
                    input.distanceM?.let { it <= config.restDistanceReminderM } == true) distanceReminderPending = true
                lastAreaDistanceM = input.distanceM
            }
            is InterventionInput.RestCertified -> {
                if (input.value.source != sessionSource) return@withLock unavailable(requestId)
                require(input.value.certifiedAtMs <= clock.scenarioTimeMs()) { "미래의 휴식 인증은 처리할 수 없습니다." }
                if (input.value.sessionId in seenRestSessions || latestRestCertificationMs?.let {
                        input.value.certifiedAtMs <= it } == true) return@withLock result(requestId, ExecutionStatus.REQUESTED)
                if (restSession != input.value.sessionId) {
                    seenRestSessions += input.value.sessionId
                    latestRestCertificationMs = input.value.certifiedAtMs
                    restSession = input.value.sessionId
                    certified = true
                    clearFirstCheck()
                    // 인증 전 운전 시간·예보는 만료됐다. 실제 Store/예보 초기화 완료를 뜻하지 않는다.
                    // 이후 소유 모듈이 휴식 후 현재 상태를 다시 공급한다. 점수·확인 조건은 보존한다.
                    trip = null; forecast = null
                    cancelPending(includeRecoveryReset = true)
                    clearTimers()
                    enqueue(CheckRequest(freshId(), CheckContext.RECOVERY))
                }
            }
            is InterventionInput.CheckCompleted -> {
                val check = input.value
                val entry = entries[check.requestId]
                val request = entry?.request as? CheckRequest
                if (request == null || request.context != check.context || !entry.started ||
                    entry.payloadHandled || entry.status in cancelledStatuses ||
                    (request.context == CheckContext.RECOVERY && entry.restSessionId != restSession) ||
                    (request.context != CheckContext.RECOVERY && entry.stage != state.value.stage)
                ) return@withLock unavailable(requestId)
                entry.payloadHandled = true
                entry.status = ExecutionStatus.SUCCEEDED
                when (check.context) {
                    CheckContext.WAYPOINT_CONSENT -> when {
                        check.outcome in humanResponseStatuses && check.intent == ResponseIntent.YES -> addWaypoint()
                        check.outcome in humanResponseStatuses && check.intent == ResponseIntent.NO -> {
                            retryAt = deadline(config.waypointRefusalRetryMs); retryOwner = check.requestId
                        }
                        check.outcome == CheckOutcome.NO_RESPONSE -> {
                            autoAddAt = deadline(config.waypointAutoAddGraceMs); autoAddOwner = check.requestId
                        }
                        else -> Unit
                    }
                    CheckContext.RECOVERY -> if (restSession != null && check.outcome == CheckOutcome.NORMAL &&
                        check.intent !in setOf(ResponseIntent.REST, ResponseIntent.NAP, ResponseIntent.SLEEPY)) {
                        recovered = true
                        recoveryCompletedAtMs = clock.scenarioTimeMs()
                        assessment = DrivingAssessment(clock.scenarioTimeMs())
                        trip = null; forecast = null
                    }
                    CheckContext.DRIVER_ALERTNESS, CheckContext.PASSENGER_ALERTNESS -> {
                        confirmation = check
                        retryAt = when {
                            check.outcome == CheckOutcome.SLOW -> deadline(config.slowCheckRetryMs)
                            check.outcome == CheckOutcome.NORMAL && assessment.pattern == DrivingPattern.TRENDING -> deadline(config.trendCheckRetryMs)
                            else -> null
                        }
                        retryOwner = if (retryAt == null) null else check.requestId
                    }
                    else -> Unit
                }
            }
            InterventionInput.Tick -> Unit
        }
        evaluate(confirmation, forceCheck, bypass, recovered, certified)
        if (forceCheck && state.value.stage == InterventionStage.CHECK && !hasPendingCheck())
            enqueue(CheckRequest(freshId(), alertnessContext))
        if (recovered) enqueue(RestRecoveryResetRequest(freshId(), requireNotNull(restSession)))
        runDueRequests()
        refreshPending()
        commands[requestId] = input
        result(requestId, ExecutionStatus.REQUESTED)
    }

    override suspend fun onResult(result: ExecutionResult): ExecutionResult = mutex.withLock {
        val entry = entries[result.requestId] ?: return@withLock unavailable(result.requestId)
        if (!entry.delivered || entry.status in terminalStatuses || entry.status == result.status ||
            entry.started && result.status in setOf(ExecutionStatus.REQUESTED, ExecutionStatus.DEFERRED)
        ) return@withLock this.result(result.requestId, entry.status)
        entry.status = result.status
        if (result.status == ExecutionStatus.STARTED) {
            entry.started = true
            entry.startedAtMs = clock.scenarioTimeMs()
            if (entry.request is CheckRequest) assistedAnchor = clock.scenarioTimeMs()
            if ((entry.request as? CheckRequest)?.context in alertnessContexts && firstCheckDueMs != null &&
                clock.scenarioTimeMs() >= requireNotNull(firstCheckDueMs) - config.baseline.sensitiveFirstCheckDelayMs)
                firstCheckRequested = true
        }
        if (entry.request is RestRequest && entry.request.action == RestAction.ADD_WAYPOINT && result.status == ExecutionStatus.SUCCEEDED) routeAdded = true
        refreshPending()
        result
    }

    override suspend fun cancel(requestId: RequestId): ExecutionResult = mutex.withLock {
        val entry = entries[requestId] ?: return@withLock unavailable(requestId)
        if (entry.request is RestRequest && entry.request.action == RestAction.ADD_WAYPOINT && entry.status == ExecutionStatus.SUCCEEDED)
            return@withLock result(requestId, ExecutionStatus.FAILED, ExecutionFailure.NOT_SUPPORTED)
        val ownsAutoAdd = autoAddAt != null && autoAddOwner == requestId
        val ownsRetry = retryAt != null && retryOwner == requestId
        if (entry.status !in terminalStatuses || entry.request is CheckRequest && (ownsAutoAdd || ownsRetry)) cancelEntry(entry)
        if (ownsAutoAdd) { autoAddAt = null; autoAddOwner = null }
        if (ownsRetry) { retryAt = null; retryOwner = null }
        refreshPending()
        result(requestId, entry.status)
    }

    override suspend fun stop(requestId: RequestId): ExecutionResult = mutex.withLock {
        cancelPending(includeRecoveryReset = true); clearFirstCheck(); active = false; clearTimers()
        refreshPending()
        result(requestId, ExecutionStatus.CANCELLED)
    }

    private fun evaluate(check: CheckResult? = null, forcedCheck: Boolean = false, bypass: Boolean = false,
        recovered: Boolean = false, certified: Boolean = false) {
        val now = clock.scenarioTimeMs()
        val assistedDue = assistedCheckDue()
        val decision = policy.evaluate(policyState, InterventionSnapshot(now,
            assessment.evaluationStatus, assessment.riskScore, assessment.pattern == DrivingPattern.TRENDING,
            trip?.continuousDriveSec, forecast?.let { it.evaluationStatus == EvaluationStatus.EVALUATED && it.dueAtMs != null && now >= it.dueAtMs } == true,
            vulnerable, sensitive, forcedCheck || hasPendingAlertnessCheck() || assistedDue || assessment.assistedDriving == true &&
                (assessment.operationGapSec ?: 0) >= config.assistedOperationGapSec, bypass, check, recovered,
            restCertified = certified))
        policyState = decision.state
        mutableState.value = decision.state.publicState
        decision.transition?.let { transition ->
            mutableTransitions.value = mutableTransitions.value + transition
            // 안내 단계 변경으로 취소한 첫 확인을 '완료'로 소모하지 않는다.
            // CHECK는 아래 기존 요청, REST_GUIDANCE는 휴식 요청이 우선한다.
            if (entries.values.any { it.firstCheck && !it.payloadHandled && it.status !in terminalStatuses })
                firstCheckRequested = false
            cancelPending(); clearTimers()
            val lastEntry = enteredAt[transition.to]
            enteredAt[transition.to] = now
            when (transition.to) {
                InterventionStage.CAUTION -> if (lastEntry == null || now - lastEntry >= config.stageNoticeCooldownMs) findArea()
                InterventionStage.CHECK -> {
                    if (lastEntry == null || now - lastEntry >= config.stageNoticeCooldownMs) {
                        enqueue(CheckRequest(freshId(), alertnessContext))
                        if (ventilationConsent) enqueue(ClimateRequest(freshId(), ClimateAction.ENABLE_DRIVER_VENTILATION, true))
                        // INT_015는 팀 미확정 Should다. 2단계 쉼터 안내를 활성화하지 않는다.
                    } else retryAt = deadline(config.stageNoticeCooldownMs - (now - lastEntry))
                }
                InterventionStage.REST_GUIDANCE -> {
                    enqueue(SpeechRequest(freshId(), restCue()))
                    findArea()
                }
                InterventionStage.NORMAL -> { candidate = null; routeAdded = false }
            }
            lastNoticeAt = timerNow()
        }
        refreshPending()
    }

    private fun handleArea(response: RestResult): Boolean {
        val entry = entries[response.execution.requestId] ?: return false
        val request = entry.request as? RestRequest ?: return false
        if (!entry.delivered || entry.payloadHandled || entry.status in cancelledStatuses ||
            entry.stage != state.value.stage || request.action != RestAction.FIND_AHEAD) return false
        if (response.execution.status !in terminalStatuses) {
            if (entry.status !in terminalStatuses) entry.status = response.execution.status
            return true
        }
        entry.payloadHandled = true
        entry.status = response.execution.status
        if (response.execution.status != ExecutionStatus.SUCCEEDED) return true
        val area = response.candidate
        if (area == null) enqueue(SpeechRequest(freshId(), SpeechCue.NO_REST_AREA))
        else if (area.id.isNotBlank() && area.distanceM.isFinite() && area.distanceM >= 0) {
            if (candidate?.id != area.id) {
                lastAreaDistanceM = area.distanceM; distanceReminderPending = false; distanceReminderSent = false
            }
            candidate = area
            if (state.value.stage == InterventionStage.CAUTION)
                enqueue(SpeechRequest(freshId(), SpeechCue.REST_AREA_AHEAD, area))
            if (state.value.stage == InterventionStage.REST_GUIDANCE && !routeAdded && !hasPendingCheck())
                enqueue(CheckRequest(freshId(), CheckContext.WAYPOINT_CONSENT, restArea = area))
        }
        return true
    }

    private fun runDueRequests() {
        val now = timerNow()
        // [해석] 출발 피로의 첫 확인 예약만으로 점수/단계를 올리지 않는다. 실제 발화는 기존 음성 게이트가 결정한다.
        if (firstCheckDueMs?.let { clock.scenarioTimeMs() >= it } == true && !firstCheckRequested &&
            state.value.stage != InterventionStage.REST_GUIDANCE && !hasPendingCheck()) {
            enqueue(CheckRequest(freshId(), alertnessContext), firstCheck = true)
            firstCheckRequested = true
        }
        if (state.value.stage == InterventionStage.CHECK && assistedCheckDue() && !hasPendingCheck())
            enqueue(CheckRequest(freshId(), alertnessContext))
        if (autoAddAt?.let { now >= it } == true) { autoAddAt = null; autoAddOwner = null; addWaypoint() }
        if (retryAt?.let { now >= it } == true && !hasPendingCheck()) {
            val firstRetry = retryOwner?.let { entries[it]?.firstCheck } == true
            retryAt = null; retryOwner = null
            if (state.value.stage == InterventionStage.CHECK || firstRetry && state.value.stage != InterventionStage.REST_GUIDANCE)
                enqueue(CheckRequest(freshId(), alertnessContext), firstCheck = firstRetry)
            if (state.value.stage == InterventionStage.REST_GUIDANCE) findArea()
        }
        val period = when (state.value.stage) {
            InterventionStage.CAUTION -> if (policyState.cautionHeldUntilRest) config.contextCautionReminderMs else null
            InterventionStage.REST_GUIDANCE -> config.restGuidanceReminderMs
            else -> null
        }
        val timeReminder = period != null && lastNoticeAt?.let { now - it >= period } == true
        val distanceReminder = state.value.stage == InterventionStage.REST_GUIDANCE && distanceReminderPending
        if ((timeReminder || distanceReminder) && !hasPendingCheck() && autoAddAt == null && !hasPendingWaypoint()) {
            lastNoticeAt = now
            if (distanceReminder) { distanceReminderPending = false; distanceReminderSent = true }
            if (state.value.stage == InterventionStage.CAUTION) findArea()
            else {
                enqueue(SpeechRequest(freshId(), restCue()))
                if (!routeAdded) findArea()
            }
        }
    }

    private fun findArea() {
        if (entries.values.any { it.request is RestRequest && it.request.action == RestAction.FIND_AHEAD && it.status !in terminalStatuses }) return
        enqueue(RestRequest(freshId(), RestAction.FIND_AHEAD, naviState = navi))
    }

    private fun addWaypoint() {
        if (state.value.stage != InterventionStage.REST_GUIDANCE || routeAdded) return
        val area = candidate ?: return
        if (entries.values.any { it.request is RestRequest && it.request.action == RestAction.ADD_WAYPOINT && it.status !in terminalStatuses }) return
        enqueue(RestRequest(freshId(), RestAction.ADD_WAYPOINT, naviState = navi, candidateId = area.id))
    }
    private fun restCue() = if (state.value.reason == TransitionReason.SAFETY_EVENT)
        SpeechCue.SAFETY_REST_RECOMMENDED else SpeechCue.REST_RECOMMENDED

    private fun hasPendingCheck() = mutableInterruptedChecks.value.isNotEmpty() || entries.values.any {
        it.request is CheckRequest && !it.payloadHandled && it.status !in cancelledStatuses
    }
    private fun hasPendingWaypoint() = entries.values.any {
        it.request is RestRequest && it.request.action == RestAction.ADD_WAYPOINT && it.status !in terminalStatuses
    }
    private fun hasPendingAlertnessCheck() = (!firstCheckInterrupted && mutableInterruptedChecks.value.keys.any { it != CheckContext.RECOVERY }) || entries.values.any {
        val request = it.request as? CheckRequest
        request != null && request.context in setOf(CheckContext.DRIVER_ALERTNESS, CheckContext.PASSENGER_ALERTNESS) &&
            !it.firstCheck && !it.payloadHandled && it.status !in cancelledStatuses
    }
    private fun assistedCheckDue(): Boolean {
        val interval = if (frequentChecks || sensitive || vulnerable || (trip?.continuousDriveSec ?: 0) >= config.normalDriveCautionSec)
            config.assistedRiskCheckIntervalMs else config.assistedCheckIntervalMs
        return assessment.assistedDriving == true && assistedAnchor?.let { clock.scenarioTimeMs() - it >= interval } == true
    }
    private fun enqueue(request: ExecutionRequest, firstCheck: Boolean = false) {
        check(request.requestId !in entries) { "외부 실행 요청 ID는 재사용할 수 없습니다." }
        entries[request.requestId] = Entry(request, state.value.stage,
            restSessionId = if (request is CheckRequest && request.context == CheckContext.RECOVERY) restSession else null,
            firstCheck = firstCheck)
        check(outbound.trySend(request).isSuccess)
    }
    private fun freshId(): RequestId = newRequestId().also { check(it !in entries) { "요청 ID 생성기가 같은 ID를 반환했습니다." } }
    private fun cancelPending(includeRecoveryReset: Boolean = false) {
        mutableInterruptedChecks.value = mutableInterruptedChecks.value.filterKeys {
            !includeRecoveryReset && it == CheckContext.RECOVERY
        }
        entries.values.filter {
            (includeRecoveryReset || it.request !is RestRecoveryResetRequest &&
                !(it.request is CheckRequest && it.request.context == CheckContext.RECOVERY)) &&
                (it.status !in terminalStatuses || it.request is CheckRequest && !it.payloadHandled && it.status !in cancelledStatuses)
        }.forEach(::cancelEntry)
    }
    private fun clearTimers() { autoAddAt = null; autoAddOwner = null; retryAt = null; retryOwner = null }
    private fun clearFirstCheck() {
        if (firstCheckInterrupted) mutableInterruptedChecks.value = mutableInterruptedChecks.value - alertnessContext
        if (retryOwner?.let { entries[it]?.firstCheck } == true) { retryAt = null; retryOwner = null }
        entries.values.filter { it.firstCheck && !it.payloadHandled && it.status !in cancelledStatuses }.forEach(::cancelEntry)
        firstCheckDueMs = null; firstCheckRequested = false; firstCheckInterrupted = false
    }
    private fun cancelEntry(entry: Entry) {
        entry.status = ExecutionStatus.CANCELLED
        if (entry.delivered) check(outbound.trySend(CancelExecutionRequest(entry.request.requestId)).isSuccess)
    }
    private fun refreshPending() {
        mutableState.value = mutableState.value.copy(pendingRequestId = entries.values.firstOrNull {
            it.request is CheckRequest && !it.payloadHandled && it.status !in cancelledStatuses
        }?.request?.requestId ?: entries.values.firstOrNull {
            it.request is RestRequest && it.request.action == RestAction.ADD_WAYPOINT && it.status !in terminalStatuses
        }?.request?.requestId ?: autoAddOwner ?: retryOwner)
    }
    private fun timerNow(): Long {
        val now = when (timerBasis) { RequestTimerBasis.SCENARIO -> clock.scenarioTimeMs(); RequestTimerBasis.ELAPSED_REALTIME -> clock.elapsedRealtimeMs() }
        require(now >= 0 && (lastTimerMs == null || now >= lastTimerMs!!)) { "요청 대기 시각이 역행했습니다." }
        lastTimerMs = now
        return now
    }
    private fun deadline(delayMs: Long) = Math.addExact(timerNow(), delayMs)
    private fun unavailable(id: RequestId) = result(id, ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)
    private fun result(id: RequestId, status: ExecutionStatus, failure: ExecutionFailure? = null) = ExecutionResult(id, status, failure)
    private companion object {
        val alertnessContexts = setOf(CheckContext.DRIVER_ALERTNESS, CheckContext.PASSENGER_ALERTNESS)
        val cancelledStatuses = setOf(ExecutionStatus.CANCELLED, ExecutionStatus.FAILED, ExecutionStatus.NOT_CONNECTED)
        val terminalStatuses = cancelledStatuses + ExecutionStatus.SUCCEEDED
        val humanResponseStatuses = setOf(CheckOutcome.NORMAL, CheckOutcome.SLOW)
    }
}
