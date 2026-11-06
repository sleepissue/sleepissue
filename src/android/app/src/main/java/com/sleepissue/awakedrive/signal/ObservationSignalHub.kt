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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 수동 운행·기존 관측 통로의 호환 구현이다. 실제 시간 주기 작업을 만들지 않는다.
 *
 * StateFlow는 화면용 최신 값이며 샘플을 합칠 수 있다. 분석 코드는 samples를 먼저 구독한 뒤
 * start를 호출한다. samples는 재생/추가 버퍼 없이 활성 소비자에게 순서대로 전달하며,
 * 느린 소비자가 있으면 발행을 기다린다. 구독자가 없을 때의 과거 샘플은 보관하지 않는다.
 *
 * 복수 입력의 우선권·최신성·주기 발행은 정책을 받는 Hub 생성자를 선택한다.
 * 어댑터는 callbackFlow 등의 취소 처리에서 차량 콜백을 해제해야 한다.
 */
internal class ObservationSignalHub(
    private val vehicleSource: VehicleSignalSource,
    private val clock: Clock,
    private val naviSource: NaviSignalSource? = null,
    private val powerSource: PowerSignalSource? = null,
) : SignalHubSession {
    private val normalizer = SignalNormalizer(clock)
    override val diagnostics = MutableStateFlow(HubDiagnostics(mode = HubPublicationMode.OBSERVATION))
    override val rawVehicleObservations: Flow<VehicleObservation> get() = samples.map { VehicleObservation(it, SignalField.entries.toSet()) }
    override suspend fun publishDue() = false
    override suspend fun startInput(source: com.sleepissue.awakedrive.engine.model.Source) = false
    override suspend fun stopInput(source: com.sleepissue.awakedrive.engine.model.Source) = false
    private val lifecycle = Mutex()
    private var sessionJob: Job? = null
    private val mutableLatestFrame = MutableStateFlow<SignalFrame?>(null)
    private val mutableLatestNavi = MutableStateFlow<NaviState?>(null)
    private val mutableLatestPower = MutableStateFlow<PowerEvent?>(null)
    private val mutableSamples = MutableSharedFlow<SignalFrame>()
    private val mutableNaviSamples = MutableSharedFlow<NaviState>()
    private val mutablePowerEvents = MutableSharedFlow<PowerEvent>()
    private val mutableCollectionState = MutableStateFlow(HubCollectionState())

    override val latestFrame: StateFlow<SignalFrame?> = mutableLatestFrame.asStateFlow()
    override val latestNavi: StateFlow<NaviState?> = mutableLatestNavi.asStateFlow()
    override val latestPower: StateFlow<PowerEvent?> = mutableLatestPower.asStateFlow()
    override val samples: SharedFlow<SignalFrame> = mutableSamples.asSharedFlow()
    override val naviSamples: SharedFlow<NaviState> = mutableNaviSamples.asSharedFlow()
    override val powerEvents: SharedFlow<PowerEvent> = mutablePowerEvents.asSharedFlow()
    override val collectionState: StateFlow<HubCollectionState> = mutableCollectionState.asStateFlow()

    /** 이미 소유 중인 세션이 있으면 false다. 완료/실패 후 재연결도 stop 다음 start로 한다. */
    override suspend fun start(ownerScope: CoroutineScope): Boolean = lifecycle.withLock {
        if (sessionJob != null) return@withLock false
        check(ownerScope.isActive) { "종료된 소유 범위에서는 Hub를 시작할 수 없습니다." }
        val job = SupervisorJob(ownerScope.coroutineContext[Job])
        sessionJob = job
        val scope = CoroutineScope(ownerScope.coroutineContext + job)
        diagnostics.value = HubDiagnostics(mode = HubPublicationMode.OBSERVATION, running = true,
            sessionId = diagnostics.value.sessionId + 1)
        mutableLatestFrame.value = null
        mutableLatestNavi.value = null
        mutableLatestPower.value = null
        mutableCollectionState.value = HubCollectionState(
            vehicle = InputCollectionStatus.COLLECTING,
            navi = if (naviSource == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.COLLECTING,
            power = if (powerSource == null) InputCollectionStatus.NOT_CONNECTED else InputCollectionStatus.COLLECTING,
        )
        job.invokeOnCompletion {
            diagnostics.update { it.copy(running = false) }
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
                require(input.updatedFields == null) { "부분 갱신은 주기 Hub 입력 계약을 사용해 주세요." }
                val frame = normalizer.vehicle(input)
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
                    val received = normalizer.navi(state)
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
    override suspend fun stop() = lifecycle.withLock {
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

    private fun InputCollectionStatus.afterCancellation(): InputCollectionStatus =
        if (this == InputCollectionStatus.COLLECTING) InputCollectionStatus.STOPPED else this
}
