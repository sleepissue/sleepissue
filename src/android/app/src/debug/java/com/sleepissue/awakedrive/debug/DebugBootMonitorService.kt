package com.sleepissue.awakedrive.debug

import android.content.Intent
import com.sleepissue.awakedrive.engine.model.SignalAvailability
import com.sleepissue.awakedrive.engine.model.SignalField
import com.sleepissue.awakedrive.pleos.PlaygroundSdkOwnership
import com.sleepissue.awakedrive.service.PlaygroundMonitorInput
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** 서비스 수명·초기화·자동 시작은 제품 구현이다. 부팅 전후의 증거만 debug에 추가한다. */
class DebugBootMonitorService : DebugIsolatedMonitorService() {
    override val persistenceContext by lazy { DebugBootAudit.storage(this) }
    private val auditScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val firstValid = AtomicBoolean()
    private var sampled = false
    override fun onCreate() {
        DebugBootAudit.record(this, "SERVICE_CREATE")
        super.onCreate()
    }
    override fun createMonitorInput(): PlaygroundMonitorInput {
        DebugBootAudit.record(this, "RESTORED_BEFORE_INPUT", mapOf("restored" to runtime.session.restoredSnapshot,
            "active" to runtime.session.preciseState(), "starts" to runtime.starts,
            "timers" to runtime.activeTimers, "owner" to PlaygroundSdkOwnership.currentOwner))
        return PlaygroundMonitorInput(this, clock, sampleObserver = { frame ->
            if (frame.gear != null && frame.qualityOf(SignalField.GEAR).availability == SignalAvailability.AVAILABLE &&
                firstValid.compareAndSet(false, true)) {
                DebugBootAudit.record(this, "FIRST_VALID_INPUT", mapOf("frame" to frame,
                    "activeBeforeAccept" to runtime.session.preciseState()))
            }
        }).also { DebugBootAudit.record(this, "INPUT_CREATED", mapOf("owner" to PlaygroundSdkOwnership.currentOwner)) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugBootAudit.record(this, "START_COMMAND", mapOf("action" to intent?.action, "startId" to startId))
        val result = super.onStartCommand(intent, flags, startId)
        if (!sampled) {
            sampled = true
            auditScope.launch {
                repeat(200) {
                    if (runtime.running && runtime.activeTimers == 1 && runtime.starts == 1) {
                        DebugBootAudit.record(this@DebugBootMonitorService, "RUNTIME_STARTED", mapOf(
                            "starts" to runtime.starts, "timers" to runtime.activeTimers,
                            "owner" to PlaygroundSdkOwnership.currentOwner, "active" to runtime.session.preciseState(),
                            "restored" to runtime.session.restoredSnapshot))
                        captureDiagnostics(false)
                        return@launch
                    }
                    delay(50)
                }
                DebugBootAudit.record(this@DebugBootMonitorService, "START_NOT_RUNNING", mapOf("diagnostic" to diagnostic,
                    "status" to runtime.status.value, "starts" to runtime.starts, "timers" to runtime.activeTimers))
                captureDiagnostics(false)
            }
        }
        return result
    }
    override fun onDestroy() {
        DebugBootAudit.record(this, "SERVICE_DESTROY", mapOf("running" to runtime.running,
            "timers" to runtime.activeTimers, "owner" to PlaygroundSdkOwnership.currentOwner))
        auditScope.cancel()
        super.onDestroy()
    }
}
