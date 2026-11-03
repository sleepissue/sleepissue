package com.sleepissue.awakedrive.engine.config

/**
 * PatternClassifier 문턱값: 이상 분, 추세형·튐형, 확인 결과 처리, 안전 우회. (REQ_PAT_001~007)
 */
data class PatternConfig(
    /** 이상 분: 1분 동안 미세 수정이 기준의 이 비율 이하이고… 0.70 → 이상, 0.71 → 아님 (REQ_PAT_001) */
    val abnormalMicroRatioMax: Float = 0.70f,
    /** …큰 수정이 기준의 이 배수 이상이거나 급조향이 있었던 분 (REQ_PAT_001) */
    val abnormalLargeRatioMin: Float = 1.5f,

    /** 패턴을 볼 때 돌아보는 분 수 (REQ_PAT_002) */
    val lookbackMinutes: Int = 10,
    /** 이상 분이 이 이상이면 추세형(졸음 쪽). 5 → 아님, 6 → 추세형 (REQ_PAT_002) */
    val trendMinAbnormalMinutes: Int = 6,
    /** 이상 분이 이 이하이고 매번 복귀하면 튐형(딴짓 쪽). 그 사이(4~5)는 애매 (REQ_PAT_003) */
    val spikeMaxAbnormalMinutes: Int = 3,
    /** 튐형의 '복귀': 이상이 시작된 뒤 이 시간 안에 기준으로 돌아옴 (REQ_PAT_003) */
    val spikeRecoverWithinMs: Long = 60_000,

    /** 음성 확인은 정상인데 추세형이면 2단계를 유지하고 이 시간 뒤 다시 확인 (REQ_PAT_006) */
    val trendRecheckDelayMs: Long = 10 * 60_000L,
    /** 음성 '느림'이 이 횟수만큼 연속이면 졸음으로 보고 3단계 (REQ_PAT_007) */
    val slowCountForStage3: Int = 2,

    /** 안전 우회: [safetyBypassWindowMs] 안에 급조향이 이 횟수 (REQ_PAT_005) */
    val safetyBypassSuddenCount: Int = 3,
    /** 300 s 안 3회 → 신호, 3회째가 첫 회로부터 300.1 s 뒤 → 아님 (REQ_PAT_005) */
    val safetyBypassWindowMs: Long = 5 * 60_000L,
)
