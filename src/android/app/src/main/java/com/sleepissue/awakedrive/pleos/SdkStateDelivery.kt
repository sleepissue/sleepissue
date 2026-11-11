package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.SignalAvailability

/** Main에서 실행하는 실제 상태 배송 경계다. Android 큐와 분리해 역순 전달도 같은 코드로 검증한다. */
internal class SdkStateDelivery<T>(
    private val store: SdkSignalStore, private val key: String,
    private val normalize: (T?) -> Any?, private val note: String,
    private val emit: (String) -> Unit,
) {
    fun deliver(raw: T?, kind: SdkDelivery, receipt: Long, began: Long?, order: Long, valid: () -> Boolean) {
        if (!valid()) return
        val value = normalize(raw)
        val accepted = store.receive(key, raw, value, kind, receivedMs = receipt,
            queryStartedMs = began, deliverySequence = order, note = note,
            availability = if ((key == "CRUISE_TARGET_KPH" || key == "VENTILATION_LEVEL" || key == "CABIN_TEMP_C") &&
                raw != null && value == null) SignalAvailability.ERROR else null,
            normalization = if (key == "CRUISE_ACTIVE" && raw != null && value == null)
                SdkNormalization.SEMANTIC_UNCONFIRMED else SdkNormalization.NORMALIZED)
        if (accepted) emit(key)
    }

    fun failed(error: Exception) {
        store.block(key, if (error is SecurityException) SignalAvailability.PERMISSION_DENIED else SignalAvailability.ERROR,
            "${error.javaClass.simpleName} · 현재값/구독 실패, 재연결 필요")
        emit(key)
    }
}
