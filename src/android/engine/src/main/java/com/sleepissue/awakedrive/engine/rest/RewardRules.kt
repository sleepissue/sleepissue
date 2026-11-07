package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.GeoPoint
import com.sleepissue.awakedrive.engine.model.InterventionStage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 지난 휴식 인증 기록 한 줄 (RewardLedger가 Room에서 읽어 넘긴다). */
data class RewardHistoryEntry(
    val areaId: String,
    val location: GeoPoint,
    val certifiedAtWallMs: Long,
    val rewarded: Boolean,
)

enum class RewardOutcome {
    AWARDED,
    NOT_ELIGIBLE,         // 2시간 운전도, 2단계 이상 알림도 없었음
    DAILY_LIMIT,          // 오늘 이미 4회
    SAME_AREA_COOLDOWN,   // 같은 쉼터를 2시간 안에 다시
    TOO_CLOSE_TO_LAST,    // 오늘 직전 인정 쉼터에서 30 km 안
}

data class RewardDecision(
    val outcome: RewardOutcome,
    val eligibleByDrive: Boolean,
    val eligibleByStage: Boolean,
) {
    val awarded: Boolean get() = outcome == RewardOutcome.AWARDED
}

/**
 * [4번] 휴식 보상 규칙 (기획서 7-3, SRS RWD). 인증된 휴식 가운데 스탬프를 줄지 정한다.
 *
 * 보상 대상 (하나라도): ① 직전 휴식(또는 출발) 뒤 2시간 이상 운전 ② 2단계 이상 알림 뒤
 * 악용 방지 (모두): 하루 4회 / 같은 쉼터 2시간 안 재인정 금지 / 직전 인정 뒤 30 km 이상 이동
 *
 * - 시간은 날짜 시각(wall)으로 잰다. 기록이 재부팅을 넘어 남기 때문이다. [가정]
 * - '하루'는 [zone] 기준 달력 날짜다.
 * - 30 km는 주행 거리 대신 직전 인정 쉼터와의 직선 거리로 잰다(직선 ≤ 주행이므로 더 엄격). [가정]
 *   날짜를 넘겨서까지 적용하면 매일 같은 쉼터를 쓰는 사람이 영영 못 받으므로 같은 날 안에서만 적용한다. [가정]
 */
class RewardRules(
    private val config: EngineConfig = EngineConfig(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    fun evaluate(
        areaId: String,
        location: GeoPoint,
        certifiedAtWallMs: Long,
        driveSecBeforeRest: Long?,
        maxStageBeforeRest: InterventionStage,
        history: List<RewardHistoryEntry>,
    ): RewardDecision {
        val byDrive = driveSecBeforeRest != null && driveSecBeforeRest >= config.rewardMinDriveSec
        val byStage = maxStageBeforeRest.level >= config.rewardMinStageLevel
        fun decide(outcome: RewardOutcome) = RewardDecision(outcome, byDrive, byStage)

        if (!byDrive && !byStage) return decide(RewardOutcome.NOT_ELIGIBLE)

        val today = day(certifiedAtWallMs)
        val rewardedBefore = history.filter { it.rewarded && it.certifiedAtWallMs <= certifiedAtWallMs }
        val rewardedToday = rewardedBefore.filter { day(it.certifiedAtWallMs) == today }
        if (rewardedToday.size >= config.rewardDailyMax) return decide(RewardOutcome.DAILY_LIMIT)

        val sameAreaRecent = rewardedBefore.any {
            it.areaId == areaId && certifiedAtWallMs - it.certifiedAtWallMs < config.rewardSameAreaCooldownMs
        }
        if (sameAreaRecent) return decide(RewardOutcome.SAME_AREA_COOLDOWN)

        val lastToday = rewardedToday.maxByOrNull { it.certifiedAtWallMs }
        if (lastToday != null && GeoMath.distanceM(lastToday.location, location) < config.rewardMinMoveM) {
            return decide(RewardOutcome.TOO_CLOSE_TO_LAST)
        }
        return decide(RewardOutcome.AWARDED)
    }

    private fun day(wallMs: Long): LocalDate = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()
}
