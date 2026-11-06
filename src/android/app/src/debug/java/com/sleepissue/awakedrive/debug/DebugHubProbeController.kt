package com.sleepissue.awakedrive.debug

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProbeInputState(val enabled: Boolean = false, val paused: Boolean = false, val hz: Int,
    val steering: Float, val speed: Float, val availability: SignalAvailability = SignalAvailability.AVAILABLE)

data class HubProbeSnapshot(val nowMs: Long = 0, val diagnostics: HubDiagnostics = HubDiagnostics(),
    val frame: SignalFrame? = null, val navi: NaviState? = null, val power: PowerEvent? = null,
    val inputs: Map<Source, ProbeInputState> = emptyMap(), val demoLocation: Boolean = true,
    val ready: Boolean = false)

/** 가짜 REAL은 SDK가 아니다. 운행 Controller·Tracker·Store·Room을 소유하거나 호출하지 않는다. */
class DebugHubProbeController(private val scope: CoroutineScope) {
    private class ProbeClock : Clock {
        var nowMs = 0L
        override fun scenarioTimeMs() = nowMs
        // 시험에서만 함께 움직이는 가짜 경과 시계다. 실제 센서 성능을 측정하지 않는다.
        override fun elapsedRealtimeMs() = nowMs
        override fun wallTimeMs() = 0L
    }
    private class Feed<T> {
        private data class Item<T>(val value: T, val done: CompletableDeferred<Unit>)
        private val items = MutableSharedFlow<Item<T>>()
        val flow: Flow<T> = flow { items.collect { item -> emit(item.value); item.done.complete(Unit) } }
        suspend fun send(value: T) { val done = CompletableDeferred<Unit>(); items.emit(Item(value, done)); done.await() }
    }
    private val clock = ProbeClock()
    private val commands = Mutex()
    private val vehicles = Source.entries.associateWith { Feed<VehicleSignalInput>() }
    private val navigation = Source.entries.associateWith { Feed<NaviState>() }
    private val powers = Source.entries.associateWith { Feed<PowerEvent>() }
    private val nextObservation = mutableMapOf<Source, Long>()
    private val inputState = mutableMapOf<Source, ProbeInputState>()
    private var demoLocation = true
    private var initialized = false
    private val mutableState = MutableStateFlow(HubProbeSnapshot())
    val state = mutableState.asStateFlow()
    private val hub = VehicleSignalHub(Source.entries.map { source -> HubSourceSet(source,
        object : VehicleSignalSource { override val samples = vehicles.getValue(source).flow },
        object : NaviSignalSource { override val states = navigation.getValue(source).flow },
        object : PowerSignalSource { override val events = powers.getValue(source).flow },
        basis(source), initiallyEnabled = false) }, clock, debugPolicy())

    init { resetInputs() }

    suspend fun initialize() = commands.withLock {
        if (!initialized) { hub.start(scope); initialized = true }
        publish()
    }

    suspend fun setInputEnabled(source: Source, enabled: Boolean) = command {
        val old = inputState.getValue(source)
        if (enabled) {
            check(hub.diagnostics.value.running) { "Hub 재시작을 먼저 눌러 주세요." }
            if (!old.enabled) hub.startInput(source)
            inputState[source] = old.copy(enabled = true, paused = false)
            emit(source)
            nextObservation[source] = clock.nowMs + 1_000 / old.hz
        } else {
            hub.stopInput(source)
            inputState[source] = old.copy(enabled = false, paused = false)
        }
    }

    suspend fun pauseRealObservations() = command {
        inputState[Source.REAL] = inputState.getValue(Source.REAL).copy(paused = true)
    }

    suspend fun setValues(source: Source, steering: Float, speed: Float) = command {
        require(steering.isFinite() && speed.isFinite() && speed >= 0)
        inputState[source] = inputState.getValue(source).copy(steering = steering, speed = speed)
    }

    suspend fun setRealRate(hz: Int) = command {
        require(hz in setOf(2, 5, 10))
        inputState[Source.REAL] = inputState.getValue(Source.REAL).copy(hz = hz)
        nextObservation[Source.REAL] = clock.nowMs + 1_000 / hz
    }

    suspend fun setRealAvailability(availability: SignalAvailability) = command {
        require(availability in setOf(SignalAvailability.AVAILABLE, SignalAvailability.UNSUPPORTED, SignalAvailability.PERMISSION_DENIED, SignalAvailability.ERROR))
        inputState[Source.REAL] = inputState.getValue(Source.REAL).copy(availability = availability)
        if (inputState.getValue(Source.REAL).enabled) {
            hub.stopInput(Source.REAL); hub.startInput(Source.REAL); emit(Source.REAL)
            nextObservation[Source.REAL] = clock.nowMs + 1_000 / inputState.getValue(Source.REAL).hz
        }
    }

    suspend fun setDemoLocation(enabled: Boolean) = command {
        demoLocation = enabled
        if (inputState.getValue(Source.DEMO).enabled && !inputState.getValue(Source.DEMO).paused) emitNavi(Source.DEMO)
    }

    suspend fun advance(deltaMs: Long) = command {
        require(deltaMs > 0 && deltaMs % 100 == 0L)
        val target = Math.addExact(clock.nowMs, deltaMs)
        while (clock.nowMs < target) {
            clock.nowMs += 100
            if (hub.diagnostics.value.running) {
                Source.entries.forEach { source ->
                    val input = inputState.getValue(source)
                    if (input.enabled && !input.paused && clock.nowMs >= nextObservation.getValue(source)) {
                        emit(source); nextObservation[source] = clock.nowMs + 1_000 / input.hz
                    }
                }
                hub.publishDue()
            }
            yield()
        }
    }

    suspend fun stopHub() = command {
        hub.stop()
        Source.entries.forEach { inputState[it] = inputState.getValue(it).copy(enabled = false, paused = false) }
    }
    suspend fun restartHub() = command {
        hub.stop(); hub.start(scope)
        Source.entries.forEach { inputState[it] = inputState.getValue(it).copy(enabled = false, paused = false) }
    }
    suspend fun reset() = command {
        hub.stop(); clock.nowMs = 0; resetInputs(); demoLocation = true; hub.start(scope)
    }
    suspend fun close() = commands.withLock { hub.stop(); initialized = false }

    private suspend fun command(action: suspend () -> Unit) = commands.withLock {
        check(initialized)
        action(); publish()
    }
    private fun resetInputs() {
        inputState[Source.REAL] = ProbeInputState(hz = 2, steering = 1f, speed = 80f)
        inputState[Source.DEMO] = ProbeInputState(hz = 10, steering = 8f, speed = 40f)
        nextObservation.clear()
    }
    private fun basis(source: Source) = if (source == Source.REAL) ObservationTimeBasis.ELAPSED_REALTIME else ObservationTimeBasis.SCENARIO
    private fun quality(source: Source, availability: SignalAvailability = SignalAvailability.AVAILABLE) =
        SignalQuality(availability, source, clock.nowMs, observedTimeBasis = basis(source))

    private suspend fun emit(source: Source) {
        val input = inputState.getValue(source)
        vehicles.getValue(source).send(VehicleSignalInput(clock.nowMs, source, input.steering, input.speed,
            gear = if (source == Source.REAL) Gear.D else Gear.P, brakeOn = false, cruiseActive = false,
            quality = SignalField.entries.associateWith { field -> quality(source,
                if (field == SignalField.STEERING_DEG) input.availability else SignalAvailability.AVAILABLE) }))
        emitNavi(source)
        powers.getValue(source).send(PowerEvent(clock.nowMs, PowerState.ON, quality(source)))
    }
    private suspend fun emitNavi(source: Source) {
        val hasLocation = source == Source.REAL || demoLocation
        navigation.getValue(source).send(NaviState(clock.nowMs,
            if (!hasLocation) null else if (source == Source.REAL) 37.5 else 35.5,
            if (!hasLocation) null else if (source == Source.REAL) 127.0 else 129.0,
            distToNextTurnM = if (source == Source.REAL) 500f else 800f,
            quality = NaviField.entries.associateWith { quality(source) }))
    }
    private fun publish() { mutableState.value = HubProbeSnapshot(clock.nowMs, hub.diagnostics.value,
        hub.latestFrame.value, hub.latestNavi.value, hub.latestPower.value, inputState.toMap(), demoLocation, true) }

    companion object {
        /** [가정] SDK 실측 전 검증값이다. 제품 최신성/유지시간의 확정값이 아니다. */
        fun debugPolicy(): HubPolicy {
            val limits = SignalFreshnessPolicy(1_000, 1_000, MissingObservationPolicy.REQUIRE_TIMESTAMP)
            return HubPolicy(SignalField.entries.associateWith { limits }, NaviField.entries.associateWith { limits }, limits,
                SignalFallbackPolicy(true, 0, ObservationTimeBasis.SCENARIO), ObservationTimeBasis.SCENARIO, HubTickDriver.MANUAL)
        }
    }
}
