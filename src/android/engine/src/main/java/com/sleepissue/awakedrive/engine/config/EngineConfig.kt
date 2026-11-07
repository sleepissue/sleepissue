package com.sleepissue.awakedrive.engine.config

/** 기획서와 SRS의 조정 가능한 문턱값을 한곳에 모은다. */
data class EngineConfig(
    val voiceSlowRatio: Double = 1.5,
    val voiceSlowAbsoluteMs: Long = 2_000L,
    val voiceResponseTimeoutMs: Long = 5_000L,
    val sttFinalizationGraceMs: Long = 500L, // [가설] ON_DEVICE 최종 결과 수신 대기
    val voiceRecheckDelayMs: Long = 30_000L,
    val defaultVoiceBaselineMs: Long = 1_000L, // [가설] 팀 측정값 확정 전 기본값
    val minimumTurnDistanceForCheckM: Float = 300f,
    val maximumCheckDeferralMs: Long = 20_000L,
    val fallbackDistanceStepM: Int = 5_000,
    val fallbackSoonThresholdM: Int = 2_500,
    val voicePreRollSilenceMs: Long = 180L,
    val voicePromptSettleMs: Long = 2_000L, // [가설] Pleos 에뮬레이터 TTS 지연 잔향 실측 제외
    val cabinSpeechSampleMs: Long = 10_000L,
    val cabinSpeechRmsThreshold: Double = 1_250.0, // [가설] Pleos 실측: 잔향 안정 후 무음 1,216 / 일반 발화 1,290~1,362 RMS
    val cabinMinimumSpeechFrames: Int = 3, // [가설] 짧은 잡음 제외용
    val voiceMinimumConsecutiveSpeechFrames: Int = 2, // [가설] '네' 발화 실측: 200 ms 연속, 단발 잡음 제외
    val voiceNoiseCalibrationFrames: Int = 3, // [가설] 신호음 후 150 ms 현재 소음 측정
    val voiceMinimumSpeechRms: Double = 300.0, // [가설] 저소음 환경 최소 문턱
    val voiceSpeechToNoiseRatio: Double = 1.08, // [가설] 배경 소음 대비 8% 상승
    val voiceMinimumRmsAboveNoise: Double = 100.0, // [가설] 배경 대비 최소 RMS 증가분
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
