package com.sleepissue.awakedrive.engine.voice

import com.sleepissue.awakedrive.engine.config.EngineConfig
import kotlin.math.roundToInt

sealed interface DistancePhrase {
    data object Soon : DistancePhrase
    data class Kilometers(val value: Int) : DistancePhrase
}

class DistancePhraseResolver(private val config: EngineConfig = EngineConfig()) {
    fun resolve(distanceM: Int): DistancePhrase {
        if (distanceM < config.fallbackSoonThresholdM) return DistancePhrase.Soon
        val roundedM = (distanceM.toDouble() / config.fallbackDistanceStepM)
            .roundToInt() * config.fallbackDistanceStepM
        return DistancePhrase.Kilometers(roundedM / 1_000)
    }
}
