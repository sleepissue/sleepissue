package com.sleepissue.awakedrive.session

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.ResponseIntent
import com.sleepissue.awakedrive.engine.rest.GeoMath
import com.sleepissue.awakedrive.engine.rest.NapEvent
import com.sleepissue.awakedrive.engine.rest.NapStart
import com.sleepissue.awakedrive.engine.rest.NapState
import com.sleepissue.awakedrive.engine.rest.RestEvent
import com.sleepissue.awakedrive.signal.ClimateAction
import com.sleepissue.awakedrive.signal.ClimatePort
import com.sleepissue.awakedrive.signal.ClimateRequest
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.signal.InterventionManager
import com.sleepissue.awakedrive.signal.InterventionRouter
import com.sleepissue.awakedrive.signal.RestAreaCandidate
import com.sleepissue.awakedrive.signal.RestCertification
import com.sleepissue.awakedrive.signal.SignalHubSession
import com.sleepissue.awakedrive.voice.VoiceScripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [4번] 휴식·파워냅·통풍을 개입 매니저와 음성에 잇는다. 매니저 요청([InterventionRouter])으로 다루지 않는 4번 흐름만 맡는다.
 *
 * 1. 휴식 인증 → `submit(RestCertified)` → 1번이 회복 확인(RECOVERY)을 요청한다.
 *    단, **파워냅 중이면 끝날 때까지 미룬다.** 쉼터에서 자는 동안 15분이 차도 자는 사람에게 묻지 않기 위해서다(기획서 7-1 "끝나면 회복 확인").
 * 2. 쉼터 도착 → "○○에 도착했어요. 15분 쉬면…" 안내 (기획서 6장 음성 대본)
 * 3. 대답 뜻: NAP("잠깐 잘게") → 파워냅 시작, VENT_OFF("통풍 꺼 줘") → 통풍 되돌리기 (기획서 6장)
 * 4. 쉼터 후보까지 직선 거리를 1초마다 `submit(RestAreaDistance)` → 1번의 "다음 쉼터 2 km 전 재안내"
 * 5. 휴식 인증 관측 루프([RestSessionMonitor.attach])와 파워냅 타이머 루프([NapSession.attach])를 돌린다
 *
 * @param speak 3번 VoiceAgent로 연결 (150~200 ms 무음·덕킹 포함)
 * @param continuousDriveSec 1번 TripStateStore.state.value.continuousDriveSec
 */
class RestWiring(
    private val manager: InterventionManager,
    private val router: InterventionRouter,
    private val restMonitor: RestSessionMonitor,
    private val nap: NapSession,
    private val climate: ClimatePort?,
    private val hub: SignalHubSession,
    private val speak: suspend (String) -> Unit,
    private val continuousDriveSec: () -> Long?,
    private val newInputId: () -> RequestId,
    private val config: EngineConfig = EngineConfig(),
) {
    private val lock = Mutex()
    private val heldCertifications = mutableListOf<RestCertification>()
    private var candidate: RestAreaCandidate? = null
    private var lastSentDistance: Pair<String, Float?>? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        listen { restMonitor.certifications.collect { onCertified(it) } }
        listen { restMonitor.events.collect { if (it is RestEvent.Arrived) speak(VoiceScripts.restArrival(it.area.name)) } }
        listen { nap.events.collect { if (it is NapEvent.Ended) releaseHeld() } }
        listen {
            router.checkResults.collect { result ->
                when (result.intent) {
                    ResponseIntent.NAP -> requestNap()
                    ResponseIntent.VENT_OFF -> restoreVentilation("vent-off-${result.requestId.value}")
                    else -> Unit
                }
            }
        }
        listen {
            router.foundAreas.collect { result ->
                if (result.execution.status == ExecutionStatus.SUCCEEDED) lock.withLock { candidate = result.candidate }
            }
        }
        launch { trackDistance() }
        restMonitor.attach(this, hub, continuousDriveSec) { manager.state.value.stage }
        nap.attach(this, hub)
    }

    /** 화면의 [잠깐 잘게] 버튼도 이것을 부른다. 쉼터 밖이면 NapSession이 안전 안내를 먼저 한다. */
    suspend fun requestNap(): NapStart = nap.request(hub.latestFrame.value?.gear, restMonitor.isAtRestArea(currentLocation()))

    private suspend fun onCertified(certification: RestCertification) {
        val napping = nap.state.value !is NapState.Idle
        if (napping) {
            lock.withLock { heldCertifications += certification }
        } else {
            manager.submit(newInputId(), InterventionInput.RestCertified(certification))
        }
    }

    private suspend fun releaseHeld() {
        val held = lock.withLock { heldCertifications.toList().also { heldCertifications.clear() } }
        held.forEach { manager.submit(newInputId(), InterventionInput.RestCertified(it)) }
    }

    private suspend fun restoreVentilation(id: String) {
        climate?.start(ClimateRequest(RequestId(id), ClimateAction.RESTORE_DRIVER_VENTILATION, priorConsent = true))
    }

    /**
     * 3단계에서 고른 쉼터까지의 거리. 내비 도로 거리가 아니라 직선 거리다 [가정] — 실제보다 짧게 나와 재안내가 조금 일찍 온다.
     * 0단계로 내려가면 1번이 후보를 버리므로 여기서도 버린다.
     */
    private suspend fun trackDistance() {
        while (currentCoroutineContext().isActive) {
            if (manager.state.value.stage == InterventionStage.NORMAL) lock.withLock { candidate = null; lastSentDistance = null }
            val area = lock.withLock { candidate }
            if (area != null) {
                val distance = currentLocation()?.let { GeoMath.distanceM(it, area.location).toFloat() }
                val key = area.id to distance
                if (lock.withLock { (lastSentDistance != key).also { lastSentDistance = key } }) {
                    manager.submit(newInputId(), InterventionInput.RestAreaDistance(area.id, distance))
                }
            }
            delay(config.naviStateIntervalMs)
        }
    }

    private fun currentLocation(): GeoPoint? {
        val navi = hub.latestNavi.value ?: return null
        val lat = navi.lat ?: return null
        val lon = navi.lon ?: return null
        return GeoPoint(lat, lon)
    }

    private fun CoroutineScope.listen(block: suspend CoroutineScope.() -> Unit) {
        launch(start = CoroutineStart.UNDISPATCHED, block = block)
    }
}
