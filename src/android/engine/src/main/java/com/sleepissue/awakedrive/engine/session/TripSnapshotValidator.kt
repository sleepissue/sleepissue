package com.sleepissue.awakedrive.engine.session

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.*

/** DB나 Android를 모르고 저장된 공개 값·정밀 근거·구간의 일관성을 확인한다. */
object TripSnapshotValidator {
    fun requireValid(snapshot: TripSnapshot, config: EngineConfig = EngineConfig()): TripRuleState {
        val state = requireNotNull(snapshot.preciseState) { "정밀 운행 상태가 없습니다." }
        val now = requireNotNull(snapshot.savedAtScenarioMs) { "논리 저장 시각이 없습니다." }
        val policy = requireNotNull(snapshot.rulePolicy) { "운행 계산 정책이 없습니다." }
        requireNotNull(snapshot.source) { "입력 출처가 없습니다." }
        requireNotNull(snapshot.timeBasis) { "운행 시간축이 없습니다." }
        require(state.trip == snapshot.state && state.driveSegments == snapshot.driveSegments) {
            "공개 상태·정밀 상태·운전 구간이 다른 저장 세대입니다."
        }
        require((state.lastObservation?.tMs ?: 0L) == now) { "관측 시각과 논리 저장 시각이 다릅니다." }
        val summary = RollingDriveWindow(config.rollingDriveWindowMs).summarize(state.driveSegments, now)
        require(summary.normalizedSegments == state.driveSegments) { "운전 구간이 정규화되지 않았습니다." }
        val observation = state.lastObservation
        val stopped = observation?.let { it.gear == Gear.P || it.power == PowerState.OFF } == true
        require((state.stopStartedAtMs != null) == stopped) { "정차 근거와 마지막 관측이 다릅니다." }
        require(state.offStartedAtMs == null || observation?.power == PowerState.OFF) { "OFF 근거와 마지막 관측이 다릅니다." }
        require(observation?.power != PowerState.OFF || state.offStartedAtMs != null) { "OFF 시작 근거가 없습니다." }
        val duration = state.stopStartedAtMs?.let { now - it } ?: 0L
        val provisional = if (state.trip.runStartMs != null && stopped &&
            policy.stopTime == StopTimePolicy.V03_RETROACTIVE && duration < config.tripStopPauseMs) duration else 0L
        require(state.trip.provisionalStopSec == provisional / 1_000L) { "잠정 정차 시간이 계산 근거와 다릅니다." }
        if (stopped) {
            val anchor = requireNotNull(state.continuousBeforeStopMs)
            val expected = when {
                state.trip.runStartMs == null || duration >= config.tripRestResetMs -> 0L
                policy.stopTime == StopTimePolicy.LEGACY_FREEZE || duration >= config.tripStopPauseMs -> anchor
                else -> Math.addExact(anchor, duration)
            }
            require(state.continuousDriveMs == expected) { "연속 시간이 정차 전 기준·경과와 다릅니다." }
        }
        val includedStop = if (state.trip.runStartMs != null && stopped && duration < config.tripStopPauseMs &&
            policy.shortStopAccumulation == ShortStopAccumulationPolicy.INCLUDE) duration else 0L
        require(state.trip.last24hDriveSec == Math.addExact(summary.durationMs, includedStop) / 1_000L) {
            "최근 24시간 합계가 운전 구간과 다릅니다."
        }
        val expectedMode = when {
            stopped -> DriveState.PAUSED
            observation?.gear in setOf(Gear.D, Gear.R, Gear.N) && state.trip.runStartMs != null && !state.pendingNewTrip -> DriveState.DRIVING
            else -> DriveState.UNKNOWN
        }
        require(state.trip.driveState == expectedMode) { "운전 상태와 정밀 관측이 다릅니다." }
        return state
    }
}
