package com.sleepissue.awakedrive.engine.model

import com.sleepissue.awakedrive.engine.session.TripRulePolicy
import com.sleepissue.awakedrive.engine.session.TripRuleState

/** WALL_EPOCH는 세션 시작 날짜 + 단조 경과 시각이다. 재실행 중 관측 공백은 누적하지 않는다. */
enum class TripTimeBasis { MANUAL_SCENARIO, ELAPSED_REALTIME, WALL_EPOCH }

enum class DriveState { UNKNOWN, DRIVING, PAUSED }

/** [startMs, endMs) 형태의 운행 논리 시간 구간이다. 합산은 RollingDriveWindow가 담당한다. */
data class DriveSegment(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0 && endMs >= startMs) { "운전 구간의 시작·끝 시각을 확인해 주세요." }
    }
}

/**
 * 메모리에 보관할 운행 상태다. 기본값은 관측/판단 완료를 뜻하지 않는다.
 * 정차 구간 보정·최근 24시간 합산·새 운행 판정은 이 자료형이 실행하지 않는다.
 */
data class TripState(
    val tripId: String? = null,
    val continuousDriveSec: Long = 0,
    val last24hDriveSec: Long = 0,
    val baselineRef: String? = null,
    val stage: InterventionStage = InterventionStage.NORMAL,
    val lastLocation: GeoPoint? = null,
    val lastGear: Gear? = null,
    val runStartMs: Long? = null,
    val stopStartMs: Long? = null,
    val driveState: DriveState = DriveState.UNKNOWN,
    val continuousDriveSecBeforeStop: Long? = null,
    val provisionalStopSec: Long = 0,
) {
    init {
        require(tripId == null || tripId.isNotBlank()) { "운행 ID는 비어 있을 수 없습니다." }
        require(baselineRef == null || baselineRef.isNotBlank()) { "기준선 참조는 비어 있을 수 없습니다." }
        require(continuousDriveSec >= 0 && last24hDriveSec >= 0 && provisionalStopSec >= 0) {
            "운전·정차 시간은 음수일 수 없습니다."
        }
        require(continuousDriveSecBeforeStop == null || continuousDriveSecBeforeStop >= 0) {
            "정차 직전 연속 운전 시간은 음수일 수 없습니다."
        }
        require(runStartMs == null || runStartMs >= 0) { "운행 시작 시각은 음수일 수 없습니다." }
        require(stopStartMs == null || stopStartMs >= 0) { "정차 시작 시각은 음수일 수 없습니다." }
    }
}

/** 공개 값만 가진 예전 호출도 표현하되 저장소는 정밀 근거가 없으면 정상 복원을 거부한다. */
data class TripSnapshot(
    val state: TripState,
    val savedAtWallMs: Long,
    val driveSegments: List<DriveSegment> = emptyList(),
    val savedAtElapsedMs: Long? = null,
    val preciseState: TripRuleState? = null,
    val savedAtScenarioMs: Long? = null,
    val rulePolicy: TripRulePolicy? = null,
    val source: Source? = null,
    val timeBasis: TripTimeBasis? = null,
    // 소유자의 버전 있는 복원 자료다. engine은 앱의 실행·직렬화 형식에 의존하지 않는다.
    val ownerState: String? = null,
) {
    init {
        require(savedAtWallMs >= 0) { "저장 날짜 시각은 음수일 수 없습니다." }
        require(savedAtElapsedMs == null || savedAtElapsedMs >= 0) { "저장 경과 시각은 음수일 수 없습니다." }
        require(savedAtScenarioMs == null || savedAtScenarioMs >= 0) { "저장 논리 시각은 음수일 수 없습니다." }
    }
}
