package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.NaviField
import com.sleepissue.awakedrive.engine.model.PowerEvent
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.SignalQuality
import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class InputCollectionStatus { NOT_CONNECTED, COLLECTING, COMPLETED, FAILED, STOPPED }

/** 값이 그대로인 정상 관측과 입력 수집 종료를 구분하는 연결 상태다. */
data class HubCollectionState(
    val vehicle: InputCollectionStatus = InputCollectionStatus.STOPPED,
    val navi: InputCollectionStatus = InputCollectionStatus.NOT_CONNECTED,
    val power: InputCollectionStatus = InputCollectionStatus.NOT_CONNECTED,
)

/**
 * 하나의 입력 소유자를 구독해 공통 단위로 전달하는 A 범위의 최소 Hub다.
 *
 * StateFlow는 화면용 최신 값이며 샘플을 합칠 수 있다. 분석 코드는 samples를 먼저 구독한 뒤
 * start를 호출한다. samples는 재생/추가 버퍼 없이 활성 소비자에게 순서대로 전달하며,
 * 느린 소비자가 있으면 발행을 기다린다. 구독자가 없을 때의 과거 샘플은 보관하지 않는다.
 *
 * 복수 실제/데모 입력의 우선권·최신성 문턱·주기 재발행은 후속 범위다.
 * 어댑터는 callbackFlow 등의 취소 처리에서 차량 콜백을 해제해야 한다.
 */
class VehicleSignalHub(
    private val vehicleSource: VehicleSignalSource,
    private val clock: Clock,
    private val naviSource: NaviSignalSource? = null,
    private val powerSource: PowerSignalSource? = null,
) {
    private val lifecycle = Mutex()
    private var sessionJob: Job? = null
    private val mutableLatestFrame = MutableStateFlow<SignalFrame?>(null)
    private val mutableLatestNavi = MutableStateFlow<NaviState?>(null)
    private val mutableLatestPower = MutableStateFlow<PowerEvent?>(null)
    private val mutableSamples = MutableSharedFlow<SignalFrame>()
    private val mutableNaviSamples = MutableSharedFlow<NaviState>()
    private val mutablePowerEvents = MutableSharedFlow<PowerEvent>()
    private val mutableCollectionState = MutableStateFlow(HubCollectionState())

    val latestFrame: StateFlow<SignalFrame?> = mutableLatestFrame.asStateFlow()
    val latestNavi: StateFlow<NaviState?> = mutableLatestNavi.asStateFlow()
    val latestPower: StateFlow<PowerEvent?> = mutableLatestPower.asStateFlow()
    val samples: SharedFlow<SignalFrame> = mutableSamples.asSharedFlow()
    val naviSamples: SharedFlow<NaviState> = mutableNaviSamples.asSharedFlow()
    val powerEvents: SharedFlow<PowerEvent> = mutablePowerEvents.asSharedFlow()
    val collectionState: StateFlow<HubCollectionState> = mutableCollectionState.asStateFlow()

    /** 이미 소유 중인 세션이 있으면 false다. 완료/실패 후 재연결도 stop 다음 start로 한다. */
    suspend fun start(ownerScope: CoroutineScope): Boolean = lifecycle.withLock {
        if (sessionJob != null) return@withLock false
        check(ownerScope.isActive) { "종료된 소유 범위에서는 Hub를 시작할 수 없습니다." }
        val job = SupervisorJob(ownerScope.coroutineContext[Job])
        sessionJob = job
        val scope = CoroutineScope(ownerScope.coroutineContext + job)
        mutableLatestFrame.value = null
        mutableLatestNavi.value = null
        mutableLatestPower.value = null
        mutableCollectionState.value = HubCollectionState(
            vehicle = InputCollectionStatus.COLLECTING,
            navi = if (naviSource == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.COLLECTING,
            power = if (powerSource == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.COLLECTING,
        )
        job.invokeOnCompletion {
            mutableLatestFrame.value = null
            mutableLatestNavi.value = null
            mutableLatestPower.value = null
            mutableCollectionState.update { state ->
                state.copy(
                    vehicle = state.vehicle.afterCancellation(),
                    navi = state.navi.afterCancellation(),
                    power = state.power.afterCancellation(),
                )
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            collectInput({ vehicleSource.samples }, { status ->
                if (status != InputCollectionStatus.COLLECTING) mutableLatestFrame.value = null
                mutableCollectionState.update { it.copy(vehicle = status) }
            }) { input ->
                val frame = normalize(input)
                mutableLatestFrame.value = frame
                mutableSamples.emit(frame)
            }
        }
        naviSource?.let { source ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                collectInput({ source.states }, { status ->
                    if (status != InputCollectionStatus.COLLECTING) mutableLatestNavi.value = null
                    mutableCollectionState.update { it.copy(navi = status) }
                }) { state ->
                    val received = normalizeNavi(state)
                    mutableLatestNavi.value = received
                    mutableNaviSamples.emit(received)
                }
            }
        }
        powerSource?.let { source ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                collectInput({ source.events }, { status ->
                    if (status != InputCollectionStatus.COLLECTING) mutableLatestPower.value = null
                    mutableCollectionState.update { it.copy(power = status) }
                }) { event ->
                    val received = event.copy(
                        state = if (event.quality.availability == SignalAvailability.AVAILABLE) event.state else PowerState.UNKNOWN,
                        quality = event.quality.copy(receivedAtElapsedMs = clock.elapsedRealtimeMs()),
                    )
                    mutableLatestPower.value = received
                    mutablePowerEvents.emit(received)
                }
            }
        }
        true
    }

    /** 반환 시 입력 수집과 대기 중인 발행이 모두 끝나고, 이전 세션의 최신 값도 비운다. */
    suspend fun stop() = lifecycle.withLock {
        sessionJob?.cancelAndJoin()
        sessionJob = null
        mutableLatestFrame.value = null
        mutableLatestNavi.value = null
        mutableLatestPower.value = null
        mutableCollectionState.value = HubCollectionState(
            vehicle = InputCollectionStatus.STOPPED,
            navi = if (naviSource == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.STOPPED,
            power = if (powerSource == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.STOPPED,
        )
    }

    private suspend fun <T> collectInput(
        input: () -> Flow<T>,
        setStatus: (InputCollectionStatus) -> Unit,
        accept: suspend (T) -> Unit,
    ) {
        try {
            // 속성 getter에서 일어나는 권한/초기화 오류도 입력 실패로 격리한다.
            input().collect { accept(it) }
            setStatus(InputCollectionStatus.COMPLETED)
        } catch (cancelled: CancellationException) {
            setStatus(InputCollectionStatus.STOPPED)
            throw cancelled
        } catch (_: Exception) {
            // 원문 오류 문자열에 비밀값이 섞일 수 있으므로 상태만 전달한다.
            setStatus(InputCollectionStatus.FAILED)
        }
    }

    private fun normalize(input: VehicleSignalInput): SignalFrame {
        val quality = mutableMapOf<SignalField, SignalQuality>()
        val receivedAtMs = clock.elapsedRealtimeMs()
        fun <T> read(field: SignalField, value: T?, isValid: (T) -> Boolean = { true }): T? {
            val supplied = input.quality[field] ?: SignalQuality(
                availability = if (value == null) SignalAvailability.UNAVAILABLE else SignalAvailability.AVAILABLE,
                source = input.source,
            )
            val availability = when {
                supplied.availability != SignalAvailability.AVAILABLE -> supplied.availability
                value == null -> SignalAvailability.UNAVAILABLE
                !isValid(value) -> SignalAvailability.ERROR
                else -> SignalAvailability.AVAILABLE
            }
            quality[field] = supplied.copy(
                availability = availability,
                source = supplied.source ?: input.source,
                receivedAtElapsedMs = receivedAtMs,
            )
            return value.takeIf { availability == SignalAvailability.AVAILABLE }
        }
        val speedKph = input.speed?.let { raw ->
            when (input.speedUnit) {
                SpeedUnit.METERS_PER_SECOND -> raw * 3.6f
                SpeedUnit.KILOMETERS_PER_HOUR -> raw
            }
        }
        return SignalFrame(
            tMs = input.tMs,
            steeringDeg = read(SignalField.STEERING_DEG, input.steeringDeg) { it.isFinite() },
            speedKph = read(SignalField.SPEED_KPH, speedKph) { it.isFinite() && it >= 0f },
            gear = read(SignalField.GEAR, input.gear),
            turnSignal = read(SignalField.TURN_SIGNAL, input.turnSignal),
            brakeOn = read(SignalField.BRAKE_ON, input.brakeOn),
            cruiseActive = read(SignalField.CRUISE_ACTIVE, input.cruiseActive),
            cruiseTargetKph = read(SignalField.CRUISE_TARGET_KPH, input.cruiseTargetKph) { it.isFinite() && it >= 0f },
            seats = read(SignalField.SEATS, input.seats),
            ventilationLevel = read(SignalField.VENTILATION_LEVEL, input.ventilationLevel) { it >= 0 },
            cabinTempC = read(SignalField.CABIN_TEMP_C, input.cabinTempC) { it.isFinite() },
            quality = quality.toMap(),
        )
    }

    private fun normalizeNavi(input: NaviState): NaviState {
        val quality = mutableMapOf<NaviField, SignalQuality>()
        val receivedAtMs = clock.elapsedRealtimeMs()
        fun <T> read(field: NaviField, value: T?): T? {
            val supplied = input.quality[field] ?: SignalQuality()
            val availability = if (supplied.availability == SignalAvailability.AVAILABLE && value == null) {
                SignalAvailability.UNAVAILABLE
            } else supplied.availability
            quality[field] = supplied.copy(availability = availability, receivedAtElapsedMs = receivedAtMs)
            return value.takeIf { availability == SignalAvailability.AVAILABLE }
        }
        return input.copy(
            lat = read(NaviField.LAT, input.lat),
            lon = read(NaviField.LON, input.lon),
            headingDeg = read(NaviField.HEADING_DEG, input.headingDeg),
            remainTimeSec = read(NaviField.REMAIN_TIME_SEC, input.remainTimeSec),
            remainDistM = read(NaviField.REMAIN_DIST_M, input.remainDistM),
            distToNextTurnM = read(NaviField.DIST_TO_NEXT_TURN_M, input.distToNextTurnM),
            quality = quality.toMap(),
        )
    }

    private fun InputCollectionStatus.afterCancellation(): InputCollectionStatus =
        if (this == InputCollectionStatus.COLLECTING) InputCollectionStatus.STOPPED else this
}
