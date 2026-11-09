package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.SignalAvailability

/** AAOS 원천/수신 시각은 보존하고, 저장소가 거부한 배송은 이전 상태로 재전달하지 않는다. */
internal class SdkCarDelivery(
    private val store: SdkSignalStore,
    private val key: String,
    private val emit: (String) -> Unit,
) {
    fun deliver(raw: Any?, normalized: Any?, kind: SdkDelivery, nativeMs: Long?, receipt: Long,
        began: Long?, note: String, availability: SignalAvailability?): Boolean {
        val accepted = store.receive(key, raw, normalized, kind, nativeMs, note, availability, receipt,
            queryStartedMs = began)
        if (accepted) emit(key)
        return accepted
    }
}
