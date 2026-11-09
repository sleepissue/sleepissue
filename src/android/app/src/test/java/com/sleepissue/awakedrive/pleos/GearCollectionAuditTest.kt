package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.*
import org.junit.Assert.*
import org.junit.Test

class GearCollectionAuditTest {
    private val clock = FakeClock()
    private fun quality(confirmed: Long = 0, state: SignalAvailability = SignalAvailability.AVAILABLE) =
        SignalQuality(state, Source.REAL, observedAtMs = 0, receivedAtElapsedMs = 0,
            observationSequence = 1, stateConfirmedAtElapsedMs = confirmed, stateLeaseUntilElapsedMs = confirmed + 3_000,
            selectionReason = if (state == SignalAvailability.AVAILABLE) SignalSelectionReason.REAL_FRESH else SignalSelectionReason.NO_VALID_INPUT)
    private fun selection(at: Long, state: SignalAvailability, confirmed: Long = 0) = GearSelectionDecision(
        at, at, at / 100 + 1, 1_800_000_000_000 + at, Gear.D.takeIf { state == SignalAvailability.AVAILABLE }, quality(confirmed, state))

    @Test fun queryStagesSeparateScheduleIoResponseAndMainHandoffWithoutChangingSourceTime() {
        val audit = GearCollectionAudit(clock)
        val planned = audit.schedule(1, SdkDelivery.REQUERY, 1_000)
        clock.elapsedMs = 1_050; val resumed = audit.resumed(planned)
        clock.elapsedMs = 1_100; val io = audit.ioStarted(resumed)
        clock.elapsedMs = 1_200; audit.responded(io, 1_200)
        clock.elapsedMs = 1_230; audit.applied(io, 1_200, quality(500), quality(1_200), Gear.D, true, "정상 반영")
        val s = audit.snapshot()
        assertEquals(50L, s.maxScheduleLagMs); assertEquals(50L, s.maxIoDispatchLagMs)
        assertEquals(100L, s.maxReadMs); assertEquals(30L, s.maxStoreHandoffMs)
        assertEquals(700L, s.maxConfirmedGapMs)
        val row = s.rows.last()
        assertEquals(1_200L, row.confirmedMs); assertEquals(4_200L, row.leaseUntilMs)
        assertEquals(0L, row.nativeMs); assertEquals(0L, row.receivedAtElapsedMs)
        assertEquals(1L, row.observationSequence)
    }

    @Test fun staleIncidentRetainsBeforeAndAfterAndMeasuresFrameBracketSeparatelyFromLogs() {
        val audit = GearCollectionAudit(clock, rowCapacity = 16, incidentPreRows = 3, incidentPostRows = 4)
        audit.selected(selection(2_900, SignalAvailability.AVAILABLE))
        audit.selected(selection(3_001, SignalAvailability.STALE))
        audit.selected(selection(3_050, SignalAvailability.STALE))
        audit.selected(selection(3_102, SignalAvailability.AVAILABLE, 3_100))
        val s = audit.snapshot()
        assertEquals(1L, s.staleTransitions); assertEquals(2L, s.staleFrames)
        assertEquals(202L, s.staleBracketMs)
        assertEquals(202L, s.rows.last().staleBracketMs)
        assertTrue(s.incidents.single().any { it.availability == SignalAvailability.STALE })
        assertTrue(s.incidents.single().any { it.confirmedMs == 3_100L })
    }

    @Test fun longCollectionHasBoundedRowsAndIncidentsAndDetachedSnapshots() {
        val audit = GearCollectionAudit(clock, rowCapacity = 8, incidentCapacity = 2, incidentPreRows = 2, incidentPostRows = 3)
        repeat(1000) { i ->
            audit.selected(selection(i * 200L, SignalAvailability.AVAILABLE, i * 200L))
            audit.selected(selection(i * 200L + 100, SignalAvailability.STALE, i * 200L))
        }
        val s = audit.snapshot(); assertEquals(2_000L, s.hubFrames); assertEquals(1_000L, s.staleTransitions)
        assertTrue(s.rows.size <= 8); assertEquals(2, s.incidents.size)
        assertTrue(s.incidents.all { it.size <= 6 })
        val frozen = s.rows
        audit.selected(selection(300_000, SignalAvailability.AVAILABLE, 300_000))
        assertEquals(frozen, s.rows)
        assertNotEquals(s.rows, audit.snapshot().rows)
    }

    @Test fun queryErrorAndUnrenewedResponseRemainVisibleWithoutExtendingLease() {
        val audit = GearCollectionAudit(clock)
        val attempt = audit.ioStarted(audit.resumed(audit.schedule(1, SdkDelivery.REQUERY, 0)))
        clock.elapsedMs = 100; audit.responded(attempt, 100, error = true)
        clock.elapsedMs = 120
        val unavailable = SignalQuality(SignalAvailability.ERROR, Source.REAL)
        audit.applied(attempt, 100, quality(), unavailable, null, false, "조회 실패")
        assertEquals(1L, audit.snapshot().queryErrors)
        assertEquals(1L, audit.snapshot().noRenewalResponses)
        assertNull(audit.snapshot().rows.last().leaseUntilMs)
    }

    @Test fun automaticSummaryKeepsCountersButDoesNotCopyOrExportTheTimeline() {
        val audit = GearCollectionAudit(clock)
        audit.selected(selection(2_900, SignalAvailability.AVAILABLE))
        audit.selected(selection(3_001, SignalAvailability.STALE))
        val summary = audit.snapshot(includeTimeline = false)
        assertFalse(summary.timelineIncluded)
        assertTrue(summary.rows.isEmpty()); assertTrue(summary.incidents.isEmpty()); assertTrue(summary.peaks.isEmpty())
        assertEquals(1L, summary.staleTransitions); assertEquals(2L, summary.hubFrames)
        val full = audit.snapshot()
        assertTrue(full.timelineIncluded); assertFalse(full.rows.isEmpty()); assertFalse(full.incidents.isEmpty())
        assertEquals(summary.staleTransitions, full.staleTransitions)
    }
}
