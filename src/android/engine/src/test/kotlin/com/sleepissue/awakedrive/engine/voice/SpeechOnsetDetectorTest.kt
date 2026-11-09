package com.sleepissue.awakedrive.engine.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechOnsetDetectorTest {
    @Test
    fun `현재 배경 소음을 측정해 상대 문턱을 만든다`() {
        val detector = detector()

        calibrate(detector, 1_100.0)

        assertEquals(1_200.0, detector.currentThresholdRms!!, 0.001)
    }

    @Test
    fun `배경 소음이 계속되면 발화로 보지 않는다`() {
        val detector = detector()
        calibrate(detector, 1_100.0)

        assertNull(detector.onFrame(rms = 1_150.0, elapsedMs = 200L))
        assertNull(detector.onFrame(rms = 1_180.0, elapsedMs = 250L))
    }

    @Test
    fun `한 프레임 잡음은 발화로 보지 않는다`() {
        val detector = detector()
        calibrate(detector, 1_100.0)

        assertNull(detector.onFrame(rms = 1_350.0, elapsedMs = 200L))
        assertNull(detector.onFrame(rms = 1_100.0, elapsedMs = 250L))
    }

    @Test
    fun `두 프레임 연속 음성의 첫 프레임을 발화 시작으로 보고 한 번만 보고한다`() {
        val detector = detector()
        calibrate(detector, 1_100.0)

        assertNull(detector.onFrame(rms = 1_300.0, elapsedMs = 800L))
        assertEquals(800L, detector.onFrame(rms = 1_350.0, elapsedMs = 850L))
        assertNull(detector.onFrame(rms = 1_400.0, elapsedMs = 900L))
    }

    private fun calibrate(detector: SpeechOnsetDetector, rms: Double) {
        assertNull(detector.onFrame(rms, 50L))
        assertNull(detector.onFrame(rms, 100L))
        assertNull(detector.onFrame(rms, 150L))
    }

    private fun detector() = SpeechOnsetDetector(
        minimumRmsThreshold = 300.0,
        minimumConsecutiveFrames = 2,
        frameDurationMs = 50L,
        noiseCalibrationFrames = 3,
        speechToNoiseRatio = 1.08,
        minimumRmsAboveNoise = 100.0,
    )
}
