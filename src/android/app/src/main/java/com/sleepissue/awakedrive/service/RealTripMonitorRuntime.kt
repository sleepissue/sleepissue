package com.sleepissue.awakedrive.service

import com.sleepissue.awakedrive.engine.model.SignalFrame
import com.sleepissue.awakedrive.signal.PlaygroundTripSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Android 생명주기와 분리한 단일 수집 수명. scope는 ROS 노드, samples는 토픽에 해당한다. */
interface RealTripMonitorInput {
    val samples: Flow<SignalFrame>
    suspend fun start(scope: CoroutineScope)
    fun close()
}

class RealTripMonitorRuntime(
    val session: PlaygroundTripSession,
    private val ownerScope: CoroutineScope,
    private val newInput: () -> RealTripMonitorInput,
    private val collectorDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val commands = Mutex()
    private var input: RealTripMonitorInput? = null
    private var inputJob: Job? = null
    private var token = 0L
    private val mutableStatus = MutableStateFlow("Room 복원 중")
    val status = mutableStatus.asStateFlow()
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()
    var starts = 0
        private set
    var activeTimers = 0
        private set
    val running get() = input != null

    suspend fun initialize() = commands.withLock {
        session.initialize()
        mutableStatus.value = "준비 · 새 입력 전 UNKNOWN · 감시 시작을 누르세요"
    }

    suspend fun start(): Boolean = commands.withLock {
        if (running) return@withLock true
        check(session.initialized)
        mutableFailure.value = null
        val next = try { newInput() } catch (e: Exception) {
            mutableStatus.value = "시작 실패 · ${e.message}"
            return@withLock false
        }
        input = next
        val job = SupervisorJob(ownerScope.coroutineContext[Job]).also { inputJob = it }
        val scope = CoroutineScope(ownerScope.coroutineContext + job)
        token = session.beginInput()
        val currentToken = token
        try {
            scope.launch(collectorDispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    next.samples.collect { frame ->
                        if (session.accept(currentToken, frame)) {
                            val snapshot = session.capture()
                            scope.launch { session.saveNow(snapshot) }
                        }
                    }
                    // SDK 연결 자체는 유지되어도 입력 Flow가 끝나면 과거 상태를 연장하지 않는다.
                    ownerScope.launch { inputFailed(next, "입력 종료 · UNKNOWN") }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { ownerScope.launch { inputFailed(next, "입력 오류 ${e.javaClass.simpleName} · UNKNOWN") } }
            }
            next.start(scope)
            activeTimers = 1
            scope.launch {
                while (isActive) { delay(1_000); session.autoSave() }
            }
            starts++
            mutableStatus.value = "감시 중 · 화면과 독립 · SDK → Hub → 운행 → Room"
            true
        } catch (e: Exception) {
            job.cancelAndJoin(); runCatching { next.close() }; session.endInput(currentToken)
            input = null; inputJob = null; activeTimers = 0
            mutableStatus.value = "시작 실패 ${e.javaClass.simpleName} · UNKNOWN"
            false
        }
    }

    private suspend fun inputFailed(expected: RealTripMonitorInput, reason: String) = commands.withLock {
        if (input !== expected) return@withLock
        stopLocked(reason)
        mutableFailure.value = reason
    }

    suspend fun stop(reason: String = "명시적 중지"): Boolean = commands.withLock { stopLocked(reason) }

    private suspend fun stopLocked(reason: String): Boolean {
        val old = input ?: return false
        // collector/타이머와 대기 중 즉시 저장을 모두 끝낸 뒤 마지막 확정 상태를 저장한다.
        inputJob?.cancelAndJoin()
        val closeFailure = runCatching { old.close() }.exceptionOrNull()
        val snapshot = session.finishInputAndCapture(token)
        input = null; inputJob = null; activeTimers = 0
        val saved = withContext(NonCancellable) { session.saveNow(snapshot) }
        mutableStatus.value = "$reason · UNKNOWN · 마지막 저장 ${if (saved) "성공" else "없음/실패"}" +
            (closeFailure?.let { " · 해제 오류 ${it.javaClass.simpleName}" } ?: "")
        return saved
    }
}
