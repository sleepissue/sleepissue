package com.sleepissue.awakedrive.engine.session

import com.sleepissue.awakedrive.engine.model.DriveSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RollingDriveWindowTest {
    @Test
    fun `창 양쪽을 절단하고 원래 논리 시각을 유지한다`() {
        val result = RollingDriveWindow(windowMs = 10_000L).summarize(
            listOf(DriveSegment(1_000L, 7_000L), DriveSegment(12_000L, 18_000L)),
            nowMs = 15_000L,
        )

        assertEquals(5_000L, result.windowStartMs)
        assertEquals(15_000L, result.windowEndMs)
        assertEquals(listOf(DriveSegment(5_000L, 7_000L), DriveSegment(12_000L, 15_000L)), result.normalizedSegments)
        assertEquals(5_000L, result.durationMs)
        assertEquals(5L, result.seconds)
    }

    @Test
    fun `순서와 중복과 겹침에 관계없이 합집합만 센다`() {
        val result = RollingDriveWindow(20_000L).summarize(
            listOf(
                DriveSegment(9_000L, 13_000L),
                DriveSegment(2_000L, 7_000L),
                DriveSegment(6_000L, 10_000L),
                DriveSegment(2_000L, 7_000L),
                DriveSegment(3_000L, 4_000L),
                DriveSegment(15_000L, 16_000L),
            ),
            nowMs = 20_000L,
        )

        assertEquals(listOf(DriveSegment(2_000L, 13_000L), DriveSegment(15_000L, 16_000L)), result.normalizedSegments)
        assertEquals(12_000L, result.durationMs)
    }

    @Test
    fun `맞닿은 구간은 합치되 사이의 빈 시간은 더하지 않는다`() {
        val result = RollingDriveWindow(10_000L).summarize(
            listOf(DriveSegment(0L, 1_000L), DriveSegment(1_000L, 2_000L), DriveSegment(2_001L, 3_000L)),
            nowMs = 10_000L,
        )

        assertEquals(listOf(DriveSegment(0L, 2_000L), DriveSegment(2_001L, 3_000L)), result.normalizedSegments)
        assertEquals(2_999L, result.durationMs)
    }

    @Test
    fun `초 변환은 구간별 버림이 아니라 전체 밀리초 합산 뒤 한다`() {
        val result = RollingDriveWindow(10_000L).summarize(
            listOf(DriveSegment(0L, 600L), DriveSegment(1_000L, 1_600L)),
            nowMs = 10_000L,
        )

        assertEquals(1_200L, result.durationMs)
        assertEquals(1L, result.seconds)
    }

    @Test
    fun `빈 입력과 길이 영인 구간은 영으로 합산한다`() {
        val window = RollingDriveWindow(10_000L)
        assertEquals(0L, window.summarize(emptyList(), 20_000L).durationMs)
        val result = window.summarize(listOf(DriveSegment(10_000L, 10_000L)), 20_000L)
        assertEquals(emptyList<DriveSegment>(), result.normalizedSegments)
        assertEquals(0L, result.seconds)
    }

    @Test
    fun `창 시작과 끝의 경계에 걸친 빈 교집합은 세지 않는다`() {
        val result = RollingDriveWindow(10_000L).summarize(
            listOf(DriveSegment(0L, 10_000L), DriveSegment(20_000L, 30_000L)),
            nowMs = 20_000L,
        )

        assertEquals(0L, result.durationMs)
        assertEquals(emptyList<DriveSegment>(), result.normalizedSegments)
    }

    @Test
    fun `완전 미래 구간은 제외하고 진행 중 구간은 현재까지만 센다`() {
        val result = RollingDriveWindow(10_000L).summarize(
            listOf(DriveSegment(15_000L, 25_000L), DriveSegment(21_000L, 24_000L)),
            nowMs = 20_000L,
        )

        assertEquals(listOf(DriveSegment(15_000L, 20_000L)), result.normalizedSegments)
        assertEquals(5_000L, result.durationMs)
    }

    @Test
    fun `현재보다 긴 창은 영에서 시작하고 현재 영은 빈 창이다`() {
        val window = RollingDriveWindow(Long.MAX_VALUE)
        val early = window.summarize(listOf(DriveSegment(0L, 10L)), nowMs = 5L)
        assertEquals(0L, early.windowStartMs)
        assertEquals(listOf(DriveSegment(0L, 5L)), early.normalizedSegments)
        assertEquals(5L, early.durationMs)
        val zero = window.summarize(listOf(DriveSegment(0L, 10L)), nowMs = 0L)
        assertEquals(0L, zero.windowStartMs)
        assertEquals(0L, zero.windowEndMs)
        assertEquals(0L, zero.durationMs)
    }

    @Test
    fun `Long 최대 시각에서도 절단과 합산이 오버플로하지 않는다`() {
        val result = RollingDriveWindow(Long.MAX_VALUE).summarize(
            listOf(
                DriveSegment(Long.MAX_VALUE - 1L, Long.MAX_VALUE),
                DriveSegment(0L, Long.MAX_VALUE - 1L),
                DriveSegment(1L, Long.MAX_VALUE),
            ),
            nowMs = Long.MAX_VALUE,
        )

        assertEquals(listOf(DriveSegment(0L, Long.MAX_VALUE)), result.normalizedSegments)
        assertEquals(Long.MAX_VALUE, result.durationMs)
        assertEquals(Long.MAX_VALUE / 1_000L, result.seconds)
    }

    @Test
    fun `Long 최대 시각 부근의 작은 창도 정확하게 절단한다`() {
        val result = RollingDriveWindow(1_000L).summarize(
            listOf(DriveSegment(0L, Long.MAX_VALUE)),
            nowMs = Long.MAX_VALUE,
        )

        assertEquals(Long.MAX_VALUE - 1_000L, result.windowStartMs)
        assertEquals(1_000L, result.durationMs)
        assertEquals(1L, result.seconds)
    }

    @Test
    fun `입력 목록과 구간은 수정하지 않는다`() {
        val first = DriveSegment(0L, 20_000L)
        val second = DriveSegment(5_000L, 10_000L)
        val segments = mutableListOf(first, second)
        val original = segments.toList()

        RollingDriveWindow(5_000L).summarize(segments, 15_000L)

        assertEquals(original, segments)
        assertEquals(DriveSegment(0L, 20_000L), first)
        assertEquals(DriveSegment(5_000L, 10_000L), second)
    }

    @Test
    fun `음수 시각과 양수가 아닌 창과 역전 구간은 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { RollingDriveWindow(0L) }
        assertThrows(IllegalArgumentException::class.java) { RollingDriveWindow(-1L) }
        assertThrows(IllegalArgumentException::class.java) { RollingDriveWindow(Long.MIN_VALUE) }
        assertThrows(IllegalArgumentException::class.java) {
            RollingDriveWindow(1L).summarize(emptyList(), nowMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RollingDriveWindow(1L).summarize(emptyList(), nowMs = Long.MIN_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) { DriveSegment(-1L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { DriveSegment(2L, 1L) }
    }
}
