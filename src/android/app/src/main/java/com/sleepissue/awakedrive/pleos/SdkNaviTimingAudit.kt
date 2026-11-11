package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.time.Clock

/** 명시적인 기기 진단에서만 생성한다. 조회 순서·주기·값·관측 시각에 관여하지 않는다. */
internal data class SdkNaviTimingPoint(
    val stage: String,
    val operation: String,
    val elapsedMs: Long,
    val dueAtMs: Long?,
    val seconds: Int?,
    val meters: Int?,
    val mode: String?,
    val threadName: String = Thread.currentThread().name,
)

internal class SdkNaviTimingAudit(private val clock: Clock, private val observer: (SdkNaviTimingPoint) -> Unit) {
    fun point(stage: String, operation: String, stampMs: Long = -1, dueAtMs: Long = -1,
        seconds: Int? = null, meters: Int? = null, mode: String? = null) {
        // 진단 소비자의 예외가 SDK 호출/배송/정리를 막거나 원래 예외를 가리지 않는다.
        try {
            observer(SdkNaviTimingPoint(stage, operation, if (stampMs >= 0) stampMs else clock.elapsedRealtimeMs(),
                dueAtMs.takeIf { it >= 0 }, seconds, meters, mode))
        } catch (_: Exception) { }
    }
}

/** OFF이면 인라인 분기만 실행한다. 추가 시계 읽기·계측점/람다 할당은 없다. */
internal inline fun <T> traceNaviCall(audit: SdkNaviTimingAudit?, operation: String, action: () -> T): T {
    if (audit == null) return action()
    audit.point("CALL_BEGIN", operation)
    return try {
        val value = action()
        audit.point("CALL_RETURN", operation)
        value
    } catch (error: Throwable) {
        audit.point("CALL_THROW", operation)
        throw error
    }
}
