package com.sleepissue.awakedrive.engine.config

/**
 * AssistModeDetector 문턱값: 인계 품질, 정기 확인 주기, 매끈한 조향·조작 공백(2차). (REQ_AST_001~008)
 */
data class AssistConfig(
    /** 크루즈가 꺼진 뒤 인계 품질을 지켜보는 시간. 29 s 급조향 → 2단계, 31 s → 아님 (REQ_AST_001·007) */
    val handoverWatchMs: Long = 30_000,
    /** 인계 품질 이상: 큰 수정이 평소 해제 때의 이 배수 이상 (REQ_AST_007) */
    val handoverLargeRatio: Float = 1.5f,
    /** 인계 품질 이상: 평소 해제 때 기록이 없으면 큰 수정이 이 횟수 이상 (REQ_AST_007) */
    val handoverLargeCountNoRecord: Int = 3,

    /** 보조 주행 중 정기 음성 확인 간격 (REQ_AST_005) */
    val periodicCheckMs: Long = 20 * 60_000L,
    /** 야간·취약 시간대·연속 운전 2시간 이상·출발 피로면 이 간격 (REQ_AST_005) */
    val periodicCheckRiskyMs: Long = 10 * 60_000L,
    /** 위의 '야간'. [가정] 기획서·SRS에 정의가 없어 임시로 22~06시 — 팀 확정 필요 (REQ_AST_005 비고) */
    val nightWindow: HourWindow = HourWindow(22, 6),
    /** 위의 '연속 운전 2시간 이상' (REQ_AST_005) */
    val riskyLongDriveMs: Long = 2 * 3_600_000L,

    // ── 2차(Should) ──

    /** 매끈한 조향 판정에 필요한 기간. 이만큼 데이터가 모이기 전에는 판정 보류 [가설] (REQ_AST_002) */
    val smoothWindowMs: Long = 3 * 60_000L,
    /** 매끈한 조향: 큰 수정·급조향 0회이고 미세 수정이 평소의 이 비율 미만 [가설] (REQ_AST_002) */
    val smoothMicroRatioMax: Float = 0.30f,
    /** 조작 공백이 이 시간을 넘으면 다음 확인을 당김. 900 s → 그대로, 901 s → 확인 [가설] (REQ_AST_006) */
    val opGapMs: Long = 15 * 60_000L,
)
