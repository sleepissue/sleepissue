package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.intervention.FirstEscalation
import com.sleepissue.awakedrive.engine.intervention.InterventionPolicy
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class DebugRequest(
    val request: ExecutionRequest,
    val label: String,
    val status: ExecutionStatus,
    val detail: String,
)

data class DebugEvent(val sequence: Long, val timeMs: Long, val message: String)

data class DebugSnapshot(
    val timeMs: Long = 0,
    val gear: Gear = Gear.P,
    val power: PowerState = PowerState.ON,
    val trip: TripState = TripState(),
    val intervention: InterventionState = InterventionState(),
    val requests: List<DebugRequest> = emptyList(),
    val events: List<DebugEvent> = emptyList(),
    val ready: Boolean = false,
    val assessmentScore: Float? = null,
    val trending: Boolean = false,
    val persistence: TripPersistenceState = TripPersistenceState(),
    val canResumeChecks: Boolean = false,
    val restoredManualInput: Boolean = false,
    val interruptedCheckDueMs: Long? = null,
)

/**
 * 개발 화면의 수동 입력을 기존 Hub → Tracker → 개입 관리자로 전달한다.
 * 소유 scope의 단일 스레드(화면에서는 Main)에서 사용한다. Mutex는 화면 조작을 순서대로 받는다.
 * SDK·마이크·내비를 실행하지 않는다. 저장소를 주입하면 실제 DB에 정밀 상태를 저장한다.
 */
class DebugHarnessController(
    private val scope: CoroutineScope,
    repository: TripRepository? = null,
    private val storageClock: Clock = object : Clock {
        override fun scenarioTimeMs() = 0L
        override fun elapsedRealtimeMs() = 0L
        override fun wallTimeMs() = 0L
    },
) {
    private val commands = Mutex()
    private val mutableState = MutableStateFlow(DebugSnapshot())
    val state: StateFlow<DebugSnapshot> = mutableState.asStateFlow()
    private var session: Session? = null
    private var generation = 0L
    private val persistence = repository?.let { TripSaveCoordinator(it, storageClock) }
    private val manualPolicy = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)

    init {
        persistence?.let { coordinator ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.state.collect { value ->
                    session?.publish() ?: run { mutableState.value = state.value.copy(persistence = value) }
                }
            }
        }
    }

    /** 앱 시작 경로다. 저장본 검증이 끝나기 전에는 정상 복원으로 표시하지 않는다. */
    suspend fun initialize() = commands.withLock {
        if (session != null) return@withLock
        val loaded = persistence?.load() ?: TripLoadResult.Empty
        if (loaded is TripLoadResult.Loaded) {
            val snapshot = loaded.snapshot
            if (snapshot.timeBasis != TripTimeBasis.MANUAL_SCENARIO) {
                persistence?.restoredFailure(RepositoryFailure.UNSUPPORTED_TIME_BASIS)
            } else if (snapshot.source != Source.DEMO) {
                persistence?.restoredFailure(RepositoryFailure.STALE_INPUT)
            } else try {
                TripSnapshotValidator.requireValid(snapshot)
                require(snapshot.rulePolicy == manualPolicy)
                val owner = TripSessionCodec.decode(requireNotNull(snapshot.ownerState))
                require(owner.gear in setOf(Gear.D, Gear.P) && owner.power in setOf(PowerState.ON, PowerState.OFF))
                val observed = requireNotNull(snapshot.preciseState?.lastObservation)
                require(owner.gear == observed.gear && owner.power == observed.power)
                owner.intervention.requireValid(requireNotNull(snapshot.savedAtScenarioMs), snapshot.state)
                val next = replaceSession(snapshot)
                persistence?.restored(snapshot)
                persistence?.configure(owner.saveTimerBasis, next.clock.nowMs, enabled = true)
                next.record("Room 복원 성공 · 저장한 가상 시각부터 재개 · 닫혀 있던 실제 시간은 합산하지 않음")
                next.record("과거 음성·경유지·확인 실행은 중단됨 · 중단된 확인은 명시적 재개 필요")
                next.publish()
                return@withLock
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { persistence?.restoredFailure(RepositoryFailure.INVALID_DATA) }
        }
        val next = replaceSession(null)
        val failed = persistence?.state?.value?.restore == TripRestoreStatus.FAILED
        persistence?.configure(SaveTimerBasis.SCENARIO, 0, enabled = !failed)
        next.record(if (failed) "Room 복원 실패 · 새 메모리 세션 · 기존 DB 보존·자동 저장 중지"
            else "빈 DB 또는 저장소 미연결 · 새 메모리 세션 시작")
        next.publish()
    }

    /** 기존 시계를 되돌리지 않고 입력 수집기·타이머·ID를 포함한 새 세션을 만든다. */
    suspend fun reset() = commands.withLock {
        val next = replaceSession(null)
        persistence?.memoryReset()
        persistence?.configure(persistence.state.value.timerBasis, 0, enabled = false)
        next.events.clear()
        next.eventSequence = 0
        next.record("전체 테스트 상태 초기화 · P / 시동 ON · 가상 시간 0 · 위험 점수 미평가")
        next.publish()
    }

    private suspend fun replaceSession(snapshot: TripSnapshot?): Session {
        val previous = session
        session = null
        previous?.close()
        val next = Session(++generation, snapshot)
        session = next
        next.start()
        return next
    }

    suspend fun saveNow() = command {
        val saved = persistence?.saveNow(clock.nowMs) { capture() } == true
        record(if (saved) "Room 즉시 저장 성공" else "Room 즉시 저장 실패 또는 저장소 미연결")
    }

    suspend fun checkAutoSave() = commands.withLock { session?.maybeSave() }

    suspend fun setSaveTimerBasis(basis: SaveTimerBasis) = command {
        persistence?.configure(basis, clock.nowMs, enabled = true)
        record("자동 저장 재개: $basis · 10초 주기 · 가상 운전 시각은 실제 경과와 별개")
    }

    suspend fun deleteSavedData() = command {
        val deleted = persistence?.delete() == true
        record(if (deleted) "저장 데이터 삭제 성공 · 메모리 유지 · 자동 저장 중지" else "저장 데이터 삭제 실패 또는 저장소 미연결")
    }

    suspend fun resumeInterruptedChecks() = command {
        record(if (manager.resumeInterruptedChecks()) "중단된 확인을 새 ID로 명시적 재개"
            else "재개할 중단 확인이 없음")
    }

    /** 시험·소유자 종료용이다. 시동 OFF나 강제 저장을 생성하지 않는다. */
    suspend fun close() = commands.withLock {
        val previous = session
        session = null
        previous?.close()
    }

    suspend fun setGear(gear: Gear) = command {
        require(gear == Gear.D || gear == Gear.P) { "개발 입력은 D/P만 지원합니다." }
        this.gear = gear
        record("기어 입력: $gear")
        observe()
    }

    suspend fun setPower(power: PowerState) = command {
        require(power == PowerState.ON || power == PowerState.OFF) { "시동 입력은 ON/OFF만 지원합니다." }
        this.power = power
        record("시동 입력: $power (가상 관측)")
        observe()
    }

    suspend fun advanceTime(deltaMs: Long) = command {
        require(deltaMs > 0) { "진행할 가상 시간은 양수여야 합니다." }
        val target = Math.addExact(clock.nowMs, deltaMs)
        record("가상 시간 진행 시작: +${deltaMs / 1_000}초")
        // 개발용 관측 간격이다. 판단 문턱은 기존 EngineConfig를 그대로 사용한다.
        while (clock.nowMs < target) {
            clock.nowMs += minOf(1_000L, target - clock.nowMs)
            observe()
            send(InterventionInput.Tick)
            yield()
            maybeSave()
        }
        record("가상 시간 진행 완료: +${deltaMs / 1_000}초")
    }

    suspend fun applyAssessment(score: Float, trending: Boolean) = command {
        require(score.isFinite() && score in 0f..100f) { "위험 점수는 0~100의 유한한 숫자여야 합니다." }
        assessmentScore = score
        this.trending = trending
        record("판단 결과 주입: 위험 점수 $score / 추세형 ${if (trending) "예" else "아니오"}")
        send(InterventionInput.Assessment(DrivingAssessment(
            clock.nowMs, EvaluationStatus.EVALUATED, score,
            if (trending) DrivingPattern.TRENDING else DrivingPattern.TRANSIENT,
        )))
    }

    suspend fun completeCheck(requestId: RequestId, outcome: CheckOutcome, intent: ResponseIntent? = null) = command {
        require(outcome in setOf(CheckOutcome.NORMAL, CheckOutcome.SLOW, CheckOutcome.NO_RESPONSE)) {
            "정상·느림·무응답 중 하나를 주입해 주세요."
        }
        val entry = activeRequest(requestId)
        val request = entry?.request as? CheckRequest
        if (request == null) {
            record("확인 결과 거부: 활성 확인 요청이 아님 (${requestId.value})")
            return@command
        }
        val responseIntent = intent ?: when {
            outcome == CheckOutcome.NO_RESPONSE -> null
            request.context == CheckContext.RECOVERY -> ResponseIntent.REFRESHED
            request.context == CheckContext.WAYPOINT_CONSENT -> ResponseIntent.YES
            else -> ResponseIntent.OK
        }
        // 반응 시간을 측정한 것으로 꾸미지 않고 판정 결과 자체만 주입한다.
        val result = send(InterventionInput.CheckCompleted(CheckResult(
            requestId, request.context, outcome, intent = responseIntent,
        )))
        if (result.status == ExecutionStatus.FAILED) {
            record("확인 결과 거부: 요청이 만료됨 (${requestId.value})")
        } else {
            val label = when (outcome) {
                CheckOutcome.NORMAL -> "정상"
                CheckOutcome.SLOW -> "느림"
                else -> "무응답"
            }
            updateRequest(requestId, ExecutionStatus.SUCCEEDED, "가상 결과: $label / 의도: ${responseIntent ?: "없음"}")
            if (request.context == CheckContext.RECOVERY && outcome == CheckOutcome.NORMAL &&
                manager.state.value.reason == TransitionReason.REST_COMPLETED) {
                assessmentScore = null
                trending = false
            }
        }
    }

    /** 위치·정차 15분 판정기는 미연결이다. 그 담당이 보낼 인증 결과만 직접 주입한다. */
    suspend fun certifyRest() = command {
        val id = "debug-$namespace-rest-${++restSequence}"
        record("휴식 인증 결과 주입: $id (위치·15분 조건 검사 생략)")
        send(InterventionInput.RestCertified(RestCertification(id, clock.nowMs, Source.DEMO)))
    }

    suspend fun resolveRest(requestId: RequestId, found: Boolean) = command {
        val entry = activeRequest(requestId)
        val request = entry?.request as? RestRequest
        if (request?.action != RestAction.FIND_AHEAD) {
            record("검색 결과 거부: 활성 쉼터 검색이 아님 (${requestId.value})")
            return@command
        }
        // 지도 검색 결과가 아닌, 다음 실행 요청을 살펴보기 위한 고정 시험 자료다.
        val candidate = if (found) RestAreaCandidate(
            "debug-rest-area", "가상 검증 쉼터", GeoPoint(37.0, 127.0), 6_000f,
        ) else null
        val result = send(InterventionInput.RestAreaResolved(RestResult(
            ExecutionResult(requestId, ExecutionStatus.SUCCEEDED), candidate,
        )))
        if (result.status != ExecutionStatus.FAILED) updateRequest(requestId, ExecutionStatus.SUCCEEDED,
            if (found) "가상 후보: 가상 검증 쉼터 / 6 km" else "가상 검색 완료: 후보 없음")
        else record("검색 결과 거부: 요청이 만료됨 (${requestId.value})")
    }

    suspend fun completeExecution(requestId: RequestId, succeeded: Boolean) = command {
        val entry = activeRequest(requestId)
        if (entry == null || entry.request is CheckRequest || entry.request is RestRecoveryResetRequest) {
            record("실행 결과 거부: 처리 가능한 활성 요청이 아님 (${requestId.value})")
            return@command
        }
        val status = if (succeeded) ExecutionStatus.SUCCEEDED else ExecutionStatus.FAILED
        val result = ExecutionResult(requestId, status, if (succeeded) null else ExecutionFailure.ERROR)
        val accepted = if (entry.request is RestRequest && entry.request.action == RestAction.FIND_AHEAD) {
            // 검색은 완료 페이로드까지 전달해야 후속 안내가 생성된다.
            send(InterventionInput.RestAreaResolved(RestResult(result))).status != ExecutionStatus.FAILED
        } else manager.onResult(result).status == status
        if (accepted) updateRequest(requestId, status,
            if (succeeded) "가상 실행 완료 (SDK 실행 아님)" else "가상 실행 실패")
        else record("실행 결과 거부: 요청이 만료됨 (${requestId.value})")
    }

    private suspend fun command(action: suspend Session.() -> Unit) = commands.withLock {
        val current = checkNotNull(session) { "검증 세션을 먼저 초기화해 주세요." }
        current.action()
        // 전달된 취소·후속 요청을 같은 조작의 화면 결과에 반영한다.
        yield()
        current.publish()
    }

    private inner class ManualClock(initialMs: Long) : Clock {
        var nowMs = initialMs
        override fun scenarioTimeMs() = nowMs
        // 수동 검증은 실제 반응 시간을 측정하지 않는다. 요청 대기는 SCENARIO를 사용한다.
        override fun elapsedRealtimeMs() = storageClock.elapsedRealtimeMs()
        override fun wallTimeMs() = storageClock.wallTimeMs()
    }

    private inner class Session(val number: Long, val restored: TripSnapshot?) {
        val namespace = UUID.randomUUID().toString()
        val restoredOwner = restored?.ownerState?.let(TripSessionCodec::decode)
        val job = SupervisorJob(scope.coroutineContext[Job])
        val owner = CoroutineScope(scope.coroutineContext + job)
        val clock = ManualClock(restored?.savedAtScenarioMs ?: 0L)
        val config = EngineConfig()
        val store = TripStateStore(restored?.state ?: TripState())
        val tracker = TripTracker(TripRules(config,
            // [가정] 기존 운행 규칙 가이드의 비교 정책을 개발 화면에만 명시적으로 선택한다.
            manualPolicy,
        ) { "debug-$namespace-trip-${++tripSequence}" }, store, Source.DEMO, restored?.preciseState ?: TripRuleState())
        val inputs = MutableSharedFlow<VehicleSignalInput>()
        val hub = VehicleSignalHub.observations(object : VehicleSignalSource {
            override val samples: Flow<VehicleSignalInput> = inputs
        }, clock)
        val manager = PolicyInterventionManager(
            InterventionPolicy(config, FirstEscalation.IMMEDIATE), config, clock,
            Source.DEMO, RequestTimerBasis.SCENARIO,
        ) { nextId() }
        var gear = restoredOwner?.gear ?: Gear.P
        var power = restoredOwner?.power ?: PowerState.ON
        var assessmentScore: Float? = restoredOwner?.intervention?.assessment?.takeIf {
            it.evaluationStatus == EvaluationStatus.EVALUATED }?.riskScore
        var trending = restoredOwner?.intervention?.assessment?.pattern == DrivingPattern.TRENDING
        var tripSequence = 0L
        var restSequence = 0L
        var idSequence = 0L
        var eventSequence = 0L
        var recordedTransitions = 0
        var observationDone: CompletableDeferred<Unit>? = null
        val events = mutableListOf<DebugEvent>()
        val requests = linkedMapOf<RequestId, DebugRequest>()

        fun nextId() = RequestId("debug-$namespace-request-${++idSequence}")

        suspend fun capture(): TripSnapshot {
            val precise = tracker.preciseState()
            val ownerState = TripSessionOwnerState(gear, power,
                persistence?.state?.value?.timerBasis ?: SaveTimerBasis.SCENARIO, manager.checkpoint())
            return TripSnapshot(precise.trip, storageClock.wallTimeMs(), precise.driveSegments,
                storageClock.elapsedRealtimeMs(), precise, clock.nowMs, manualPolicy, Source.DEMO,
                TripTimeBasis.MANUAL_SCENARIO, TripSessionCodec.encode(ownerState))
        }

        suspend fun maybeSave() {
            if (persistence?.maybeSave(clock.nowMs) { capture() } == true) record("Room 주기 저장 성공")
            publish()
        }

        suspend fun start() {
            owner.launch(start = CoroutineStart.UNDISPATCHED) {
                manager.requests.collect { request -> handleRequest(request) }
            }
            owner.launch(start = CoroutineStart.UNDISPATCHED) {
                hub.samples.collect { frame ->
                    val done = checkNotNull(observationDone)
                    try {
                        val previous = tracker.state.value
                        val result = checkNotNull(tracker.accept(frame, PowerEvent(
                            frame.tMs, power, quality(frame.tMs),
                        )))
                        result.events.forEach { event ->
                            record(when (event) {
                                is TripRuleEvent.NewTrip -> "운행 시작: ${event.tripId}"
                                is TripRuleEvent.BaselineResetRequested -> "기준선 초기화 요청: ${event.tripId} (담당 미연결)"
                                is TripRuleEvent.RestResetReached -> "정차 15분 도달: 연속 운전 시간 초기화 (휴식 인증과 별개)"
                            })
                        }
                        if (previous.driveState != result.state.trip.driveState) record(
                            "운행 상태: ${previous.driveState} → ${result.state.trip.driveState}",
                        )
                        send(InterventionInput.TripChanged(result.state.trip))
                        done.complete(Unit)
                    } catch (error: Exception) {
                        done.completeExceptionally(error)
                        throw error
                    }
                }
            }
            if (restoredOwner == null) manager.start(nextId())
            else manager.restore(restoredOwner.intervention, store.state.value)
            hub.start(owner)
            if (restoredOwner == null) observe()
        }

        suspend fun close() {
            manager.stop(nextId())
            hub.stop()
            job.cancelAndJoin()
        }

        suspend fun observe() {
            val done = CompletableDeferred<Unit>()
            observationDone = done
            inputs.emit(VehicleSignalInput(clock.nowMs, Source.DEMO, gear = gear,
                quality = mapOf(SignalField.GEAR to quality(clock.nowMs))))
            done.await()
            observationDone = null
        }

        fun quality(tMs: Long) = SignalQuality(
            SignalAvailability.AVAILABLE, Source.DEMO, observedAtMs = tMs,
            observedTimeBasis = ObservationTimeBasis.SCENARIO,
        )

        suspend fun send(input: InterventionInput): ExecutionResult {
            val result = manager.submit(nextId(), input)
            val transitions = manager.transitions.value
            while (recordedTransitions < transitions.size) {
                val transition = transitions[recordedTransitions++]
                record("개입 단계 ${transition.from.level} → ${transition.to.level} / 전환 이유: ${transition.reason}")
            }
            store.update { it.copy(stage = manager.state.value.stage) }
            return result
        }

        suspend fun handleRequest(request: ExecutionRequest) {
            if (request is CancelExecutionRequest) {
                updateRequest(request.requestId, ExecutionStatus.CANCELLED, "정책에 의해 취소됨 · 후속 결과 주입 불가")
            } else {
                val label = requestLabel(request)
                requests[request.requestId] = DebugRequest(request, label, ExecutionStatus.REQUESTED, "요청 발생")
                record("요청 발생: $label (${request.requestId.value})")
                if (request is RestRecoveryResetRequest) {
                    manager.onResult(ExecutionResult(request.requestId, ExecutionStatus.NOT_CONNECTED,
                        ExecutionFailure.NOT_CONNECTED))
                    updateRequest(request.requestId, ExecutionStatus.NOT_CONNECTED,
                        "미연결: 연속 시간·평활값 초기화·예보 담당 연결 필요. 운행 카운터를 임의 변경하지 않음")
                } else {
                    manager.onResult(ExecutionResult(request.requestId, ExecutionStatus.STARTED))
                    updateRequest(request.requestId, ExecutionStatus.STARTED,
                        "가상 시작 · 결과 주입 대기 (실제 음성·SDK 실행 아님)")
                }
            }
            publish()
        }

        fun activeRequest(id: RequestId): DebugRequest? = requests[id]?.takeIf { it.status == ExecutionStatus.STARTED }

        fun updateRequest(id: RequestId, status: ExecutionStatus, detail: String) {
            val previous = requests[id] ?: return
            requests[id] = previous.copy(status = status, detail = detail)
            record("${previous.label}: $status / $detail (${id.value})")
        }

        fun record(message: String) {
            events += DebugEvent(++eventSequence, clock.nowMs, message)
        }

        fun publish() {
            if (session !== this) return
            mutableState.value = DebugSnapshot(clock.nowMs, gear, power, tracker.state.value,
                manager.state.value, requests.values.toList(), events.toList(), true,
                assessmentScore, trending, persistence?.state?.value ?: TripPersistenceState(),
                manager.interruptedChecks.value.values.any { clock.nowMs >= it }, restored != null,
                manager.interruptedChecks.value.values.minOrNull())
        }
    }

    private fun requestLabel(request: ExecutionRequest): String = when (request) {
        is CheckRequest -> when (request.context) {
            CheckContext.DRIVER_ALERTNESS -> "운전자 확인 요청"
            CheckContext.PASSENGER_ALERTNESS -> "동승자 확인 요청"
            CheckContext.WAYPOINT_CONSENT -> "경유지 추가 동의 요청"
            CheckContext.RECOVERY -> "휴식 후 회복 확인 요청"
            CheckContext.PRE_DEPARTURE_CONDITION -> "출발 전 상태 확인 요청"
        }
        is RestRequest -> when (request.action) {
            RestAction.FIND_AHEAD -> "진행 방향 앞 쉼터 검색 요청"
            RestAction.ADD_WAYPOINT -> "쉼터 경유지 추가 요청"
            RestAction.REMOVE_WAYPOINT -> "쉼터 경유지 제거 요청"
        }
        is SpeechRequest -> "음성 안내 요청: ${request.cue}"
        is RestRecoveryResetRequest -> "정상 회복 후 상태 초기화 요청"
        is ClimateRequest -> "운전석 통풍 요청: ${request.action}"
        else -> "외부 실행 요청"
    }
}
