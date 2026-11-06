package com.sleepissue.awakedrive.engine.check

import com.sleepissue.awakedrive.engine.config.EngineConfig

class VoiceBaseline(private val config: EngineConfig) {
    fun resolve(personalBaselineMs: Long?, todayFirstResponsesMs: List<Long>): Long {
        personalBaselineMs?.let { return it }
        if (todayFirstResponsesMs.size < 2) return config.defaultVoiceBaselineMs
        return todayFirstResponsesMs.take(2).average().toLong()
    }
}
