package com.sleepissue.awakedrive.engine.model

enum class InterventionStage(val level: Int) {
    NORMAL(0), CAUTION(1), CHECK(2), REST_GUIDANCE(3),
}

enum class EvaluationStatus { NOT_EVALUATED, EVALUATED, UNAVAILABLE }

enum class TransitionReason {
    SCORE, TIME, FORECAST, CHECK_RESULT, REST_COMPLETED, SAFETY_EVENT, USER_REQUEST,
}

@JvmInline
value class RequestId(val value: String) {
    init {
        require(value.isNotBlank()) { "요청 ID는 비어 있을 수 없습니다." }
    }
}

data class InterventionState(
    val stage: InterventionStage = InterventionStage.NORMAL,
    val evaluationStatus: EvaluationStatus = EvaluationStatus.NOT_EVALUATED,
    val reason: TransitionReason? = null,
    val pendingRequestId: RequestId? = null,
)

/** 실제 외부 요청의 최소 공통 계약이다. 실행 성공을 의미하지 않는다. */
interface ExecutionRequest {
    val requestId: RequestId
}

enum class ExecutionStatus {
    NOT_CONNECTED, REQUESTED, STARTED, DEFERRED, SUCCEEDED, FAILED, CANCELLED,
}

enum class ExecutionFailure {
    NOT_SUPPORTED, PERMISSION_DENIED, UNAVAILABLE, TIMED_OUT, ERROR, NOT_CONNECTED,
}

data class ExecutionResult(
    val requestId: RequestId,
    val status: ExecutionStatus,
    val failure: ExecutionFailure? = null,
)

enum class CheckContext {
    DRIVER_ALERTNESS, PASSENGER_ALERTNESS, WAYPOINT_CONSENT, RECOVERY, PRE_DEPARTURE_CONDITION,
}

enum class ResponseChannel { VOICE, PASSENGER_BUTTON, DRIVER_BUTTON }

/** 문장·음성 원본 대신 다음 행동을 정하는 의도만 전달한다. */
enum class ResponseIntent { OK, REST, NAP, YES, NO, VENT_OFF, REFRESHED, NEUTRAL, SLEEPY }

enum class CheckOutcome { NORMAL, SLOW, NO_RESPONSE, UNAVAILABLE, CANCELLED, ERROR }

data class CheckResult(
    val requestId: RequestId,
    val context: CheckContext,
    val outcome: CheckOutcome,
    val responseTimeMs: Long? = null,
    val intent: ResponseIntent? = null,
    val channel: ResponseChannel? = null,
) {
    init {
        require(responseTimeMs == null || responseTimeMs >= 0) { "실제 반응 시간은 음수일 수 없습니다." }
    }
}

enum class SafetyEventType { REPEATED_RAPID_STEERING }

/** 30초 주기 상태 갱신을 기다리지 않고 받을 안전 이벤트 계약이다. */
data class SafetyEvent(val tMs: Long, val type: SafetyEventType, val source: Source) {
    init {
        require(tMs >= 0) { "안전 이벤트 논리 시각은 음수일 수 없습니다." }
    }
}
