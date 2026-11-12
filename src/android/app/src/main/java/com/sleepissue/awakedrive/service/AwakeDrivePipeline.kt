package com.sleepissue.awakedrive.service

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.forecast.ForecastRecalcTrigger
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.engine.rest.HeadingEstimator
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.fatigue.FatigueAssessmentRunner
import com.sleepissue.awakedrive.session.ForecastCoordinator
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.signal.InterventionManager
import com.sleepissue.awakedrive.signal.InterventionRouter
import com.sleepissue.awakedrive.signal.SignalHubSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 신호 → 판단 → 개입 → 실행을 한 줄로 잇는 조립 지점 (CLAUDE.md 4장 한 방향 흐름).
 * ROS로 치면 launch 파일이다. 노드(엔진·매니저·라우터·4번 휴식)는 밖에서 만들고, 여기서는 토픽을 잇고 시작 순서만 지킨다.
 *
 * 순서: router.start → [wireRest] → manager.start → 나머지 입력 루프 (결과를 먼저 듣고 요청을 받는다, RestModule 문서).
 *
 * 매니저에 넣는 입력:
 * - TripChanged: 1번 운행 상태가 바뀔 때마다. 운행 id가 바뀌면 2번 기준선·예보도 새 운행으로 (REQ_TRP_008)
 * - NavigationChanged: 내비 1 Hz. 진행 방향이 없으면 위치 이력으로 채운다 → 4번 쉼터 고르기 (REQ_RST_002)
 * - VentilationConsentChanged: 첫 실행 설정의 통풍 동의
 * - Tick: 1초마다. 매니저는 스스로 타이머를 돌리지 않는다(경유지 10초 자동 추가 등)
 * - 2번 판단: [FatigueAssessmentRunner.run]
 */
class AwakeDrivePipeline(
    private val hub: SignalHubSession,
    private val trip: StateFlow<TripState>,
    private val manager: InterventionManager,
    private val runner: FatigueAssessmentRunner,
    private val router: InterventionRouter,
    private val forecast: ForecastCoordinator,
    private val ventilationConsent: StateFlow<Boolean>,
    private val clock: Clock,
    private val config: EngineConfig = EngineConfig(),
    // 서비스는 Dispatchers.Default를 넘겨 10 Hz 엔진 계산을 메인 스레드 밖에서 돌린다. 시험은 기본값(같은 스케줄러).
    private val engineContext: CoroutineContext = EmptyCoroutineContext,
) {
    private val sequence = AtomicLong()
    private val heading = HeadingEstimator(config)

    fun newInputId() = RequestId("pipeline-${sequence.incrementAndGet()}")

    /** 반환된 Job을 취소하면 모든 루프가 멈춘다. 매니저 정지는 호출자가 한다. */
    suspend fun start(scope: CoroutineScope, wireRest: () -> Unit = {}): Job {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val owner = CoroutineScope(scope.coroutineContext + job)
        router.start(owner)
        wireRest()
        manager.start(newInputId())

        owner.launch { ventilationConsent.collect { submit(InterventionInput.VentilationConsentChanged(it)) } }
        owner.launch {
            var tripId: String? = null
            trip.collect { state ->
                if (state.tripId != null && state.tripId != tripId) {
                    tripId = state.tripId
                    heading.reset()
                    forecast.onNewTrip()
                    runner.onNewTrip(clock.scenarioTimeMs(), manager)
                    submit(InterventionInput.TripChanged(state))
                    forecast.recalculate(ForecastRecalcTrigger.TRIP_START)
                } else submit(InterventionInput.TripChanged(state))
            }
        }
        owner.launch {
            var hadDestination = false
            hub.naviSamples.collect { navi ->
                heading.observe(navi)
                submit(InterventionInput.NavigationChanged(heading.fill(navi)))
                val hasDestination = navi.remainTimeSec != null
                if (hasDestination && !hadDestination) forecast.recalculate(ForecastRecalcTrigger.DESTINATION_SET)
                hadDestination = hasDestination
            }
        }
        owner.launch {
            forecast.watchDepartureFatigue(runner.snapshots.map { it.baseline.departureFatigue }.distinctUntilChanged())
        }
        owner.launch {
            // 2번의 계산 결과를 그대로 전달한다. 지연 시간을 다시 더하거나 점수/단계를 주입하지 않는다.
            runner.snapshots.map { it.baseline.firstCheckDueMs }.distinctUntilChanged().collect { due ->
                trip.value.tripId?.let { submit(InterventionInput.FirstCheckScheduleChanged(it, due)) }
            }
        }
        owner.launch(engineContext) { runner.run(hub.samples, { hub.latestNavi.value }, { trip.value }, manager) }
        owner.launch {
            while (isActive) {
                delay(config.naviStateIntervalMs)
                submit(InterventionInput.Tick)
            }
        }
        return job
    }

    private suspend fun submit(input: InterventionInput) = manager.submit(newInputId(), input)
}
