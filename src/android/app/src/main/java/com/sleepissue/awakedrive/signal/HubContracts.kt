package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class InputCollectionStatus { NOT_CONNECTED, COLLECTING, COMPLETED, FAILED, STOPPED }
enum class HubPublicationMode { OBSERVATION, PERIODIC }
enum class HubTickDriver { AUTOMATIC, MANUAL }

data class HubCollectionState(
    val vehicle: InputCollectionStatus = InputCollectionStatus.STOPPED,
    val navi: InputCollectionStatus = InputCollectionStatus.NOT_CONNECTED,
    val power: InputCollectionStatus = InputCollectionStatus.NOT_CONNECTED,
)

/** 동일 원본의 차량·내비·전원을 보존한 앱 내부 전달 단위다. 원천 시각과 품질을 다시 찍지 않는다. */
data class CompleteSignalObservation(val vehicle: VehicleSignalInput, val navi: NaviState, val power: PowerEvent)

/** 세 채널을 함께 가진 원본만 제공한다. SDK의 기존 개별 Source 인터페이스와 독립적이다. */
interface CompleteSignalSource {
    val observations: Flow<CompleteSignalObservation>
}

/**
 * 출처별 콜백 소유자다. rateTimeBasis는 센서 원천 시각과 같아야 한다.
 * complete가 있으면 완전 관측 통로 하나를 구독하며 개별 세 통로는 구독하지 않는다.
 */
data class HubSourceSet(val source: Source, val vehicle: VehicleSignalSource? = null,
    val navi: NaviSignalSource? = null, val power: PowerSignalSource? = null,
    val rateTimeBasis: ObservationTimeBasis, val initiallyEnabled: Boolean = true,
    val complete: CompleteSignalSource? = null)

/** 최신성·우선권·발행 시계는 미확정 정책이므로 조립자가 모두 선택한다. */
data class HubPolicy(
    val vehicleFreshness: Map<SignalField, SignalFreshnessPolicy>,
    val naviFreshness: Map<NaviField, SignalFreshnessPolicy>,
    val powerFreshness: SignalFreshnessPolicy,
    val fallback: SignalFallbackPolicy,
    val publicationTimeBasis: ObservationTimeBasis,
    val tickDriver: HubTickDriver,
) {
    init {
        require(vehicleFreshness.keys.containsAll(SignalField.entries))
        require(naviFreshness.keys.containsAll(NaviField.entries))
        require(publicationTimeBasis in setOf(ObservationTimeBasis.SCENARIO, ObservationTimeBasis.ELAPSED_REALTIME))
    }
}

data class HubInputDiagnostics(val collection: HubCollectionState = HubCollectionState(),
    val vehicleCallbacks: Long = 0, val steeringRate: SourceRate = SourceRate(),
    val vehicleQuality: Map<SignalField, SignalQuality> = emptyMap(),
    val naviQuality: Map<NaviField, SignalQuality> = emptyMap(), val powerQuality: SignalQuality? = null)

/**
 * rates는 출처와 원천 시간축을 포함한다. DEMO의 시나리오 Hz는 실제 센서 성능이 아니다.
 * 주기 Hub 진단은 발행 또는 수집 상태 변경 시의 스냅샷이다. 수신 콜백마다 전체 품질을 다시 계산하지 않는다.
 * nextPublicationAtMs는 HubPolicy.publicationTimeBasis의 다음 독립 발행 시한이다. frame.tMs에서 추정하지 않는다.
 * Hub가 정지했거나 시계 오류가 있으면 시한은 null이다. publicationTimeBasis는 설정한 정책의 시간축이다.
 */
data class HubDiagnostics(val mode: HubPublicationMode = HubPublicationMode.PERIODIC,
    val running: Boolean = false, val sessionId: Long = 0, val emittedFrames: Long = 0,
    val skippedDeadlines: Long = 0, val publicationRate: SourceRate = SourceRate(),
    val inputs: Map<Source, HubInputDiagnostics> = emptyMap(), val clockFailure: Boolean = false,
    val nextPublicationAtMs: Long? = null,
    val publicationTimeBasis: ObservationTimeBasis = ObservationTimeBasis.UNKNOWN)

data class VehicleObservation(val frame: SignalFrame, val updatedFields: Set<SignalField>)

interface SignalHubSession {
    val latestFrame: StateFlow<SignalFrame?>
    val latestNavi: StateFlow<NaviState?>
    val latestPower: StateFlow<PowerEvent?>
    val samples: Flow<SignalFrame>
    val naviSamples: Flow<NaviState>
    val powerEvents: Flow<PowerEvent>
    val rawVehicleObservations: Flow<VehicleObservation>
    val collectionState: StateFlow<HubCollectionState>
    val diagnostics: StateFlow<HubDiagnostics>
    suspend fun start(ownerScope: CoroutineScope): Boolean
    suspend fun stop()
    suspend fun publishDue(): Boolean
    suspend fun startInput(source: Source): Boolean
    suspend fun stopInput(source: Source): Boolean
}
