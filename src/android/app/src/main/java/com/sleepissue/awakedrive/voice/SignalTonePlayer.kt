package com.sleepissue.awakedrive.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

class SignalTonePlayer(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : AutoCloseable {
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)

    fun play(onEnded: () -> Unit) {
        val durationMs = 250
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
        handler.postDelayed(onEnded, durationMs.toLong())
    }

    override fun close() {
        tone.release()
    }
}
