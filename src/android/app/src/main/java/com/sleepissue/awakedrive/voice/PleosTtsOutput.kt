package com.sleepissue.awakedrive.voice

import android.content.Context
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.ArrayDeque
import java.util.Locale

/** Pleos TextToSpeech 3.3.1의 ON_DEVICE 전용 어댑터. */
class PleosTtsOutput(
    context: Context,
    private val fallbackPlayer: FallbackPlayer,
) : VoiceOutput, AutoCloseable {
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private var state = State.INITIALIZING
    private var initializationError: Throwable? = null
    private var eventListenerRegistered = false
    private val queue = ArrayDeque<PendingSpeech>()
    private var active: PendingSpeech? = null

    private val eventListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            active?.onComplete?.invoke()
            active = null
            startNext()
        }

        override fun onStop() {
            active?.onComplete?.invoke()
            active = null
            startNext()
        }

        override fun onError(errorType: ErrorType) {
            val request = active
            active = null
            request?.onError?.invoke(IllegalStateException("Pleos TTS 오류: $errorType"))
            startNext()
        }
    }

    init {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                if (state == State.CLOSED) {
                    tts.release()
                    return
                }
                tts.addEventListener(eventListener)
                eventListenerRegistered = true
                tts.setLocale(Locale.KOREA)
                state = State.READY
                startNext()
            }

            override fun onFailure(errorType: ErrorType) {
                if (state == State.CLOSED) return
                state = State.FAILED
                initializationError = IllegalStateException("Pleos TTS 초기화 실패: $errorType")
                failQueued(requireNotNull(initializationError))
            }
        })
    }

    override fun speak(text: String, onComplete: () -> Unit, onError: (Throwable) -> Unit) {
        val request = PendingSpeech(text, onComplete, onError)
        when (state) {
            State.READY, State.INITIALIZING -> {
                queue.addLast(request)
                startNext()
            }
            State.FAILED -> onError(
                initializationError ?: IllegalStateException("Pleos TTS를 사용할 수 없습니다."),
            )
            State.CLOSED -> onError(IllegalStateException("Pleos TTS가 해제됐습니다."))
        }
    }

    override fun playFallback(cue: FallbackCue, onComplete: () -> Unit) {
        fallbackPlayer.play(cue, onComplete)
    }

    private fun startNext() {
        if (state != State.READY || active != null || queue.isEmpty()) return
        val request = queue.removeFirst()
        active = request
        tts.speak(request.text, true)
    }

    private fun failQueued(error: Throwable) {
        while (queue.isNotEmpty()) queue.removeFirst().onError(error)
    }

    override fun close() {
        if (state == State.CLOSED) return
        if (state == State.READY) tts.stop()
        if (eventListenerRegistered) {
            tts.removeEventListener(eventListener)
            eventListenerRegistered = false
        }
        if (state != State.INITIALIZING) tts.release()
        state = State.CLOSED
        active = null
        failQueued(IllegalStateException("Pleos TTS가 해제됐습니다."))
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
