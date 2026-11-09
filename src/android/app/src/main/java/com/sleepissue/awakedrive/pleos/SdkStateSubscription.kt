package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.time.Clock
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** SDK 비동기 조회 하나가 끝나야 다음 조회를 예약한다. Main 반영/화면 진행은 기다리지 않는다. */
internal class SdkStateSubscription<T>(
    private val scope: CoroutineScope, private val clock: Clock, private val policy: SdkProbePolicy,
    private val query: ((T?) -> Unit, (Exception) -> Unit) -> Unit,
    private val deliver: (T?, SdkDelivery, Long, Long?, Long, () -> Boolean) -> Unit,
    private val failed: (Exception) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Response<T>(val value: T?, val receivedMs: Long, val sequence: Long)
    private val lock = Any()
    private var alive = true
    private var started = false
    private var revision = 0L
    private var sequence = 0L
    private var job: Job? = null

    fun start() = synchronized(lock) {
        if (started || !alive) return@synchronized
        started = true
        job = scope.launch(dispatcher) {
            var kind = SdkDelivery.QUERY
            while (isActive && synchronized(lock) { alive }) {
                val before = synchronized(lock) { revision }
                val began = clock.elapsedRealtimeMs()
                try {
                    val response = withTimeout(policy.stateLeaseMs) {
                        suspendCancellableCoroutine<Response<T>> { continuation ->
                            val completed = AtomicBoolean()
                            try {
                                query({ value ->
                                    val at = clock.elapsedRealtimeMs()
                                    if (completed.compareAndSet(false, true) && continuation.isActive) {
                                        val order = synchronized(lock) { ++sequence }
                                        continuation.resume(Response(value, at, order))
                                    }
                                }, { error ->
                                    if (completed.compareAndSet(false, true) && continuation.isActive)
                                        continuation.resumeWithException(error)
                                })
                            } catch (error: Exception) {
                                if (completed.compareAndSet(false, true) && continuation.isActive)
                                    continuation.resumeWithException(error)
                            }
                        }
                    }
                    deliver(response.value, kind, response.receivedMs, began, response.sequence) {
                        synchronized(lock) { alive && before == revision }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    error(IllegalStateException("현재값 조회 응답 기한 초과 · 재연결 필요", timeout)); break
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (exception: Exception) { error(exception); break }
                kind = SdkDelivery.REQUERY
                delay(policy.stateRequeryMs)
            }
        }
    }

    fun callback(value: T?) {
        val at = clock.elapsedRealtimeMs()
        val order = synchronized(lock) {
            if (!alive) return
            revision++; ++sequence
        }
        deliver(value, SdkDelivery.CALLBACK, at, null, order) { synchronized(lock) { alive } }
    }

    /** 오류/권한/해제 뒤 과거 응답·콜백으로 회복하지 않는다. 재개는 새 어댑터다. */
    fun error(exception: Exception) {
        synchronized(lock) { if (!alive) return; alive = false; revision++ }
        job?.cancel()
        failed(exception)
    }

    fun close() {
        synchronized(lock) { alive = false; revision++ }
        job?.cancel()
    }
}

/** 공식 현재값 조회로 확인하는 상태다. 조향·착석의 의미 보류와 기어 정책은 별도로 보존한다. */
internal val sdkRetainedStateKeys = setOf("TURN_SIGNAL", "BRAKE_ON", "CRUISE_ACTIVE", "CRUISE_TARGET_KPH",
    "VENTILATION_LEVEL", "CABIN_TEMP_C")
