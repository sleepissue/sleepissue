package com.sleepissue.awakedrive.voice

import android.content.Context
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

/** Pleos TextToSpeech 3.3.1의 ON_DEVICE 전용 어댑터. */
class PleosTtsOutput(
    context: Context,
    private val fallbackPlayer: FallbackPlayer,
) : VoiceOutput, AutoCloseable {
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private var state = State.INITIALIZING
    private var initializationError: Throwable? = null
    private var pending: PendingSpeech? = null
    private var active: PendingSpeech? = null

    private val eventListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            active?.onComplete?.invoke()
            active = null
        }

        override fun onStop() {
            active?.onComplete?.invoke()
            active = null
        }

        override fun onError(errorType: ErrorType) {
            val request = active ?: pending
            active = null
            pending = null
            request?.onError?.invoke(IllegalStateException("Pleos TTS 오류: $errorType"))
        }
    }

    init {
        tts.addEventListener(eventListener)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.setLocale(Locale.KOREA)
                state = State.READY
                pending?.also {
                    pending = null
                    start(it)
                }
            }

            override fun onFailure(errorType: ErrorType) {
                state = State.FAILED
                initializationError = IllegalStateException("Pleos TTS 초기화 실패: $errorType")
                pending?.onError?.invoke(requireNotNull(initializationError))
                pending = null
            }
        })
    }

    override fun speak(text: String, onComplete: () -> Unit, onError: (Throwable) -> Unit) {
        val request = PendingSpeech(text, onComplete, onError)
        when (state) {
            State.READY -> start(request)
            State.INITIALIZING -> pending = request
            State.FAILED -> onError(
                initializationError ?: IllegalStateException("Pleos TTS를 사용할 수 없습니다."),
            )
            State.CLOSED -> onError(IllegalStateException("Pleos TTS가 해제됐습니다."))
        }
    }

    override fun playFallback(cue: FallbackCue, onComplete: () -> Unit) {
        fallbackPlayer.play(cue, onComplete)
    }

    private fun start(request: PendingSpeech) {
        active = request
        tts.speak(request.text, true)
    }

    override fun close() {
        tts.stop()
        tts.removeEventListener(eventListener)
        tts.release()
        state = State.CLOSED
        active = null
        pending = null
    }

    private data class PendingSpeech(
        val text: String,
        val onComplete: () -> Unit,
        val onError: (Throwable) -> Unit,
    )

    private enum class State { INITIALIZING, READY, FAILED, CLOSED }
}

fun interface FallbackPlayer {
    fun play(cue: FallbackCue, onComplete: () -> Unit)
}
