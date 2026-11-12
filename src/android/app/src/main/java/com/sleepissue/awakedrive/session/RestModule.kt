package com.sleepissue.awakedrive.session

import android.content.Context
import com.sleepissue.awakedrive.climate.DriverVentilationController
import com.sleepissue.awakedrive.climate.PrefsVentilationMemory
import com.sleepissue.awakedrive.data.RestRecordDatabase
import com.sleepissue.awakedrive.data.RoomRestRecordStore
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.rest.NapTimer
import com.sleepissue.awakedrive.engine.rest.RestAreaSelector
import com.sleepissue.awakedrive.engine.rest.RestCertifier
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.navi.RestAreaRepository
import com.sleepissue.awakedrive.navi.RestAreaRestPort
import com.sleepissue.awakedrive.pleos.PleosNaviBridge
import com.sleepissue.awakedrive.pleos.PleosSeatVentilationDevice
import com.sleepissue.awakedrive.signal.InterventionManager
import com.sleepissue.awakedrive.signal.InterventionRouter
import com.sleepissue.awakedrive.signal.SignalHubSession
import com.sleepissue.awakedrive.voice.VoiceScripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * [4번] 실제 앱에서 4번 부품을 한 번에 만든다. 감시 서비스(1번)가 이렇게 쓴다:
 *
 * ```kotlin
 * val rest = RestModule(context, scope, clock) { text -> voiceAgent.speakAndWait(text) }
 * // 예보(FCS_001·004): 출발 피로 false→true, 휴식 인증 뒤에 다시 계산해 1번에 Forecast로 넘긴다
 * val forecast = ForecastCoordinator(ForecastEngine(clock, config), clock, manager,
 *     { ForecastCoordinator.inputFrom(tripStore.state.value, hub.latestNavi.value) }, { RequestId("fc-${++k}") })
 * scope.launch { forecast.watchDepartureFatigue(runner.snapshots.map { it.baseline.departureFatigue }) }
 * // 휴식 인증 + 회복 정상(RES_011): 2번 엔진 평활값 0 + 예보 재계산
 * val reset = RestRecoveryReset({ runner.onRestCertified() }, forecast)
 * val router = InterventionRouter(manager, check = checkPort, speech = speechPort,
 *     rest = rest.restPort, climate = rest.climate, recoveryReset = { reset(it) }, newInputId = { RequestId("in-${++n}") })
 * router.start(scope)
 * val wiring = rest.wire(manager, router, hub, { tripStore.state.value.continuousDriveSec }, { RequestId("rest-${++m}") })
 * // 화면: rest.restMonitor.ui(휴식 화면), rest.nap.state(파워냅 화면), rest.ledger.stamps(스탬프),
 * //       rest.climate.activeByApp(통풍 켜짐 표시), wiring.requestNap()([잠깐 잘게] 버튼)
 * // 종료: rest.close()
 * ```
 * 순서가 중요하다: router.start → rest.wire → manager.start (결과를 먼저 듣고 요청을 받는다).
 *
 * @param scope 서비스 수명 범위. 서비스가 끝나면 취소한다
 * @param speak 3번 VoiceAgent. 말이 끝날 때까지 기다린다
 */
class RestModule(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val config: EngineConfig = EngineConfig(),
    private val speak: suspend (String) -> Unit,
    private val demoMode: Boolean = false,
) : AutoCloseable {
    val repository = RestAreaRepository(context)
    private val naviBridge: com.sleepissue.awakedrive.navi.NaviBridge = if (demoMode) object : com.sleepissue.awakedrive.navi.NaviBridge {
        override suspend fun addRestWaypoint(area: com.sleepissue.awakedrive.engine.rest.RestArea) =
            com.sleepissue.awakedrive.navi.WaypointOutcome.Failed(com.sleepissue.awakedrive.engine.model.ExecutionFailure.UNAVAILABLE, "데모에서는 실제 내비 변경을 실행하지 않습니다.")
    } else PleosNaviBridge(context, config)
    private val ventilationDevice: com.sleepissue.awakedrive.climate.SeatVentilationDevice = if (demoMode) object : com.sleepissue.awakedrive.climate.SeatVentilationDevice {
        override suspend fun capability(): com.sleepissue.awakedrive.engine.climate.VentilationCapability? = null
        override suspend fun read(): Int? = null
        override suspend fun write(level: Int) = Unit
    } else PleosSeatVentilationDevice(context)
    private val database = RestRecordDatabase.open(context)

    val restPort = RestAreaRestPort(repository, RestAreaSelector(config), naviBridge, scope)
    val climate = DriverVentilationController(
        ventilationDevice, PrefsVentilationMemory(context), { speak(VoiceScripts.VENTILATION_MANUAL) }, scope, config,
    )
    val ledger = RewardLedger(RoomRestRecordStore(database.dao()), clock)
    // 쉼터 표는 처음 접근할 때 한 번 파싱한다(468줄, 수 ms).
    val restMonitor = RestSessionMonitor(RestCertifier(repository.areas, config), ledger, clock, climate, config, PrefsRestStateStore(context))
    val nap = NapSession(NapTimer(config), ToneNapAlarm(scope), speak, clock, config)

    private var wiringJob: Job? = null

    /** 매니저·라우터·Hub에 잇고 관측 루프를 시작한다. 한 번만 부른다. */
    fun wire(
        manager: InterventionManager,
        router: InterventionRouter,
        hub: SignalHubSession,
        continuousDriveSec: () -> Long?,
        newInputId: () -> RequestId,
    ): RestWiring {
        check(wiringJob == null) { "RestModule.wire는 한 번만 부릅니다." }
        val wiring = RestWiring(manager, router, restMonitor, nap, climate, hub, speak, continuousDriveSec, newInputId, config)
        wiringJob = wiring.start(scope)
        return wiring
    }

    override fun close() {
        wiringJob?.cancel()
        wiringJob = null
        runCatching { (naviBridge as? AutoCloseable)?.close() }
        runCatching { (ventilationDevice as? AutoCloseable)?.close() }
        runCatching { database.close() }
    }
}
