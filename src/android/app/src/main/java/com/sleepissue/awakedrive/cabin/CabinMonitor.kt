package com.sleepissue.awakedrive.cabin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.Clock
import com.sleepissue.awakedrive.voice.AndroidElapsedClock
import kotlin.math.sqrt

/** 확인 직전 지정된 시간만 PCM을 읽고, 원본을 보관하지 않은 채 말소리 유무만 반환한다. */
class CabinMonitor(
    private val context: Context,
    private val config: EngineConfig = EngineConfig(),
    private val clock: Clock = AndroidElapsedClock(),
    private val callbackHandler: Handler = Handler(Looper.getMainLooper()),
) : AutoCloseable {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    fun sample(onResult: (speechDetected: Boolean) -> Unit, onError: (Throwable) -> Unit) {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError(SecurityException("RECORD_AUDIO 권한이 없어 버튼 확인으로 전환합니다."))
            return
        }
        val sampleRate = 16_000
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            onError(IllegalStateException("AudioRecord 버퍼를 만들 수 없습니다: $minimum"))
            return
        }

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimum * 2,
            )
            check(recorder?.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 초기화 실패" }
        } catch (error: Throwable) {
            recorder?.release()
            recorder = null
            onError(error)
            return
        }

        running = true
        worker = Thread({ monitor(minimum, onResult, onError) }, "AwakeDrive-CabinMonitor")
            .also(Thread::start)
    }

    private fun monitor(
        bufferSize: Int,
        onResult: (Boolean) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val localRecorder = requireNotNull(recorder)
        val buffer = ShortArray(bufferSize)
        var speechFrames = 0
        val endAtMs = clock.nowMs() + config.cabinSpeechSampleMs
        try {
            localRecorder.startRecording()
            while (running && clock.nowMs() < endAtMs) {
                val count = localRecorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                var squares = 0.0
                for (index in 0 until count) {
                    val value = buffer[index].toDouble()
                    squares += value * value
                }
                val rms = sqrt(squares / count)
                if (rms >= config.cabinSpeechRmsThreshold) speechFrames++
            }
            val detected = speechFrames >= config.cabinMinimumSpeechFrames
            if (running) callbackHandler.post { onResult(detected) }
        } catch (error: Throwable) {
            if (running) callbackHandler.post { onError(error) }
        } finally {
            stopRecorder()
            worker = null
        }
    }

    fun stop() {
        running = false
        stopRecorder()
        worker?.interrupt()
        worker = null
    }

    @Synchronized
    private fun stopRecorder() {
        running = false
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
    }

    override fun close() = stop()
}
