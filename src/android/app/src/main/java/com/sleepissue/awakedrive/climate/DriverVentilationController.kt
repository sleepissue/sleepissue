package com.sleepissue.awakedrive.climate

import com.sleepissue.awakedrive.engine.climate.VentilationPlan
import com.sleepissue.awakedrive.engine.climate.VentilationRules
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.ExecutionFailure
import com.sleepissue.awakedrive.engine.model.ExecutionResult
import com.sleepissue.awakedrive.engine.model.ExecutionStatus
import com.sleepissue.awakedrive.engine.model.RequestId
import com.sleepissue.awakedrive.signal.ClimateAction
import com.sleepissue.awakedrive.signal.ClimatePort
import com.sleepissue.awakedrive.signal.ClimateRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 화면·음성에 알릴 통풍 결과. 3번은 [ENABLED_BY_APP]일 때 VoiceScripts.VENTILATION_ON을 읽는다. */
enum class VentilationEvent { ENABLED_BY_APP, MANUAL_PROMPTED, ENABLED_BY_DRIVER, MANUAL_TIMED_OUT, RESTORED }

/**
 * [4번] ClimateController — 1번 [ClimatePort] 계약의 실제 구현 (기획서 6장 '통풍 규칙', SRS CLM).
 *
 * ENABLE_DRIVER_VENTILATION (2단계, 사전 동의한 경우만)
 *  1. 지원 범위·현재 값을 읽고 [VentilationRules.planEnable]로 할 일을 정한다
 *  2. 바꾸기 전 값을 [VentilationMemory]에 저장한다
 *  3. setHvacSeatVentilation → 다시 읽어 반영 확인 (기본 2초)
 *  4. 반영이 안 되면 [promptManual]("통풍을 켜 보세요") → 운전자가 켰는지 읽어 확인 (기본 30초)
 * RESTORE_DRIVER_VENTILATION (휴식 인증 뒤, 또는 "통풍 꺼 줘")
 *  - 저장한 값으로 되돌리고 다시 읽어 확인한다. 저장한 값이 없으면 할 일 없이 SUCCEEDED
 *
 * [start]는 바로 REQUESTED(또는 즉시 FAILED)를 돌려주고, STARTED·최종 결과는 [results]로 보낸다(RestAreaRestPort와 같은 방식).
 * 창문은 열지 않는다. 통풍은 깨우는 해결책이 아니라 쉼터까지 버티는 보조다.
 *
 * @param promptManual "통풍을 켜 보세요" 음성. 조립 지점에서 3번 VoiceAgent로 연결한다
 */
class DriverVentilationController(
    private val device: SeatVentilationDevice,
    private val memory: VentilationMemory,
    private val promptManual: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val config: EngineConfig = EngineConfig(),
) : ClimatePort {

    private val mutableResults = MutableSharedFlow<ExecutionResult>(extraBufferCapacity = 32)
    override val results: SharedFlow<ExecutionResult> = mutableResults.asSharedFlow()

    private val mutableEvents = MutableSharedFlow<VentilationEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<VentilationEvent> = mutableEvents.asSharedFlow()

    /** 2단계 화면의 '통풍 켜짐' 표시용. 앱 요청으로 켜진 상태면 true. */
    private val mutableActive = MutableStateFlow(memory.priorLevel != null)
    val activeByApp: StateFlow<Boolean> = mutableActive.asStateFlow()

    private val lock = Mutex()
    private val deviceLock = Mutex() // 켜기·되돌리기가 서로 끼어들지 않게
    private val running = mutableMapOf<RequestId, Job>()

    override suspend fun start(request: ClimateRequest): ExecutionResult = lock.withLock {
        val id = request.requestId
        if (id in running) return@withLock result(id, ExecutionStatus.FAILED, ExecutionFailure.ERROR)
        if (request.action == ClimateAction.ENABLE_DRIVER_VENTILATION && !request.priorConsent) {
            return@withLock result(id, ExecutionStatus.FAILED, ExecutionFailure.PERMISSION_DENIED)
        }
        launchFor(id) {
            mutableResults.emit(result(id, ExecutionStatus.STARTED))
            val final = deviceLock.withLock {
                when (request.action) {
                    ClimateAction.ENABLE_DRIVER_VENTILATION -> enable(id)
                    ClimateAction.RESTORE_DRIVER_VENTILATION -> restore(id)
                }
            }
            mutableResults.emit(final)
        }
        result(id, ExecutionStatus.REQUESTED)
    }

    override suspend fun cancel(requestId: RequestId): ExecutionResult {
        val job = lock.withLock { running.remove(requestId) }
            ?: return result(requestId, ExecutionStatus.FAILED, ExecutionFailure.UNAVAILABLE)
        job.cancel()
        return result(requestId, ExecutionStatus.CANCELLED).also { mutableResults.emit(it) }
    }

    private suspend fun enable(id: RequestId): ExecutionResult {
        val capability = device.capability()
        val current = if (capability?.readable == true) device.read() else null
        return when (val plan = VentilationRules.planEnable(consent = true, capability, current, config)) {
            VentilationPlan.NoConsent -> result(id, ExecutionStatus.FAILED, ExecutionFailure.PERMISSION_DENIED)
            VentilationPlan.NotSupported -> result(id, ExecutionStatus.FAILED, ExecutionFailure.NOT_SUPPORTED)
            is VentilationPlan.AlreadyOn -> result(id, ExecutionStatus.SUCCEEDED) // 운전자가 이미 켬 → 되돌리지도 않음
            is VentilationPlan.Raise -> raise(id, plan)
        }
    }

    private suspend fun raise(id: RequestId, plan: VentilationPlan.Raise): ExecutionResult {
        // 이미 저장한 값이 있으면(두 번째 2단계 등) 처음 값을 지킨다.
        if (memory.priorLevel == null) memory.priorLevel = plan.priorLevel
        if (plan.writable) {
            val wrote = runCatching { device.write(plan.targetLevel) }
                .onFailure { if (it is CancellationException) throw it }
                .isSuccess
            if (wrote && waitUntil(config.ventilationVerifyTimeoutMs) { VentilationRules.isApplied(plan.targetLevel, it) }) {
                mutableActive.value = true
                mutableEvents.emit(VentilationEvent.ENABLED_BY_APP)
                return result(id, ExecutionStatus.SUCCEEDED)
            }
        }
        // 공식 샘플에서 설정 값이 유지되지 않은 기록이 있다 → 운전자에게 부탁하고 상태를 읽어 확인
        promptManual()
        mutableEvents.emit(VentilationEvent.MANUAL_PROMPTED)
        if (waitUntil(config.ventilationManualWaitMs) { VentilationRules.isManuallyRaised(plan.priorLevel, it) }) {
            mutableActive.value = true
            mutableEvents.emit(VentilationEvent.ENABLED_BY_DRIVER)
            return result(id, ExecutionStatus.SUCCEEDED)
        }
        // 켜지지 않았으니 되돌릴 것도 없다.
        if (!mutableActive.value) memory.priorLevel = null
        mutableEvents.emit(VentilationEvent.MANUAL_TIMED_OUT)
        return result(id, ExecutionStatus.FAILED, ExecutionFailure.TIMED_OUT)
    }

    private suspend fun restore(id: RequestId): ExecutionResult {
        val saved = memory.priorLevel ?: return result(id, ExecutionStatus.SUCCEEDED)
        val current = runCatching { device.read() }.getOrNull()
        val target = VentilationRules.restoreTarget(saved, current)
        if (target != null) {
            device.write(target)
            if (!waitUntil(config.ventilationVerifyTimeoutMs) { VentilationRules.isApplied(target, it) }) {
                // 값을 지우지 않는다 → 다음 RESTORE 요청 때 다시 시도
                return result(id, ExecutionStatus.FAILED, ExecutionFailure.TIMED_OUT)
            }
        }
        memory.priorLevel = null
        mutableActive.value = false
        mutableEvents.emit(VentilationEvent.RESTORED)
        return result(id, ExecutionStatus.SUCCEEDED)
    }

    /** 상태를 다시 읽어 조건이 맞을 때까지 기다린다. 읽기 실패는 '아직 아님'으로 본다. */
    private suspend fun waitUntil(timeoutMs: Long, condition: (Int?) -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition(readOrNull())) delay(config.ventilationPollIntervalMs)
            true
        } ?: false

    private suspend fun readOrNull(): Int? = try {
        device.read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun launchFor(id: RequestId, block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                mutableResults.emit(result(id, ExecutionStatus.FAILED, ExecutionFailure.PERMISSION_DENIED))
            } catch (e: Exception) {
                mutableResults.emit(result(id, ExecutionStatus.FAILED, ExecutionFailure.ERROR))
            } finally {
                lock.withLock { if (running[id] === coroutineContext[Job]) running.remove(id) }
            }
        }
        running[id] = job
        job.start()
    }

    private fun result(id: RequestId, status: ExecutionStatus, failure: ExecutionFailure? = null) =
        ExecutionResult(id, status, failure)
}
