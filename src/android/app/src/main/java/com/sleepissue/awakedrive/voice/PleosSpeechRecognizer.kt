package com.sleepissue.awakedrive.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlin.math.sqrt

data class RecognitionCallbacks(
    val onSpeechLatency: (Long) -> Unit,
    val onIntent: (SpeechIntent) -> Unit,
    val onEnded: () -> Unit,
    val onUnavailable: (Throwable) -> Unit,
)

/** Pleos SpeechToText 3.3.1의 ON_DEVICE 전용 어댑터. */
class PleosSpeechRecognizer(
    private val context: Context,
    private val parser: SpeechIntentParser = SpeechIntentParser(),
    private val config: EngineConfig = EngineConfig(),
) : AutoCloseable {
    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    private var state = State.INITIALIZING
    private var initializationError: Throwable? = null
    private var questionType: QuestionType? = null
    private var callbacks: RecognitionCallbacks? = null
    @Volatile private var speechStartReported = false
    @Volatile private var capturing = false
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var sessionUsesStt = false
    private val mainHandler = Handler(Looper.getMainLooper())

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
            val endedCallbacks = callbacks ?: return
            // SDK가 종료를 최종 STT 결과보다 먼저 알리는 경우가 있어 짧게 결과를 기다린다.
            mainHandler.postDelayed({
                if (callbacks !== endedCallbacks) return@postDelayed
                endedCallbacks.onEnded()
                clearSession()
            }, config.sttFinalizationGraceMs)
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
                if (!capturing) clearSession()
            }
        })
    }

    fun start(questionType: QuestionType, callbacks: RecognitionCallbacks): Boolean {
        val useStt = state == State.READY
        if (!useStt) {
            callbacks.onUnavailable(
                initializationError ?: IllegalStateException("Pleos STT가 준비되지 않았습니다: $state"),
            )
        }
        this.questionType = questionType
        this.callbacks = callbacks
        speechStartReported = false
        sessionUsesStt = useStt
        return startAudioCapture(callbacks, useStt)
    }

    fun stop() {
        stopAudioCapture(completeInput = true)
    }

    private fun startAudioCapture(callbacks: RecognitionCallbacks, useStt: Boolean): Boolean {
        val minimum = AudioRecord.getMinBufferSize(
            PCM_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            callbacks.onUnavailable(IllegalStateException("AudioRecord 버퍼 생성 실패: $minimum"))
            return false
        }
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                PCM_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 2, BYTES_PER_100_MS * 2),
            ).also {
                check(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 초기화 실패" }
                it.startRecording()
            }
        } catch (error: Throwable) {
            releaseRecorder()
            callbacks.onUnavailable(error)
            return false
        }

        capturing = true
        val captureStartedAtMs = SystemClock.elapsedRealtime()
        captureThread = Thread({
            val chunk = ByteArray(BYTES_PER_100_MS)
            try {
                while (capturing) {
                    val count = recorder?.read(
                        chunk,
                        0,
                        chunk.size,
                        AudioRecord.READ_BLOCKING,
                    ) ?: break
                    if (count == chunk.size) {
                        reportSpeechStartIfNeeded(chunk, captureStartedAtMs)
                        if (useStt) stt.sendAudio(chunk.copyOf())
                    }
                }
            } catch (error: Throwable) {
                if (capturing) callbacks.onUnavailable(error)
            } finally {
                releaseRecorder()
            }
        }, "AwakeDrive-SttCapture").also(Thread::start)
        return true
    }

    private fun reportSpeechStartIfNeeded(chunk: ByteArray, captureStartedAtMs: Long) {
        if (speechStartReported) return
        var squares = 0.0
        var index = 0
        while (index < chunk.size - 1) {
            val sample = ((chunk[index + 1].toInt() shl 8) or
                (chunk[index].toInt() and 0xff)).toShort().toDouble()
            squares += sample * sample
            index += 2
        }
        val rms = sqrt(squares / (chunk.size / 2))
        if (rms < config.cabinSpeechRmsThreshold) return
        speechStartReported = true
        callbacks?.onSpeechLatency?.invoke(SystemClock.elapsedRealtime() - captureStartedAtMs)
    }

    private fun stopAudioCapture(completeInput: Boolean) {
        if (!capturing) return
        capturing = false
        recorder?.runCatching { stop() }
        captureThread?.interrupt()
        captureThread = null
        if (completeInput && sessionUsesStt) stt.completeAudioSend()
    }

    @Synchronized
    private fun releaseRecorder() {
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
    }

    private fun clearSession() {
        questionType = null
        callbacks = null
        speechStartReported = false
        sessionUsesStt = false
    }

    override fun close() {
        mainHandler.removeCallbacksAndMessages(null)
        stopAudioCapture(completeInput = false)
        stt.removeListener(resultListener)
        stt.release()
        state = State.CLOSED
        clearSession()
    }

    private enum class State { INITIALIZING, READY, FAILED, CLOSED }

    private companion object {
        const val PCM_SAMPLE_RATE = 16_000
        const val BYTES_PER_100_MS = 3_200
    }
}
