package com.sleepissue.awakedrive.engine.model

enum class DriveState { UNKNOWN, DRIVING, PAUSED }

/** [startMs, endMs) 형태의 운행 논리 시간 구간이다. 구간 합산 규칙은 후속 구현 대상이다. */
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

/** 저장 날짜 시각과 부팅 내 실제 경과 시각을 구분한다. 저장·복원 자체는 아직 수행하지 않는다. */
data class TripSnapshot(
    val state: TripState,
    val savedAtWallMs: Long,
    val driveSegments: List<DriveSegment> = emptyList(),
    val savedAtElapsedMs: Long? = null,
) {
    init {
        require(savedAtWallMs >= 0) { "저장 날짜 시각은 음수일 수 없습니다." }
        require(savedAtElapsedMs == null || savedAtElapsedMs >= 0) { "저장 경과 시각은 음수일 수 없습니다." }
    }
}
