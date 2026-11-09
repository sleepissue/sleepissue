package com.sleepissue.awakedrive.engine.voice

/** STT 인식 결과와 분리해 PCM RMS로 실제 발화 시작 시점만 판정한다. */
class SpeechOnsetDetector(
    private val minimumRmsThreshold: Double,
    private val minimumConsecutiveFrames: Int,
    private val frameDurationMs: Long,
    private val noiseCalibrationFrames: Int,
    private val speechToNoiseRatio: Double,
    private val minimumRmsAboveNoise: Double,
) {
    private var consecutiveSpeechFrames = 0
    private var detected = false
    private var calibratedFrames = 0
    private var noiseRmsSum = 0.0

    var currentThresholdRms: Double? = null
        private set

    init {
        require(minimumConsecutiveFrames > 0)
        require(frameDurationMs > 0L)
        require(noiseCalibrationFrames > 0)
        require(speechToNoiseRatio >= 1.0)
        require(minimumRmsAboveNoise >= 0.0)
    }

    fun onFrame(rms: Double, elapsedMs: Long): Long? {
        if (detected) return null
        if (calibratedFrames < noiseCalibrationFrames) {
            noiseRmsSum += rms
            calibratedFrames++
            if (calibratedFrames == noiseCalibrationFrames) {
                val noiseFloor = noiseRmsSum / noiseCalibrationFrames
                currentThresholdRms = maxOf(
                    minimumRmsThreshold,
                    noiseFloor * speechToNoiseRatio,
                    noiseFloor + minimumRmsAboveNoise,
                )
            }
            return null
        }
        if (rms < requireNotNull(currentThresholdRms)) {
            consecutiveSpeechFrames = 0
            return null
        }

        consecutiveSpeechFrames++
        if (consecutiveSpeechFrames < minimumConsecutiveFrames) return null

        detected = true
        return (elapsedMs - (minimumConsecutiveFrames - 1) * frameDurationMs)
            .coerceAtLeast(0L)
    }
}
