package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.signal.GearSelectionDecision

data class GearQueryAttempt(
    val id: Long, val generation: Long, val delivery: SdkDelivery,
    val scheduledMs: Long, val dueMs: Long, val resumedMs: Long? = null, val ioStartedMs: Long? = null,
)

data class GearAuditRow(
    val stage: String, val atMs: Long, val attempt: GearQueryAttempt? = null,
    val responseMs: Long? = null, val previousConfirmedMs: Long? = null,
    val confirmedMs: Long? = null, val leaseUntilMs: Long? = null, val nativeMs: Long? = null,
    val gear: Gear? = null, val availability: SignalAvailability? = null, val reason: String? = null,
    val renewed: Boolean? = null, val hubSequence: Long? = null, val scenarioMs: Long? = null,
    val evaluationFromMs: Long? = null, val evaluationToMs: Long? = null, val staleBracketMs: Long? = null,
    val receivedAtElapsedMs: Long? = null, val observationSequence: Long? = null,
    val threadName: String = Thread.currentThread().name,
)

data class GearAuditSnapshot(
    val scheduler: String, val rowCapacity: Int, val incidentCapacity: Int, val incidentRowCapacity: Int,
    val rowsRecorded: Long, val queryStarts: Long, val queryResponses: Long, val queryErrors: Long,
    val noRenewalResponses: Long, val hubFrames: Long, val staleTransitions: Long, val staleFrames: Long,
    val maxScheduleLagMs: Long, val maxIoDispatchLagMs: Long, val maxReadMs: Long,
    val maxStoreHandoffMs: Long, val maxConfirmedGapMs: Long, val staleBracketMs: Long,
    val timelineIncluded: Boolean, val rows: List<GearAuditRow>, val incidents: List<List<GearAuditRow>>,
    val peaks: Map<String, GearAuditRow>,
)

/** 수집 경로에서는 숫자와 제한된 메모리 기록만 갱신한다. 파일 저장·JSON·화면 처리는 하지 않는다. */
class GearCollectionAudit(private val clock: Clock, val scheduler: String = "MAIN_FIXED_DELAY",
    private val rowCapacity: Int = 512, private val incidentCapacity: Int = 3,
    private val incidentPreRows: Int = 64, private val incidentPostRows: Int = 32,
) {
    init { require(rowCapacity > 0 && incidentCapacity > 0 && incidentPreRows >= 0 && incidentPostRows >= 0) }
    private val rows = ArrayDeque<GearAuditRow>()
    private class Incident(val rows: MutableList<GearAuditRow>, var remaining: Int)
    private val incidents = mutableListOf<Incident>()
    private val peaks = mutableMapOf<String, GearAuditRow>() // 고정된 다섯 단계의 최댓값만 보존한다.
    private var nextQuery = 0L
    private var rowsRecorded = 0L
    private var queryStarts = 0L
    private var queryResponses = 0L
    private var queryErrors = 0L
    private var noRenewal = 0L
    private var hubFrames = 0L
    private var staleTransitions = 0L
    private var staleFrames = 0L
    private var maxScheduleLag = 0L
    private var maxIoLag = 0L
    private var maxRead = 0L
    private var maxHandoff = 0L
    private var maxConfirmGap = 0L
    private var excludedGap = 0L
    private var lastAvailability: SignalAvailability? = null
    private var lastGear: Gear? = null
    private var lastConfirmation: Long? = null
    private var lastAvailableScenario: Long? = null
    private var staleStartedAfter: Long? = null

    @Synchronized fun schedule(generation: Long, delivery: SdkDelivery, dueMs: Long): GearQueryAttempt {
        val at = clock.elapsedRealtimeMs()
        return GearQueryAttempt(++nextQuery, generation, delivery, at, dueMs).also {
            append(GearAuditRow("SCHEDULED", at, it))
        }
    }

    @Synchronized fun resumed(attempt: GearQueryAttempt): GearQueryAttempt {
        val at = clock.elapsedRealtimeMs()
        val next = attempt.copy(resumedMs = at)
        val row = GearAuditRow("RESUMED", at, next)
        if (at - attempt.dueMs > maxScheduleLag) { maxScheduleLag = at - attempt.dueMs; peaks["SCHEDULE"] = row }
        append(row); return next
    }

    @Synchronized fun ioStarted(attempt: GearQueryAttempt): GearQueryAttempt {
        val at = clock.elapsedRealtimeMs(); queryStarts++
        val next = attempt.copy(ioStartedMs = at)
        val row = GearAuditRow("IO_STARTED", at, next)
        val lag = at - (attempt.resumedMs ?: attempt.scheduledMs)
        if (lag > maxIoLag) { maxIoLag = lag; peaks["IO_DISPATCH"] = row }
        append(row); return next
    }

    @Synchronized fun responded(attempt: GearQueryAttempt, atMs: Long, error: Boolean = false) {
        queryResponses++; if (error) queryErrors++
        val row = GearAuditRow(if (error) "QUERY_ERROR" else "RESPONSE", atMs, attempt, responseMs = atMs)
        val elapsed = atMs - (attempt.ioStartedMs ?: atMs)
        if (elapsed > maxRead) { maxRead = elapsed; peaks["READ"] = row }
        append(row)
    }

    @Synchronized fun applied(attempt: GearQueryAttempt?, responseMs: Long, before: SignalQuality,
        after: SignalQuality, gear: Gear?, renewed: Boolean, reason: String,
    ) {
        val at = clock.elapsedRealtimeMs()
        if (attempt != null && !renewed) noRenewal++
        val beforeConfirmed = before.stateConfirmedAtElapsedMs
        val afterConfirmed = after.stateConfirmedAtElapsedMs
        val row = GearAuditRow("STORE_APPLIED", at, attempt, responseMs, beforeConfirmed,
            afterConfirmed, after.stateLeaseUntilElapsedMs, after.observedAtMs,
            gear, after.availability, reason, renewed, receivedAtElapsedMs = after.receivedAtElapsedMs,
            observationSequence = after.observationSequence)
        if (at - responseMs > maxHandoff) { maxHandoff = at - responseMs; peaks["STORE_HANDOFF"] = row }
        if (renewed && beforeConfirmed != null && afterConfirmed != null) {
            if (afterConfirmed - beforeConfirmed > maxConfirmGap) {
                maxConfirmGap = afterConfirmed - beforeConfirmed; peaks["CONFIRM_GAP"] = row
            }
        }
        append(row)
    }

    /** 판정 전후의 실제 단조 시각을 함께 보관한다. 발행 시각이나 소비 시각을 판정 시각으로 바꾸지 않는다. */
    @Synchronized fun selected(d: GearSelectionDecision) {
        hubFrames++
        val q = d.quality
        val stale = q.availability == SignalAvailability.STALE
        val newStale = stale && lastAvailability != SignalAvailability.STALE
        if (stale) staleFrames++
        if (newStale) { staleTransitions++; staleStartedAfter = lastAvailableScenario }
        var gap: Long? = null
        if (q.availability == SignalAvailability.AVAILABLE && d.gear != null) {
            staleStartedAfter?.let { val elapsed = (d.scenarioMs - it).coerceAtLeast(0); gap = elapsed; excludedGap += elapsed }
            staleStartedAfter = null; lastAvailableScenario = d.scenarioMs
        }
        if (lastAvailability != q.availability || lastGear != d.gear || lastConfirmation != q.stateConfirmedAtElapsedMs) {
            append(GearAuditRow("HUB_SELECTED", d.evaluatedToMs, confirmedMs = q.stateConfirmedAtElapsedMs,
                leaseUntilMs = q.stateLeaseUntilElapsedMs, nativeMs = q.observedAtMs, gear = d.gear,
                availability = q.availability, reason = q.selectionReason?.name,
                hubSequence = d.sequence, scenarioMs = d.scenarioMs,
                evaluationFromMs = d.evaluatedFromMs, evaluationToMs = d.evaluatedToMs, staleBracketMs = gap,
                receivedAtElapsedMs = q.receivedAtElapsedMs, observationSequence = q.observationSequence))
        }
        if (newStale && incidents.size < incidentCapacity) {
            incidents += Incident(rows.takeLast(incidentPreRows + 1).toMutableList(), incidentPostRows)
        }
        lastAvailability = q.availability; lastGear = d.gear; lastConfirmation = q.stateConfirmedAtElapsedMs
    }

    private fun append(row: GearAuditRow) {
        rowsRecorded++; rows.addLast(row)
        if (rows.size > rowCapacity) rows.removeFirst()
        incidents.forEach { if (it.remaining > 0) { it.rows += row; it.remaining-- } }
    }

    @Synchronized fun snapshot(includeTimeline: Boolean = true) = GearAuditSnapshot(scheduler, rowCapacity, incidentCapacity,
        incidentPreRows + 1 + incidentPostRows, rowsRecorded, queryStarts, queryResponses, queryErrors,
        noRenewal, hubFrames, staleTransitions, staleFrames, maxScheduleLag, maxIoLag, maxRead,
        maxHandoff, maxConfirmGap, excludedGap, includeTimeline,
        if (includeTimeline) rows.toList() else emptyList(),
        if (includeTimeline) incidents.map { it.rows.toList() } else emptyList(),
        if (includeTimeline) peaks.toMap() else emptyMap())
}
