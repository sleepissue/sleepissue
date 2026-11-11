package com.sleepissue.awakedrive.debug

import android.os.SystemClock
import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.*

/** 30분 서비스 시험의 메모리 감사다. 발행/소비 스레드에서 파일 I/O를 하지 않는다. */
class DebugServiceContinuityAudit(val runId: String) {
    init { require(runId.matches(Regex("[a-zA-Z0-9-]{1,64}"))) }
    data class Published(val sessionId: Long, val sequence: Long, val atMs: Long)
    data class Consumed(val sessionId: Long?, val sequence: Long?, val publishedMs: Long?,
        val atMs: Long, val tMs: Long, val gear: Gear?, val availability: SignalAvailability,
        val source: Source?, val receivedMs: Long?, val confirmedMs: Long?, val leaseUntilMs: Long?)
    data class Saved(val startedMs: Long, val completedMs: Long, val result: String,
        val captureElapsedMs: Long?, val scenarioMs: Long?, val trip: TripState, val continuousMs: Long?)
    private val published = ArrayList<Published>()
    private val consumed = ArrayList<Consumed>()
    private val saves = ArrayList<Saved>()
    private val lifecycle = ArrayList<Map<String, Any>>()
    private var dropped = 0L
    private var inputCreates = 0
    @Synchronized fun published(p: FramePublication, atMs: Long) {
        if (published.size < 25_000) published += Published(p.sessionId, p.sequence, atMs) else dropped++
    }
    @Synchronized fun consumed(f: SignalFrame) {
        val q = f.qualityOf(SignalField.GEAR)
        if (consumed.size < 25_000) consumed += Consumed(f.publication?.sessionId, f.publication?.sequence,
            f.publication?.publishedAtElapsedMs, SystemClock.elapsedRealtime(), f.tMs, f.gear,
            q.availability, q.source, q.receivedAtElapsedMs, q.stateConfirmedAtElapsedMs, q.stateLeaseUntilElapsedMs)
        else dropped++
    }
    @Synchronized fun lifecycle(event: String) {
        lifecycle += mapOf("event" to event, "atMs" to SystemClock.elapsedRealtime(), "pid" to android.os.Process.myPid())
    }
    @Synchronized fun inputCreated() { inputCreates++ }
    @Synchronized fun summary(): Map<String, Any> = mapOf("published" to published.size,
        "consumed" to consumed.size, "saves" to saves.size, "dropped" to dropped, "inputCreates" to inputCreates)
    @Synchronized fun snapshot(): Map<String, Any> = mapOf("published" to published.toList(),
        "consumed" to consumed.toList(), "saves" to saves.toList(), "lifecycle" to lifecycle.toList(),
        "dropped" to dropped, "inputCreates" to inputCreates)
    fun repository(delegate: TripRepository): TripRepository = object : TripRepository by delegate {
        override suspend fun save(snapshot: TripSnapshot): RepositoryResult {
            val started = SystemClock.elapsedRealtime()
            try {
                val result = delegate.save(snapshot)
                saved(started, snapshot, result.toString())
                return result
            } catch (error: Throwable) {
                saved(started, snapshot, "THREW_${error.javaClass.simpleName}")
                throw error
            }
        }
    }
    @Synchronized private fun saved(started: Long, snapshot: TripSnapshot, result: String) {
        if (saves.size < 5_000) saves += Saved(started, SystemClock.elapsedRealtime(), result,
            snapshot.savedAtElapsedMs, snapshot.savedAtScenarioMs, snapshot.state, snapshot.preciseState?.continuousDriveMs)
        else dropped++
    }
}
