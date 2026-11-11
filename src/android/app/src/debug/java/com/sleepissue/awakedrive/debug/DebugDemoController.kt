package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.demo.*
import com.sleepissue.awakedrive.fatigue.FatigueBridge
import com.sleepissue.awakedrive.performance.*
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckResult as VoiceCheckResult
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser
import com.sleepissue.awakedrive.signal.*
import com.sleepissue.awakedrive.voice.CheckManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

enum class DebugDemoQuestionPhase { IDLE, GUIDANCE, AWAITING_VOICE, VOICE_STARTED, FINISHED, FAILED, CANCELLED }

data class DebugDemoSnapshot(
    val playback: DemoPlaybackState = DemoPlaybackState(),
    val frame: SignalFrame? = null,
    val navi: NaviState? = null,
    val power: PowerEvent? = null,
    val diagnostics: HubDiagnostics = HubDiagnostics(),
    val fakeRealEnabled: Boolean = false,
    val questionPhase: DebugDemoQuestionPhase = DebugDemoQuestionPhase.IDLE,
    val questionElapsedMs: Long? = null,
    val response: VoiceCheckResult? = null,
    val message: String = "검증 예제를 로드해 주세요.",
    val ready: Boolean = false,
    val durationMs: Long? = null,
    val traceSummary: String = "",
    val terminalTrace: DebugDemoTrace.CapturedTrace? = null,
    val deliveryAudit: DemoDeliveryAuditSnapshot? = null,
    val performanceStages: Map<String, Map<String, Number>> = emptyMap(),
    val transportMode: DemoTransportMode = DemoTransportMode.SEPARATE_FLOWS,
    val sourcePreparation: String = "UNKNOWN",
    val workerBackend: String = "CALLER_SCOPE",
    val detailedTimingEnabled: Boolean = false,
    val replayStartedAtElapsedMs: Long? = null,
    val processingCompletedAtElapsedMs: Long? = null,
)

/**
 * JSON/가짜 질문/가짜 REAL 전용 세션이다. Tracker·Store·Room·SDK를 소유하지 않는다.
 * Flow는 ROS 토픽처럼 Hub 입력에만 연결하고, 화면이 사라지면 재생과 수집을 정리한다.
 */
class DebugDemoController(
    private val scope: CoroutineScope,
    private val realClock: Clock,
    private val automaticTicks: Boolean = true,
    private val probe: WorkProbe = WorkProbe.NONE,
    private val transportMode: DemoTransportMode = DemoTransportMode.COMPLETE_OBSERVATION,
    private val maxSamplesPerTick: Int = if (automaticTicks) 4 else Int.MAX_VALUE,
    private val adaptiveTicks: Boolean = true,
    private val workerBackend: String = "CALLER_SCOPE",
    private val restAreas: List<com.sleepissue.awakedrive.engine.rest.RestArea> = emptyList(),
) {
    private val config = EngineConfig()
    private val commands = Mutex()
    private val pendingCommands = AtomicInteger()
    private val clock = DemoClock(realClock)
    private var json = ""
    private var loadedScenario: DemoScenario? = null
    private val repository = DemoScenarioRepository {
        DemoJsonLoader().parse(json).also { loadedScenario = (it as? DemoLoadResult.Loaded)?.scenario }
    }
    private val deliveryAudit = DemoDeliveryAudit()
    private val player = ScenarioDemoPlayer(clock, repository, scope, automaticTicks = false, probe = probe, deliveryAudit = deliveryAudit, transportMode = transportMode,
        defaultWorkBudgetMs = 2, defaultMaxSamples = maxSamplesPerTick)
    // 재생 목표는 실제 경과대로 흐르되, Hub는 아직 전달하지 않은 원본보다 앞서 평가하지 않는다.
    // 수신 중인 원본도 nextOriginalScenarioMs에 포함된다. 실제 REAL의 TTL 시계는 그대로다.
    private val handoffClock = object : Clock {
        override fun scenarioTimeMs() = minOf(clock.scenarioTimeMs(), player.nextOriginalScenarioMs())
        override fun elapsedRealtimeMs() = realClock.elapsedRealtimeMs()
        override fun wallTimeMs() = realClock.wallTimeMs()
    }
    private val realVehicle = MutableSharedFlow<VehicleSignalInput>()
    private val realNavi = MutableSharedFlow<NaviState>()
    private val realPower = MutableSharedFlow<PowerEvent>()
    private val hub = VehicleSignalHub(listOf(
        HubSourceSet(Source.DEMO, player, player, player, ObservationTimeBasis.SCENARIO,
            complete = player.takeIf { transportMode == DemoTransportMode.COMPLETE_OBSERVATION }),
        HubSourceSet(Source.REAL,
            object : VehicleSignalSource { override val samples = realVehicle.asSharedFlow() },
            object : NaviSignalSource { override val states = realNavi.asSharedFlow() },
            object : PowerSignalSource { override val events = realPower.asSharedFlow() },
            ObservationTimeBasis.ELAPSED_REALTIME, initiallyEnabled = false),
    ), handoffClock, debugPolicy(), config, probe)
    private val mutableState = MutableStateFlow(DebugDemoSnapshot())
    val state = mutableState.asStateFlow()

    private var presentation: DemoUiSession? = null
    private var presentationJob: Job? = null
    private val mutablePresentation = MutableStateFlow(com.sleepissue.awakedrive.ui.DriveSessionUiState())
    val presentationState = mutablePresentation.asStateFlow()

    private suspend fun resetPresentation() {
        presentationJob?.cancelAndJoin()
        presentationJob = null
        presentation = null
        mutablePresentation.value = com.sleepissue.awakedrive.ui.DriveSessionUiState()
    }

    private fun startPresentation() {
        val scenario = loadedScenario ?: return
        val owner = DemoUiSession(clock, scenario.startEpochMs, restAreas, config,
            player.playbackState.value.sessionId)
        presentation = owner
        presentationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.samples.collect { frame ->
                owner.observe(frame, hub.latestNavi.value, hub.latestPower.value)
                mutablePresentation.value = owner.state.copy(
                    demoPlaying = player.playbackState.value.phase == DemoPhase.PLAYING)
            }
        }
    }

    suspend fun uiAction(action: com.sleepissue.awakedrive.ui.DriveUiAction) = command {
        presentation?.action(action)
        presentation?.let { mutablePresentation.value = it.state.copy(demoPlaying = player.playbackState.value.phase == DemoPhase.PLAYING) }
    }

    suspend fun recoveryResult(outcome: CheckOutcome) = command {
        presentation?.recoveryResult(outcome)
        presentation?.let { mutablePresentation.value = it.state }
    }

    private var recoveryVoiceToken: Pair<RequestId, Long>? = null

    suspend fun beginRecoveryVoice() = command {
        if (player.playbackState.value.phase == DemoPhase.PLAYING) {
            val token = nextId("recovery-voice") to player.playbackState.value.sessionId
            val result = player.execute(DemoCommand.QuestionStarted(token.first, token.second))
            if (result.status == ExecutionStatus.SUCCEEDED) recoveryVoiceToken = token
        }
    }

    suspend fun endRecoveryVoice(outcome: CheckOutcome) = command {
        recoveryVoiceToken?.let { token ->
            player.execute(DemoCommand.QuestionFinished(token.first, token.second, DemoQuestionOutcome.FINISHED))
        }
        recoveryVoiceToken = null
        presentation?.recoveryResult(outcome)
        presentation?.let { mutablePresentation.value = it.state }
    }

    private var initialized = false
    private var ticker: Job? = null
    private var catchUpPending = false
    private var waitingNearDeadline = false
    private var timeout: Job? = null
    private var sequence = 0L
    private var fakeRealEnabled = false
    private var lastRealEmissionMs: Long? = null
    private var questionPhase = DebugDemoQuestionPhase.IDLE
    private var questionStartedAtMs: Long? = null
    private var questionEndedAtMs: Long? = null
    private var questionToken: Pair<RequestId, Long>? = null
    private var lastQuestionToken: Pair<RequestId, Long>? = null
    private var response: VoiceCheckResult? = null
    private var message = "검증 예제를 로드해 주세요."
    private var checkManager = newCheckManager()
    private val trace = DebugDemoTrace(probe)
    private var terminalTrace: DebugDemoTrace.CapturedTrace? = null
    private val terminalCaptures = Channel<DebugDemoTrace.CapturedTrace>(Channel.UNLIMITED)
    val completedTraces: Flow<DebugDemoTrace.CapturedTrace> = terminalCaptures.receiveAsFlow()
    private var lastScreenSnapshotMs: Long? = null
    private var lastMetricsSnapshotMs: Long? = null
    private var replayStartedAtElapsedMs: Long? = null
    private var processingCompletedAtElapsedMs: Long? = null

    suspend fun initialize() = commands.withLock {
        if (initialized) return@withLock
        initialized = true
        if (automaticTicks) ticker = scope.launch {
            // 60배 구간에서도 100ms 시나리오 관측을 짧은 실제 주기로 전달한다.
            var nearDeadlineYields = 0
            while (isActive) {
                when {
                    catchUpPending || pendingCommands.get() > 0 -> {
                        nearDeadlineYields = 0
                        yield()
                    }
                    // 1ms 미만의 다음 원본 시한에서는 bounded yield로 정수 ms 대기의 초과를 줄인다.
                    // 횟수 상한 뒤에는 반드시 delay하여 멈춘 시험 시계에서도 무한 실행하지 않는다.
                    waitingNearDeadline && nearDeadlineYields < 64 -> {
                        nearDeadlineYields++
                        yield()
                    }
                    else -> {
                        nearDeadlineYields = 0
                        delay(1)
                    }
                }
                if (pendingCommands.get() == 0) tick()
            }
        }
        publish()
    }

    suspend fun loadJson(value: String) = commands.withLock {
        check(initialized)
        // 시간축을 0으로 돌리기 전에 기존 Hub 캐시·대기 세대를 먼저 폐기한다.
        hub.stop()
        resetPresentation()
        player.execute(DemoCommand.Stop(nextId("before-load")))
        // 다음 로드가 화면의 종료 캡처를 지우기 전에 저장 통로에 인계한다.
        if (loadedScenario != null && terminalTrace == null) publish()
        clearQuestion()
        fakeRealEnabled = false
        lastRealEmissionMs = null
        terminalTrace = null
        replayStartedAtElapsedMs = null
        processingCompletedAtElapsedMs = null
        json = value
        val result = player.execute(DemoCommand.Load(nextId("load"), "debug-editor"))
        if (player.playbackState.value.phase == DemoPhase.READY) {
            startPresentation()
            hub.start(scope)
        }
        message = "JSON 로드: ${result.status} · phase=${player.playbackState.value.phase} · error=${player.playbackState.value.error}"
        publish()
    }

    suspend fun start() = command {
        if (player.playbackState.value.phase == DemoPhase.READY)
            replayStartedAtElapsedMs = realClock.elapsedRealtimeMs()
        message = "시작: ${player.execute(DemoCommand.Start(nextId("start"))).status}"
    }

    suspend fun pause() = command {
        message = "일시정지: ${player.execute(DemoCommand.Pause(nextId("pause"))).status}"
    }

    suspend fun resume() = command {
        message = "재개: ${player.execute(DemoCommand.Resume(nextId("resume"))).status}"
    }

    suspend fun stop() = command {
        player.execute(DemoCommand.Stop(nextId("stop")))
        publish(updateScreen = false, retainTerminal = false)
        hub.stop()
        clearQuestion()
        fakeRealEnabled = false
        message = "중지 · Hub 캐시 제거 · 다시 재생하려면 JSON 로드 → 시작"
    }

    suspend fun reset() = command {
        if (loadedScenario != null && terminalTrace == null) {
            player.execute(DemoCommand.Stop(nextId("before-reset")))
            publish(updateScreen = false, retainTerminal = false)
            hub.stop()
            publish()
        }
        hub.stop()
        resetPresentation()
        player.execute(DemoCommand.Reset(nextId("reset")))
        loadedScenario = null
        terminalTrace = null
        replayStartedAtElapsedMs = null
        processingCompletedAtElapsedMs = null
        deliveryAudit.reset(emptyList())
        clearQuestion()
        fakeRealEnabled = false
        lastRealEmissionMs = null
        message = "초기화 · JSON 미로드 · 기존 운행/Room 저장본은 보존"
    }

    suspend fun setFakeReal(enabled: Boolean) = command {
        check(hub.diagnostics.value.running) { "JSON 로드 후 시작해 주세요." }
        if (enabled && !fakeRealEnabled) hub.startInput(Source.REAL)
        if (!enabled && fakeRealEnabled) hub.stopInput(Source.REAL)
        fakeRealEnabled = enabled
        if (enabled) emitFakeReal()
        hub.publishDue()
        message = if (enabled) "가짜 REAL 켜짐 · SDK 수신이 아님 · 80km/h / 좌표37.5,127.0" else "가짜 REAL 꺼짐 · DEMO 대체 확인"
    }

    /** 실제 TTS가 아니라 안내 시작 이벤트를 수동으로 재현한다. */
    suspend fun beginQuestion() = command {
        val playback = player.playbackState.value
        check(playback.phase == DemoPhase.PLAYING) { "재생 중에 질문 안내 시작을 눌러 주세요." }
        check(questionToken == null) { "현재 질문을 판정·실패·취소로 끝내 주세요." }
        val token = nextId("question") to playback.sessionId
        player.execute(DemoCommand.QuestionStarted(token.first, token.second))
        if (player.playbackState.value.questionRequestId == token.first) {
            questionToken = token
            checkManager = newCheckManager()
            questionPhase = DebugDemoQuestionPhase.GUIDANCE
            questionStartedAtMs = realClock.elapsedRealtimeMs()
            questionEndedAtMs = null
            response = null
            message = "가짜 안내 시작 · 판정 종료까지 1배속 · 실제 TTS 연결은 미완료"
        }
    }

    suspend fun repeatQuestionStart() = command {
        val token = checkNotNull(questionToken) { "진행 중인 질문이 없습니다." }
        message = "중복 시작: ${player.execute(DemoCommand.QuestionStarted(token.first, token.second)).status} · 같은 질문 유지"
    }

    suspend fun markBeepEnded() = command {
        check(questionPhase == DebugDemoQuestionPhase.GUIDANCE) { "질문 안내 시작을 먼저 눌러 주세요." }
        checkManager.onBeepEnded()
        questionPhase = DebugDemoQuestionPhase.AWAITING_VOICE
        message = "가짜 신호음 끝 · 지금부터 배속 없는 실제 응답 시간 측정"
        val token = questionToken
        timeout?.cancel()
        timeout = scope.launch {
            delay(config.voiceResponseTimeoutMs)
            commands.withLock {
                if (questionToken == token && questionPhase == DebugDemoQuestionPhase.AWAITING_VOICE) {
                    finishQuestionInternal()
                    publish()
                }
            }
        }
    }

    suspend fun markVoiceStarted() = command {
        check(questionPhase == DebugDemoQuestionPhase.AWAITING_VOICE) { "신호음 끝을 먼저 눌러 주세요." }
        checkManager.onVoiceStarted()
        timeout?.cancel()
        timeout = null
        questionPhase = DebugDemoQuestionPhase.VOICE_STARTED
        message = "가짜 목소리 시작 기록 · 판정 종료를 늦춰도 응답 시간은 늘지 않음"
    }

    suspend fun finishQuestion() = command {
        check(questionPhase == DebugDemoQuestionPhase.AWAITING_VOICE || questionPhase == DebugDemoQuestionPhase.VOICE_STARTED) {
            "신호음 끝을 먼저 눌러 주세요."
        }
        finishQuestionInternal()
    }

    suspend fun failQuestion() = command { endQuestion(DemoQuestionOutcome.FAILED) }
    suspend fun cancelQuestion() = command(WorkStage.QUESTION_CANCEL_WAIT, WorkStage.QUESTION_CANCEL_BODY) { endQuestion(DemoQuestionOutcome.CANCELLED) }

    suspend fun repeatPreviousQuestionResult() = command {
        val token = checkNotNull(lastQuestionToken) { "이전 질문이 없습니다." }
        message = "이전/중복 판정: ${player.execute(DemoCommand.QuestionFinished(token.first, token.second)).status} · 현재 질문 배속 확인"
    }

    /** 자동 실행과 결정적인 단위시험이 공유하는 실제 경과 Tick이다. */
    suspend fun tick() = commands.withLock {
        if (!initialized) return@withLock
        val measuredTick = probe.begin(WorkStage.CONTROLLER_TICK)
        try {
            waitingNearDeadline = false
            if (automaticTicks && adaptiveTicks && !catchUpPending && player.playbackState.value.phase == DemoPhase.PLAYING) {
                val remaining = try {
                    probe.measure(WorkStage.SCHEDULER_POLL) {
                        val diagnostics = hub.diagnostics.value
                        val publicationAt = if (diagnostics.publicationTimeBasis == ObservationTimeBasis.SCENARIO)
                            diagnostics.nextPublicationAtMs ?: Long.MAX_VALUE else Long.MAX_VALUE
                        minOf(player.nextOriginalScenarioMs(), publicationAt) - clock.scenarioTimeMs()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // 시계 오류 처리는 기존 Player.pump의 실패·무효화 경로에 맡긴다.
                    0L
                }
                if (remaining > 0) {
                    waitingNearDeadline = remaining < (player.playbackState.value.speedMultiplier ?: 1)
                    return@withLock
                }
            }
            // 25ms 묶음 끝을 기다리지 않고 완전 관측 경계에서 발행한다.
            // 밀린 원본이 없으면 즉시, 따라잡는 동안에는 실제1ms마다 발행 기회를 준다.
            val pumpStarted = realClock.elapsedRealtimeMs()
            catchUpPending = false
            player.pump(workBudgetMs = 2, maxSamples = maxSamplesPerTick, shouldYield = { pendingCommands.get() > 0 }) { caughtUp ->
                catchUpPending = !caughtUp
                if ((caughtUp || realClock.elapsedRealtimeMs() - pumpStarted >= 1) && hub.publishDue()) {
                    publish(updateScreen = false)
                }
            }
            val phase = player.playbackState.value.phase
            if (phase in setOf(DemoPhase.STOPPED, DemoPhase.COMPLETED, DemoPhase.FAILED)) {
                catchUpPending = false
                if (hub.diagnostics.value.running) {
                    publish(updateScreen = false, retainTerminal = false)
                    hub.stop()
                }
                fakeRealEnabled = false
                if (questionToken != null) clearQuestion()
            } else if (hub.diagnostics.value.running) {
                if (fakeRealEnabled && lastRealEmissionMs?.let { realClock.elapsedRealtimeMs() - it >= 100 } != false) emitFakeReal()
                hub.publishDue()
            }
            publish(updateScreen = !automaticTicks || terminalTrace == null && phase in setOf(DemoPhase.COMPLETED, DemoPhase.STOPPED, DemoPhase.FAILED))
        } finally { probe.end(WorkStage.CONTROLLER_TICK, measuredTick) }
    }

    suspend fun close() {
        ticker?.cancelAndJoin(); ticker = null
        commands.withLock {
            if (!initialized) return@withLock
            player.execute(DemoCommand.Stop(nextId("close")))
            publish(updateScreen = false, retainTerminal = false)
            hub.stop()
            clearQuestion()
            fakeRealEnabled = false
            initialized = false
            message = "화면 중지 · 재생·Hub 수집 종료 · Room 보존"
            publish()
        }
    }

    suspend fun captureTrace(): DebugDemoTrace.CapturedTrace = commands.withLock { terminalTrace ?: trace.capture() }

    suspend fun exportTrace(): List<Map<String, Any?>> = commands.withLock { terminalTrace ?: trace.capture() }.records(
        mapOf("performanceStages" to probe.snapshot()))

    private suspend fun command(waitStage: WorkStage = WorkStage.CONTROLLER_COMMAND_WAIT,
        bodyStage: WorkStage = WorkStage.CONTROLLER_COMMAND_BODY, action: suspend () -> Unit) {
        pendingCommands.incrementAndGet()
        var acquired = false
        val wait = probe.begin(WorkStage.CONTROLLER_COMMAND_WAIT)
        val specificWait = if (waitStage != WorkStage.CONTROLLER_COMMAND_WAIT) probe.begin(waitStage) else -1L
        try {
            commands.lock()
            acquired = true
            pendingCommands.decrementAndGet()
            probe.end(WorkStage.CONTROLLER_COMMAND_WAIT, wait)
            probe.end(waitStage, specificWait)
            probe.measure(WorkStage.CONTROLLER_COMMAND_BODY) {
                check(initialized)
                if (bodyStage == WorkStage.CONTROLLER_COMMAND_BODY) action()
                else probe.measure(bodyStage) { action() }
                publish()
            }
        } finally {
            if (acquired) commands.unlock() else pendingCommands.decrementAndGet()
        }
    }

    private suspend fun finishQuestionInternal() {
        response = checkManager.finish(config.defaultVoiceBaselineMs, QuestionType.DRIVER_CHECK,
            if (questionPhase == DebugDemoQuestionPhase.VOICE_STARTED) "네" else null)
        endQuestion(DemoQuestionOutcome.FINISHED)
        message = "가짜 판정 ${response?.verdict} · 실제 응답 ${response?.latencyMs ?: "없음"}ms · 구간 배속 복귀"
    }

    private suspend fun endQuestion(outcome: DemoQuestionOutcome) {
        val token = checkNotNull(questionToken) { "진행 중인 질문이 없습니다." }
        player.execute(DemoCommand.QuestionFinished(token.first, token.second, outcome))
        timeout?.cancel(); timeout = null
        questionEndedAtMs = realClock.elapsedRealtimeMs()
        lastQuestionToken = token
        questionToken = null
        questionPhase = when (outcome) {
            DemoQuestionOutcome.FINISHED -> DebugDemoQuestionPhase.FINISHED
            DemoQuestionOutcome.FAILED -> DebugDemoQuestionPhase.FAILED
            DemoQuestionOutcome.CANCELLED -> DebugDemoQuestionPhase.CANCELLED
        }
        if (outcome != DemoQuestionOutcome.FINISHED) message = "가짜 질문 $outcome · 무응답 판정과 구별 · 구간 배속 복귀"
    }

    private fun clearQuestion() {
        timeout?.cancel(); timeout = null
        questionToken?.let { lastQuestionToken = it }
        questionToken = null
        questionStartedAtMs = null
        questionEndedAtMs = null
        response = null
        questionPhase = DebugDemoQuestionPhase.IDLE
        checkManager = newCheckManager()
    }

    private fun newCheckManager() = CheckManager(clock, CheckPolicy(config), SpeechIntentParser())
    private fun nextId(prefix: String) = RequestId("demo-debug-$prefix-${++sequence}")
    private fun publish(updateScreen: Boolean = true, retainTerminal: Boolean = true) = probe.measure(WorkStage.UI_SNAPSHOT) {
        val playing = player.playbackState.value.phase == DemoPhase.PLAYING
        if (mutablePresentation.value.demoPlaying != playing) mutablePresentation.value = mutablePresentation.value.copy(demoPlaying = playing)
        val now = realClock.elapsedRealtimeMs()
        val screenDue = updateScreen || lastScreenSnapshotMs?.let { now - it >= 100 } != false
        val phaseNow = player.playbackState.value.phase
        if (phaseNow == DemoPhase.COMPLETED && processingCompletedAtElapsedMs == null)
            processingCompletedAtElapsedMs = now
        val terminalDue = retainTerminal && terminalTrace == null && phaseNow in setOf(DemoPhase.COMPLETED, DemoPhase.STOPPED, DemoPhase.FAILED)
        val metricsDue = terminalDue || lastMetricsSnapshotMs?.let { now - it >= 100 } != false
        if (metricsDue) lastMetricsSnapshotMs = now
        // FAILED에서는 Player가 보존한 마지막 안전 위치만 사용한다. 실패한 시계를 다시 읽지 않는다.
        val playback = if (phaseNow == DemoPhase.FAILED) player.playbackState.value else player.playbackSnapshot()
        val snapshot = DebugDemoSnapshot(playback, hub.latestFrame.value,
            hub.latestNavi.value, hub.latestPower.value, hub.diagnostics.value, fakeRealEnabled,
            questionPhase, questionStartedAtMs?.let { (questionEndedAtMs ?: realClock.elapsedRealtimeMs()) - it },
            response, message, initialized, loadedScenario?.durationMs,
            deliveryAudit = if (metricsDue) deliveryAudit.snapshot() else null,
            performanceStages = if (metricsDue) probe.snapshot() else emptyMap(), transportMode = transportMode, sourcePreparation = "LOAD_FROZEN", workerBackend = workerBackend, detailedTimingEnabled = probe.enabled,
            replayStartedAtElapsedMs = replayStartedAtElapsedMs, processingCompletedAtElapsedMs = processingCompletedAtElapsedMs)
        val original = loadedScenario?.samples?.getOrNull((snapshot.playback.deliveredSamples - 1).toInt())
        trace.record(snapshot, realClock.elapsedRealtimeMs(),
            if (phaseNow == DemoPhase.FAILED) playback.scenarioTimeMs else clock.scenarioTimeMs(), original)
        // 화면에는 같은 작업 통로에서 만든 불변 스냅샷만 넘긴다. trace의 가변 집계를 직접 읽지 않는다.
        val phase = snapshot.playback.phase
        if (retainTerminal && terminalTrace == null && phase in setOf(DemoPhase.COMPLETED, DemoPhase.STOPPED, DemoPhase.FAILED) && snapshot.playback.scenarioId != null) {
            terminalTrace = trace.capture(snapshot.playback.sessionId)
            terminalCaptures.trySend(requireNotNull(terminalTrace))
        }
        if (screenDue) {
            mutableState.value = snapshot.copy(traceSummary = trace.summary(), terminalTrace = terminalTrace)
            lastScreenSnapshotMs = now
        }
    }

    private suspend fun emitFakeReal() {
        val elapsed = realClock.elapsedRealtimeMs()
        val t = clock.scenarioTimeMs()
        val quality = SignalQuality(SignalAvailability.AVAILABLE, Source.REAL, elapsed,
            observedTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME)
        realVehicle.emit(VehicleSignalInput(t, Source.REAL, steeringDeg = 1f, speed = 80f, gear = Gear.D,
            turnSignal = TurnSignal.OFF, brakeOn = false, cruiseActive = false,
            quality = SignalField.entries.associateWith { quality }))
        realNavi.emit(NaviState(t, 37.5, 127.0, distToNextTurnM = 500f,
            quality = NaviField.entries.associateWith { quality }))
        realPower.emit(PowerEvent(t, PowerState.ON, quality))
        lastRealEmissionMs = elapsed
    }

    companion object {
        /** [가정] 화면 검증 TTL 1초·REAL 상실 유지 0. 제품 최신성 확정값이 아니다. */
        fun debugPolicy(): HubPolicy {
            val freshness = SignalFreshnessPolicy(1_000, 1_000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
            return HubPolicy(SignalField.entries.associateWith { freshness }, NaviField.entries.associateWith { freshness },
                freshness, SignalFallbackPolicy(true, 0, ObservationTimeBasis.ELAPSED_REALTIME),
                ObservationTimeBasis.SCENARIO, HubTickDriver.MANUAL)
        }
    }
}
