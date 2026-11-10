package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.performance.WorkProbe
import com.sleepissue.awakedrive.performance.WorkStage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 원천 수집은 발행 소비자를 기다리지 않는다. 발행 통로는 replay 0/대기 1/DROP_OLDEST다.
 * 순번으로 소비자 누락을 알 수 있으며, 수집 종료 후에도 null 품질 프레임은 주기 발행한다.
 * 늦은 Tick은 과거 프레임을 꾸며 채우지 않고 현재 한 장과 누락 deadline 수를 제공한다.
 */
internal class PeriodicSignalHub(
    sources: List<HubSourceSet>, private val clock: Clock, private val policy: HubPolicy,
    private val config: EngineConfig,
    private val probe: WorkProbe = WorkProbe.NONE,
    private val gearObserver: ((GearSelectionDecision) -> Unit)? = null,
    // 두 번째 값은 실제 버스 emit 직전 elapsed다. 기존 메타데이터/관측 시각을 덮지 않는다.
    private val emissionObserver: ((FramePublication, Long) -> Unit)? = null,
    private val timingObserver: ((HubTimingPoint) -> Unit)? = null,
) : SignalHubSession {
    private inner class Input(val definition: HubSourceSet) {
        var epoch = 0L
        var job: Job? = null
        var state = idleState(definition)
        val vehicle = mutableMapOf<SignalField, SignalCandidate<Any>>()
        val navi = mutableMapOf<NaviField, SignalCandidate<Any>>()
        var power: SignalCandidate<Any>? = null
        val failures = mutableMapOf<String, SignalAvailability>()
        var meter = RateMeter(config)
        var callbacks = 0L
        var observationSequence = 0L
    }
    /** 한 번의 발행에서 평가한 후보를 선택·좌표 쌍·진단이 공유한다. */
    private data class EvaluatedInput(
        val vehicle: Map<SignalField, SignalCandidate<Any>>,
        val navi: Map<NaviField, SignalCandidate<Any>>,
        val power: SignalCandidate<Any>,
        val rate: SourceRate,
    )
    private val inputs = sources.associate { it.source to Input(it) }
    private val allVehicleFields = SignalField.entries.toSet()
    private val lifecycle = Mutex()
    private val stateLock = Mutex()
    private var job: Job? = null
    private var owner: CoroutineScope? = null
    private var generation = 0L
    private var nextDeadline = 0L
    private var nextNaviDeadline = 0L
    private var lastTick = 0L
    private var lastScenario = 0L
    private var lastElapsed = 0L
    private val normalizer = SignalNormalizer(clock)
    private var selectors = SignalField.entries.associateWith { SignalSelector(policy.fallback) }
    private var naviSelectors = NaviField.entries.associateWith { SignalSelector(policy.fallback) }
    private var locationSelector = SignalSelector(policy.fallback)
    private var powerSelector = SignalSelector(policy.fallback)
    private var publicationMeter = RateMeter(config)
    private val mutableFrame = MutableStateFlow<SignalFrame?>(null)
    private val mutableNavi = MutableStateFlow<NaviState?>(null)
    private val mutablePower = MutableStateFlow<PowerEvent?>(null)
    private val mutableCollection = MutableStateFlow(HubCollectionState())
    private val mutableDiagnostics = MutableStateFlow(HubDiagnostics())
    private data class Envelope<T>(val generation: Long, val value: T)
    private fun <T> bus() = MutableSharedFlow<Envelope<T>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val frameBus = bus<SignalFrame>()
    private val naviBus = bus<NaviState>()
    private val powerBus = bus<PowerEvent>()
    private val observationBus = bus<VehicleObservation>()
    private fun <T> Flow<Envelope<T>>.current(): Flow<T> = filter {
        diagnostics.value.running && it.generation == diagnostics.value.sessionId
    }.map { it.value }
    override val latestFrame = mutableFrame.asStateFlow()
    override val latestNavi = mutableNavi.asStateFlow()
    override val latestPower = mutablePower.asStateFlow()
    override val samples = frameBus.current()
    override val naviSamples = naviBus.current()
    override val powerEvents = powerBus.current()
    override val rawVehicleObservations = observationBus.current()
    override val collectionState = mutableCollection.asStateFlow()
    override val diagnostics = mutableDiagnostics.asStateFlow()

    init { require(sources.isNotEmpty() && inputs.size == sources.size) { "출처당 입력 소유자는 하나여야 합니다." } }

    override suspend fun start(ownerScope: CoroutineScope): Boolean = lifecycle.withLock {
        if (job != null) return@withLock false
        check(ownerScope.isActive)
        val parent = SupervisorJob(ownerScope.coroutineContext[Job])
        job = parent; owner = CoroutineScope(ownerScope.coroutineContext + parent)
        withStateLock {
            generation++
            lastTick = publicationNow(); lastScenario = clock.scenarioTimeMs(); lastElapsed = clock.elapsedRealtimeMs()
            require(minOf(lastTick, lastScenario, lastElapsed) >= 0)
            nextDeadline = Math.addExact(lastTick, config.signalFrameIntervalMs)
            nextNaviDeadline = Math.addExact(lastTick, config.naviStateIntervalMs)
            selectors = SignalField.entries.associateWith { SignalSelector(policy.fallback) }
            naviSelectors = NaviField.entries.associateWith { SignalSelector(policy.fallback) }
            locationSelector = SignalSelector(policy.fallback); powerSelector = SignalSelector(policy.fallback)
            publicationMeter = RateMeter(config).also { it.start(lastTick, policy.publicationTimeBasis) }
            mutableFrame.value = null; mutableNavi.value = null; mutablePower.value = null
            inputs.values.forEach {
                it.state = idleState(it.definition); it.vehicle.clear(); it.navi.clear(); it.power = null
                it.failures.clear(); it.callbacks = 0; it.observationSequence = 0; it.meter = RateMeter(config)
            }
            mutableDiagnostics.value = HubDiagnostics(running = true, sessionId = generation,
                nextPublicationAtMs = nextDeadline, publicationTimeBasis = policy.publicationTimeBasis)
            updateDiagnostics()
        }
        parent.invokeOnCompletion {
            inputs.values.forEach { input -> input.meter.stop(); input.state = idleState(input.definition) }
            publicationMeter.stop()
            mutableFrame.value = null; mutableNavi.value = null; mutablePower.value = null
            mutableDiagnostics.update { it.copy(running = false, nextPublicationAtMs = null) }
            updateDiagnostics()
        }
        inputs.values.filter { it.definition.initiallyEnabled }.forEach { startInputLocked(it) }
        if (policy.tickDriver == HubTickDriver.AUTOMATIC) owner!!.launch {
            while (isActive) {
                var scheduled = 0L
                val wait = withStateLock("timer") {
                    scheduled = nextDeadline
                    (nextDeadline - publicationNow()).coerceIn(1, config.signalFrameIntervalMs)
                }
                timing(HubTimingStage.TIMER_SCHEDULED, "timer", scheduled)
                delay(wait)
                timing(HubTimingStage.TIMER_RESUMED, "timer", scheduled)
                publishDue()
            }
        }
        true
    }

    override suspend fun startInput(source: Source): Boolean = lifecycle.withLock {
        val input = inputs[source] ?: return@withLock false
        if (job?.isActive != true || input.job != null) return@withLock false
        startInputLocked(input)
        true
    }

    private suspend fun startInputLocked(input: Input) {
        val child = SupervisorJob(requireNotNull(job))
        val scope = CoroutineScope(requireNotNull(owner).coroutineContext + child)
        val epoch = withStateLock {
            input.epoch++; input.job = child; input.vehicle.clear(); input.navi.clear(); input.power = null
            input.callbacks = 0; input.observationSequence = 0; input.failures.clear()
            input.state = if (input.definition.complete != null) HubCollectionState(
                InputCollectionStatus.COLLECTING, InputCollectionStatus.COLLECTING, InputCollectionStatus.COLLECTING)
                else HubCollectionState(status(input.definition.vehicle), status(input.definition.navi), status(input.definition.power))
            if (input.definition.vehicle != null || input.definition.complete != null) input.meter.start(clock.timeAt(input.definition.rateTimeBasis) ?: 0, input.definition.rateTimeBasis)
            updateDiagnostics(); input.epoch
        }
        val session = generation
        val complete = input.definition.complete
        if (complete != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                collect(input, session, epoch, "complete", { complete.observations }) { value ->
                    // 세 채널은 같은 잠금 안에서 모두 수신한다. 중간 상태는 발행기로 노출하지 않는다.
                    probe.measure(WorkStage.HUB_RECEIVE_COMPLETE) {
                        acceptVehicle(input, value.vehicle)
                        acceptNavi(input, value.navi)
                        acceptPower(input, value.power)
                    }
                }
            }
        } else {
            input.definition.vehicle?.let { source -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
                collect(input, session, epoch, "vehicle", { source.samples }) { value -> acceptVehicle(input, value) }
            } }
            input.definition.navi?.let { source -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
                collect(input, session, epoch, "navi", { source.states }) { value -> acceptNavi(input, value) }
            } }
            input.definition.power?.let { source -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
                collect(input, session, epoch, "power", { source.events }) { value -> acceptPower(input, value) }
            } }
        }
    }

    private suspend fun <T> collect(input: Input, session: Long, epoch: Long, channel: String,
        source: () -> Flow<T>, accept: (T) -> Unit) {
        var end = InputCollectionStatus.COMPLETED
        var failure = SignalAvailability.UNAVAILABLE
        try {
            source().collect { value -> withStateLock(channel) {
                if (session == generation && epoch == input.epoch && diagnostics.value.running) {
                    // 수집 경로에서는 값·품질·계수만 갱신한다. 전체 진단은 발행/수집 상태 변경 때 만든다.
                    probe.measure(WorkStage.HUB_RECEIVE) { accept(value) }
                }
            } }
        } catch (cancelled: CancellationException) { end = InputCollectionStatus.STOPPED; throw cancelled }
        catch (_: SecurityException) { end = InputCollectionStatus.FAILED; failure = SignalAvailability.PERMISSION_DENIED }
        catch (_: Exception) { end = InputCollectionStatus.FAILED; failure = SignalAvailability.ERROR }
        finally {
            withStateLock {
                if (session == generation && epoch == input.epoch) {
                    input.state = when (channel) {
                        "vehicle" -> input.state.copy(vehicle = end)
                        "navi" -> input.state.copy(navi = end)
                        "complete" -> HubCollectionState(end, end, end)
                        else -> input.state.copy(power = end)
                    }
                    if (channel == "complete") {
                        input.failures["vehicle"] = failure; input.failures["navi"] = failure; input.failures["power"] = failure
                    } else input.failures[channel] = failure
                    if (channel == "vehicle" || channel == "complete") input.meter.stop()
                    updateDiagnostics()
                }
            }
        }
    }

    private fun acceptVehicle(input: Input, value: VehicleSignalInput) = probe.measure(WorkStage.HUB_RECEIVE_VEHICLE) {
        val frame = probe.measure(WorkStage.HUB_NORMALIZE) { normalizer.vehicle(value) }
        val fields = value.updatedFields ?: allVehicleFields
        val observations = if (value.confirmationFields.isEmpty()) fields else fields - value.confirmationFields
        if (observations.isNotEmpty()) input.callbacks++
        fields.forEach { field ->
            val q = frame.qualityOf(field).let { if (value.source != input.definition.source) it.copy(availability = SignalAvailability.ERROR) else it }
            if (field == SignalField.STEERING_DEG) {
                if (q.availability == SignalAvailability.AVAILABLE && field !in value.confirmationFields) probe.measure(WorkStage.RATE_OBSERVE) { input.meter.observe(q.observedAtMs, q.observedTimeBasis,
                    clock.timeAt(input.definition.rateTimeBasis) ?: 0) }
                else if (q.availability == SignalAvailability.UNSUPPORTED || q.availability == SignalAvailability.PERMISSION_DENIED ||
                    q.availability == SignalAvailability.ERROR)
                    probe.measure(WorkStage.RATE_OBSERVE) { input.meter.observe(null, ObservationTimeBasis.UNKNOWN, clock.timeAt(input.definition.rateTimeBasis) ?: 0) }
            }
            input.vehicle[field] = cache(input, input.vehicle[field], frame.valueOf(field), q,
                confirmationOnly = field in value.confirmationFields)
        }
        // replay 없는 통로다. 활성 구독자가 없으면 관측 전달용 복사본을 만들 필요가 없다.
        if (observations.isNotEmpty() && observationBus.subscriptionCount.value > 0) {
            val observed = frame.copy(quality = observations.associateWith { requireNotNull(input.vehicle[it]).quality })
            observationBus.tryEmit(Envelope(generation, VehicleObservation(observed, observations)))
        }
    }

    private fun acceptNavi(input: Input, value: NaviState) = probe.measure(WorkStage.HUB_RECEIVE_NAVI) {
        val normalized = probe.measure(WorkStage.HUB_NORMALIZE) { normalizer.navi(value) }
        NaviField.entries.forEach { field -> input.navi[field] = cache(input, input.navi[field], normalized.valueOf(field), normalized.qualityOf(field),
            confirmationOnly = input.definition.navi?.stateReportsOnly == true) }
    }

    private fun acceptPower(input: Input, value: PowerEvent) = probe.measure(WorkStage.HUB_RECEIVE_POWER) {
        val q = probe.measure(WorkStage.HUB_NORMALIZE) { value.quality.copy(receivedAtElapsedMs = clock.elapsedRealtimeMs()) }
        input.power = cache(input, input.power, value.state.takeIf { q.availability == SignalAvailability.AVAILABLE }, q)
    }

    private fun cache(input: Input, previous: SignalCandidate<Any>?, value: Any?, quality: SignalQuality,
        confirmationOnly: Boolean = false): SignalCandidate<Any> {
        var availability = if (quality.source != null && quality.source != input.definition.source) SignalAvailability.ERROR
            else quality.availability
        val sameBasis = previous?.quality?.observedTimeBasis == quality.observedTimeBasis
        val at = quality.observedAtMs
        val before = previous?.quality?.observedAtMs
        if (quality.stateConfirmedAtElapsedMs != null && previous != null &&
            previous.value == value && quality.availability == previous.quality.availability &&
            quality.observedAtMs == previous.quality.observedAtMs &&
            quality.receivedAtElapsedMs == previous.quality.receivedAtElapsedMs) {
            return previous.copy(quality = quality.copy(source = input.definition.source,
                observationSequence = previous.quality.observationSequence))
        }
        if (value != null && availability == SignalAvailability.AVAILABLE && sameBasis && at != null && before != null) {
            if (at == before && previous.quality.availability == SignalAvailability.AVAILABLE) {
                // 같은 원천 상태의 재조회는 센서 관측을 만들지 않고 확인 lease만 전달한다.
                if (quality.stateConfirmedAtElapsedMs != null &&
                    quality.stateConfirmedAtElapsedMs != previous.quality.stateConfirmedAtElapsedMs) {
                    return previous.copy(value = value, quality = quality.copy(
                        observationSequence = previous.quality.observationSequence))
                }
                return previous
            }
            if (at < before) availability = SignalAvailability.ERROR
        }
        val q = quality.copy(availability = availability, source = input.definition.source,
            sourceRateHz = null, sourceRateInfo = null, selectionReason = null,
            observationSequence = if (confirmationOnly) previous?.quality?.observationSequence ?: 0 else ++input.observationSequence)
        return SignalCandidate(value.takeIf { availability == SignalAvailability.AVAILABLE }, q)
    }

    override suspend fun stopInput(source: Source): Boolean = lifecycle.withLock {
        val input = inputs[source] ?: return@withLock false
        val child = input.job ?: return@withLock false
        withStateLock {
            input.epoch++; input.state = idleState(input.definition); input.meter.stop()
            input.failures.clear(); updateDiagnostics()
        }
        child.cancelAndJoin(); input.job = null
        true
    }

    override suspend fun stop() = lifecycle.withLock {
        withStateLock { mutableDiagnostics.update { it.copy(running = false, nextPublicationAtMs = null) } }
        job?.cancelAndJoin(); job = null; owner = null
        withStateLock {
            inputs.values.forEach { it.job = null; it.vehicle.clear(); it.navi.clear(); it.power = null; it.meter.stop(); it.state = idleState(it.definition) }
            mutableFrame.value = null; mutableNavi.value = null; mutablePower.value = null
            updateDiagnostics()
        }
    }

    override suspend fun publishDue(): Boolean {
        timing(HubTimingStage.PUBLISH_ENTERED, "publish")
        val started = probe.begin(WorkStage.HUB_PUBLISH)
        try { return withStateLock("publish") {
            if (job?.isActive != true || !diagnostics.value.running || diagnostics.value.clockFailure) return@withStateLock false
            val now = publicationNow()
            val scenario = clock.scenarioTimeMs(); val elapsed = clock.elapsedRealtimeMs()
            if (now < lastTick || scenario < lastScenario || elapsed < lastElapsed) {
                mutableDiagnostics.update { it.copy(clockFailure = true, nextPublicationAtMs = null) }
                return@withStateLock false
            }
            lastTick = now; lastScenario = scenario; lastElapsed = elapsed
            if (now < nextDeadline) return@withStateLock false
            val due = nextDeadline
            val missed = (now - nextDeadline) / config.signalFrameIntervalMs
            nextDeadline = Math.addExact(nextDeadline, Math.multiplyExact(missed + 1, config.signalFrameIntervalMs))
            val metadata = FramePublication(generation, diagnostics.value.emittedFrames + 1, elapsed)
            probe.measure(WorkStage.RATE_OBSERVE) { publicationMeter.observe(now, policy.publicationTimeBasis, now) }
            val selectionNow = requireNotNull(clock.timeAt(policy.fallback.timerBasis))
            val evaluatedFrom = if (gearObserver != null) clock.elapsedRealtimeMs() else 0L
            val evaluated = evaluateInputs()
            val selected = SignalField.entries.associateWith { field ->
                selectors.getValue(field).select(evaluated[Source.REAL]?.vehicle?.get(field), evaluated[Source.DEMO]?.vehicle?.get(field), selectionNow)
            }
            @Suppress("UNCHECKED_CAST") fun <T> read(field: SignalField): T? = selected.getValue(field).value as T?
            gearObserver?.invoke(GearSelectionDecision(evaluatedFrom, clock.elapsedRealtimeMs(),
                metadata.sequence, scenario, read(SignalField.GEAR), selected.getValue(SignalField.GEAR).quality))
            val frame = SignalFrame(scenario, read(SignalField.STEERING_DEG), read(SignalField.SPEED_KPH), read(SignalField.GEAR),
                read(SignalField.TURN_SIGNAL), read(SignalField.BRAKE_ON), read(SignalField.CRUISE_ACTIVE), read(SignalField.CRUISE_TARGET_KPH),
                read(SignalField.SEATS), read(SignalField.VENTILATION_LEVEL), read(SignalField.CABIN_TEMP_C), selected.mapValues { it.value.quality }, metadata)
            val navi = selectNavi(scenario, selectionNow, evaluated)
            val p = powerSelector.select(evaluated[Source.REAL]?.power, evaluated[Source.DEMO]?.power, selectionNow)
            val power = PowerEvent(scenario, p.value as? PowerState ?: PowerState.UNKNOWN, p.quality)
            timing(HubTimingStage.FRAME_READY, "publish", due, metadata.sequence)
            mutableFrame.value = frame; mutableNavi.value = navi; mutablePower.value = power
            updateDiagnostics(evaluated, metadata.sequence, missed)
            timing(HubTimingStage.DIAGNOSTICS_READY, "publish", due, metadata.sequence)
            emissionObserver?.invoke(metadata, clock.elapsedRealtimeMs())
            frameBus.tryEmit(Envelope(generation, frame))
            timing(HubTimingStage.EMIT_COMPLETED, "publish", due, metadata.sequence)
            powerBus.tryEmit(Envelope(generation, power))
            if (now >= nextNaviDeadline) {
                nextNaviDeadline = Math.addExact(nextNaviDeadline,
                    Math.multiplyExact((now - nextNaviDeadline) / config.naviStateIntervalMs + 1, config.naviStateIntervalMs))
                naviBus.tryEmit(Envelope(generation, navi))
            }
            true
        }
        } finally { probe.end(WorkStage.HUB_PUBLISH, started) }
    }

    private fun candidate(input: Input, channel: String, original: SignalCandidate<Any>?, limits: SignalFreshnessPolicy): SignalCandidate<Any> {
        val state = when (channel) { "vehicle" -> input.state.vehicle; "navi" -> input.state.navi; else -> input.state.power }
        val c: SignalCandidate<Any> = original ?: SignalCandidate(null, SignalQuality(source = input.definition.source))
        if (!canReadClock()) {
            // 종료·시계 오류 정리는 고장 난 Clock을 다시 읽지 않는다. 원본 시각은 그대로 남긴다.
            val availability = if (diagnostics.value.clockFailure) SignalAvailability.ERROR else SignalAvailability.UNAVAILABLE
            return c.copy(value = null, quality = c.quality.copy(availability = availability))
        }
        val current = if (state == InputCollectionStatus.COLLECTING) c else c.copy(value = null,
            quality = c.quality.copy(availability = input.failures[channel] ?: SignalAvailability.UNAVAILABLE))
        return current.freshness(limits, clock)
    }

    private fun evaluateInputs(): Map<Source, EvaluatedInput> = inputs.mapValues { (_, input) ->
        val rate = probe.measure(WorkStage.RATE_SAMPLE) {
            if (canReadClock()) input.meter.sample(rateNow(input)) else input.meter.lastKnownSnapshot()
        }
        EvaluatedInput(
            SignalField.entries.associateWith { field ->
                val c = candidate(input, "vehicle", input.vehicle[field], policy.vehicleFreshness.getValue(field))
                if (field == SignalField.STEERING_DEG) c.copy(quality = c.quality.copy(sourceRateHz = rate.hz, sourceRateInfo = rate)) else c
            },
            NaviField.entries.associateWith { field -> candidate(input, "navi", input.navi[field], policy.naviFreshness.getValue(field)) },
            candidate(input, "power", input.power, policy.powerFreshness), rate,
        )
    }

    private fun locationCandidate(input: EvaluatedInput?): SignalCandidate<Any>? {
        if (input == null) return null
        val lat = input.navi.getValue(NaviField.LAT)
        val lon = input.navi.getValue(NaviField.LON)
        val pairValid = lat.quality.availability == SignalAvailability.AVAILABLE && lon.quality.availability == SignalAvailability.AVAILABLE &&
            lat.quality.observedAtMs == lon.quality.observedAtMs && lat.quality.observedTimeBasis == lon.quality.observedTimeBasis &&
            lat.quality.receivedAtElapsedMs == lon.quality.receivedAtElapsedMs
        val q = when {
            lat.quality.availability != SignalAvailability.AVAILABLE -> lat.quality
            lon.quality.availability != SignalAvailability.AVAILABLE -> lon.quality
            !pairValid -> lat.quality.copy(availability = SignalAvailability.ERROR)
            else -> lat.quality
        }
        return SignalCandidate(if (pairValid) GeoPoint(lat.value as Double, lon.value as Double) else null, q)
    }

    private fun selectNavi(scenario: Long, selectionNow: Long, evaluated: Map<Source, EvaluatedInput>): NaviState {
        val real = evaluated[Source.REAL]
        val demo = evaluated[Source.DEMO]
        val fields = mutableMapOf<NaviField, SignalCandidate<Any>>()
        NaviField.entries.forEach { field ->
            if (field != NaviField.LAT && field != NaviField.LON) fields[field] = naviSelectors.getValue(field).select(
                real?.navi?.get(field), demo?.navi?.get(field), selectionNow)
        }
        val location = locationSelector.select(locationCandidate(real), locationCandidate(demo), selectionNow, demoLocationFirst = true)
        val point = location.value as? GeoPoint
        fields[NaviField.LAT] = SignalCandidate(point?.lat, location.quality)
        fields[NaviField.LON] = SignalCandidate(point?.lon, location.quality)
        @Suppress("UNCHECKED_CAST") fun <T> read(field: NaviField): T? = fields.getValue(field).value as T?
        return NaviState(scenario, read(NaviField.LAT), read(NaviField.LON), read(NaviField.HEADING_DEG),
            read(NaviField.REMAIN_TIME_SEC), read(NaviField.REMAIN_DIST_M), read(NaviField.DIST_TO_NEXT_TURN_M), fields.mapValues { it.value.quality })
    }

    /** 잠금을 기다리는 시간과 잠금 안 처리 시간을 분리해 계측한다. */
    private suspend inline fun <T> withStateLock(operation: String = "state", action: () -> T): T {
        timing(HubTimingStage.LOCK_REQUESTED, operation)
        val started = probe.begin(WorkStage.HUB_LOCK_WAIT)
        try { stateLock.lock() } finally { probe.end(WorkStage.HUB_LOCK_WAIT, started) }
        try {
            timing(HubTimingStage.LOCK_ACQUIRED, operation)
            return action()
        } finally {
            try { timing(HubTimingStage.LOCK_RELEASING, operation) }
            finally { stateLock.unlock() }
        }
    }

    private fun timing(stage: HubTimingStage, operation: String, dueAtMs: Long = -1, sequence: Long = -1) {
        // 호출 인자도 primitive로 유지한다. 계측을 끄면 nullable Long 포장·시계 읽기·점 할당을 하지 않는다.
        val observer = timingObserver ?: return
        observer(HubTimingPoint(stage, clock.elapsedRealtimeMs(), System.nanoTime(), operation,
            dueAtMs.takeIf { it >= 0 }, sequence.takeIf { it > 0 }))
    }

    // 부모 취소 중에는 하위 수집기의 finally가 부모 완료 콜백보다 먼저 실행될 수 있다.
    private fun canReadClock() = diagnostics.value.running && !diagnostics.value.clockFailure && job?.isActive == true
    private fun publicationNow() = requireNotNull(clock.timeAt(policy.publicationTimeBasis))
    private fun rateNow(input: Input) = clock.timeAt(input.definition.rateTimeBasis) ?: 0
    private fun status(source: Any?) = if (source == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.COLLECTING
    private fun idleState(source: HubSourceSet) = HubCollectionState(
        if (source.vehicle == null && source.complete == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.STOPPED,
        if (source.navi == null && source.complete == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.STOPPED,
        if (source.power == null && source.complete == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.STOPPED)
    /** 수신마다 실행하지 않는다. 발행 시에는 이미 평가한 후보와 원천 Hz를 재사용한다. */
    private fun updateDiagnostics(evaluated: Map<Source, EvaluatedInput>? = null, emittedFrames: Long? = null,
        missedDeadlines: Long = 0) = probe.measure(WorkStage.HUB_DIAGNOSTICS) {
        val values = evaluated ?: evaluateInputs()
        mutableDiagnostics.update { old -> old.copy(emittedFrames = emittedFrames ?: old.emittedFrames,
            skippedDeadlines = old.skippedDeadlines + missedDeadlines,
            nextPublicationAtMs = nextDeadline.takeIf { old.running && !old.clockFailure && job?.isActive == true },
            publicationTimeBasis = policy.publicationTimeBasis,
            publicationRate = probe.measure(WorkStage.RATE_SAMPLE) {
                if (canReadClock()) publicationMeter.sample(publicationNow()) else publicationMeter.lastKnownSnapshot()
            },
            inputs = inputs.mapValues { (source, input) ->
                val resolved = values.getValue(source)
                HubInputDiagnostics(input.state, input.callbacks, resolved.rate,
                    resolved.vehicle.mapValues { it.value.quality }, resolved.navi.mapValues { it.value.quality }, resolved.power.quality)
            }) }
        fun merged(read: (Input) -> InputCollectionStatus) = inputs.values.map(read).let { statuses ->
            listOf(InputCollectionStatus.COLLECTING, InputCollectionStatus.FAILED, InputCollectionStatus.COMPLETED,
                InputCollectionStatus.STOPPED, InputCollectionStatus.NOT_CONNECTED).first { it in statuses }
        }
        if (evaluated == null) mutableCollection.value = HubCollectionState(
            merged { it.state.vehicle }, merged { it.state.navi }, merged { it.state.power })
    }
}
