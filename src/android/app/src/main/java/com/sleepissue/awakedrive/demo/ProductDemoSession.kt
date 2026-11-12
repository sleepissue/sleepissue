package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.rest.RestArea
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.engine.time.DemoClock
import com.sleepissue.awakedrive.signal.*
import com.sleepissue.awakedrive.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 제품 데모도 Player → Hub → 판단 순서로 전달한다. 실행 부품은 선택적으로 연결하며 보상은 메모리에 둔다. */
class ProductDemoSession(
    parent: CoroutineScope,
    private val realClock: Clock,
    private val areas: List<RestArea>,
    private val executionFactory: DemoExecutionFactory? = null,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val commands = Mutex()
    private val clock = DemoClock(realClock)
    private var json = ""
    private var scenario: DemoScenario? = null
    private var selectedSpeed = 1
    private val player = ScenarioDemoPlayer(clock, DemoScenarioRepository {
        when (val result = DemoJsonLoader().parse(json)) {
            is DemoLoadResult.Loaded -> {
                val value = result.scenario.copy(speedSegments = result.scenario.speedSegments.map { it.copy(factor = selectedSpeed) })
                scenario = value
                DemoLoadResult.Loaded(value)
            }
            is DemoLoadResult.Failed -> { scenario = null; result }
        }.also { json = "" }
    }, scope, automaticTicks = false, transportMode = DemoTransportMode.COMPLETE_OBSERVATION)
    private val handoffClock = object : Clock {
        override fun scenarioTimeMs() = minOf(clock.scenarioTimeMs(), player.nextOriginalScenarioMs())
        override fun elapsedRealtimeMs() = realClock.elapsedRealtimeMs()
        override fun wallTimeMs() = realClock.wallTimeMs()
    }
    private val freshness = SignalFreshnessPolicy(1000, 1000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
    private val hub = VehicleSignalHub(listOf(HubSourceSet(Source.DEMO, player, player, player,
        ObservationTimeBasis.SCENARIO, complete = player)), handoffClock,
        HubPolicy(SignalField.entries.associateWith { freshness }, NaviField.entries.associateWith { freshness },
            freshness, SignalFallbackPolicy(true, 0, ObservationTimeBasis.ELAPSED_REALTIME),
            ObservationTimeBasis.SCENARIO, HubTickDriver.MANUAL), EngineConfig())
    private val mutableState = MutableStateFlow(DriveSessionUiState(demoSession = true))
    val state = mutableState.asStateFlow()
    val playback = player.playbackState
    private var owner: DemoUiSession? = null
    private var execution: DemoExecution? = null
    private val events = Channel<Pair<DemoExecution, DemoExecutionEvent>>(Channel.UNLIMITED)
    private var passengerChecks = true
    private var sequence = 0L
    private fun id() = RequestId("product-demo-${++sequence}")
    private fun publish() {
        owner?.let { current ->
            execution?.let {
                current.executionState(it.manager.state.value, it.manager.transitions.value)
                it.updateAlarm(current.state.nap, current.state.restAreaName != null)
            }
            mutableState.value = current.state.copy(demoPlaying = playback.value.phase == DemoPhase.PLAYING)
        }
    }

    init {
        scope.launch {
            for ((source, event) in events) commands.withLock {
                if (source !== execution) return@withLock
                when (event) {
                    is DemoExecutionEvent.Question -> {
                        if (event.active) player.execute(DemoCommand.QuestionStarted(event.id, playback.value.sessionId))
                        else if (playback.value.questionRequestId == event.id)
                            player.execute(DemoCommand.QuestionFinished(event.id, playback.value.sessionId, event.outcome))
                    }
                    is DemoExecutionEvent.Button -> owner?.voiceButton(event.context)
                    is DemoExecutionEvent.Checked -> {
                        if (event.result.context == CheckContext.RECOVERY) owner?.recoveryResult(event.result.outcome)
                        if (event.result.intent == ResponseIntent.NAP && owner?.state?.parked == true) owner?.action(DriveUiAction.StartNap)
                    }
                    is DemoExecutionEvent.Message -> owner?.feedback(event.text, event.waypoint)
                    is DemoExecutionEvent.ResetAfterRecovery -> { owner?.resetAfterRecovery(); event.completed.complete(Unit) }
                    DemoExecutionEvent.Refresh -> Unit
                }
                publish()
            }
        }
        scope.launch {
            while (isActive) {
                commands.withLock {
                    if (playback.value.phase == DemoPhase.PLAYING) {
                        player.pump(workBudgetMs = 2, maxSamples = 4) { publishFrame() }
                        publishFrame()
                        publish()
                    }
                }
                delay(1)
            }
        }
    }

    suspend fun load(value: String, speed: Int) = commands.withLock {
        require(speed in listOf(1, 10, 60))
        hub.stop()
        execution?.close(); execution = null
        player.execute(DemoCommand.Stop(id()))
        // 배속은 검증된 모델에서 바꿔 거대한 JSON 트리를 다시 만들지 않는다.
        selectedSpeed = speed
        scenario = null
        owner = null
        json = value
        player.execute(DemoCommand.Load(id(), "product-demo"))
        val loaded = scenario
        if (playback.value.phase != DemoPhase.READY || loaded == null) {
            owner = null
            mutableState.value = DriveSessionUiState(demoSession = true, message = "데모를 불러오지 못했습니다: ${playback.value.error}")
            return@withLock
        }
        owner = DemoUiSession(clock, loaded.startEpochMs, areas, sessionId = playback.value.sessionId,
            managedExecution = executionFactory != null)
        if (executionFactory != null) {
            val currentOwner = requireNotNull(owner)
            val ports = executionFactory.create(scope) { currentOwner.state }
            val executionClock = object : Clock {
                override fun scenarioTimeMs() = currentOwner.state.tMs
                override fun elapsedRealtimeMs() = realClock.elapsedRealtimeMs()
                override fun wallTimeMs() = loaded.startEpochMs + scenarioTimeMs()
            }
            lateinit var connected: DemoExecution
            connected = DemoExecution(scope, executionClock, ports, { events.send(connected to it) })
            execution = connected
            connected.start()
        }
        hub.start(scope)
        publish()
    }
    // 수동 Hub 발행 직후 같은 내비·전원 묶음을 읽는다. 최신값 Flow를 늦게 읽어 다른 프레임과 섞지 않는다.
    private suspend fun publishFrame() {
        if (!hub.publishDue()) return
        val frame = hub.latestFrame.value ?: return
        owner?.observe(frame, hub.latestNavi.value, hub.latestPower.value)
        owner?.let { execution?.submit(it.drainExecutionInputs()) }
        publish()
    }

    suspend fun start() = commands.withLock { player.execute(DemoCommand.Start(id())); publish() }
    suspend fun pause() = commands.withLock { player.execute(DemoCommand.Pause(id())); publish() }
    suspend fun resume() = commands.withLock { player.execute(DemoCommand.Resume(id())); publish() }
    suspend fun stop() = commands.withLock {
        player.execute(DemoCommand.Stop(id())); hub.stop()
        execution?.close(); execution = null
        owner?.voiceButton(null)
        publish()
    }
    suspend fun action(value: DriveUiAction) = commands.withLock {
        val connected = execution
        if (value is DriveUiAction.Preferences) passengerChecks = value.value.passengerChecks
        when {
            connected != null && value == DriveUiAction.CancelWaypoint -> connected.cancelWaypoint()
            connected != null && value is DriveUiAction.VoiceResponse -> connected.respond(value.intent)
            value is DriveUiAction.Passenger && !passengerChecks -> owner?.feedback("동승자 확인 설정이 꺼져 있습니다")
            connected != null && value is DriveUiAction.Passenger -> connected.respondPassenger(value.rest)
            connected != null && value == DriveUiAction.RequestRecovery -> {
                if (!connected.manager.resumeInterruptedChecks()) owner?.feedback("진행 중인 음성 회복 확인에 답해 주세요")
            }
            else -> owner?.action(value)
        }
        owner?.let { connected?.submit(it.drainExecutionInputs()) }
        publish()
    }
    suspend fun close() { stop(); scope.cancel() }
}
