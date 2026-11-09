package com.sleepissue.awakedrive.signal

import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.CheckContext
import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.EvaluationStatus
import com.sleepissue.awakedrive.engine.model.ExecutionRequest
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.PowerEvent
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.SeatOccupancy
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.SignalQuality
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.model.TurnSignal
import com.sleepissue.awakedrive.engine.model.TripSnapshot
import kotlinx.coroutines.flow.Flow

/** 원시 속도의 단위만 구분한다. 나머지 필드는 이름에 적힌 내부 단위로 공급한다. */
enum class SpeedUnit { METERS_PER_SECOND, KILOMETERS_PER_HOUR }

/**
 * 차량 어댑터의 입력이다. speed는 speedUnit 단위이며 Hub가 한 번만 km/h로 바꾼다.
 * tMs는 시나리오/운행 논리 시각이고, 센서의 원천 시각은 quality에 따로 남긴다.
 * 기본은 완전한 관측 묶음이다. updatedFields를 명시한 경우에만 부분 갱신한다.
 * 갱신 대상의 null은 이전 값 유지 명령이나 실제 0이 아니다.
 */
data class VehicleSignalInput(
    val tMs: Long,
    val source: Source,
    val steeringDeg: Float? = null,
    val speed: Float? = null,
    val speedUnit: SpeedUnit = SpeedUnit.KILOMETERS_PER_HOUR,
    val gear: Gear? = null,
    val turnSignal: TurnSignal? = null,
    val brakeOn: Boolean? = null,
    val cruiseActive: Boolean? = null,
    val cruiseTargetKph: Float? = null,
    val seats: SeatOccupancy? = null,
    val ventilationLevel: Int? = null,
    val cabinTempC: Float? = null,
    val quality: Map<SignalField, SignalQuality> = emptyMap(),
    // null은 완전 관측, 명시한 필드 집합은 부분 갱신이다. 집합 안의 null은 값을 지운다.
    val updatedFields: Set<SignalField>? = null,
    // 현재값 재조회로만 확인한 필드다. 캐시/품질은 갱신하지만 센서 관측 통로·콜백 계수에는 넣지 않는다.
    val confirmationFields: Set<SignalField> = emptySet(),
)

/** 구독 종료는 Flow 수집 취소로 전파한다. 실제 콜백 해제는 어댑터의 책임이다. */
interface VehicleSignalSource {
    val samples: Flow<VehicleSignalInput>
}

interface NaviSignalSource {
    val states: Flow<NaviState>
    // 조회 응답과 자발 알림을 구별할 수 없는 상태 보고 원천이다. Hub가 센서 관측 순번을 만들지 않는다.
    val stateReportsOnly: Boolean get() = false
}

interface PowerSignalSource {
    val events: Flow<PowerEvent>
}

/** 2번 담당의 계산 결과다. 미평가/계산 불가를 0점으로 바꾸지 않는다. */
data class DrivingAssessment(
    val tMs: Long,
    val evaluationStatus: EvaluationStatus = EvaluationStatus.NOT_EVALUATED,
    val riskScore: Float? = null,
    val pattern: DrivingPattern? = null,
    val assistedDriving: Boolean? = null,
    val operationGapSec: Long? = null,
)

enum class DrivingPattern { TRANSIENT, TRENDING, UNCERTAIN }

interface DrivingAssessmentSource {
    val assessments: Flow<DrivingAssessment>
}

/** 예보 계산 자체와 등급 정의는 4번 담당이다. dueAtMs는 운행 논리 시각이다. */
data class ForecastAssessment(
    val tMs: Long,
    val evaluationStatus: EvaluationStatus = EvaluationStatus.NOT_EVALUATED,
    val dueAtMs: Long? = null,
)

interface ForecastSource {
    val forecasts: Flow<ForecastAssessment>
}

data class RestCertification(
    val sessionId: String,
    val certifiedAtMs: Long,
    val source: Source,
)

interface RestCertificationSource {
    val certifications: Flow<RestCertification>
}

data class CheckRequest(
    override val requestId: RequestId,
    val context: CheckContext,
    val restArea: RestAreaCandidate? = null,
) : ExecutionRequest

interface CheckPort {
    /**
     * 비동기 실행 진행 통로다. DEFERRED는 아직 질문을 시작하지 않았음을 뜻한다.
     * STARTED는 요청 접수가 아니라 실제 질문 안내 음성이 시작된 시점에 보낸다.
     * DemoPlayer의 질문 구간은 STARTED부터 최종 results 또는 실패·취소까지 연결한다.
     */
    val executionResults: Flow<ExecutionResult>
    /** 사람의 확인 결과다. 장치 실패·실행 취소와 정상 질문 후 무응답을 구분한다. */
    val results: Flow<CheckResult>
    suspend fun start(request: CheckRequest): ExecutionResult
    suspend fun cancel(requestId: RequestId): ExecutionResult
}

/** 안내 종류만 전달한다. 실제 TTS 문구·오디오 시작 시각은 음성 담당이 소유한다. */
enum class SpeechCue { CAUTION, REST_AREA_AHEAD, NO_REST_AREA, REST_RECOMMENDED, SAFETY_REST_RECOMMENDED }

data class SpeechRequest(
    override val requestId: RequestId,
    val cue: SpeechCue,
    val restArea: RestAreaCandidate? = null,
) : ExecutionRequest

interface SpeechPort {
    val results: Flow<ExecutionResult>
    suspend fun start(request: SpeechRequest): ExecutionResult
    suspend fun cancel(requestId: RequestId): ExecutionResult
}

data class RestAreaCandidate(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val distanceM: Float,
)

enum class RestAction { FIND_AHEAD, ADD_WAYPOINT, REMOVE_WAYPOINT }

data class RestRequest(
    override val requestId: RequestId,
    val action: RestAction,
    val naviState: NaviState? = null,
    val candidateId: String? = null,
) : ExecutionRequest

data class RestResult(
    val execution: ExecutionResult,
    val candidate: RestAreaCandidate? = null,
)

/** 경유지 취소는 제거 성공을 뜻하지 않는다. 확인된 결과를 results로 전달한다. */
interface RestPort {
    val results: Flow<RestResult>
    suspend fun start(request: RestRequest): ExecutionResult
    suspend fun cancel(requestId: RequestId): ExecutionResult
}

enum class ClimateAction { ENABLE_DRIVER_VENTILATION, RESTORE_DRIVER_VENTILATION }

data class ClimateRequest(
    override val requestId: RequestId,
    val action: ClimateAction,
    val priorConsent: Boolean,
) : ExecutionRequest

/** 운전석 통풍의 실행 요청이다. 지원·동의·반영 확인은 실제 구현에서 검증한다. */
interface ClimatePort {
    val results: Flow<ExecutionResult>
    suspend fun start(request: ClimateRequest): ExecutionResult
    suspend fun cancel(requestId: RequestId): ExecutionResult
}

enum class RepositoryFailure { NOT_CONNECTED, UNAVAILABLE, ERROR, INVALID_DATA, STALE_INPUT, UNSUPPORTED_VERSION, UNSUPPORTED_TIME_BASIS }

sealed interface TripLoadResult {
    data class Loaded(val snapshot: TripSnapshot) : TripLoadResult
    data object Empty : TripLoadResult
    data class Failed(val reason: RepositoryFailure) : TripLoadResult
}

sealed interface RepositoryResult {
    data object Succeeded : RepositoryResult
    data class Failed(val reason: RepositoryFailure) : RepositoryResult
}

/** 메모리 보관과 영속 저장의 경계다. 상태와 정밀 구간은 하나의 저장 단위다. */
interface TripRepository {
    suspend fun load(): TripLoadResult
    suspend fun save(snapshot: TripSnapshot): RepositoryResult
    suspend fun delete(): RepositoryResult
}
