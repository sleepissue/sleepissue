package com.sleepissue.awakedrive.pleos

import com.sleepissue.awakedrive.engine.model.FramePublication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SdkPublicationAuditTest {
    @Test fun shortAndZeroIntervalsAreVisibleSeparatelyFromMissingAndDuplicateSequences() {
        val audit = SdkPublicationAudit()
        listOf(100L, 225L, 300L, 300L, 400L).forEachIndexed { index, at -> audit.record(1, index + 1L, at) }
        val result = audit.snapshot()
        assertEquals(125L, result.maxPublishedGapMs)
        assertEquals(0L, result.minPublishedGapMs)
        assertEquals(1L, result.gapsOver110ms)
        assertEquals(2L, result.gapsBelow90ms)
        assertEquals(1L, result.zeroIntervals)
        assertEquals(0L, result.missingSequences)
        assertEquals(0L, result.duplicates)
        audit.record(2, 1, 500)
        assertNull(audit.snapshot().minPublishedGapMs)
        assertEquals(0L, audit.snapshot().gapsBelow90ms)
    }

    @Test fun publicationMetadataDefinesCadenceEvenWhenConsumerArrivalIsMuchLater() = runTest {
        val audit = SdkPublicationAudit()
        val metadata = listOf(FramePublication(7, 1, 1_000), FramePublication(7, 2, 1_110), FramePublication(7, 3, 1_221))
        for (publication in metadata) {
            // 소비자의 가상 도착 간격60초를 발행 간격으로 집계하면 안 된다.
            advanceTimeBy(60_000)
            audit.record(publication.sessionId, publication.sequence, publication.publishedAtElapsedMs)
        }
        val result = audit.snapshot()
        assertEquals(3L, result.frames)
        assertEquals(1_000L, result.firstPublishedAtElapsedMs)
        assertEquals(1_221L, result.lastPublishedAtElapsedMs)
        assertEquals(221L, result.timeSpanMs)
        assertEquals(111L, result.maxPublishedGapMs)
        assertEquals("110ms 경계는 포함하고 초과만 센다.", 1L, result.gapsOver110ms)
        assertEquals(1L, result.gapsOverLimit)
        assertEquals(0L, result.missingSequences)
        assertEquals(0L, result.duplicates)
        assertEquals(0L, result.outOfOrder)
    }

    @Test fun lateSequencesRepairMissingCountWhileDuplicateAndOrderCountersRemainIndependent() {
        val audit = SdkPublicationAudit()
        audit.record(1, 1, 100)
        audit.record(1, 4, 400)
        assertEquals(2L, audit.snapshot().missingSequences)
        audit.record(1, 4, 400)
        audit.record(1, 2, 200)
        assertEquals(1L, audit.snapshot().missingSequences)
        audit.record(1, 2, 200)
        audit.record(1, 3, 300)
        val result = audit.snapshot()
        assertEquals(6L, result.frames)
        assertEquals(0L, result.missingSequences)
        assertEquals(2L, result.duplicates)
        assertEquals(3L, result.outOfOrder)
        assertEquals(300L, result.timeSpanMs)
        assertEquals(300L, result.maxPublishedGapMs)
        assertEquals(1L, result.gapsOver110ms)
        // 소비자가 늦게 메운 순번으로 이미 관찰한 간격을 과거 프레임처럼 다시 만들지 않는다.
        audit.record(1, 7, 700)
        assertEquals(2L, audit.snapshot().missingSequences)
        assertEquals(2L, audit.snapshot().gapsOver110ms)
    }

    @Test fun aNewSessionResetsAllCountersAndPreviouslyCapturedSnapshotStaysImmutable() {
        val audit = SdkPublicationAudit()
        audit.record(10, 3, 500)
        val before = audit.snapshot()
        assertEquals("첫 수신 이전 순번도 미관찰로 센다.", 2L, before.missingSequences)
        audit.record(11, 1, 10_000)
        val after = audit.snapshot()
        assertEquals(11L, after.sessionId)
        assertEquals(1L, after.frames)
        assertEquals(10_000L, after.firstPublishedAtElapsedMs)
        assertEquals(10_000L, after.lastPublishedAtElapsedMs)
        assertEquals(0L, after.timeSpanMs)
        assertEquals(0L, after.maxPublishedGapMs)
        assertEquals(0L, after.gapsOver110ms)
        assertEquals(0L, after.missingSequences)
        assertEquals(0L, after.duplicates)
        assertEquals(0L, after.outOfOrder)
        assertEquals(10L, before.sessionId)
        assertEquals(1L, before.frames)
        assertEquals(500L, before.firstPublishedAtElapsedMs)
        assertEquals(2L, before.missingSequences)
    }

    @Test fun reversedPublicationTimesAreFlaggedAndCustomLimitDoesNotChangeTheNamedTcCounter() {
        val audit = SdkPublicationAudit(maxGapMs = 200)
        audit.record(2, 1, 100)
        audit.record(2, 2, 90)
        audit.record(2, 3, 300)
        assertEquals("구성한200ms 경계는 유효하다.", 0L, audit.snapshot().gapsOverLimit)
        audit.record(2, 4, 501)
        val result = audit.snapshot()
        assertEquals(1L, result.outOfOrder)
        assertEquals(0L, result.missingSequences)
        assertEquals(411L, result.timeSpanMs)
        assertEquals(201L, result.maxPublishedGapMs)
        assertEquals(200L, result.maxGapMs)
        assertEquals(1L, result.gapsOverLimit)
        assertEquals("SIG TC의110ms 초과 계수는 별도로 유지한다.", 2L, result.gapsOver110ms)
    }
}
