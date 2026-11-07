package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckResult
import com.sleepissue.awakedrive.engine.check.CheckType
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import com.sleepissue.awakedrive.engine.time.Clock
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser

/**
 * 신호음과 음성 감지의 시각을 순수 판정 로직에 연결한다.
 * STT 완료 시각이 아니라 [onVoiceStarted] 시각만 대답 시간에 사용한다.
 */
class CheckManager(
    private val clock: Clock,
    private val policy: CheckPolicy,
    private val intentParser: SpeechIntentParser,
) {
    private var beepEndedAtMs: Long? = null
    private var voiceStartedAtMs: Long? = null
    private var measuredLatencyMs: Long? = null

    fun onBeepEnded() {
        beepEndedAtMs = clock.elapsedRealtimeMs()
        voiceStartedAtMs = null
        measuredLatencyMs = null
    }

    /** Pleos EPD가 입력 버퍼의 음성 시작 위치로 계산한 지연을 전달한다. */
    fun onVoiceLatencyMeasured(latencyMs: Long) {
        if (beepEndedAtMs != null && measuredLatencyMs == null) {
            measuredLatencyMs = latencyMs.coerceAtLeast(0L)
        }
    }

    fun onVoiceStarted() {
        if (beepEndedAtMs != null && voiceStartedAtMs == null) {
            voiceStartedAtMs = clock.elapsedRealtimeMs()
        }
    }

    fun finish(
        baselineMs: Long,
        questionType: QuestionType,
        recognizedText: String?,
        type: CheckType = CheckType.VOICE,
    ): CheckResult {
        val now = clock.elapsedRealtimeMs()
        requireNotNull(beepEndedAtMs) { "onBeepEnded()를 먼저 호출해야 합니다." }
        val latency = measuredLatencyMs ?: voiceStartedAtMs?.let { started ->
            (started - requireNotNull(beepEndedAtMs)).coerceAtLeast(0L)
        }
        val result = CheckResult(
            type = type,
            latencyMs = latency?.toInt(),
            verdict = policy.verdict(latency, baselineMs),
            intent = recognizedText?.let { intentParser.parse(it, questionType) } ?: SpeechIntent.NONE,
            ts = now,
        )
        beepEndedAtMs = null
        voiceStartedAtMs = null
        measuredLatencyMs = null
        return result
    }
}
