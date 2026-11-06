package com.sleepissue.awakedrive.session

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.engine.model.Source
import com.sleepissue.awakedrive.engine.rest.RestCertifier
import com.sleepissue.awakedrive.engine.rest.RestEvent
import com.sleepissue.awakedrive.engine.rest.RestObservation
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.ClimateAction
import com.sleepissue.awakedrive.signal.ClimatePort
import com.sleepissue.awakedrive.signal.ClimateRequest
import com.sleepissue.awakedrive.signal.RestCertification
import com.sleepissue.awakedrive.signal.RestCertificationSource
import com.sleepissue.awakedrive.signal.SignalHubSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 휴식 화면이 보여 줄 것. */
data class RestUiState(
    val areaName: String? = null,
    val startedAtMs: Long? = null,
    val certified: Boolean = false,
    val lastRecord: RestRecord? = null,
)

/**
 * [4번] RestSession — 휴식 자동 인증을 앱에 연결한다 (기획서 7-1·7-3, SRS RES).
 *
 * ROS로 치면 Hub 토픽(기어·위치·전원)과 1번 상태(연속 운전·단계)를 구독해 [RestCertifier]에 넣는 노드다.
 * 인증되면:
 *  1. 1번 [RestCertificationSource] 계약으로 [certifications]에 내보낸다 → 1번이 3단계 해제·회복 확인을 진행
 *  2. [RewardLedger]에 기록하고 스탬프를 정한다
 *  3. 앱이 켰던 운전석 통풍을 되돌린다 ([climate]가 있으면)
 *
 * 도착([RestEvent.Arrived])은 [events]로 내보낸다. "○○에 도착했어요. 15분 쉬면…" 안내는 조립 지점에서 3번 음성으로 잇는다.
 */
class RestSessionMonitor(
    private val certifier: RestCertifier,
    private val ledger: RewardLedger,
    private val clock: Clock,
    private val climate: ClimatePort? = null,
    private val config: EngineConfig = EngineConfig(),
) : RestCertificationSource {

    private val mutableCertifications = MutableSharedFlow<RestCertification>(extraBufferCapacity = 8)
    override val certifications: SharedFlow<RestCertification> = mutableCertifications.asSharedFlow()

    private val mutableEvents = MutableSharedFlow<RestEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RestEvent> = mutableEvents.asSharedFlow()

    private val mutableUi = MutableStateFlow(RestUiState())
    val ui: StateFlow<RestUiState> = mutableUi.asStateFlow()

    private val lock = Mutex()

    /** 지금 쉼터 반경 안에 서 있는가. 파워냅의 안전 안내 여부에 쓴다. */
    fun isAtRestArea(location: GeoPoint?): Boolean = location?.let { certifier.nearest(it) } != null

    suspend fun observe(observation: RestObservation, source: Source): List<RestEvent> = lock.withLock {
        val events = certifier.observe(observation)
        events.forEach { handle(it, source) }
        events
    }

    private suspend fun handle(event: RestEvent, source: Source) {
        when (event) {
            is RestEvent.Arrived -> mutableUi.value = mutableUi.value.copy(
                areaName = event.area.name, startedAtMs = event.startedAtMs, certified = false,
            )
            is RestEvent.Certified -> {
                mutableCertifications.emit(RestCertification(event.sessionId, event.certifiedAtMs, source))
                val record = ledger.record(event)
                mutableUi.value = mutableUi.value.copy(certified = true, lastRecord = record)
                climate?.start(ClimateRequest(RequestId("vent-restore-${event.sessionId}"), ClimateAction.RESTORE_DRIVER_VENTILATION, priorConsent = true))
            }
            is RestEvent.Left -> mutableUi.value = mutableUi.value.copy(areaName = null, startedAtMs = null, certified = false)
        }
        mutableEvents.emit(event)
    }

    /**
     * Hub의 최신 값을 [EngineConfig.naviStateIntervalMs](1초)마다 읽어 관측으로 넣는다.
     * 시각은 Clock의 운행 논리 시각이라 데모 배속·시동 꺼짐 재현이 그대로 적용된다.
     *
     * @param continuousDriveSec 1번 TripStateStore.state.value.continuousDriveSec
     * @param stage 1번 InterventionManager.state.value.stage
     */
    fun attach(
        scope: CoroutineScope,
        hub: SignalHubSession,
        continuousDriveSec: () -> Long?,
        stage: () -> InterventionStage?,
    ): Job = scope.launch {
        while (isActive) {
            val frame = hub.latestFrame.value
            val navi = hub.latestNavi.value
            val lat = navi?.lat
            val lon = navi?.lon
            val observation = RestObservation(
                tMs = clock.scenarioTimeMs(),
                location = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
                gear = frame?.gear,
                power = hub.latestPower.value?.state,
                continuousDriveSec = continuousDriveSec(),
                stage = stage(),
            )
            val source = frame?.quality?.get(SignalField.GEAR)?.source ?: Source.REAL
            observe(observation, source)
            delay(config.naviStateIntervalMs)
        }
    }
}
