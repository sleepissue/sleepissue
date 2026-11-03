package com.sleepissue.awakedrive.voice

import android.os.Handler
import android.os.Looper
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckResult
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import com.sleepissue.awakedrive.engine.config.EngineConfig

/** 신호음 → STT/EPD → 5초 판정 → 필요 시 30초 재확인의 한 세션을 관리한다. */
class VoiceCheckCoordinator(
    private val checkManager: CheckManager,
    private val policy: CheckPolicy,
    private val recognizer: PleosSpeechRecognizer,
    private val tonePlayer: SignalTonePlayer,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val config: EngineConfig = EngineConfig(),
) {
    private var completed = false
    private var latestIntent = SpeechIntent.NONE
    private var previousResult: CheckResult? = null
    private val timeout = Runnable { finishCurrent() }

    fun start(
        baselineMs: Long,
        questionType: QuestionType,
        onResult: (CheckResult, twoConsecutiveSlow: Boolean) -> Unit,
        onSttFallbackRequired: () -> Unit,
    ) {
        completed = false
        latestIntent = SpeechIntent.NONE
        tonePlayer.play {
            checkManager.onBeepEnded()
            val started = recognizer.start(
                questionType,
                RecognitionCallbacks(
                    onSpeechLatency = checkManager::onVoiceLatencyMeasured,
                    onIntent = { latestIntent = it },
                    onEnded = { finishCurrent() },
                    onUnavailable = {
                        onSttFallbackRequired()
                        finishCurrent()
                    },
                ),
            )
            if (started) handler.postDelayed(timeout, config.voiceResponseTimeoutMs)
        }

        fun deliver(result: CheckResult) {
            val consecutiveSlow = policy.twoConsecutiveSlow(previousResult, result)
            previousResult = result
            onResult(result, consecutiveSlow)
            if (result.verdict == com.sleepissue.awakedrive.engine.check.CheckVerdict.SLOW &&
                !consecutiveSlow
            ) {
                handler.postDelayed(
                    { start(baselineMs, questionType, onResult, onSttFallbackRequired) },
                    config.voiceRecheckDelayMs,
                )
            }
        }
        resultConsumer = ::deliver
        activeBaselineMs = baselineMs
        activeQuestionType = questionType
    }

    private var activeBaselineMs = 0L
    private var activeQuestionType = QuestionType.DRIVER_CHECK
    private var resultConsumer: ((CheckResult) -> Unit)? = null

    private fun finishCurrent() {
        if (completed) return
        completed = true
        handler.removeCallbacks(timeout)
        recognizer.stop()
        val result = checkManager.finish(
            baselineMs = activeBaselineMs,
            questionType = activeQuestionType,
            recognizedText = null,
        ).copy(intent = latestIntent)
        resultConsumer?.invoke(result)
    }

    fun cancel() {
        completed = true
        handler.removeCallbacks(timeout)
        recognizer.stop()
    }
}
