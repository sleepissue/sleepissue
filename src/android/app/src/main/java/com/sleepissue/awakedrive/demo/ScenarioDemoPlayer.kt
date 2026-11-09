package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.signal.VehicleSignalInput
import com.sleepissue.awakedrive.signal.CompleteSignalObservation
import com.sleepissue.awakedrive.signal.CompleteSignalSource
import com.sleepissue.awakedrive.performance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/**
 * JSON 원본 관측을 순서대로 전달하는 Source다. 판단 엔진이나 Hub의 선택 정책을 호출하지 않는다.
 * 소유 scope는 화면/서비스 수명에 묶는다. Hub 구독을 먼저 연결한 뒤 Start한다.
 * load/reset으로 상대 시각을 되돌리기 전 Hub를 stop하고 새 세션으로 구성하는 것은 조립자의 책임이다.
 */
class ScenarioDemoPlayer(
    val clock: DemoClock,
    private val repository: DemoScenarioRepository,
    private val scope: CoroutineScope,
    private val automaticTicks: Boolean = true,
    private val probe: WorkProbe = WorkProbe.NONE,
    private val deliveryAudit: DemoDeliveryAudit? = null,
    val transportMode: DemoTransportMode = DemoTransportMode.SEPARATE_FLOWS,
    private val defaultWorkBudgetMs: Long = PUMP_WORK_BUDGET_MS,
    private val defaultMaxSamples: Int = Int.MAX_VALUE,
) : DemoPlayer, CompleteSignalSource {
    private companion object {
        // 기술적 작업 예산이다. 단일 emit의 대기까지 제한하는 timeout은 아니며 표본 경계에서 양보한다.
        const val PUMP_WORK_BUDGET_MS = 25L
    }
    private data class Emission<T>(val sessionId: Long, val value: T, val original: Boolean = false, val emittedAtNs: Long = -1)
    private val lock = Mutex()
    private val vehicleBus = MutableSharedFlow<Emission<VehicleSignalInput>>()
    private val naviBus = MutableSharedFlow<Emission<NaviState>>()
    private val powerBus = MutableSharedFlow<Emission<PowerEvent>>()
    private val completeBus = MutableSharedFlow<Emission<CompleteSignalObservation>>()
    private val mutableState = MutableStateFlow(DemoPlaybackState(connection = ExecutionStatus.SUCCEEDED))
    override val playbackState = mutableState.asStateFlow()
    @Volatile private var generation = 0L
    override val samples: Flow<VehicleSignalInput> = vehicleBus.filter { it.sessionId == generation }.map {
        probe.end(WorkStage.EMIT_TO_RECEIVE_VEHICLE, it.emittedAtNs)
        if (transportMode == DemoTransportMode.SEPARATE_FLOWS) deliveryAudit?.observe(DemoDeliveryChannel.VEHICLE, it.value.tMs, !it.original)
        it.value
    }
    override val states: Flow<NaviState> = naviBus.filter { it.sessionId == generation }.map {
        probe.end(WorkStage.EMIT_TO_RECEIVE_NAVI, it.emittedAtNs)
        if (transportMode == DemoTransportMode.SEPARATE_FLOWS) deliveryAudit?.observe(DemoDeliveryChannel.NAVI, it.value.tMs, !it.original)
        it.value
    }
    override val events: Flow<PowerEvent> = powerBus.filter { it.sessionId == generation }.map {
        probe.end(WorkStage.EMIT_TO_RECEIVE_POWER, it.emittedAtNs)
        if (transportMode == DemoTransportMode.SEPARATE_FLOWS) deliveryAudit?.observe(DemoDeliveryChannel.POWER, it.value.tMs, !it.original)
        it.value
    }
    override val observations: Flow<CompleteSignalObservation> = completeBus.filter { it.sessionId == generation }.map {
        probe.end(WorkStage.EMIT_TO_RECEIVE_COMPLETE, it.emittedAtNs)
        deliveryAudit?.observe(DemoDeliveryChannel.VEHICLE, it.value.vehicle.tMs, !it.original)
        deliveryAudit?.observe(DemoDeliveryChannel.NAVI, it.value.navi.tMs, !it.original)
        deliveryAudit?.observe(DemoDeliveryChannel.POWER, it.value.power.tMs, !it.original)
        it.value
    }
    private var scenario: DemoScenario? = null
    private var nextSample = 0
    private var deliveredSamples = 0L
    private var lastOriginalSampleMs: Long? = null
    private var lastDeliveryElapsedMs: Long? = null
    private var pumpTargetMs: Long? = null
    private var ticker: Job? = null
    private val completedQuestions = mutableSetOf<RequestId>()

    override suspend fun execute(command: DemoCommand): ExecutionResult = lock.withLock {
        try {
            when (command) {
                is DemoCommand.Load -> load(command)
                is DemoCommand.Start -> {
                    if (playbackState.value.phase != DemoPhase.READY) return@withLock rejected(command, "로드 후 시작해 주세요.")
                    if (!scope.isActive) return@withLock rejected(command, "재생 소유 범위가 종료됐습니다.")
                    clock.start()
                    mutableState.value = playbackState.value.copy(phase = DemoPhase.PLAYING, failureMessage = null)
                    pumpLocked()
                    if (automaticTicks) startTicker()
                    succeeded(command)
                }
                is DemoCommand.Pause -> {
                    if (playbackState.value.phase != DemoPhase.PLAYING) return@withLock rejected(command, "재생 중에만 일시정지할 수 있습니다.")
                    // 수신 시점에 먼저 동결한다. 느린 소비자에게 전달하는 동안 운행을 더 진행하지 않는다.
                    clock.pause()
                    pumpLocked()
                    if (playbackState.value.phase == DemoPhase.COMPLETED) return@withLock succeeded(command)
                    mutableState.value = playbackState.value.copy(phase = DemoPhase.PAUSED)
                    updateTime()
                    succeeded(command)
                }
                is DemoCommand.Resume -> {
                    if (playbackState.value.phase != DemoPhase.PAUSED) return@withLock rejected(command, "일시정지한 재생만 재개할 수 있습니다.")
                    clock.resume()
                    mutableState.value = playbackState.value.copy(phase = DemoPhase.PLAYING, failureMessage = null)
                    updateTime()
                    succeeded(command)
                }
                is DemoCommand.Stop -> {
                    ticker?.cancel(); ticker = null
                    clock.stop()
                    if (scenario != null) invalidateOutputs()
                    mutableState.value = playbackState.value.copy(phase = if (scenario == null) DemoPhase.NOT_LOADED else DemoPhase.STOPPED,
                        questionRequestId = null, questionOutcome = playbackState.value.questionRequestId?.let { DemoQuestionOutcome.CANCELLED })
                    updateTime()
                    succeeded(command)
                }
                is DemoCommand.Reset -> {
                    ticker?.cancel(); ticker = null
                    clock.stop()
                    if (scenario != null) invalidateOutputs()
                    generation++
                    clock.reset(); scenario = null; clearDeliveryCounters(); completedQuestions.clear()
                    mutableState.value = DemoPlaybackState(connection = ExecutionStatus.SUCCEEDED, sessionId = generation)
                    succeeded(command)
                }
                is DemoCommand.QuestionStarted -> startQuestion(command)
                is DemoCommand.QuestionFinished -> finishQuestion(command)
            }
        } catch (cancelled: CancellationException) {
            ticker?.cancel(); ticker = null
            clock.halt()
            mutableState.value = playbackState.value.copy(phase = if (scenario == null) DemoPhase.NOT_LOADED else DemoPhase.STOPPED,
                sessionId = generation, speedMultiplier = 0, questionRequestId = null, questionOutcome = DemoQuestionOutcome.CANCELLED)
            withContext(NonCancellable) {
                if (scenario != null) invalidateOutputs(playbackState.value.scenarioTimeMs)
            }
            throw cancelled
        } catch (_: Exception) {
            failPlayback()
            ExecutionResult(command.requestId, ExecutionStatus.FAILED, ExecutionFailure.ERROR)
        }
    }

    /** 결정적 시험/수동 드라이버용이다. 실제 화면은 소유 scope의 자동 ticker를 사용한다. */
    suspend fun pump(workBudgetMs: Long = defaultWorkBudgetMs, maxSamples: Int = defaultMaxSamples,
        shouldYield: () -> Boolean = { false }, afterSample: suspend (caughtUp: Boolean) -> Unit = {}) = lock.withLock {
        require(workBudgetMs > 0 && maxSamples > 0)
        try { pumpLocked(workBudgetMs, maxSamples, shouldYield, afterSample) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failPlayback() }
    }

    private suspend fun load(command: DemoCommand.Load): ExecutionResult {
        if (playbackState.value.phase in setOf(DemoPhase.PLAYING, DemoPhase.PAUSED)) {
            return rejected(command, "재생을 중지한 뒤 로드해 주세요.")
        }
        ticker?.cancel(); ticker = null
        clock.stop()
        if (scenario != null) invalidateOutputs()
        generation++
        scenario = null; clearDeliveryCounters(); completedQuestions.clear(); clock.reset()
        return when (val loaded = repository.load(command.scenarioId)) {
            is DemoLoadResult.Failed -> {
                mutableState.value = DemoPlaybackState(connection = ExecutionStatus.SUCCEEDED, phase = DemoPhase.FAILED,
                    sessionId = generation, error = loaded.error)
                ExecutionResult(command.requestId, ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)
            }
            is DemoLoadResult.Loaded -> {
                val value = probe.measure(WorkStage.DEMO_PREPARE) { prepareScenario(loaded.scenario) }
                clock.configure(value.durationMs, value.speedSegments)
                scenario = value
                deliveryAudit?.reset(value.samples.map { it.tMs })
                mutableState.value = DemoPlaybackState(connection = ExecutionStatus.SUCCEEDED, phase = DemoPhase.READY,
                    scenarioId = value.id, sessionId = generation)
                updateTime()
                succeeded(command)
            }
        }
    }

    /**
     * 재생 중의 원본 복사를 로드 경계로 옮긴다. 값·availability·quality 키는 그대로 보존한다.
     * 시나리오 시간만 고정하며 실제 수신 시각은 null로 둬 Hub가 수신할 때 부여하게 한다.
     * Repository나 공개 Flow 구독자의 가변 컨테이너가 이후 원본을 바꾸지 못하도록 사본을 동결한다.
     */
    private fun prepareScenario(value: DemoScenario): DemoScenario {
        fun canonicalQuality(q: SignalQuality, atMs: Long): SignalQuality =
            if (q.source == Source.DEMO && q.observedAtMs == atMs &&
                q.observedTimeBasis == ObservationTimeBasis.SCENARIO && q.receivedAtElapsedMs == null &&
                q.sourceRateHz == null && q.sourceRateInfo == null && q.observationSequence == null && q.selectionReason == null) q
            else q.copy(source = Source.DEMO, observedAtMs = atMs, observedTimeBasis = ObservationTimeBasis.SCENARIO,
                receivedAtElapsedMs = null, sourceRateHz = null, sourceRateInfo = null,
                observationSequence = null, selectionReason = null)
        val samples = value.samples.map { sample ->
            val powerQuality = canonicalQuality(sample.power.quality, sample.tMs)
            val power = if (sample.power.tMs == sample.tMs && powerQuality === sample.power.quality) sample.power
                else sample.power.copy(tMs = sample.tMs, quality = powerQuality)
            sample.copy(
                vehicle = sample.vehicle.copy(tMs = sample.tMs, source = Source.DEMO,
                    quality = Collections.unmodifiableMap(sample.vehicle.quality.mapValues { canonicalQuality(it.value, sample.tMs) }),
                    updatedFields = sample.vehicle.updatedFields?.let { Collections.unmodifiableSet(it.toSet()) }),
                navi = sample.navi.copy(tMs = sample.tMs,
                    quality = Collections.unmodifiableMap(sample.navi.quality.mapValues { canonicalQuality(it.value, sample.tMs) })),
                power = power,
            )
        }
        return value.copy(samples = Collections.unmodifiableList(samples),
            speedSegments = Collections.unmodifiableList(value.speedSegments.toList()))
    }

    private suspend fun startQuestion(command: DemoCommand.QuestionStarted): ExecutionResult {
        if (command.sessionId != generation || playbackState.value.phase !in setOf(DemoPhase.PLAYING, DemoPhase.PAUSED)) {
            return rejected(command, "이 재생 세션의 질문 시작이 아닙니다.")
        }
        val current = playbackState.value.questionRequestId
        if (current == command.requestId) return succeeded(command)
        if (current != null || command.requestId in completedQuestions) return rejected(command, "중첩 질문 또는 종료된 질문 ID입니다.")
        // 질문 이벤트의 수신 시점에 바로 1배로 전환한다. 다음 100ms 발행을 기다리지 않는다.
        clock.setQuestionActive(true)
        mutableState.value = playbackState.value.copy(questionRequestId = command.requestId, questionOutcome = null, failureMessage = null)
        updateTime()
        return succeeded(command)
    }

    private fun finishQuestion(command: DemoCommand.QuestionFinished): ExecutionResult {
        if (command.sessionId != generation) return rejected(command, "이전 세션의 질문 종료입니다.")
        if (playbackState.value.questionRequestId != command.requestId) {
            return if (command.requestId in completedQuestions) succeeded(command) else rejected(command, "시작하지 않은 질문의 종료입니다.")
        }
        clock.setQuestionActive(false)
        completedQuestions += command.requestId
        mutableState.value = playbackState.value.copy(questionRequestId = null, questionOutcome = command.outcome, failureMessage = null)
        updateTime()
        return succeeded(command)
    }

    private fun startTicker() {
        ticker?.cancel()
        val session = generation
        // 본문 진입 전 소유 scope가 취소돼도 finally 정리 경로를 설치한다.
        ticker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (isActive) {
                    // 60배에서도 100ms 원본 간격(실제 약1.67ms)을 따라간다. 늦으면 원본 시각 순으로 따라잡는다.
                    delay(1)
                    pump()
                    if (playbackState.value.phase !in setOf(DemoPhase.PLAYING, DemoPhase.PAUSED)) break
                }
            } finally {
                // 소유 scope 종료 시 화면의 재생 배지를 남기거나 배속 시계를 계속 돌리지 않는다.
                val terminatingJob = coroutineContext[Job]
                withContext(NonCancellable) {
                    lock.withLock {
                        // 이전 ticker의 늦은 finally가 새 로드/재생 세션을 중지하지 않게 한다.
                        if (generation == session && ticker === terminatingJob &&
                            playbackState.value.phase in setOf(DemoPhase.PLAYING, DemoPhase.PAUSED)) {
                            runCatching { clock.stop() }
                            mutableState.value = playbackState.value.copy(phase = DemoPhase.STOPPED, speedMultiplier = 0,
                                questionRequestId = null, questionOutcome = DemoQuestionOutcome.CANCELLED)
                        }
                    }
                }
            }
        }
    }

    private suspend fun pumpLocked(workBudgetMs: Long = defaultWorkBudgetMs, maxSamples: Int = defaultMaxSamples,
        shouldYield: () -> Boolean = { false }, afterSample: suspend (caughtUp: Boolean) -> Unit = {}): Unit = probe.measure(WorkStage.PUMP) {
        if (playbackState.value.phase !in setOf(DemoPhase.PLAYING, DemoPhase.PAUSED)) return
        val loaded = requireNotNull(scenario)
        val startedElapsedMs = clock.elapsedRealtimeMs()
        var now = clock.scenarioTimeMs()
        pumpTargetMs = now
        var emittedInPump = 0
        while (nextSample < loaded.samples.size && loaded.samples[nextSample].tMs <= now) {
            currentCoroutineContext().ensureActive()
            val sample = loaded.samples[nextSample]
            // 로드 때 확정한 불변 원본을 그대로 전달한다. 실제 수신 시각은 Hub가 별도 품질에 부여한다.
            probe.measure(WorkStage.PUMP_DELIVERY) {
                deliver(sample.vehicle, sample.navi, sample.power, original = true)
            }
            nextSample++
            emittedInPump++
            deliveredSamples++
            lastOriginalSampleMs = sample.tMs
            lastDeliveryElapsedMs = clock.elapsedRealtimeMs()
            // 완전한 세 채널 경계에서 발행 기회를 준다. 원본을 건너뛰거나 과거 프레임을 만들지 않는다.
            afterSample(nextSample >= loaded.samples.size || loaded.samples[nextSample].tMs > clock.scenarioTimeMs())
            // 전달하는 동안에도 실제 시계는 흐른다. 진입 목표에만 머물면 60배에서 곧 STALE이 된다.
            // PAUSED는 시각만 동결하고, 예산 때문에 남은 원본은 다음 pump에서도 순서대로 전달한다.
            now = clock.scenarioTimeMs()
            pumpTargetMs = now
            if (emittedInPump >= maxSamples || shouldYield() || clock.elapsedRealtimeMs() - startedElapsedMs >= workBudgetMs) break
        }
        updateTime()
        // 끝에 도달했어도 아직 못 전한 원본은 버리지 않는다. 세 채널을 모두 전달한 뒤 종료한다.
        if (clock.scenarioTimeMs() >= loaded.durationMs && nextSample == loaded.samples.size) {
            clock.stop()
            invalidateOutputs()
            mutableState.value = playbackState.value.copy(phase = DemoPhase.COMPLETED, speedMultiplier = 0,
                questionRequestId = null, questionOutcome = playbackState.value.questionRequestId?.let { DemoQuestionOutcome.CANCELLED })
            updateTime()
        }
    }

    /** 새 원천 관측을 꾸며 내지 않는 명시적 무효화다. null은 마지막 값 유지가 아니다. */
    private suspend fun invalidateOutputs(at: Long = clock.scenarioTimeMs()) {
        val unavailable = SignalQuality(SignalAvailability.UNAVAILABLE, Source.DEMO, at,
            observedTimeBasis = ObservationTimeBasis.SCENARIO)
        deliver(VehicleSignalInput(at, Source.DEMO, quality = SignalField.entries.associateWith { unavailable }),
            NaviState(at, quality = NaviField.entries.associateWith { unavailable }),
            PowerEvent(at, PowerState.UNKNOWN, unavailable), original = false)
    }

    private suspend fun deliver(vehicle: VehicleSignalInput, navi: NaviState, power: PowerEvent, original: Boolean) {
        if (transportMode == DemoTransportMode.COMPLETE_OBSERVATION) {
            probe.measure(WorkStage.PUMP_COMPLETE_EMIT) {
                completeBus.emit(Emission(generation, CompleteSignalObservation(vehicle, navi, power), original,
                    probe.begin(WorkStage.EMIT_TO_RECEIVE_COMPLETE)))
            }
        }
        // 기존 인터페이스의 활성 구독자도 같은 원본을 받는다. 내부 묶음 Hub는 이 세 Flow를 구독하지 않는다.
        if (transportMode == DemoTransportMode.SEPARATE_FLOWS || vehicleBus.subscriptionCount.value > 0) {
            probe.measure(WorkStage.PUMP_VEHICLE_EMIT) {
                vehicleBus.emit(Emission(generation, vehicle, original, probe.begin(WorkStage.EMIT_TO_RECEIVE_VEHICLE)))
            }
        }
        if (transportMode == DemoTransportMode.SEPARATE_FLOWS || naviBus.subscriptionCount.value > 0) {
            probe.measure(WorkStage.PUMP_NAVI_EMIT) {
                naviBus.emit(Emission(generation, navi, original, probe.begin(WorkStage.EMIT_TO_RECEIVE_NAVI)))
            }
        }
        if (transportMode == DemoTransportMode.SEPARATE_FLOWS || powerBus.subscriptionCount.value > 0) {
            probe.measure(WorkStage.PUMP_POWER_EMIT) {
                powerBus.emit(Emission(generation, power, original, probe.begin(WorkStage.EMIT_TO_RECEIVE_POWER)))
            }
        }
    }

    /** Controller의 직렬 실행 통로에서만 다음 원본 시한을 조회한다. 시계·전달 위치를 바꾸지 않는다. */
    fun nextOriginalScenarioMs(): Long {
        val loaded = scenario ?: return Long.MAX_VALUE
        return if (nextSample < loaded.samples.size) loaded.samples[nextSample].tMs else loaded.durationMs
    }

    /** 같은 재생 실행 통로에서만 읽는다. 묶음 중간 trace에도 방금 전달한 원본 계수를 제공한다. */
    fun playbackSnapshot(): DemoPlaybackState {
        val time = clock.snapshot()
        return playbackState.value.copy(scenarioTimeMs = time.scenarioTimeMs, realElapsedMs = time.realElapsedMs,
            segmentMultiplier = time.segmentMultiplier, speedMultiplier = time.speedMultiplier,
            deliveredSamples = deliveredSamples, lastOriginalSampleMs = lastOriginalSampleMs,
            lastDeliveryElapsedMs = lastDeliveryElapsedMs, pumpTargetMs = pumpTargetMs,
            pumpFinishedScenarioMs = time.scenarioTimeMs)
    }

    private fun updateTime() { mutableState.value = playbackSnapshot() }

    private fun clearDeliveryCounters() {
        nextSample = 0; deliveredSamples = 0
        lastOriginalSampleMs = null; lastDeliveryElapsedMs = null; pumpTargetMs = null
    }

    private suspend fun failPlayback() {
        // 시계 오류로 멈추는 경우 같은 시계를 다시 읽지 않는다. 소유자는 Hub도 중지해야 한다.
        clock.halt()
        mutableState.value = playbackState.value.copy(phase = DemoPhase.FAILED, speedMultiplier = 0,
            questionRequestId = null, questionOutcome = DemoQuestionOutcome.FAILED,
            failureMessage = "재생 입력 또는 단조 시계 오류입니다. 중지 후 다시 로드해 주세요.")
        if (scenario != null) invalidateOutputs(playbackState.value.scenarioTimeMs)
        ticker?.cancel(); ticker = null
    }
    private fun succeeded(command: DemoCommand) = ExecutionResult(command.requestId, ExecutionStatus.SUCCEEDED)
    private fun rejected(command: DemoCommand, message: String): ExecutionResult {
        mutableState.value = playbackState.value.copy(failureMessage = message)
        return ExecutionResult(command.requestId, ExecutionStatus.FAILED, ExecutionFailure.ERROR)
    }
}
