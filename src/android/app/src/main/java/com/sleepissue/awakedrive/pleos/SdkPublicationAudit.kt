package com.sleepissue.awakedrive.pleos

import java.util.TreeMap

/** 실제 SDK 검증 세션의 Hub 발행 메타데이터 감사다. 센서 원천 Hz를 나타내지 않는다. */
data class SdkPublicationAuditSnapshot(
    val sessionId: Long? = null,
    val frames: Long = 0,
    val firstPublishedAtElapsedMs: Long? = null,
    val lastPublishedAtElapsedMs: Long? = null,
    val timeSpanMs: Long = 0,
    val maxPublishedGapMs: Long = 0,
    val gapsOver110ms: Long = 0,
    val missingSequences: Long = 0,
    val duplicates: Long = 0,
    val outOfOrder: Long = 0,
    val maxGapMs: Long = 110,
    val gapsOverLimit: Long = 0,
)

/**
 * FramePublication의 실제 발행 경과 시각만 사용한다. 소비자 도착 시각·시나리오 시각은 받지 않는다.
 * 기본110ms는 SDK 10Hz TC의100ms 간격+10ms 허용 기준이며 제품 최신성 TTL이 아니다.
 * frames는 중복을 포함한 record 호출 수다. missingSequences는1부터의 미관찰 순번이며 늦게 오면 회복된다.
 * 중복과 역행은 독립 계수다. 세션이 달라지면 감사 창을 새로 시작하며 이전 snapshot은 바뀌지 않는다.
 *
 * 발행 간격은 새 고유 순번이 기존 최대 발행 시각보다 앞으로 진행할 때 관찰한 차이다.
 * 중간 순번 누락 시 그 사이 발행 시각을 복원하지 않으므로 실제 발행 지연과 소비자 누락을 함께 대조해야 한다.
 * 정상 순차 경로는 과거 순번 Set을 쌓지 않는다. 드문 누락 구간만 보관해 지연 수신/중복을 구별한다.
 */
class SdkPublicationAudit(private val maxGapMs: Long = 110) {
    init { require(maxGapMs > 0) { "발행 간격 허용치는 양수여야 합니다." } }

    private var sessionId: Long? = null
    private var frames = 0L
    private var firstPublishedAtElapsedMs: Long? = null
    private var lastPublishedAtElapsedMs: Long? = null
    private var maxPublishedGapMs = 0L
    private var gapsOver110ms = 0L
    private var gapsOverLimit = 0L
    private var highestSequence = 0L
    private var missingSequences = 0L
    private var duplicates = 0L
    private var outOfOrder = 0L
    private val missingRanges = TreeMap<Long, Long>()

    @Synchronized
    fun record(sessionId: Long, sequence: Long, publishedAtElapsedMs: Long) {
        require(sessionId >= 0 && sequence > 0 && publishedAtElapsedMs >= 0) {
            "세션·발행 시각은 음수가 아니며 순번은1부터 시작해야 합니다."
        }
        if (this.sessionId != sessionId) reset(sessionId)
        frames++
        val latestTime = lastPublishedAtElapsedMs
        if (sequence < highestSequence || latestTime != null && publishedAtElapsedMs < latestTime) outOfOrder++
        val duplicate = if (sequence > highestSequence) {
            val missing = sequence - highestSequence - 1
            if (missing > 0) {
                missingRanges[highestSequence + 1] = sequence - 1
                missingSequences += missing
            }
            highestSequence = sequence
            false
        } else !fillMissingSequence(sequence)
        if (duplicate) { duplicates++; return }

        firstPublishedAtElapsedMs = firstPublishedAtElapsedMs?.let { minOf(it, publishedAtElapsedMs) } ?: publishedAtElapsedMs
        if (latestTime == null) lastPublishedAtElapsedMs = publishedAtElapsedMs
        else if (publishedAtElapsedMs > latestTime) {
            val gap = publishedAtElapsedMs - latestTime
            maxPublishedGapMs = maxOf(maxPublishedGapMs, gap)
            if (gap > 110) gapsOver110ms++
            if (gap > maxGapMs) gapsOverLimit++
            lastPublishedAtElapsedMs = publishedAtElapsedMs
        }
    }

    @Synchronized
    fun snapshot(): SdkPublicationAuditSnapshot = SdkPublicationAuditSnapshot(
        sessionId = sessionId,
        frames = frames,
        firstPublishedAtElapsedMs = firstPublishedAtElapsedMs,
        lastPublishedAtElapsedMs = lastPublishedAtElapsedMs,
        timeSpanMs = if (frames == 0L) 0 else requireNotNull(lastPublishedAtElapsedMs) - requireNotNull(firstPublishedAtElapsedMs),
        maxPublishedGapMs = maxPublishedGapMs,
        gapsOver110ms = gapsOver110ms,
        missingSequences = missingSequences,
        duplicates = duplicates,
        outOfOrder = outOfOrder,
        maxGapMs = maxGapMs,
        gapsOverLimit = gapsOverLimit,
    )

    private fun fillMissingSequence(sequence: Long): Boolean {
        val range = missingRanges.floorEntry(sequence) ?: return false
        if (sequence > range.value) return false
        val first = range.key
        val last = range.value
        missingRanges.remove(first)
        if (sequence > first) missingRanges[first] = sequence - 1
        if (sequence < last) missingRanges[sequence + 1] = last
        missingSequences--
        return true
    }

    private fun reset(sessionId: Long) {
        this.sessionId = sessionId
        frames = 0; firstPublishedAtElapsedMs = null; lastPublishedAtElapsedMs = null
        maxPublishedGapMs = 0; gapsOver110ms = 0; gapsOverLimit = 0
        highestSequence = 0; missingSequences = 0; duplicates = 0; outOfOrder = 0
        missingRanges.clear()
    }
}
