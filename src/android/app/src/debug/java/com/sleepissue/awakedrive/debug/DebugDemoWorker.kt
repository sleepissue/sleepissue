package com.sleepissue.awakedrive.debug

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class DebugDemoWorkerBackend { DEFAULT_LANE, HANDLER_IMMEDIATE }

/**
 * debug 재생 실행 통로의 소유자다. 기본값은 기존 Default 단일 실행 통로이며 Handler는 실험 옵션이다.
 * scope는 소유하지 않는다. 호출자는 재생·Hub·명령 Job과 trace 저장을 끝내고 join한 뒤 close해야 한다.
 * 종료 전에 Looper를 닫으면 남은 continuation이 거절되어 IO 통로로 이동할 수 있다.
 */
class DebugDemoWorker private constructor(
    val backend: DebugDemoWorkerBackend,
    val dispatcher: CoroutineDispatcher,
    private val handlerThread: HandlerThread? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    /** 소유한 Handler만 종료한다. 공유 Dispatchers.Default는 닫지 않는다. */
    override fun close() {
        if (closed.compareAndSet(false, true)) handlerThread?.quitSafely()
    }

    /** close 이후 실제 Thread 종료를 기다린다. Main을 막거나 자기 Thread를 기다리지 않는다. */
    suspend fun awaitTermination() {
        val thread = handlerThread ?: return
        check(closed.get()) { "실행 통로를 close한 뒤 종료를 기다려야 합니다." }
        check(Thread.currentThread() !== thread) { "재생 실행 통로 안에서 자기 Thread 종료를 기다릴 수 없습니다." }
        withContext(Dispatchers.IO) { thread.join() }
    }

    companion object {
        fun create(backend: DebugDemoWorkerBackend = DebugDemoWorkerBackend.DEFAULT_LANE): DebugDemoWorker = when (backend) {
            DebugDemoWorkerBackend.DEFAULT_LANE -> DebugDemoWorker(backend, Dispatchers.Default.limitedParallelism(1))
            DebugDemoWorkerBackend.HANDLER_IMMEDIATE -> createHandlerThread()
        }

        private fun createHandlerThread(): DebugDemoWorker {
            // 기본 우선순위를 유지한다. 성능 비교에 우선순위 변경을 섞지 않는다.
            val thread = HandlerThread("AwakeDrive-demo-worker")
            thread.start()
            return try {
                val looper = checkNotNull(thread.looper) { "재생 HandlerThread의 Looper를 만들지 못했습니다." }
                val dispatcher = Handler(looper).asCoroutineDispatcher("AwakeDriveDemo").immediate
                DebugDemoWorker(DebugDemoWorkerBackend.HANDLER_IMMEDIATE, dispatcher, thread)
            } catch (error: Throwable) {
                thread.quitSafely()
                throw error
            }
        }
    }
}
