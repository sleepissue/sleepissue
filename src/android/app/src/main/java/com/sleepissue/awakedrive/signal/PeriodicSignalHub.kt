package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
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
    private val inputs = sources.associate { it.source to Input(it) }
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
        stateLock.withLock {
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
            mutableDiagnostics.value = HubDiagnostics(running = true, sessionId = generation)
            updateDiagnostics()
        }
        parent.invokeOnCompletion {
            inputs.values.forEach { input -> input.meter.stop(); input.state = idleState(input.definition) }
            publicationMeter.stop()
            mutableFrame.value = null; mutableNavi.value = null; mutablePower.value = null
            mutableDiagnostics.update { it.copy(running = false) }
            updateDiagnostics()
        }
        inputs.values.filter { it.definition.initiallyEnabled }.forEach { startInputLocked(it) }
        if (policy.tickDriver == HubTickDriver.AUTOMATIC) owner!!.launch {
            while (isActive) {
                val wait = stateLock.withLock { (nextDeadline - publicationNow()).coerceIn(1, config.signalFrameIntervalMs) }
                delay(wait)
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
        val epoch = stateLock.withLock {
            input.epoch++; input.job = child; input.vehicle.clear(); input.navi.clear(); input.power = null
            input.callbacks = 0; input.observationSequence = 0; input.failures.clear()
            input.state = HubCollectionState(status(input.definition.vehicle), status(input.definition.navi), status(input.definition.power))
            if (input.definition.vehicle != null) input.meter.start(clock.timeAt(input.definition.rateTimeBasis) ?: 0, input.definition.rateTimeBasis)
            updateDiagnostics(); input.epoch
        }
        val session = generation
        input.definition.vehicle?.let { source -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
            collect(input, session, epoch, "vehicle", { source.samples }) { value -> acceptVehicle(input, value) }
        } }
        input.definition.navi?.let { source -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
            collect(input, session, epoch, "navi", { source.states }) { value ->
                val normalized = normalizer.navi(value)
                NaviField.entries.forEach { field -> input.navi[field] = cache(input, input.navi[field], normalized.valueOf(field), normalized.qualityOf(field)) }
            }
        } }
        input.definition.power?.let { source -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
            collect(input, session, epoch, "power", { source.events }) { value ->
                val q = value.quality.copy(receivedAtElapsedMs = clock.elapsedRealtimeMs())
                input.power = cache(input, input.power, value.state.takeIf { q.availability == SignalAvailability.AVAILABLE }, q)
            }
        } }
    }

    private suspend fun <T> collect(input: Input, session: Long, epoch: Long, channel: String,
        source: () -> Flow<T>, accept: (T) -> Unit) {
        var end = InputCollectionStatus.COMPLETED
        var failure = SignalAvailability.UNAVAILABLE
        try {
            source().collect { value -> stateLock.withLock {
                if (session == generation && epoch == input.epoch && diagnostics.value.running) { accept(value); updateDiagnostics() }
            } }
        } catch (cancelled: CancellationException) { end = InputCollectionStatus.STOPPED; throw cancelled }
        catch (_: SecurityException) { end = InputCollectionStatus.FAILED; failure = SignalAvailability.PERMISSION_DENIED }
        catch (_: Exception) { end = InputCollectionStatus.FAILED; failure = SignalAvailability.ERROR }
        finally {
            stateLock.withLock {
                if (session == generation && epoch == input.epoch) {
                    input.state = when (channel) {
                        "vehicle" -> input.state.copy(vehicle = end)
                        "navi" -> input.state.copy(navi = end)
                        else -> input.state.copy(power = end)
                    }
                    input.failures[channel] = failure
                    if (channel == "vehicle") input.meter.stop()
                    updateDiagnostics()
                }
            }
        }
    }

    private fun acceptVehicle(input: Input, value: VehicleSignalInput) {
        val frame = normalizer.vehicle(value)
        val fields = value.updatedFields ?: SignalField.entries.toSet()
        input.callbacks++
        fields.forEach { field ->
            val q = frame.qualityOf(field).let { if (value.source != input.definition.source) it.copy(availability = SignalAvailability.ERROR) else it }
            if (field == SignalField.STEERING_DEG) {
                if (q.availability == SignalAvailability.AVAILABLE) input.meter.observe(q.observedAtMs, q.observedTimeBasis,
                    clock.timeAt(input.definition.rateTimeBasis) ?: 0)
                else if (q.availability in setOf(SignalAvailability.UNSUPPORTED, SignalAvailability.PERMISSION_DENIED, SignalAvailability.ERROR))
                    input.meter.observe(null, ObservationTimeBasis.UNKNOWN, clock.timeAt(input.definition.rateTimeBasis) ?: 0)
            }
            input.vehicle[field] = cache(input, input.vehicle[field], frame.valueOf(field), q)
        }
        val observed = frame.copy(quality = fields.associateWith { requireNotNull(input.vehicle[it]).quality })
        observationBus.tryEmit(Envelope(generation, VehicleObservation(observed, fields)))
    }

    private fun cache(input: Input, previous: SignalCandidate<Any>?, value: Any?, quality: SignalQuality): SignalCandidate<Any> {
        var q = quality.copy(sourceRateHz = null, sourceRateInfo = null, selectionReason = null)
        if (q.source != null && q.source != input.definition.source) q = q.copy(availability = SignalAvailability.ERROR)
        q = q.copy(source = input.definition.source)
        val sameBasis = previous?.quality?.observedTimeBasis == q.observedTimeBasis
        val at = q.observedAtMs
        val before = previous?.quality?.observedAtMs
        if (value != null && q.availability == SignalAvailability.AVAILABLE && sameBasis && at != null && before != null) {
            if (at == before && previous.quality.availability == SignalAvailability.AVAILABLE) return previous
            if (at < before) q = q.copy(availability = SignalAvailability.ERROR)
        }
        q = q.copy(observationSequence = ++input.observationSequence)
        return SignalCandidate(value.takeIf { q.availability == SignalAvailability.AVAILABLE }, q)
    }

    override suspend fun stopInput(source: Source): Boolean = lifecycle.withLock {
        val input = inputs[source] ?: return@withLock false
        val child = input.job ?: return@withLock false
        stateLock.withLock {
            input.epoch++; input.state = idleState(input.definition); input.meter.stop()
            input.failures.clear(); updateDiagnostics()
        }
        child.cancelAndJoin(); input.job = null
        true
    }

    override suspend fun stop() = lifecycle.withLock {
        stateLock.withLock { mutableDiagnostics.update { it.copy(running = false) } }
        job?.cancelAndJoin(); job = null; owner = null
        stateLock.withLock {
            inputs.values.forEach { it.job = null; it.vehicle.clear(); it.navi.clear(); it.power = null; it.meter.stop(); it.state = idleState(it.definition) }
            mutableFrame.value = null; mutableNavi.value = null; mutablePower.value = null
            updateDiagnostics()
        }
    }

    override suspend fun publishDue(): Boolean = stateLock.withLock {
        if (job?.isActive != true || !diagnostics.value.running || diagnostics.value.clockFailure) return@withLock false
        val now = publicationNow()
        val scenario = clock.scenarioTimeMs(); val elapsed = clock.elapsedRealtimeMs()
        if (now < lastTick || scenario < lastScenario || elapsed < lastElapsed) {
            mutableDiagnostics.update { it.copy(clockFailure = true) }
            return@withLock false
        }
        lastTick = now; lastScenario = scenario; lastElapsed = elapsed
        if (now < nextDeadline) return@withLock false
        val missed = (now - nextDeadline) / config.signalFrameIntervalMs
        nextDeadline = Math.addExact(nextDeadline, Math.multiplyExact(missed + 1, config.signalFrameIntervalMs))
        val metadata = FramePublication(generation, diagnostics.value.emittedFrames + 1, elapsed)
        publicationMeter.observe(now, policy.publicationTimeBasis, now)
        val selectionNow = requireNotNull(clock.timeAt(policy.fallback.timerBasis))
        val selected = SignalField.entries.associateWith { field ->
            selectors.getValue(field).select(vehicleCandidate(Source.REAL, field), vehicleCandidate(Source.DEMO, field), selectionNow)
        }
        @Suppress("UNCHECKED_CAST") fun <T> read(field: SignalField): T? = selected.getValue(field).value as T?
        val frame = SignalFrame(scenario, read(SignalField.STEERING_DEG), read(SignalField.SPEED_KPH), read(SignalField.GEAR),
            read(SignalField.TURN_SIGNAL), read(SignalField.BRAKE_ON), read(SignalField.CRUISE_ACTIVE), read(SignalField.CRUISE_TARGET_KPH),
            read(SignalField.SEATS), read(SignalField.VENTILATION_LEVEL), read(SignalField.CABIN_TEMP_C), selected.mapValues { it.value.quality }, metadata)
        val navi = selectNavi(scenario, selectionNow)
        val p = powerSelector.select(powerCandidate(Source.REAL), powerCandidate(Source.DEMO), selectionNow)
        val power = PowerEvent(scenario, p.value as? PowerState ?: PowerState.UNKNOWN, p.quality)
        mutableFrame.value = frame; mutableNavi.value = navi; mutablePower.value = power
        mutableDiagnostics.update { it.copy(emittedFrames = metadata.sequence, skippedDeadlines = it.skippedDeadlines + missed) }
        updateDiagnostics()
        frameBus.tryEmit(Envelope(generation, frame)); powerBus.tryEmit(Envelope(generation, power))
        if (now >= nextNaviDeadline) {
            nextNaviDeadline = Math.addExact(nextNaviDeadline,
                Math.multiplyExact((now - nextNaviDeadline) / config.naviStateIntervalMs + 1, config.naviStateIntervalMs))
            naviBus.tryEmit(Envelope(generation, navi))
        }
        true
    }

    private fun candidate(input: Input, channel: String, original: SignalCandidate<Any>?, limits: SignalFreshnessPolicy): SignalCandidate<Any> {
        val state = when (channel) { "vehicle" -> input.state.vehicle; "navi" -> input.state.navi; else -> input.state.power }
        val c: SignalCandidate<Any> = original ?: SignalCandidate(null, SignalQuality(source = input.definition.source))
        val current = if (state == InputCollectionStatus.COLLECTING) c else c.copy(value = null,
            quality = c.quality.copy(availability = input.failures[channel] ?: SignalAvailability.UNAVAILABLE))
        return current.freshness(limits, clock)
    }

    private fun vehicleCandidate(source: Source, field: SignalField): SignalCandidate<Any>? = inputs[source]?.let { input ->
        val c = candidate(input, "vehicle", input.vehicle[field], policy.vehicleFreshness.getValue(field))
        if (field == SignalField.STEERING_DEG) {
            val rate = input.meter.sample(rateNow(input))
            c.copy(quality = c.quality.copy(sourceRateHz = rate.hz, sourceRateInfo = rate))
        } else c
    }
    private fun naviCandidate(source: Source, field: NaviField) = inputs[source]?.let {
        candidate(it, "navi", it.navi[field], policy.naviFreshness.getValue(field))
    }
    private fun powerCandidate(source: Source) = inputs[source]?.let { candidate(it, "power", it.power, policy.powerFreshness) }

    private fun locationCandidate(source: Source): SignalCandidate<Any>? {
        val lat = naviCandidate(source, NaviField.LAT) ?: return null
        val lon = naviCandidate(source, NaviField.LON) ?: return null
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

    private fun selectNavi(scenario: Long, selectionNow: Long): NaviState {
        val fields = NaviField.entries.associateWith { field -> naviSelectors.getValue(field).select(
            naviCandidate(Source.REAL, field), naviCandidate(Source.DEMO, field), selectionNow) }.toMutableMap()
        val location = locationSelector.select(locationCandidate(Source.REAL), locationCandidate(Source.DEMO), selectionNow, demoLocationFirst = true)
        val point = location.value as? GeoPoint
        fields[NaviField.LAT] = SignalCandidate(point?.lat, location.quality)
        fields[NaviField.LON] = SignalCandidate(point?.lon, location.quality)
        @Suppress("UNCHECKED_CAST") fun <T> read(field: NaviField): T? = fields.getValue(field).value as T?
        return NaviState(scenario, read(NaviField.LAT), read(NaviField.LON), read(NaviField.HEADING_DEG),
            read(NaviField.REMAIN_TIME_SEC), read(NaviField.REMAIN_DIST_M), read(NaviField.DIST_TO_NEXT_TURN_M), fields.mapValues { it.value.quality })
    }

    private fun publicationNow() = requireNotNull(clock.timeAt(policy.publicationTimeBasis))
    private fun rateNow(input: Input) = clock.timeAt(input.definition.rateTimeBasis) ?: 0
    private fun status(source: Any?) = if (source == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.COLLECTING
    private fun idleState(source: HubSourceSet) = HubCollectionState(
        if (source.vehicle == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.STOPPED,
        if (source.navi == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.STOPPED,
        if (source.power == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.STOPPED)
    private fun updateDiagnostics() {
        mutableDiagnostics.update { old -> old.copy(publicationRate = publicationMeter.sample(publicationNow()),
            inputs = inputs.mapValues { (_, input) -> HubInputDiagnostics(input.state, input.callbacks, input.meter.sample(rateNow(input)),
                SignalField.entries.associateWith { candidate(input, "vehicle", input.vehicle[it], policy.vehicleFreshness.getValue(it)).quality },
                NaviField.entries.associateWith { candidate(input, "navi", input.navi[it], policy.naviFreshness.getValue(it)).quality },
                candidate(input, "power", input.power, policy.powerFreshness).quality) }) }
        fun merged(read: (Input) -> InputCollectionStatus) = inputs.values.map(read).let { statuses ->
            listOf(InputCollectionStatus.COLLECTING, InputCollectionStatus.FAILED, InputCollectionStatus.COMPLETED,
                InputCollectionStatus.STOPPED, InputCollectionStatus.NOT_CONNECTED).first { it in statuses }
        }
        mutableCollection.value = HubCollectionState(merged { it.state.vehicle }, merged { it.state.navi }, merged { it.state.power })
    }
}
