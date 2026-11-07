package com.sleepissue.awakedrive.service

import android.content.Context
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.pleos.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*

/** 실제 SDK 소유권을 획득한 서비스만 어댑터와 Hub를 만든다. DEMO/수동 입력은 연결하지 않는다. */
class PlaygroundMonitorInput(context: Context, clock: Clock) : RealTripMonitorInput {
    private val lease = checkNotNull(PlaygroundSdkOwnership.acquire("REAL 운행 감시 서비스")) {
        "SDK 소유 중: ${PlaygroundSdkOwnership.currentOwner} · 해당 화면에서 먼저 중지하세요"
    }
    private val config = EngineConfig()
    private val policy = SdkProbePolicy(ttlMs = 3_000, naviPollMs = config.naviStateIntervalMs)
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val adapter = PlaygroundSignalAdapter(context.applicationContext, clock, policy, adapterScope)
    private val freshness = SignalFreshnessPolicy(policy.ttlMs, policy.ttlMs, MissingObservationPolicy.USE_RECEIPT_AGE)
    val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, adapter.vehicleSource, adapter.naviSource,
        rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
        HubPolicy(SignalField.entries.associateWith { freshness }, NaviField.entries.associateWith { freshness }, freshness,
            SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME),
            ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.AUTOMATIC), config)
    override val samples get() = hub.samples
    override suspend fun start(scope: CoroutineScope) {
        hub.start(CoroutineScope(scope.coroutineContext + Dispatchers.Default))
        adapter.start()
    }
    override fun close() {
        try { adapter.close() }
        finally {
            adapterScope.cancel()
            PlaygroundSdkOwnership.release(lease)
        }
    }
}
