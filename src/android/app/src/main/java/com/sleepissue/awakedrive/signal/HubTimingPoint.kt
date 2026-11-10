package com.sleepissue.awakedrive.signal

/** 선택적 기술 계측이다. nanoTime은 처리 경과 분석에만 쓰며 Clock·최신성·관측 시각을 바꾸지 않는다. */
enum class HubTimingStage {
    TIMER_SCHEDULED, TIMER_RESUMED, LOCK_REQUESTED, LOCK_ACQUIRED, LOCK_RELEASING,
    PUBLISH_ENTERED, FRAME_READY, DIAGNOSTICS_READY, EMIT_BEFORE, EMIT_COMPLETED, CONSUMER_RECEIVED,
}

data class HubTimingPoint(
    val stage: HubTimingStage,
    val elapsedMs: Long,
    val nanoTime: Long,
    val operation: String,
    val dueAtMs: Long? = null,
    val sequence: Long? = null,
    val threadName: String = Thread.currentThread().name,
    // debug 수집기가 채운다. Java thread ID를 Linux TID로 오인하지 않는다.
    val nativeThreadId: Int? = null,
)
