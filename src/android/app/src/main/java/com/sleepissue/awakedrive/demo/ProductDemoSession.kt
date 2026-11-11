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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 제품 데모도 Player → Hub → 판단 순서로 전달한다. SDK·실제 보상 기록은 건드리지 않는다. */
class ProductDemoSession(
    parent: CoroutineScope,
    private val realClock: Clock,
    private val areas: List<RestArea>,
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
    private var observation: Job? = null
    private var sequence = 0L
    private fun id() = RequestId("product-demo-${++sequence}")
    private fun publish() { owner?.let { mutableState.value = it.state.copy(demoPlaying = playback.value.phase == DemoPhase.PLAYING) } }

    init {
        scope.launch {
            while (isActive) {
                commands.withLock {
                    if (playback.value.phase == DemoPhase.PLAYING) {
                        player.pump(workBudgetMs = 2, maxSamples = 4) { hub.publishDue() }
                        hub.publishDue()
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
        observation?.cancelAndJoin()
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
        owner = DemoUiSession(clock, loaded.startEpochMs, areas, sessionId = playback.value.sessionId)
        observation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.samples.collect { frame ->
                owner?.observe(frame, hub.latestNavi.value, hub.latestPower.value)
                publish()
            }
        }
        hub.start(scope)
        publish()
    }
    suspend fun start() = commands.withLock { player.execute(DemoCommand.Start(id())); publish() }
    suspend fun pause() = commands.withLock { player.execute(DemoCommand.Pause(id())); publish() }
    suspend fun resume() = commands.withLock { player.execute(DemoCommand.Resume(id())); publish() }
    suspend fun stop() = commands.withLock { player.execute(DemoCommand.Stop(id())); hub.stop(); publish() }
    suspend fun action(value: DriveUiAction) = commands.withLock { owner?.action(value); publish() }
    suspend fun close() { stop(); observation?.cancelAndJoin(); scope.cancel() }
}
