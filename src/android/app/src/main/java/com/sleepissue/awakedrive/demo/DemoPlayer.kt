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

enum class DemoPhase { NOT_LOADED, READY, PLAYING, PAUSED, STOPPED, FAILED }

data class DemoPlaybackState(
    val connection: ExecutionStatus = ExecutionStatus.NOT_CONNECTED,
    val phase: DemoPhase = DemoPhase.NOT_LOADED,
    val scenarioId: String? = null,
    val speedMultiplier: Int? = null,
    val questionRequestId: RequestId? = null,
)

/** 로드 경로·JSON 형식·배속 계산은 B의 구현 대상이다. scenarioId는 시나리오 식별자다. */
sealed interface DemoCommand {
    val requestId: RequestId
    data class Load(override val requestId: RequestId, val scenarioId: String) : DemoCommand
    data class Start(override val requestId: RequestId) : DemoCommand
    data class Pause(override val requestId: RequestId) : DemoCommand
    data class Resume(override val requestId: RequestId) : DemoCommand
    data class Stop(override val requestId: RequestId) : DemoCommand
    data class Reset(override val requestId: RequestId) : DemoCommand
    data class QuestionStarted(override val requestId: RequestId) : DemoCommand
    data class QuestionFinished(override val requestId: RequestId) : DemoCommand
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
