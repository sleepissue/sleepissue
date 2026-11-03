package com.sleepissue.awakedrive.engine.check

/** 원문 음성 없이 오늘의 정상 응답 시간 두 개만 모은다. 영속화는 TripStateStore가 맡는다. */
data class VoiceBaselineSamples(
    val normalResponsesMs: List<Long> = emptyList(),
) {
    fun record(result: CheckResult): VoiceBaselineSamples {
        if (result.verdict != CheckVerdict.NORMAL || result.latencyMs == null) return this
        if (normalResponsesMs.size >= REQUIRED_SAMPLE_COUNT) return this
        return copy(normalResponsesMs = normalResponsesMs + result.latencyMs.toLong())
    }

    fun todayBaselineMs(): Long? = normalResponsesMs
        .takeIf { it.size >= REQUIRED_SAMPLE_COUNT }
        ?.take(REQUIRED_SAMPLE_COUNT)
        ?.average()
        ?.toLong()

    private companion object {
        const val REQUIRED_SAMPLE_COUNT = 2
    }
}
