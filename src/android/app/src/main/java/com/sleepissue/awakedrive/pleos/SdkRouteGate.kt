package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.time.Clock

/** 경로 존재를 확인하기 전/만료 후 SDK 기본 0을 유효한 남은 거리로 쓰지 않는다. */
class SdkRouteGate(private val clock: Clock, private val policy: SdkProbePolicy) {
    private var active: Boolean? = null
    private var receivedMs: Long? = null
    fun update(isRouting: Boolean, receivedAtMs: Long = clock.elapsedRealtimeMs()) {
        active = isRouting; receivedMs = receivedAtMs
    }
    fun clear() { active = null; receivedMs = null }
    fun acceptsRouteValues(): Boolean = active == true && receivedMs?.let {
        val age = clock.elapsedRealtimeMs() - it
        age in 0..policy.ttlMs
    } == true
}
