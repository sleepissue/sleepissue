package com.sleepissue.awakedrive.voice

import com.sleepissue.awakedrive.engine.config.EngineConfig

/** Pleos TTS와 녹음 음성 대체 구현이 공통으로 지켜야 할 최소 계약. */
interface VoiceOutput {
    fun speak(text: String, onComplete: () -> Unit, onError: (Throwable) -> Unit)
    fun playFallback(cue: FallbackCue, onComplete: () -> Unit)
}

enum class FallbackCue { GENERIC_GUIDANCE, CHECK_PROMPT, REST_WARNING }

interface DuckingAudioFocus {
    fun acquire()
    fun release()
}

class VoiceAgent(
    private val output: VoiceOutput,
    private val audioFocus: DuckingAudioFocus,
    private val delay: (Long, () -> Unit) -> Unit,
    private val config: EngineConfig = EngineConfig(),
) {
    fun speak(text: String, fallback: FallbackCue, onComplete: () -> Unit = {}) {
        audioFocus.acquire()
        val finish = {
            audioFocus.release()
            onComplete()
        }
        delay(config.voicePreRollSilenceMs) {
            output.speak(
                text = text,
                onComplete = finish,
                onError = { output.playFallback(fallback, finish) },
            )
        }
    }
}
