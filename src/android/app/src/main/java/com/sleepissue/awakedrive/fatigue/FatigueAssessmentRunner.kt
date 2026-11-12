package com.sleepissue.awakedrive.fatigue

import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.model.CheckResult
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.engine.model.TripState
import com.sleepissue.awakedrive.signal.DrivingAssessment
import com.sleepissue.awakedrive.signal.DrivingAssessmentSource
import com.sleepissue.awakedrive.signal.InterventionInput
import com.sleepissue.awakedrive.signal.InterventionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 2번 엔진 출력 연결기의 실행 부분: Hub 프레임 흐름을 받아 [FatigueBridge]를 돌리고, 결과를 1번 InterventionManager에 넣는다.
 * 1번 [DrivingAssessmentSource] 계약도 구현한다 (30초 갱신 → [assessments]).
 *
 * 앱에서 붙이는 법 (1번 Hub·TripStateStore·InterventionManager가 만들어진 뒤):
 * ```
 * val runner = FatigueAssessmentRunner(FatigueBridge(engineConfig))
 * scope.launch { runner.run(hub.samples, { hub.latestNavi.value }, { tripStore.state.value }, interventionManager) }
 * // 화면(5번)은 runner.snapshots, 예보(4번)는 snapshots의 baseline.departureFatigue를 본다
 * // 사람에게 물어본 확인 결과도 넣는다(딴짓 기록). 4번 InterventionRouter면:
 * scope.launch { router.checkResults.collect { runner.onCheckResult(it) } }
 * ```
 *
 * - 프레임은 들어온 순서대로 하나씩 처리한다(엔진은 한 스레드 전용). 출발 전 컨디션 같은 다른 입력도 같은 잠금을 거친다.
 * - InterventionManager.submit은 요청 ID가 겹치면 거절하므로 "fatigue-종류-시각-순번"으로 겹치지 않게 만든다.
 * - ROS로 치면 신호 토픽 구독 → 엔진 노드 → 단계 전환 노드에 서비스 호출을 하는 브리지 노드다.
 */
class FatigueAssessmentRunner(private val bridge: FatigueBridge) : DrivingAssessmentSource {

    private val lock = Mutex()
    private var sequence = 0L

    private val assessmentFlow = MutableSharedFlow<DrivingAssessment>(replay = 1, extraBufferCapacity = 16)

    /** 30초 갱신마다 1번 형식으로 바꾼 판단 (1번 DrivingAssessmentSource 계약) */
    override val assessments: SharedFlow<DrivingAssessment> = assessmentFlow.asSharedFlow()

    private val snapshotFlow = MutableStateFlow(bridge.snapshot)

    /** 프레임마다 갱신되는 2번 엔진 상태 (화면·예보용) */
    val snapshots: StateFlow<FatigueSnapshot> = snapshotFlow.asStateFlow()

    /**
     * 데모 재생이면 시나리오 시작 시각(startEpochMs), 실제 주행이면 0. 취약 시간대 계산에만 쓴다 ([FatigueBridge.epochOffsetMs]).
     * 데모를 시작할 때(`runner.run(...)` 전에) 넣는다: `runner.epochOffsetMs = scenario.startEpochMs`
     */
    var epochOffsetMs: Long
        get() = bridge.epochOffsetMs
        set(value) { bridge.epochOffsetMs = value }

    /**
     * [frames]가 끝나거나 이 코루틴이 취소될 때까지 돈다.
     * @param navi 지금 내비 상태 (회전 지점 거리). 없으면 null → 게이트가 '5분 고속 유지' 규칙을 쓴다
     * @param trip 지금 운행 상태 (연속 운전·24시간 누적 운전)
     */
    suspend fun run(
        frames: Flow<SignalFrame>,
        navi: () -> NaviState?,
        trip: () -> TripState?,
        manager: InterventionManager,
    ) {
        frames.onEach { frame ->
            val inputs = lock.withLock {
                val out = bridge.onFrame(frame, navi(), trip())
                snapshotFlow.value = bridge.snapshot
                out
            }
            deliver(frame.tMs, inputs, manager)
        }.collect()
    }

    @Volatile var ageBand: AgeBand? = bridge.ageBand
        private set

    /** 프레임과 같은 잠금으로 설정을 반영한다. */
    suspend fun updateAgeBand(ageBand: AgeBand?) {
        lock.withLock { bridge.ageBand = ageBand; this.ageBand = ageBand }
    }

    /** 출발 전 컨디션 (REQ_BSL_007). '졸려요'면 민감 모드가 켜져 1번에 알린다 */
    suspend fun reportCondition(sleepy: Boolean, tMs: Long, manager: InterventionManager) {
        val inputs = lock.withLock {
            val out = bridge.reportCondition(sleepy, tMs)
            snapshotFlow.value = bridge.snapshot
            out
        }
        deliver(tMs, inputs, manager)
    }

    /**
     * 사람에게 물어본 주의 확인 결과를 엔진에 알린다 (REQ_PAT_004 딴짓 기록). 단계는 1번이 CheckCompleted로 따로 판단한다.
     * 4번 InterventionRouter: `router.checkResults.collect { runner.onCheckResult(it) }`
     * 3번 InterventionRequestRouter: 결과를 매니저에 CheckCompleted로 넣는 곳에서 같이 부른다
     */
    suspend fun onCheckResult(result: CheckResult) {
        lock.withLock {
            bridge.onCheckResult(result)
            snapshotFlow.value = bridge.snapshot
        }
    }

    /** 휴식 인증 + 회복 확인 뒤 (REQ_RES_011) */
    suspend fun onRestCertified() {
        lock.withLock { bridge.onRestCertified() }
    }

    /** 새 운행 (REQ_TRP_008) */
    suspend fun onNewTrip(tMs: Long, manager: InterventionManager) {
        val inputs = lock.withLock {
            val out = bridge.onNewTrip()
            snapshotFlow.value = bridge.snapshot
            out
        }
        deliver(tMs, inputs, manager)
    }

    private suspend fun deliver(tMs: Long, inputs: List<InterventionInput>, manager: InterventionManager) {
        for (input in inputs) {
            if (input is InterventionInput.Assessment) assessmentFlow.emit(input.value)
            manager.submit(nextRequestId(input, tMs), input)
        }
    }

    private fun nextRequestId(input: InterventionInput, tMs: Long): RequestId {
        val kind = when (input) {
            is InterventionInput.Assessment -> "assessment"
            is InterventionInput.Safety -> "safety-${input.value.type.name.lowercase()}"
            is InterventionInput.SensitiveModeChanged -> "sensitive"
            is InterventionInput.VulnerableTimeChanged -> "vulnerable"
            is InterventionInput.CheckDue -> "check-due"
            else -> "input"
        }
        return RequestId("fatigue-$kind-$tMs-${++sequence}")
    }
}
