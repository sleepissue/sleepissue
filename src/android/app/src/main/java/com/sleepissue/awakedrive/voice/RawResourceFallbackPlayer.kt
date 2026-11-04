package com.sleepissue.awakedrive.voice

import android.content.Context
import android.media.MediaPlayer

/** 팀이 녹음한 res/raw 음원을 cue별로 재생하는 TTS 실패 대체 구현. */
class RawResourceFallbackPlayer(
    private val context: Context,
    private val resources: Map<FallbackCue, Int>,
) : FallbackPlayer, AutoCloseable {
    private var player: MediaPlayer? = null

    override fun play(cue: FallbackCue, onComplete: () -> Unit) {
        closePlayer()
        val resource = resources[cue]
        if (resource == null) {
            onComplete()
            return
        }
        val created = MediaPlayer.create(context, resource)
        if (created == null) {
            onComplete()
            return
        }
        player = created.apply {
            setOnCompletionListener {
                closePlayer()
                onComplete()
            }
            setOnErrorListener { _, _, _ ->
                closePlayer()
                onComplete()
                true
            }
            start()
        }
    }

    private fun closePlayer() {
        player?.release()
        player = null
    }

    override fun close() = closePlayer()
}
