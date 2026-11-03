package com.sleepissue.awakedrive.engine.config

/**
 * 엔진의 모든 숫자 문턱값을 모으는 한 곳. (CLAUDE.md 4장 설계 규칙)
 *
 * 기획서 숫자는 "초깃값이며 시연 데이터로 조정"이므로 코드에 직접 쓰지 않고 여기서만 꺼내 쓴다.
 * 바꿀 때는 `EngineConfig().copy(fatigue = FatigueConfig(gateMinSpeedKph = 50f))`처럼 만든다.
 *
 * 역할별로 묶음을 나눠 파일을 따로 둔다. 다른 역할은 자기 묶음 파일을 만들고 아래에 한 줄만 추가한다.
 * (한 파일을 여럿이 고치면 merge 충돌이 잦아서)
 *
 * 비고 표기: [제안값] 기획서에 없어 SRS에서 정한 값 / [해석] 기획서 해석 / [가설] 근거가 약한 설계 /
 * [가정] 임시로 둔 값. 모두 팀 확정 또는 시연 데이터로 조정할 대상이다.
 */
data class EngineConfig(
    /** 2번: 게이트·조향 지표·위험 점수·맥락 가중 (REQ_FAT) */
    val fatigue: FatigueConfig = FatigueConfig(),
    /** 2번: 기본값·오늘의 나·출발 피로 (REQ_BSL) */
    val baseline: BaselineConfig = BaselineConfig(),
    /** 2번: 이상 분·추세형·튐형·안전 우회 (REQ_PAT) */
    val pattern: PatternConfig = PatternConfig(),
    /** 2번: 보조 주행 판정·정기 확인·인계 품질 (REQ_AST) */
    val assist: AssistConfig = AssistConfig(),
    // 다른 역할의 묶음은 여기에 추가 (예: intervention, check, forecast, rest)
)
