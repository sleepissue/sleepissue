package com.sleepissue.awakedrive.voice

import android.os.Handler
import android.os.Looper
import com.sleepissue.awakedrive.engine.check.CheckPolicy
import com.sleepissue.awakedrive.engine.check.CheckResult
import com.sleepissue.awakedrive.engine.check.CheckType
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
    private var generation = 0L
    private var latestIntent = SpeechIntent.NONE
    private var previousResult: CheckResult? = null
    private var promptSettle: Runnable? = null
    private var timeout: Runnable? = null
    private var recheck: Runnable? = null

    fun start(
        baselineMs: Long,
        questionType: QuestionType,
        checkType: CheckType = CheckType.VOICE,
        onResult: (CheckResult, twoConsecutiveSlow: Boolean) -> Unit,
        onSttFallbackRequired: () -> Unit,
    ) {
        val currentGeneration = ++generation
        completed = false
        latestIntent = SpeechIntent.NONE
        activeBaselineMs = baselineMs
        activeQuestionType = questionType
        activeCheckType = checkType

        fun deliver(result: CheckResult) {
            if (currentGeneration != generation) return
            val consecutiveSlow = policy.twoConsecutiveSlow(previousResult, result)
            previousResult = result
            onResult(result, consecutiveSlow)
            if (result.verdict == com.sleepissue.awakedrive.engine.check.CheckVerdict.SLOW &&
                !consecutiveSlow
            ) {
                recheck = Runnable {
                    if (currentGeneration == generation) {
                        start(baselineMs, questionType, checkType, onResult, onSttFallbackRequired)
                    }
                }.also { handler.postDelayed(it, config.voiceRecheckDelayMs) }
            }
        }
        resultConsumer = ::deliver

        promptSettle = Runnable {
            if (currentGeneration != generation) return@Runnable
            promptSettle = null
            tonePlayer.play {
                if (currentGeneration != generation) return@play
                checkManager.onBeepEnded()
                var unavailable = false
                val started = recognizer.start(
                    questionType,
                    RecognitionCallbacks(
                        onSpeechLatency = checkManager::onVoiceLatencyMeasured,
                        onIntent = { if (currentGeneration == generation) latestIntent = it },
                        onEnded = { finishCurrent(currentGeneration) },
                        onUnavailable = {
                            if (currentGeneration == generation) {
                                unavailable = true
                                onSttFallbackRequired()
                            }
                        },
                    ),
                )
                if (started) {
                    timeout = Runnable {
                        if (currentGeneration != generation) return@Runnable
                        recognizer.stop()
                        handler.postDelayed(
                            { finishCurrent(currentGeneration) },
                            config.sttFinalizationGraceMs,
                        )
                    }
                        .also { handler.postDelayed(it, config.voiceResponseTimeoutMs) }
                } else if (unavailable) {
                    finishCurrent(currentGeneration)
                }
            }
        }.also { handler.postDelayed(it, config.voicePromptSettleMs) }
    }

    private var activeBaselineMs = 0L
    private var activeQuestionType = QuestionType.DRIVER_CHECK
    private var activeCheckType = CheckType.VOICE
    private var resultConsumer: ((CheckResult) -> Unit)? = null

    private fun finishCurrent(expectedGeneration: Long) {
        if (completed || expectedGeneration != generation) return
        completed = true
        timeout?.let(handler::removeCallbacks)
        timeout = null
        recognizer.stop()
        val result = checkManager.finish(
            baselineMs = activeBaselineMs,
            questionType = activeQuestionType,
            recognizedText = null,
            type = activeCheckType,
        ).copy(intent = latestIntent)
        resultConsumer?.invoke(result)
    }

    fun cancel() {
        generation++
        completed = true
        promptSettle?.let(handler::removeCallbacks)
        timeout?.let(handler::removeCallbacks)
        recheck?.let(handler::removeCallbacks)
        promptSettle = null
        timeout = null
        recheck = null
        recognizer.stop()
    }
}
