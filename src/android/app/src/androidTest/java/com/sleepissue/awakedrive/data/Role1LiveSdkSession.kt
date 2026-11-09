package com.sleepissue.awakedrive.data

import android.content.Context
import android.os.SystemClock
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.signal.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.pleos.*
import com.sleepissue.awakedrive.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** SDK 수집의 기기 회귀용이다. Room·운행·서비스·가짜 입력을 만들지 않는다. */
internal class Role1LiveSdkSession(context: Context) {
    private val clock = object : Clock {
        override fun scenarioTimeMs() = SystemClock.elapsedRealtime()
        override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
        override fun wallTimeMs() = java.time.Clock.systemUTC().millis()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = SdkProbePolicy(3_000, 1_000)
    val adapter = PlaygroundSignalAdapter(context, clock, policy, scope)
    private val fresh = SignalFreshnessPolicy(3_000, 3_000, MissingObservationPolicy.USE_RECEIPT_AGE)
    val hub = VehicleSignalHub(listOf(HubSourceSet(Source.REAL, adapter.vehicleSource, adapter.naviSource,
        rateTimeBasis = ObservationTimeBasis.UNKNOWN)), clock,
        HubPolicy(SignalField.entries.associateWith { fresh }, NaviField.entries.associateWith { fresh }, fresh,
            SignalFallbackPolicy(false, 0, ObservationTimeBasis.ELAPSED_REALTIME),
            ObservationTimeBasis.ELAPSED_REALTIME, HubTickDriver.AUTOMATIC), EngineConfig())
    var ventilationObservations = 0L
        private set
    var gearObservations = 0L
        private set
    suspend fun start() = withContext(Dispatchers.Main) {
        hub.start(CoroutineScope(scope.coroutineContext + Dispatchers.Default))
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.rawVehicleObservations.collect {
                if (SignalField.VENTILATION_LEVEL in it.updatedFields) ventilationObservations++
                if (SignalField.GEAR in it.updatedFields) gearObservations++
            }
        }
        adapter.start()
    }
    suspend fun awaitVentilation(value: Int) = withTimeout(10_000) {
        while (hub.latestFrame.value?.ventilationLevel != value ||
            hub.latestFrame.value?.qualityOf(SignalField.VENTILATION_LEVEL)?.availability != SignalAvailability.AVAILABLE) delay(100)
    }
    suspend fun close() {
        withContext(Dispatchers.Main) { adapter.close() }
        scope.coroutineContext[Job]?.cancelAndJoin()
        hub.stop()
    }
}
