package com.sleepissue.awakedrive.performance

import java.util.concurrent.atomic.AtomicLongArray

enum class WorkStage {
    HUB_LOCK_WAIT, HUB_RECEIVE, HUB_NORMALIZE, HUB_DIAGNOSTICS, RATE_OBSERVE, RATE_SAMPLE,
    HUB_PUBLISH, PUMP, PUMP_DELIVERY, CONTROLLER_TICK, TRACE_RECORD, UI_SNAPSHOT, UI_RENDER,
    PUMP_VEHICLE_EMIT, PUMP_NAVI_EMIT, PUMP_POWER_EMIT, PUMP_COMPLETE_EMIT,
    EMIT_TO_RECEIVE_VEHICLE, EMIT_TO_RECEIVE_NAVI, EMIT_TO_RECEIVE_POWER, EMIT_TO_RECEIVE_COMPLETE,
    HUB_RECEIVE_VEHICLE, HUB_RECEIVE_NAVI, HUB_RECEIVE_POWER, HUB_RECEIVE_COMPLETE,
    CONTROLLER_COMMAND_WAIT, CONTROLLER_COMMAND_BODY, UI_COMMAND_DISPATCH,
    QUESTION_CANCEL_WAIT, QUESTION_CANCEL_BODY, UI_CANCEL_DISPATCH, DEMO_PREPARE, SCHEDULER_POLL,
}

/** 기술 계측 전용 시계다. 운행 시각·관측 시각·최신성 계산에는 사용하지 않는다. */
class WorkProbe(private val nanoTime: (() -> Long)? = null, private val threadAllocatedBytes: (() -> Long)? = null) {
    val enabled: Boolean get() = nanoTime != null
    private val counts = AtomicLongArray(WorkStage.entries.size)
    private val totals = AtomicLongArray(WorkStage.entries.size)
    private val maximums = AtomicLongArray(WorkStage.entries.size)
    private val allocations = AtomicLongArray(WorkStage.entries.size)
    private val allocationCounts = AtomicLongArray(WorkStage.entries.size)
    private val buckets = AtomicLongArray(WorkStage.entries.size * 32)

    fun begin(stage: WorkStage): Long = nanoTime?.invoke() ?: -1L
    fun end(stage: WorkStage, start: Long) {
        if (start < 0) return
        val duration = (requireNotNull(nanoTime).invoke() - start).coerceAtLeast(0)
        val index = stage.ordinal
        counts.incrementAndGet(index); totals.addAndGet(index, duration)
        var before = maximums.get(index)
        while (duration > before && !maximums.compareAndSet(index, before, duration)) before = maximums.get(index)
        val micros = duration / 1_000
        val bucket = if (micros == 0L) 0 else (64 - java.lang.Long.numberOfLeadingZeros(micros)).coerceAtMost(31)
        buckets.incrementAndGet(index * 32 + bucket)
    }

    fun allocatedBytes(): Long = threadAllocatedBytes?.invoke() ?: -1L
    fun recordAllocation(stage: WorkStage, before: Long) {
        if (before < 0) return
        val after = allocatedBytes()
        if (after < before) return
        allocations.addAndGet(stage.ordinal, after - before)
        allocationCounts.incrementAndGet(stage.ordinal)
    }

    inline fun <T> measure(stage: WorkStage, block: () -> T): T {
        val start = begin(stage)
        val bytes = allocatedBytes()
        try { return block() } finally { recordAllocation(stage, bytes); end(stage, start) }
    }

    fun snapshot(): Map<String, Map<String, Number>> {
        if (!enabled) return emptyMap()
        return WorkStage.entries.associate { stage ->
            val i = stage.ordinal
            val count = counts.get(i)
            var sum = 0L
            var p95 = 0L
            for (b in 0..31) {
                sum += buckets.get(i * 32 + b)
                if (sum >= (count * 95 + 99) / 100) { p95 = if (b == 0) 1 else 1L shl b; break }
            }
            stage.name to linkedMapOf("calls" to count, "totalNs" to totals.get(i),
                "meanNs" to if (count == 0L) 0L else totals.get(i) / count, "maxNs" to maximums.get(i),
                "p95UpperUs" to p95, "allocatedBytes" to allocations.get(i), "allocationCalls" to allocationCounts.get(i))
        }
    }

    companion object { val NONE = WorkProbe() }
}
