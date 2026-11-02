package com.sleepissue.awakedrive.engine.session

import com.sleepissue.awakedrive.engine.model.DriveSegment
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.PowerState
import com.sleepissue.awakedrive.engine.model.TripState

/** 서로 다른 문서의 정차 계산을 앱 기본값으로 확정하지 않고 호출자가 선택한다. */
enum class StopTimePolicy {
    V03_RETROACTIVE,
    LEGACY_FREEZE,
}

/** 짧은 정차의 최근 24시간 포함 여부는 연속 시간 정책과 별도로 명시한다. */
enum class ShortStopAccumulationPolicy { EXCLUDE, INCLUDE }

data class TripRulePolicy(
    val stopTime: StopTimePolicy,
    val shortStopAccumulation: ShortStopAccumulationPolicy,
)

/**
 * tMs는 운행 논리 시각이다. null/UNKNOWN은 지금의 미관측이며 이전 값 유지 명령이 아니다.
 * 관측 소유자는 입력 중단을 명시적으로 전달해야 한다. 앱 종료를 전원 OFF로 바꾸지 않는다.
 * 같은 시각의 다른 관측은 허용하며 이미 지난 구간을 다시 누적하지 않는다.
 */
data class TripObservation(val tMs: Long, val gear: Gear?, val power: PowerState?) {
    init {
        require(tMs >= 0) { "운행 관측 시각은 음수일 수 없습니다." }
    }
}

/**
 * 초 단위 공개 상태와 별도로 ms 정밀 상태를 유지한다. 영속 복원 형식은 후속 구현 대상이다.
 * driveSegments는 확정 구간이며, 진행 중 짧은 정차의 잠정 포함분은 저장하지 않는다.
 * pendingNewTrip은 실제 OFF가 임계 시간을 넘었으나 아직 다음 첫 D단을 기다리는 상태다.
 */
data class TripRuleState(
    val trip: TripState = TripState(),
    val continuousDriveMs: Long = Math.multiplyExact(trip.continuousDriveSec, 1_000L),
    val driveSegments: List<DriveSegment> = emptyList(),
    val lastObservation: TripObservation? = null,
    val stopStartedAtMs: Long? = null,
    val continuousBeforeStopMs: Long? = null,
    val offStartedAtMs: Long? = null,
    val pendingNewTrip: Boolean = false,
    val restResetEmitted: Boolean = false,
) {
    init {
        require(continuousDriveMs >= 0) { "정밀 연속 운전 시간은 음수일 수 없습니다." }
        require(trip.continuousDriveSec == continuousDriveMs / 1_000L) {
            "공개 연속 시간과 정밀 연속 시간은 같은 상태여야 합니다."
        }
        require(continuousDriveMs == 0L || trip.runStartMs != null) {
            "연속 운전 시간이 있으면 첫 D단 시작 근거도 필요합니다."
        }
        require(continuousBeforeStopMs == null || continuousBeforeStopMs >= 0) {
            "정차 전 기준 시간은 음수일 수 없습니다."
        }
        require(stopStartedAtMs == null || stopStartedAtMs >= 0) { "정차 시작 시각은 음수일 수 없습니다." }
        require(offStartedAtMs == null || offStartedAtMs >= 0) { "OFF 시작 시각은 음수일 수 없습니다." }
        require((stopStartedAtMs == null) == (continuousBeforeStopMs == null)) {
            "정차 시작과 정차 전 기준값은 함께 보관해야 합니다."
        }
        require(trip.stopStartMs == stopStartedAtMs &&
            trip.continuousDriveSecBeforeStop == continuousBeforeStopMs?.div(1_000L)
        ) {
            "공개 정차 상태와 정밀 정차 상태는 일치해야 합니다."
        }
        if (lastObservation == null) {
            require(continuousDriveMs == 0L && trip.last24hDriveSec == 0L && driveSegments.isEmpty() &&
                trip.runStartMs == null && stopStartedAtMs == null && offStartedAtMs == null &&
                !pendingNewTrip && !restResetEmitted
            ) {
                "관측·구간 없이 카운터만 복원할 수 없습니다. 정밀 운행 상태를 명시해 주세요."
            }
        }
        lastObservation?.let { observation ->
            require(stopStartedAtMs == null || stopStartedAtMs <= observation.tMs) { "정차 시작은 마지막 관측보다 늦을 수 없습니다." }
            require(offStartedAtMs == null || offStartedAtMs <= observation.tMs) { "OFF 시작은 마지막 관측보다 늦을 수 없습니다." }
            require(trip.runStartMs == null || trip.runStartMs <= observation.tMs) { "첫 D단 시각은 마지막 관측보다 늦을 수 없습니다." }
            require(driveSegments.all { it.endMs <= observation.tMs }) { "미래의 운전 구간을 운행 상태에 넣을 수 없습니다." }
        }
        require(driveSegments.zipWithNext().all { (left, right) -> left.endMs <= right.startMs }) {
            "정밀 운행 상태에는 시각순으로 정리한 중복 없는 운전 구간이 필요합니다."
        }
        val historyMs = driveSegments.fold(0L) { total, segment ->
            Math.addExact(total, segment.endMs - segment.startMs)
        }
        val provisionalMs = if (trip.runStartMs != null && stopStartedAtMs != null && lastObservation != null) {
            lastObservation.tMs - stopStartedAtMs
        } else 0L
        require(trip.provisionalStopSec <= provisionalMs / 1_000L) {
            "공개 잠정 정차 시간은 관측된 정차 구간보다 클 수 없습니다."
        }
        // 짧은 정차 포함 여부는 필수 정책에서 정하므로 두 가능한 투영만 허용한다.
        require(trip.last24hDriveSec == historyMs / 1_000L ||
            trip.last24hDriveSec == Math.addExact(historyMs, provisionalMs) / 1_000L
        ) {
            "공개 누적 시간은 보관된 운전 구간·잠정 정차 구간으로 설명할 수 있어야 합니다."
        }
    }
}

sealed interface TripRuleEvent {
    /** 새 운행의 실제 첫 D단 시각이다. P단 목적지 설정 시각과 구분한다. */
    data class NewTrip(val tripId: String, val startedAtMs: Long) : TripRuleEvent

    data class BaselineResetRequested(val tripId: String) : TripRuleEvent

    /** 연속 시간 초기화 통지이며, 휴식 인증·보상·단계 해제를 뜻하지 않는다. */
    data class RestResetReached(val atMs: Long) : TripRuleEvent
}

data class TripRuleResult(val state: TripRuleState, val events: List<TripRuleEvent> = emptyList())
