package com.sleepissue.awakedrive.engine.config

/** 기획서와 SRS의 조정 가능한 문턱값을 한곳에 모은다. */
data class EngineConfig(
    val voiceSlowRatio: Double = 1.5,
    val voiceSlowAbsoluteMs: Long = 2_000L,
    val voiceResponseTimeoutMs: Long = 5_000L,
    val voiceRecheckDelayMs: Long = 30_000L,
    val defaultVoiceBaselineMs: Long = 1_000L, // [가설] 팀 측정값 확정 전 기본값
    val minimumTurnDistanceForCheckM: Float = 300f,
    val maximumCheckDeferralMs: Long = 60_000L,
    val fallbackDistanceStepM: Int = 5_000,
    val fallbackSoonThresholdM: Int = 2_500,
    val voicePreRollSilenceMs: Long = 180L,
    val cabinSpeechSampleMs: Long = 10_000L,
    val cabinSpeechRmsThreshold: Double = 1_200.0, // [가설] 에뮬레이터 실측 후 조정
    val cabinMinimumSpeechFrames: Int = 3, // [가설] 짧은 잡음 제외용
    val sttPcmBytesPerSecond: Long = 32_000L, // [가설] 공식 예시 16 kHz·16-bit·mono
    val rearPassengerMaximumWaitMs: Long = 180_000L,
    val conversationSilenceForDriverCheckMs: Long = 30_000L,
    val passengerTrendRecheckMs: Long = 600_000L,
    val stagePromotionIntervalMs: Long = 30_000L,
    val sameStageNotificationCooldownMs: Long = 600_000L,
    val stage3RenotificationMs: Long = 300_000L,
    val stageRecoveryHoldMs: Long = 600_000L,
    val normalStage1Threshold: Int = 40,
    val normalStage2Threshold: Int = 60,
    val normalStage3Threshold: Int = 80,
    val sensitiveThresholdOffset: Int = 10,
)
