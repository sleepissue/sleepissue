package com.sleepissue.awakedrive.voice

import android.content.Context
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import com.sleepissue.awakedrive.engine.check.QuestionType
import com.sleepissue.awakedrive.engine.check.SpeechIntent
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.voice.SpeechIntentParser
import java.util.Locale

data class RecognitionCallbacks(
    val onSpeechLatency: (Long) -> Unit,
    val onIntent: (SpeechIntent) -> Unit,
    val onEnded: () -> Unit,
    val onUnavailable: (Throwable) -> Unit,
)

/** Pleos SpeechToText 3.3.1의 ON_DEVICE 전용 어댑터. */
class PleosSpeechRecognizer(
    context: Context,
    private val parser: SpeechIntentParser = SpeechIntentParser(),
    private val config: EngineConfig = EngineConfig(),
) : AutoCloseable {
    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    private var state = State.INITIALIZING
    private var initializationError: Throwable? = null
    private var questionType: QuestionType? = null
    private var callbacks: RecognitionCallbacks? = null
    private var speechStartReported = false

    private val resultListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onUpdated(stt: String, completed: Boolean) {
            if (!completed) return
            val type = questionType ?: return
            callbacks?.onIntent?.invoke(parser.parse(stt, type))
        }

        override fun onUpdatedEpdData(on: Long, off: Long) {
            if (speechStartReported || on < 0L) return
            speechStartReported = true
            val latencyMs = on * 1_000L / config.sttPcmBytesPerSecond
            callbacks?.onSpeechLatency?.invoke(latencyMs)
        }

        override fun onEndedRecognition() {
            callbacks?.onEnded?.invoke()
            clearSession()
        }

        override fun onError(errorType: ErrorType) {
            callbacks?.onUnavailable?.invoke(IllegalStateException("Pleos STT 오류: $errorType"))
            clearSession()
        }
    }

    init {
        stt.addListener(resultListener)
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                stt.setLocale(Locale.KOREA)
                state = State.READY
            }

            override fun onFailure(errorType: ErrorType) {
                state = State.FAILED
                initializationError = IllegalStateException("Pleos STT 초기화 실패: $errorType")
                callbacks?.onUnavailable?.invoke(
                    requireNotNull(initializationError),
                )
                clearSession()
            }
        })
    }

    fun start(questionType: QuestionType, callbacks: RecognitionCallbacks): Boolean {
        if (state != State.READY) {
            callbacks.onUnavailable(
                initializationError ?: IllegalStateException("Pleos STT가 준비되지 않았습니다: $state"),
            )
            return false
        }
        this.questionType = questionType
        this.callbacks = callbacks
        speechStartReported = false
        stt.request()
        return true
    }

    fun stop() {
        stt.stop()
    }

    private fun clearSession() {
        questionType = null
        callbacks = null
        speechStartReported = false
    }

    override fun close() {
        stt.stop()
        stt.removeListener(resultListener)
        stt.release()
        state = State.CLOSED
        clearSession()
    }

    private enum class State { INITIALIZING, READY, FAILED, CLOSED }
}
