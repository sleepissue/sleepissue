package com.sleepissue.awakedrive.engine.config

/**
 * AssistModeDetector 문턱값: 인계 품질, 매끈한 조향(2차). (REQ_AST_001~003·007, AST_002)
 *
 * 보조 주행 중 정기 확인 간격(REQ_AST_005)과 조작 공백 기준(REQ_AST_006)은 여기 두지 않는다.
 * 2번은 보조 주행 여부와 조작 공백 시간([com.sleepissue.awakedrive.engine.model.AssistState])만 내보내고,
 * 언제 확인할지는 단계 전환(1번 InterventionPolicy)이 그쪽 설정
 * (assistedCheckIntervalMs 20분, assistedRiskCheckIntervalMs 10분, assistedOperationGapSec 900초)으로 정한다.
 */
data class AssistConfig(
    /** 크루즈가 꺼진 뒤 인계 품질을 지켜보는 시간. 29 s 급조향 → 2단계, 31 s → 아님 (REQ_AST_001·007) */
    val handoverWatchMs: Long = 30_000,
    /** 인계 품질 이상: 큰 수정이 평소 해제 때의 이 배수 이상 (REQ_AST_007) */
    val handoverLargeRatio: Float = 1.5f,
    /** 인계 품질 이상: 평소 해제 때 기록이 없으면 큰 수정이 이 횟수 이상 (REQ_AST_007) */
    val handoverLargeCountNoRecord: Int = 3,

    // ── 2차(Should) ──

    /** 매끈한 조향 판정에 필요한 기간. 이만큼 데이터가 모이기 전에는 판정 보류 [가설] (REQ_AST_002) */
    val smoothWindowMs: Long = 3 * 60_000L,
    /** 매끈한 조향: 큰 수정·급조향 0회이고 미세 수정이 평소의 이 비율 미만 [가설] (REQ_AST_002) */
    val smoothMicroRatioMax: Float = 0.30f,
)
