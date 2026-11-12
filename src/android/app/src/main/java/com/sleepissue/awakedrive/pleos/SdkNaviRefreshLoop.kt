package com.sleepissue.awakedrive.pleos

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** 정기 조회는 유지하되 경로 변경 알림은 다음 주기를 기다리지 않는다. 요청 폭주는 한 번으로 합친다. */
internal class SdkNaviRefreshLoop(private val intervalMs: Long, private val nowMs: () -> Long) {
    private val refresh = Channel<Unit>(Channel.CONFLATED)

    init { require(intervalMs > 0) }

    fun requestRefresh() { refresh.trySend(Unit) }

    suspend fun run(beforeWait: (Long) -> Unit = {}, afterWait: (Long) -> Unit = {}, query: () -> Unit) {
        var periodicDue = nowMs()
        while (currentCoroutineContext().isActive) {
            // 대기 중 첫 알림이 receiver로 배송된 뒤 버퍼에 쌓인 연타도 이번 조회로 함께 처리한다.
            refresh.tryReceive()
            val now = nowMs()
            if (now >= periodicDue) {
                periodicDue = Math.addExact(periodicDue,
                    Math.multiplyExact((now - periodicDue) / intervalMs + 1, intervalMs))
            }
            query()
            // 추가 조회는 정기 예약을 뒤로 미루지 않는다. 늦으면 과거 조회를 반복하지 않는다.
            beforeWait(periodicDue)
            withTimeoutOrNull((periodicDue - nowMs()).coerceAtLeast(1)) { refresh.receive() }
            afterWait(periodicDue)
        }
    }
}
