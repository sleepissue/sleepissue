package com.sleepissue.awakedrive.demo

import java.util.Collections

enum class DemoDeliveryChannel { VEHICLE, NAVI, POWER }

data class DemoDeliveryChannelAudit(
    val originalCount: Long,
    val uniqueExpectedCount: Long,
    val missingSamples: Long,
    val remainingExpectedSamples: Long,
    val duplicates: Long,
    val outOfOrder: Long,
    val unknownTimestamps: Long,
    val invalidationCount: Long,
    val firstOriginalMs: Long?,
    val lastOriginalMs: Long?,
    val hash: String,
)

data class DemoDeliveryAuditSnapshot(
    val expectedTotal: Int,
    val expectedHash: String,
    val channels: Map<DemoDeliveryChannel, DemoDeliveryChannelAudit>,
)

/**
 * Hub 입력에서 받은 원본 시각을 독립적으로 감사한다. 값·품질이 null이어도 원본 관측이다.
 * 명시적으로 표시한 무효화만 별도 계수로 센다. 정상 경로는 배열·정수 갱신만 수행한다.
 * ROS의 토픽 수신 감사처럼 VEHICLE/NAVI/POWER마다 수신 수·순서·시각 해시를 따로 유지한다.
 */
class DemoDeliveryAudit {
    private class ChannelState(expectedTotal: Int) {
        val seen = BooleanArray(expectedTotal)
        var nextExpectedIndex = 0
        var highestExpectedIndex = -1
        var originalCount = 0L
        var uniqueExpectedCount = 0L
        var duplicates = 0L
        var outOfOrder = 0L
        var unknownTimestamps = 0L
        var invalidationCount = 0L
        var firstOriginalMs = 0L
        var lastOriginalMs = 0L
        var hash = HASH_OFFSET
        // 알 수 없는 시각은 오류 경로에서만 저장한다. 정상 원본마다 박싱·객체 생성을 하지 않는다.
        var unknownTimes: MutableSet<Long>? = null
    }

    private var expectedTimes = LongArray(0)
    private var expectedHash = HASH_OFFSET
    private var states = Array(DemoDeliveryChannel.entries.size) { ChannelState(0) }

    @Synchronized
    fun reset(expectTimes: List<Long>) {
        val copied = expectTimes.toLongArray()
        var hash = HASH_OFFSET
        for (index in copied.indices) {
            require(copied[index] >= 0 && (index == 0 || copied[index] > copied[index - 1])) {
                "예상 원본 시각은 음수가 아니며 엄격히 증가해야 합니다."
            }
            hash = appendTime(hash, copied[index])
        }
        expectedTimes = copied
        expectedHash = hash
        states = Array(DemoDeliveryChannel.entries.size) { ChannelState(copied.size) }
    }

    @Synchronized
    fun observe(channel: DemoDeliveryChannel, tMs: Long, invalidation: Boolean = false) {
        val state = states[channel.ordinal]
        if (invalidation) {
            state.invalidationCount++
            return
        }
        if (state.originalCount == 0L) state.firstOriginalMs = tMs
        else if (tMs < state.lastOriginalMs) state.outOfOrder++
        state.lastOriginalMs = tMs
        state.originalCount++
        state.hash = appendTime(state.hash, tMs)
        val next = state.nextExpectedIndex
        val index = if (next < expectedTimes.size && expectedTimes[next] == tMs) next
            else expectedTimes.binarySearch(tMs)
        if (index < 0) {
            state.unknownTimestamps++
            val unknown = state.unknownTimes ?: HashSet<Long>().also { state.unknownTimes = it }
            if (!unknown.add(tMs)) state.duplicates++
            return
        }
        if (state.seen[index]) state.duplicates++
        else {
            state.seen[index] = true
            state.uniqueExpectedCount++
        }
        if (index > state.highestExpectedIndex) state.highestExpectedIndex = index
        if (index >= next) state.nextExpectedIndex = index + 1
    }

    /**
     * missingSamples는 가장 앞서 받은 예상 시각까지의 실제 미수신 수다. 늦게 오면 회복된다.
     * remainingExpectedSamples는 미래 원본을 포함한 전체 미수신 수이며 종료 시 최종 누락 수다.
     * duplicates와 outOfOrder는 독립 계수다. 이미 받은 과거 시각은 둘 다 증가할 수 있다.
     */
    @Synchronized
    fun snapshot(): DemoDeliveryAuditSnapshot {
        val channels = DemoDeliveryChannel.entries.associateWith { channel ->
            val state = states[channel.ordinal]
            DemoDeliveryChannelAudit(
                originalCount = state.originalCount,
                uniqueExpectedCount = state.uniqueExpectedCount,
                missingSamples = state.highestExpectedIndex + 1L - state.uniqueExpectedCount,
                remainingExpectedSamples = expectedTimes.size.toLong() - state.uniqueExpectedCount,
                duplicates = state.duplicates,
                outOfOrder = state.outOfOrder,
                unknownTimestamps = state.unknownTimestamps,
                invalidationCount = state.invalidationCount,
                firstOriginalMs = state.firstOriginalMs.takeIf { state.originalCount > 0 },
                lastOriginalMs = state.lastOriginalMs.takeIf { state.originalCount > 0 },
                hash = formatHash(state.hash),
            )
        }
        return DemoDeliveryAuditSnapshot(expectedTimes.size, formatHash(expectedHash), Collections.unmodifiableMap(channels))
    }

    private companion object {
        // FNV-1a 64비트에 각 원본 ms의 하위 바이트부터 8바이트를 넣는다. 보안 해시는 아니다.
        const val HASH_OFFSET = -3750763034362895579L
        const val HASH_PRIME = 1099511628211L
        fun appendTime(before: Long, timeMs: Long): Long {
            var hash = before
            var remaining = timeMs
            repeat(8) {
                hash = (hash xor (remaining and 0xffL)) * HASH_PRIME
                remaining = remaining ushr 8
            }
            return hash
        }
        fun formatHash(hash: Long): String = java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }
}
