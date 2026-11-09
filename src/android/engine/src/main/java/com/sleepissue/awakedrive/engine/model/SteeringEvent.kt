package com.sleepissue.awakedrive.engine.model

/**
 * 단계 전환(1번)이 바로 반응해야 하는 조향 사건. FatigueEngine.drainEvents()로 꺼낸다.
 *
 * 1번 develop-jdw 브랜치의 SafetyEventType과 값 이름을 맞췄다. 그 타입이 main에 들어오면 그쪽으로 바꾼다.
 */
enum class SteeringEventType {
    /** 멈춤 뒤 급조향 1회 → 2단계 확인 (REQ_INT_002). 보조 주행 중에는 내지 않는다 (기획서 5-4) */
    RAPID_STEERING,

    /** 5분 안에 급조향 3회 → 원인과 상관없이 3단계 (REQ_PAT_005). 보조 주행 중에도 낸다 */
    REPEATED_RAPID_STEERING,

    /** 보조 주행이 꺼진 직후 30초 인계 품질 이상 → 2단계 (REQ_AST_007) */
    HANDOVER_UNSAFE,
}

/** 조향 사건 하나. [tMs]는 사건이 일어난 게이트 판정 시각이라 실제 시각보다 10초 늦게 나온다. */
data class SteeringEvent(val tMs: Long, val type: SteeringEventType)
