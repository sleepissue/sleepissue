package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.NaviState
import com.sleepissue.awakedrive.engine.model.PowerEvent
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.signal.NaviSignalSource
import com.sleepissue.awakedrive.signal.PowerSignalSource
import com.sleepissue.awakedrive.signal.VehicleSignalInput
import com.sleepissue.awakedrive.signal.VehicleSignalSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

enum class DemoPhase { NOT_LOADED, READY, PLAYING, PAUSED, STOPPED, COMPLETED, FAILED }

/** 판정 완료·장치 실패·실행 취소를 무응답과 섞지 않고 질문 잠금을 해제한다. */
enum class DemoQuestionOutcome { FINISHED, FAILED, CANCELLED }

data class DemoPlaybackState(
    val connection: ExecutionStatus = ExecutionStatus.NOT_CONNECTED,
    val phase: DemoPhase = DemoPhase.NOT_LOADED,
    val scenarioId: String? = null,
    val speedMultiplier: Int? = null,
    val questionRequestId: RequestId? = null,
    val sessionId: Long = 0,
    val scenarioTimeMs: Long = 0,
    val realElapsedMs: Long = 0,
    val segmentMultiplier: Int? = null,
    val questionOutcome: DemoQuestionOutcome? = null,
    val error: DemoLoadError? = null,
    val failureMessage: String? = null,
    // 원본 세 채널의 Flow 인계 완료 수다. Hub 수신 수·주기 발행 수와 구별한다.
    val deliveredSamples: Long = 0,
    val lastOriginalSampleMs: Long? = null,
    val lastDeliveryElapsedMs: Long? = null,
    val pumpTargetMs: Long? = null,
    val pumpFinishedScenarioMs: Long? = null,
)

/** scenarioId는 저장소가 해석하는 식별자다. 질문 이벤트는 현재 재생 sessionId를 반드시 전달한다. */
sealed interface DemoCommand {
    val requestId: RequestId
    data class Load(override val requestId: RequestId, val scenarioId: String) : DemoCommand
    data class Start(override val requestId: RequestId) : DemoCommand
    data class Pause(override val requestId: RequestId) : DemoCommand
    data class Resume(override val requestId: RequestId) : DemoCommand
    data class Stop(override val requestId: RequestId) : DemoCommand
    data class Reset(override val requestId: RequestId) : DemoCommand
    data class QuestionStarted(override val requestId: RequestId, val sessionId: Long? = null) : DemoCommand
    data class QuestionFinished(override val requestId: RequestId, val sessionId: Long? = null,
        val outcome: DemoQuestionOutcome = DemoQuestionOutcome.FINISHED) : DemoCommand
}

/** 재생 출력도 실제 입력과 같은 Hub 입력 계약을 사용한다. 엔진을 직접 호출하지 않는다. */
interface DemoPlayer : VehicleSignalSource, NaviSignalSource, PowerSignalSource {
    val playbackState: StateFlow<DemoPlaybackState>
    suspend fun execute(command: DemoCommand): ExecutionResult
}

/** A의 미연결 구현이다. 데모를 재생한 것처럼 상태·샘플·성공을 생성하지 않는다. */
class UnconnectedDemoPlayer : DemoPlayer {
    override val playbackState: StateFlow<DemoPlaybackState> =
        MutableStateFlow(DemoPlaybackState()).asStateFlow()
    override val samples: Flow<VehicleSignalInput> = emptyFlow()
    override val states: Flow<NaviState> = emptyFlow()
    override val events: Flow<PowerEvent> = emptyFlow()

    override suspend fun execute(command: DemoCommand) = ExecutionResult(
        requestId = command.requestId,
        status = ExecutionStatus.NOT_CONNECTED,
        failure = ExecutionFailure.NOT_CONNECTED,
    )
}
