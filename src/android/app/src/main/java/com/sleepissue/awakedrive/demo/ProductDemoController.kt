package com.sleepissue.awakedrive.demo

import android.content.Context
import com.sleepissue.awakedrive.data.*
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.demo.DemoTripPreset
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.session.*
import com.sleepissue.awakedrive.engine.rest.RestObservation
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.*
import com.sleepissue.awakedrive.pleos.*
import com.sleepissue.awakedrive.service.MonitoringPipeline
import com.sleepissue.awakedrive.session.*
import com.sleepissue.awakedrive.signal.*
import com.sleepissue.awakedrive.voice.VoiceButtonRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** 제품 데모의 수명 소유자. 원본 → Player → 같은 Hub → 기존 판단 파이프라인만 사용한다. */
class ProductDemoController(private val context: Context, private val owner: CoroutineScope, private val realClock: Clock) {
    private val config = EngineConfig()
    private val commands = Mutex()
    private var session: CoroutineScope? = null
    private var player: ScenarioDemoPlayer? = null
    private var hub: VehicleSignalHub? = null
    private var tracker: TripTracker? = null
    private var store: TripStateStore? = null
    var pipeline: MonitoringPipeline? = null
        private set
    private var adapter: PlaygroundSignalAdapter? = null
    private var lease: PlaygroundSdkOwnership.Lease? = null
    private var timeline: Clock? = null
    private var voiceSpeed: DemoVoiceSpeedGate? = null
    private var manual: MutableSharedFlow<CompleteSignalObservation>? = null
    @Volatile private var parkedAt: Long? = null
    @Volatile private var savingAt: Long? = null
    @Volatile private var parkElapsed = 0L
    private var parkJob: Job? = null
    private var namespace = ""
    private var serial = 0L
    private val prefs = context.getSharedPreferences(RESTART_PREFS, Context.MODE_PRIVATE)
    private val mutablePlayback = MutableStateFlow(DemoPlaybackState())
    val playback = mutablePlayback.asStateFlow()
    private val mutableMessage = MutableStateFlow("시나리오를 선택하세요. REAL 운행 기록과 분리됩니다.")
    val message = mutableMessage.asStateFlow()
    val trip = MutableStateFlow(TripState())
    val frame = MutableStateFlow<SignalFrame?>(null)
    val navi = MutableStateFlow<NaviState?>(null)
    val buttonRequest = MutableStateFlow<VoiceButtonRequest?>(null)
    val pipelineVersion = MutableStateFlow(0)
    private fun id() = RequestId("product-demo-${++serial}")

    suspend fun loadAndStart(scenarioId: String, speed: ProductDemoSpeed = ProductDemoSpeed.ORIGINAL) = commands.withLock {
        require(scenarioId in SCENARIOS.map { it.first })
        check(PlaygroundSdkOwnership.currentOwner == null || lease != null) { "기존 감시·SDK 화면에서 감시를 중지한 뒤 데모를 여세요." }
        mutableMessage.value = "이전 데모 정리 중"
        stopSession()
        namespace = UUID.randomUUID().toString()
        mutableMessage.value = "JSON 읽기·검증 중 · 잠시 기다려 주세요"
        val loaded = withContext(Dispatchers.IO) { DemoJsonLoader().parse(context.assets.open("$scenarioId.json").bufferedReader().use { it.readText() }) }
        if (loaded !is DemoLoadResult.Loaded) {
            mutablePlayback.value = DemoPlaybackState(phase = DemoPhase.FAILED, error = (loaded as DemoLoadResult.Failed).error)
            mutableMessage.value = "JSON 로드 실패 ${loaded.error}"; return@withLock
        }
        mutableMessage.value = "JSON 검증 완료 · 판단 연결 중"
        val scenario = loaded.scenario.withPlaybackSpeed(speed)
        val playbackInput = DemoLoadResult.Loaded(scenario)
        val scope = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))
        session = scope
        val demoClock = DemoClock(realClock)
        val epoch = scenario.startEpochMs
        val logical = object : Clock {
            override fun scenarioTimeMs() = savingAt ?: parkedAt?.let { it + realClock.elapsedRealtimeMs() - parkElapsed } ?: (epoch + demoClock.scenarioTimeMs())
            override fun wallTimeMs() = scenarioTimeMs()
            override fun elapsedRealtimeMs() = realClock.elapsedRealtimeMs()
        }
        timeline = logical
        val p = ScenarioDemoPlayer(demoClock, DemoScenarioRepository { playbackInput }, CoroutineScope(scope.coroutineContext + Dispatchers.Default),
            transportMode = DemoTransportMode.COMPLETE_OBSERVATION, defaultWorkBudgetMs = 2, defaultMaxSamples = 4)
        player = p
        check(p.execute(DemoCommand.Load(id(), scenarioId)).status == ExecutionStatus.SUCCEEDED)
        val overrides = MutableSharedFlow<CompleteSignalObservation>()
        manual = overrides
        fun SignalQuality.epoch() = copy(observedAtMs = observedAtMs?.plus(epoch))
        val source = object : CompleteSignalSource {
            override val observations = merge(p.observations.map { o -> CompleteSignalObservation(
                o.vehicle.copy(tMs = o.vehicle.tMs + epoch, quality = o.vehicle.quality.mapValues { it.value.epoch() }),
                o.navi.copy(tMs = o.navi.tMs + epoch, quality = o.navi.quality.mapValues { it.value.epoch() }),
                o.power.copy(tMs = o.power.tMs + epoch, quality = o.power.quality.epoch())) }, overrides)
        }
        val sdk = PlaygroundSignalAdapter(context, logical, SdkProbePolicy(3_000, config.naviStateIntervalMs), scope)
        adapter = sdk
        val fresh = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
        val h = VehicleSignalHub(listOf(
            HubSourceSet(Source.DEMO, rateTimeBasis = ObservationTimeBasis.SCENARIO, complete = source),
            HubSourceSet(Source.REAL, sdk.vehicleSource, sdk.naviSource, rateTimeBasis = ObservationTimeBasis.UNKNOWN, initiallyEnabled = false)
        ), logical, HubPolicy(SignalField.entries.associateWith { fresh }, NaviField.entries.associateWith { fresh }, fresh,
            SignalFallbackPolicy(true, 0, ObservationTimeBasis.SCENARIO), ObservationTimeBasis.SCENARIO, HubTickDriver.AUTOMATIC), config)
        hub = h
        val initial = (scenario.tripPreset ?: DemoTripPreset(0, 0)).seed(epoch, "demo-$namespace")
        val memory = TripStateStore(initial.trip)
        store = memory
        val t = TripTracker(newRules(), memory, Source.DEMO, initial)
        tracker = t
        scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { h.samples.collect { f ->
            if (t.accept(f, h.latestPower.value?.takeIf { it.tMs == f.tMs }) == null) t.markInputUnavailable(f.tMs)
            frame.value = f; trip.value = memory.state.value
        } }
        scope.launch { h.naviSamples.collect { navi.value = it } }
        val speedGate = DemoVoiceSpeedGate(demoClock) { request, status ->
            scope.launch {
                if (status == ExecutionStatus.STARTED) p.execute(DemoCommand.QuestionStarted(request, p.playbackState.value.sessionId))
                else p.execute(DemoCommand.QuestionFinished(request, p.playbackState.value.sessionId,
                    when (status) { ExecutionStatus.CANCELLED -> DemoQuestionOutcome.CANCELLED; ExecutionStatus.FAILED -> DemoQuestionOutcome.FAILED; else -> DemoQuestionOutcome.FINISHED }))
            }
        }
        voiceSpeed = speedGate
        mutableMessage.value = "음성·휴식 파이프라인 조립 중"
        val pipeline = MonitoringPipeline.create(context, scope, logical, h, memory.state, MutableStateFlow(false), config,
            source = Source.DEMO, restContext = DemoStorageContext(context, namespace), onVoiceLifecycle = speedGate::onLifecycle)
        mutableMessage.value = "파이프라인 조립 완료 · Hub 시작 중"
        this.pipeline = pipeline
        pipelineVersion.value++
        scope.launch { pipeline.manager.state.collect { s -> memory.update { it.copy(stage = s.stage) }; trip.value = memory.state.value } }
        scope.launch { pipeline.check.buttonRequests.collect { buttonRequest.value = it } }
        scope.launch { p.playbackState.collect { state ->
            mutablePlayback.value = state
            if (state.phase in TERMINAL) {
                h.stop()
                // 실패·종료 후 늦은 음성 확인/자동 경유지 요청도 끝낸다.
                pipeline.close()
            }
        } }
        h.start(CoroutineScope(scope.coroutineContext + Dispatchers.Default))
        mutableMessage.value = "Hub 시작 완료 · 원본 재생 시작 중"
        p.execute(DemoCommand.Start(id()))
        mutableMessage.value = "${SCENARIOS.first { it.first == scenarioId }.second} · ${speed.label} · JSON → Hub → 판단 실행 중"
    }

    suspend fun pause() = commands.withLock { player?.execute(DemoCommand.Pause(id())) }
    suspend fun resume() = commands.withLock { check(parkedAt == null) { "쉼터 준비 후에는 시동 끔 또는 중지를 선택하세요." }; player?.execute(DemoCommand.Resume(id())) }
    suspend fun stop() = commands.withLock { stopSession(); mutablePlayback.value = DemoPlaybackState(phase = DemoPhase.STOPPED) }
    suspend fun reset() = commands.withLock { stopSession(); mutablePlayback.value = DemoPlaybackState(); trip.value = TripState(); mutableMessage.value = "데모 초기화 · 저장된 REAL 기록은 보존됩니다." }

    suspend fun enableSdk() = commands.withLock {
        check(parkedAt == null && player != null)
        if (lease == null) lease = checkNotNull(PlaygroundSdkOwnership.acquire("제품 데모 SDK 우선 입력"))
        hub?.startInput(Source.REAL); adapter?.start()
        mutableMessage.value = "실제 Playground SDK 입력 우선 · DEMO와 출처를 구별해 표시합니다."
    }

    /** 시연자가 명시적으로 넣은 쉼터·P단 관측이다. 실제 도착으로 표시하지 않는다. */
    suspend fun preparePark() = commands.withLock {
        val p = checkNotNull(player); check(p.playbackState.value.phase in setOf(DemoPhase.PLAYING, DemoPhase.PAUSED))
        if (p.playbackState.value.phase == DemoPhase.PLAYING) p.execute(DemoCommand.Pause(id()))
        hub?.stopInput(Source.REAL); adapter?.close(); lease?.let { PlaygroundSdkOwnership.release(it) }; lease = null
        val at = requireNotNull(timeline).scenarioTimeMs()
        parkElapsed = realClock.elapsedRealtimeMs()
        parkedAt = at
        val area = requireNotNull(pipeline).rest.repository.areas.first()
        val location = area.location
        fun q() = SignalQuality(SignalAvailability.AVAILABLE, Source.DEMO, at, observedTimeBasis = ObservationTimeBasis.SCENARIO)
        val v = VehicleSignalInput(at, Source.DEMO, speed = 0f, gear = Gear.P,
            quality = SignalField.entries.associateWith { if (it in setOf(SignalField.GEAR, SignalField.SPEED_KPH)) q() else q().copy(availability = SignalAvailability.UNAVAILABLE) })
        val n = NaviState(at, lat = location.lat, lon = location.lon,
            quality = NaviField.entries.associateWith { if (it in setOf(NaviField.LAT, NaviField.LON)) q() else q().copy(availability = SignalAvailability.UNAVAILABLE) })
        parkJob = requireNotNull(session).launch {
            while (isActive) {
                val now = logicalTime()
                fun SignalQuality.now() = copy(observedAtMs = now)
                manual?.emit(CompleteSignalObservation(v.copy(tMs = now, quality = v.quality.mapValues { it.value.now() }),
                    n.copy(tMs = now, quality = n.quality.mapValues { it.value.now() }), PowerEvent(now, PowerState.ON, q().now())))
                delay(100)
            }
        }
        withTimeout(5_000) { requireNotNull(hub).latestFrame.first { it?.gear == Gear.P && it.tMs >= at } }
        store?.update { it.copy(lastLocation = location) }
        requireNotNull(pipeline).rest.restMonitor.observe(RestObservation(at, location, Gear.P, PowerState.ON,
            trip.value.continuousDriveSec, requireNotNull(pipeline).manager.state.value.stage), Source.DEMO)
        mutableMessage.value = "모의 쉼터 P단 준비 완료 · ${area.name} · 다음: 시동 끔(저장 후 앱 종료)"
    }

    /** 성공한 디스크 저장 뒤에만 Activity가 앱 프로세스를 종료한다. */
    suspend fun savePowerOff() = commands.withLock {
        checkNotNull(parkedAt) { "쉼터 P단 준비를 먼저 누르세요." }
        parkJob?.cancelAndJoin(); parkJob = null
        val at = logicalTime()
        savingAt = at
        val manager = checkNotNull(pipeline).manager
        // 기존 소유자 codec은 평가와 운행 저장이 같은 Clock 순간이어야 한다. timestamp를 덮어쓰지 않고 실제 Tick을 평가한다.
        manager.submit(id(), InterventionInput.Tick)
        val checkpoint = manager.checkpoint()
        val rules = newRules()
        val t = checkNotNull(tracker)
        val before = t.preciseState()
        val off = rules.reduce(before, TripObservation(at, Gear.P, PowerState.OFF)).state
        val rest = checkNotNull(pipeline).rest
        rest.restMonitor.observe(RestObservation(at, before.trip.lastLocation, Gear.P, PowerState.OFF,
            before.trip.continuousDriveSec, manager.state.value.stage), Source.DEMO)
        PrefsRestStateStore(DemoStorageContext(context, namespace)).flush()
        val state = off.copy(trip = off.trip.copy(stage = checkpoint.policy.publicState.stage))
        val ownerState = TripSessionOwnerState(Gear.P, PowerState.OFF, SaveTimerBasis.SCENARIO, checkpoint)
        val snapshot = TripSnapshot(state.trip, realClock.wallTimeMs(), state.driveSegments, realClock.elapsedRealtimeMs(),
            state, at, POLICY, Source.DEMO, TripTimeBasis.MANUAL_SCENARIO, TripSessionCodec.encode(ownerState))
        TripSnapshotValidator.requireValid(snapshot, config)
        checkpoint.requireValid(at, state.trip)
        val db = TripDatabase.open(context, RESTART_DB)
        try { check(RoomTripRepository(db.checkpointDao()).save(snapshot) == RepositoryResult.Succeeded) { "데모 Room 저장 실패" } }
        finally { db.close() }
        check(prefs.edit().putString("namespace", namespace).putBoolean("pending", true).putLong("savedAt", at).commit()) { "데모 복원 표식 저장 실패" }
        stopSession()
    }

    /** 새 프로세스에서 Room를 읽고 딱 한 번 22분 이동한다. 인증은 기존 RestCertifier/RewardLedger가 계산한다. */
    suspend fun restorePowerOff(): Boolean = commands.withLock {
        if (!prefs.getBoolean("pending", false)) return@withLock false
        val db = TripDatabase.open(context, RESTART_DB)
        val saved = try { (RoomTripRepository(db.checkpointDao()).load() as? TripLoadResult.Loaded)?.snapshot ?: error("데모 저장본을 읽지 못했습니다.") }
            finally { db.close() }
        check(saved.source == Source.DEMO)
        val at = requireNotNull(saved.savedAtScenarioMs) + POWER_OFF_GAP_MS
        val precise = newRules().reduce(requireNotNull(saved.preciseState), TripObservation(at, Gear.P, PowerState.ON)).state
        val ns = checkNotNull(prefs.getString("namespace", null))
        val storage = DemoStorageContext(context, ns)
        val restState = PrefsRestStateStore(storage)
        val certifier = com.sleepissue.awakedrive.engine.rest.RestCertifier(com.sleepissue.awakedrive.navi.RestAreaRepository(context).areas, config)
        check(certifier.restore(checkNotNull(restState.load()), at)) { "데모 휴식 상태 복원 실패" }
        val clock = object : Clock {
            override fun scenarioTimeMs() = at
            override fun elapsedRealtimeMs() = realClock.elapsedRealtimeMs()
            override fun wallTimeMs() = at
        }
        val restDb = RestRecordDatabase.open(storage)
        val records = try {
            val ledger = RewardLedger(RoomRestRecordStore(restDb.dao()), clock)
            val monitor = RestSessionMonitor(certifier, ledger, clock, config = config, stateStore = restState)
            monitor.observe(RestObservation(at, precise.trip.lastLocation, Gear.P, PowerState.ON,
                saved.state.continuousDriveSec, saved.state.stage), Source.DEMO)
            restDb.dao().all()
        } finally { restDb.close() }
        trip.value = precise.trip
        mutablePlayback.value = DemoPlaybackState(phase = DemoPhase.STOPPED)
        val ownerState = TripSessionCodec.decode(requireNotNull(saved.ownerState))
        val result = "DMO_005 데모 복원 완료 · 시계 +22분 · 인증 ${records.size}건 · 연속 ${precise.trip.continuousDriveSec}초 · 최근24시간 ${precise.trip.last24hDriveSec}초\n" +
            "운행 ID ${precise.trip.tripId} · 단계 ${ownerState.intervention.policy.publicState.stage} · 기준선 참조 ${precise.trip.baselineRef ?: "없음"}\n실제 시동 OFF→ON/STR 시험이 아닙니다. 엔진 내부 학습 창 복원은 미지원입니다."
        check(prefs.edit().putBoolean("pending", false).putString("result", result).commit())
        mutableMessage.value = result
        true
    }

    fun timingSnapshot(): DemoPlaybackState? = player?.playbackSnapshot()

    suspend fun completeButton(intent: ResponseIntent) {
        val request = buttonRequest.value ?: return
        pipeline?.check?.completeWithButton(request.requestId, intent, request.passengerButton)
        buttonRequest.value = null
    }
    suspend fun reportFailure(error: Exception) {
        stop()
        mutablePlayback.value = DemoPlaybackState(phase = DemoPhase.FAILED)
        mutableMessage.value = "데모 실행 실패 · ${error.javaClass.simpleName}: ${error.message?.take(160)}"
    }
    suspend fun showLastResult() {
        val result = prefs.getString("result", null) ?: return
        val db = TripDatabase.open(context, RESTART_DB)
        val saved = try { (RoomTripRepository(db.checkpointDao()).load() as? TripLoadResult.Loaded)?.snapshot } finally { db.close() }
        if (saved?.source == Source.DEMO) {
            val at = requireNotNull(saved.savedAtScenarioMs) + POWER_OFF_GAP_MS
            trip.value = newRules().reduce(requireNotNull(saved.preciseState), TripObservation(at, Gear.P, PowerState.ON)).state.trip
        }
        mutableMessage.value = result
    }
    private fun logicalTime() = requireNotNull(timeline).scenarioTimeMs()
    private fun newRules() = TripRules(config, POLICY) { "demo-${UUID.randomUUID()}" }
    private suspend fun stopSession() {
        mutablePlayback.value = playback.value.copy(phase = DemoPhase.STOPPED, speedMultiplier = 0, questionRequestId = null)
        parkJob?.cancelAndJoin(); parkJob = null
        voiceSpeed?.cancel(); voiceSpeed = null
        player?.execute(DemoCommand.Stop(id()))
        hub?.stop(); pipeline?.close(); pipeline = null
        adapter?.close(); adapter = null
        lease?.let { PlaygroundSdkOwnership.release(it) }; lease = null
        session?.cancel(); session = null; player = null; hub = null; tracker = null; store = null; parkedAt = null; savingAt = null
        frame.value = null; navi.value = null; buttonRequest.value = null; pipelineVersion.value++
    }
    companion object {
        const val RESTART_PREFS = "product_demo_restart"
        const val RESTART_DB = "awakedrive_demo_restart.db"
        const val POWER_OFF_GAP_MS = 22 * 60_000L // SRS TC_DMO_005의 데모 이동량. 실제 대기시간이 아니다.
        val SCENARIOS = listOf("normal" to "정상", "distraction" to "딴짓", "drowsy" to "졸음", "talk" to "대화", "assist" to "보조 주행", "departure" to "출발 피로")
        val POLICY = TripRulePolicy(StopTimePolicy.V03_RETROACTIVE, ShortStopAccumulationPolicy.EXCLUDE)
        private val TERMINAL = setOf(DemoPhase.COMPLETED, DemoPhase.FAILED)
    }
}
