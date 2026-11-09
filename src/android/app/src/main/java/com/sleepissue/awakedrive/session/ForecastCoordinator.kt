package com.sleepissue.awakedrive.session

import com.sleepissue.awakedrive.engine.model.EvaluationStatus
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.engine.forecast.ForecastEngine
import com.sleepissue.awakedrive.engine.forecast.ForecastInput
import com.sleepissue.awakedrive.engine.forecast.ForecastRecalcTrigger
import com.sleepissue.awakedrive.engine.forecast.ForecastResult
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.ForecastAssessment
import com.sleepissue.awakedrive.signal.ForecastSource
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.signal.InterventionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [4번] 예보를 다시 계산해 1번 단계 전환에 넘긴다 (REQ_FCS_001·004, REQ_RES_011 "예보 재계산 1회").
 * 1번 [ForecastSource] 계약의 구현이다.
 *
 * 계산은 순수 [ForecastEngine]이 하고, 이 클래스는 "언제 다시 계산할지"와 "결과를 어디로 보낼지"만 맡는다.
 * ROS로 치면 트리거 토픽을 구독해 예보 서비스를 부르고, 결과를 `forecast` 토픽으로 발행하는 노드다.
 *
 * - 출발 피로(2번 `baseline.departureFatigue`)가 false → true로 바뀌는 순간 → [ForecastRecalcTrigger.START_FATIGUE_DETECTED].
 *   이후 이 운행에서는 계속 절반 규칙을 적용한다(엔진이 운행 중 한 번만 정한다, REQ_BSL_005). [onNewTrip]에서 지운다.
 * - 휴식 인증 + 회복 정상 → [ForecastRecalcTrigger.REST_VERIFIED]. 연속 운전 시간은 0으로 계산한다(REQ_RES_011 표 "연속 0").
 * - 목적지 설정·운행 시작·컨디션 입력·운전 재개는 감시 서비스가 [recalculate]를 직접 부른다.
 *
 * @param input 지금 예보 입력(연속·24시간 누적·남은 시간·개인 졸음 시점·컨디션·연령대). [inputFrom]으로 만들 수 있다
 */
class ForecastCoordinator(
    private val engine: ForecastEngine,
    private val clock: Clock,
    private val manager: InterventionManager,
    private val input: () -> ForecastInput,
    private val newInputId: () -> RequestId,
) : ForecastSource {
    private val lock = Mutex()
    private var departureFatigue = false

    private val mutableForecasts = MutableSharedFlow<ForecastAssessment>(replay = 1, extraBufferCapacity = 16)
    override val forecasts: SharedFlow<ForecastAssessment> = mutableForecasts.asSharedFlow()

    private val mutableLatest = MutableStateFlow<ForecastResult?>(null)
    /** 마지막 예보 결과. 출발 화면(UI_001)·예보 TTS 문구(`ttsMessage`)에 쓴다. 말할지는 호출하는 쪽이 정한다 */
    val latest: StateFlow<ForecastResult?> = mutableLatest.asStateFlow()

    /** 예보를 계산해 1번에 `Forecast` 입력으로 넘기고 결과를 돌려준다. */
    suspend fun recalculate(trigger: ForecastRecalcTrigger): ForecastResult = lock.withLock {
        var base = input()
        if (departureFatigue) base = base.copy(startFatigueDetected = true)
        if (trigger == ForecastRecalcTrigger.REST_VERIFIED) base = base.copy(continuousDrivingMin = 0)
        val result = engine.calculate(base, trigger)
        val assessment = ForecastAssessment(clock.scenarioTimeMs(), EvaluationStatus.EVALUATED, result.expectedDrowsyAtMs)
        mutableLatest.value = result
        mutableForecasts.emit(assessment)
        manager.submit(newInputId(), InterventionInput.Forecast(assessment))
        result
    }

    /**
     * 2번 연결기의 출발 피로 값을 지켜본다. `runner.snapshots.map { it.baseline.departureFatigue }`를 넘긴다.
     * 끝나지 않는 Flow면 이 함수도 끝나지 않으므로 서비스 범위에서 `launch`로 부른다.
     */
    suspend fun watchDepartureFatigue(departureFatigue: Flow<Boolean>) {
        departureFatigue.distinctUntilChanged().collect { detected ->
            val rising = lock.withLock { (detected && !this.departureFatigue).also { if (it) this.departureFatigue = true } }
            if (rising) recalculate(ForecastRecalcTrigger.START_FATIGUE_DETECTED)
        }
    }

    /** 새 운행(REQ_TRP_008): 출발 피로는 운행마다 다시 정한다. */
    suspend fun onNewTrip() {
        lock.withLock { departureFatigue = false }
    }

    companion object {
        /**
         * 1번 운행 상태·내비 상태에서 예보 입력을 만든다.
         * 개인 졸음 시점 기록(FCS_002)은 2차 Room 저장 전까지 빈 목록 → 기본 120분.
         */
        fun inputFrom(
            trip: TripState?,
            navi: NaviState?,
            onsetHistoryMin: List<Int> = emptyList(),
            base: ForecastInput = ForecastInput(0, null, 0, emptyList()),
        ): ForecastInput = base.copy(
            continuousDrivingMin = ((trip?.continuousDriveSec ?: 0) / 60).toInt(),
            last24hCumulativeDrivingMin = ((trip?.last24hDriveSec ?: 0) / 60).toInt(),
            remainingTimeToDestinationMin = navi?.remainTimeSec?.let { (it / 60).toInt() },
            onsetHistoryMin = onsetHistoryMin,
        )
    }
}
