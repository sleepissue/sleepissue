package com.sleepissue.awakedrive.engine.session

import com.sleepissue.awakedrive.engine.model.DriveSegment

/**
 * 최근 관측 창의 합집합이다. normalizedSegments는 창에 절단한 원래 논리 시각을 유지한다.
 * 초 단위 값은 각 구간을 따로 버림하지 않고 전체 밀리초를 합산한 뒤 한 번만 변환한다.
 */
class RollingDriveSummary internal constructor(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val normalizedSegments: List<DriveSegment>,
    val durationMs: Long,
) {
    val seconds: Long get() = durationMs / MILLIS_PER_SECOND

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * [max(0, nowMs - windowMs), nowMs) 안의 운전 관측 구간을 합산한다.
 *
 * 중복·겹침·서로 맞닿은 구간은 하나로 합치며 길이 0과 완전 미래 구간은 제외한다.
 * 미래까지 이어진 구간은 nowMs까지만 센다. 입력 구간과 그 시간 기준은 바꾸지 않는다.
 *
 * 정차를 운전에 포함할지, 오늘이 언제 시작되는지는 판단하지 않는다.
 * 호출자가 같은 논리 시간축의 nowMs와 정책을 적용한 구간을 공급해야 한다.
 * 정렬 후 선형 병합하므로 구간 수 n에 대해 O(n log n)으로 동작한다.
 */
class RollingDriveWindow(val windowMs: Long) {
    init {
        require(windowMs > 0L) { "운전 관측 창은 양수여야 합니다." }
    }

    fun summarize(segments: Iterable<DriveSegment>, nowMs: Long): RollingDriveSummary {
        require(nowMs >= 0L) { "현재 논리 시각은 음수일 수 없습니다." }
        // 뺄셈 전에 비교하므로 창이 현재 시각보다 길어도 음수·오버플로가 생기지 않는다.
        val windowStartMs = if (nowMs < windowMs) 0L else nowMs - windowMs
        val clipped = segments.mapNotNull { segment ->
            val startMs = maxOf(segment.startMs, windowStartMs)
            val endMs = minOf(segment.endMs, nowMs)
            if (startMs < endMs) DriveSegment(startMs, endMs) else null
        }.sortedWith(compareBy<DriveSegment> { it.startMs }.thenBy { it.endMs })

        val normalized = mutableListOf<DriveSegment>()
        for (segment in clipped) {
            val previous = normalized.lastOrNull()
            if (previous == null || segment.startMs > previous.endMs) {
                normalized += segment
            } else if (segment.endMs > previous.endMs) {
                normalized[normalized.lastIndex] = DriveSegment(previous.startMs, segment.endMs)
            }
        }
        // 합집합 길이는 [0, nowMs) 이하이므로 Long 범위를 넘지 않는다.
        // 향후 병합 구현이 바뀌어 이 불변식이 깨지면 조용히 래핑하지 않고 오류로 드러낸다.
        val durationMs = normalized.fold(0L) { totalMs, segment ->
            Math.addExact(totalMs, segment.endMs - segment.startMs)
        }
        return RollingDriveSummary(windowStartMs, nowMs, normalized.toList(), durationMs)
    }
}
