package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.model.*
import com.sleepissue.awakedrive.signal.VehicleSignalInput
import org.junit.Assert.*
import org.junit.Test

class DemoDeliveryAuditTest {
    @Test fun allEighteenThousandOriginalsHaveMatchingCountsOrderAndHashOnEachChannel() {
        val expected = (0 until 18_000).map { it * 100L }
        val audit = DemoDeliveryAudit()
        audit.reset(expected)
        expected.forEach { time -> DemoDeliveryChannel.entries.forEach { audit.observe(it, time) } }
        DemoDeliveryChannel.entries.forEach { audit.observe(it, 1_800_000, invalidation = true) }
        val result = audit.snapshot()
        assertEquals(18_000, result.expectedTotal)
        result.channels.values.forEach { channel ->
            assertEquals(18_000L, channel.originalCount)
            assertEquals(18_000L, channel.uniqueExpectedCount)
            assertEquals(0L, channel.missingSamples)
            assertEquals(0L, channel.remainingExpectedSamples)
            assertEquals(0L, channel.duplicates)
            assertEquals(0L, channel.outOfOrder)
            assertEquals(0L, channel.unknownTimestamps)
            assertEquals(1L, channel.invalidationCount)
            assertEquals(0L, channel.firstOriginalMs)
            assertEquals(1_799_900L, channel.lastOriginalMs)
            assertEquals(result.expectedHash, channel.hash)
        }
    }

    @Test fun nullOriginalValuesStillCountAndOnlyExplicitFlagMarksInvalidation() {
        val unavailable = SignalQuality(SignalAvailability.UNAVAILABLE, Source.DEMO)
        val samples = listOf(0L, 100L, 200L).map { time -> DemoScenarioSample(time,
            VehicleSignalInput(time, Source.DEMO, quality = SignalField.entries.associateWith { unavailable }),
            NaviState(time, quality = NaviField.entries.associateWith { unavailable }),
            PowerEvent(time, PowerState.UNKNOWN, unavailable)) }
        val audit = DemoDeliveryAudit()
        audit.reset(samples.map { it.tMs })
        samples.forEach { sample ->
            assertNull(sample.vehicle.speed)
            assertNull(sample.vehicle.steeringDeg)
            assertNull(sample.vehicle.gear)
            assertNull(sample.navi.lat)
            assertNull(sample.navi.lon)
            audit.observe(DemoDeliveryChannel.VEHICLE, sample.vehicle.tMs)
            audit.observe(DemoDeliveryChannel.NAVI, sample.navi.tMs)
            audit.observe(DemoDeliveryChannel.POWER, sample.power.tMs)
        }
        val originalHash = audit.snapshot().expectedHash
        DemoDeliveryChannel.entries.forEach { audit.observe(it, 200, invalidation = true) }
        audit.snapshot().channels.values.forEach { channel ->
            assertEquals(3L, channel.originalCount)
            assertEquals(1L, channel.invalidationCount)
            assertEquals(0L, channel.duplicates)
            assertEquals(0L, channel.remainingExpectedSamples)
            assertEquals(originalHash, channel.hash)
        }
    }

    @Test fun gapsLateArrivalsDuplicateReversalsAndUnknownTimesRemainDistinct() {
        val audit = DemoDeliveryAudit()
        audit.reset(listOf(0, 100, 200, 300, 400).map(Int::toLong))
        audit.observe(DemoDeliveryChannel.VEHICLE, 0)
        audit.observe(DemoDeliveryChannel.VEHICLE, 200)
        val gap = audit.snapshot().channels.getValue(DemoDeliveryChannel.VEHICLE)
        assertEquals(1L, gap.missingSamples)
        assertEquals(3L, gap.remainingExpectedSamples)
        audit.observe(DemoDeliveryChannel.VEHICLE, 200)
        audit.observe(DemoDeliveryChannel.VEHICLE, 100)
        audit.observe(DemoDeliveryChannel.VEHICLE, 999)
        audit.observe(DemoDeliveryChannel.VEHICLE, 999)
        audit.observe(DemoDeliveryChannel.VEHICLE, 300)
        val result = audit.snapshot()
        val vehicle = result.channels.getValue(DemoDeliveryChannel.VEHICLE)
        assertEquals(7L, vehicle.originalCount)
        assertEquals(4L, vehicle.uniqueExpectedCount)
        assertEquals(0L, vehicle.missingSamples)
        assertEquals(1L, vehicle.remainingExpectedSamples)
        assertEquals(2L, vehicle.duplicates)
        assertEquals(2L, vehicle.outOfOrder)
        assertEquals(2L, vehicle.unknownTimestamps)
        assertNotEquals(result.expectedHash, vehicle.hash)
        assertEquals(0L, result.channels.getValue(DemoDeliveryChannel.NAVI).originalCount)
        assertEquals(5L, result.channels.getValue(DemoDeliveryChannel.NAVI).remainingExpectedSamples)
    }

    @Test fun resetCopiesExpectedTimesAndPreviouslyReturnedSnapshotCannotChange() {
        val expected = mutableListOf(0L, 100L)
        val audit = DemoDeliveryAudit()
        audit.reset(expected)
        expected[1] = 999L
        audit.observe(DemoDeliveryChannel.NAVI, 0)
        val before = audit.snapshot()
        audit.observe(DemoDeliveryChannel.NAVI, 100)
        assertEquals(1L, before.channels.getValue(DemoDeliveryChannel.NAVI).originalCount)
        assertEquals(1L, before.channels.getValue(DemoDeliveryChannel.NAVI).remainingExpectedSamples)
        assertEquals(0L, audit.snapshot().channels.getValue(DemoDeliveryChannel.NAVI).unknownTimestamps)
        try {
            @Suppress("UNCHECKED_CAST")
            (before.channels as MutableMap<DemoDeliveryChannel, DemoDeliveryChannelAudit>).clear()
            fail("반환한 채널 맵은 변경할 수 없어야 합니다.")
        } catch (_: UnsupportedOperationException) { }
        audit.reset(listOf(500L))
        val reset = audit.snapshot()
        assertEquals(1, reset.expectedTotal)
        reset.channels.values.forEach {
            assertEquals(0L, it.originalCount)
            assertNull(it.firstOriginalMs)
            assertNull(it.lastOriginalMs)
            assertEquals(1L, it.remainingExpectedSamples)
            assertEquals(0L, it.invalidationCount)
        }
    }

    @Test fun invalidExpectedSequenceCannotReplaceTheExistingAudit() {
        val audit = DemoDeliveryAudit()
        audit.reset(listOf(0L, 100L))
        for (invalid in listOf(listOf(-1L), listOf(100L, 100L), listOf(200L, 100L))) {
            try {
                audit.reset(invalid)
                fail("예상 시각은 음수가 아니며 엄격히 증가해야 합니다.")
            } catch (_: IllegalArgumentException) { }
        }
        audit.observe(DemoDeliveryChannel.POWER, 0)
        assertEquals(2, audit.snapshot().expectedTotal)
        assertEquals(0L, audit.snapshot().channels.getValue(DemoDeliveryChannel.POWER).unknownTimestamps)
    }
}
