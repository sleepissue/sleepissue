package com.sleepissue.awakedrive.engine.config

import com.sleepissue.awakedrive.engine.model.AgeBand
import com.sleepissue.awakedrive.engine.model.VulnerableTimeRange

/**
 * 취약 시간대 기본값을 한 곳에 둔다. (기획서 4-5 맥락 가중 표, REQ_FAT_015, REQ_FCS_005)
 *
 * 팀 [EngineConfig]의 `vulnerableBaseRanges`·`vulnerableAgeRanges`(예보·1단계 안내)와
 * 2번 [ContextWeightConfig](맥락 가중 K)가 이 값을 기본값으로 같이 쓴다.
 * 시간대를 바꿀 때는 EngineConfig 값만 바꾼다. `FatigueEngine(EngineConfig)`가 그 값을 K 계산에 넣는다.
 */
object VulnerableTimeDefaults {

    /** 기본 취약 시간대: 새벽 2~7시, 오후 2~5시 (Horne & Reyner 1995, 도로교통공단) */
    val BASE: List<VulnerableTimeRange> = listOf(
        VulnerableTimeRange.hours(2, 7),
        VulnerableTimeRange.hours(14, 17),
    )

    /** 연령대를 설정하면 기본 시간대에 더하는 시간대 (REQ_FAT_015) */
    val BY_AGE: Map<AgeBand, List<VulnerableTimeRange>> = mapOf(
        AgeBand.TWENTIES to listOf(VulnerableTimeRange.hours(4, 8)),
        AgeBand.THIRTIES_FORTIES to listOf(VulnerableTimeRange.hours(22, 2)),
        AgeBand.FIFTIES_PLUS to listOf(VulnerableTimeRange.hours(14, 18)),
    )
}
