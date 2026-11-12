package com.sleepissue.awakedrive.engine.config

/**
 * PatternClassifier 문턱값: 이상 분, 추세형·튐형, 안전 우회. (REQ_PAT_001~003·005)
 *
 * 음성 확인 결과에 따라 단계를 바꾸거나 다시 확인하는 규칙(REQ_PAT_004·006·007)은 여기 두지 않는다.
 * 예외로 딴짓 '기록'(REQ_PAT_004)은 2번이 하므로 그 대기 시간 [distractionResolveMs]만 여기 둔다.
 * 그 결정은 단계 전환(1번 InterventionPolicy)이 [com.sleepissue.awakedrive.engine.model.PatternState]와
 * 확인 결과를 받아서 하고, 문턱값도 그쪽 설정(trendCheckRetryMs 10분, slowCheckCountForRest 2회)을 쓴다.
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

    /** 안전 우회: [safetyBypassWindowMs] 안에 급조향이 이 횟수 (REQ_PAT_005) */
    val safetyBypassSuddenCount: Int = 3,
    /** 300 s 안 3회 → 신호, 3회째가 첫 회로부터 300.1 s 뒤 → 아님. 300.0 s는 포함 [해석] (REQ_PAT_005) */
    val safetyBypassWindowMs: Long = 5 * 60_000L,
    /**
     * 안전 우회 신호를 켠 뒤 유지하는 시간 [제안값].
     * 패턴 상태를 30초마다 내보내므로 순간만 켜면 받는 쪽이 놓칠 수 있다. 3회째 급조향부터 5분 동안 켜 두고,
     * 그사이 조건을 다시 만족하면 그때부터 다시 5분이다.
     */
    val safetyBypassHoldMs: Long = 5 * 60_000L,

    /**
     * 정상 대답 뒤 딴짓으로 기록할지 기다리는 시간 [제안값] (REQ_PAT_004).
     * 급조향 바로 뒤의 확인은 그 1분 칸이 아직 판정 전이라(30초 갱신·10초 확정 지연) 패턴이 '없음'이나 '애매'다.
     * 대답 뒤 이 시간 안에 튐형이 되면 딴짓으로 기록하고, 추세형이 되거나 끝까지 애매면 기록하지 않는다
     * (애매하면 단정하지 않는 쪽, 기획서 4-1 원칙 3 [해석]). 이상 분 판정(최대 70초) + 복귀 1분을 덮는 3분.
     */
    val distractionResolveMs: Long = 3 * 60_000L,
)
