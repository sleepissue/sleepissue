package com.sleepissue.awakedrive.engine.session

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.DriveSegment
import com.sleepissue.awakedrive.engine.model.DriveState
import com.sleepissue.awakedrive.engine.model.Gear
import com.sleepissue.awakedrive.engine.model.PowerState

/**
 * 시간·관측을 받아 다음 운행 상태를 계산한다. SDK·날짜 시계·DB·휴식 인증을 호출하지 않는다.
 * 숫자 경계는 EngineConfig에, 문서 간 미정인 정책 선택은 필수 policy 인자에 둔다.
 */
class TripRules(
    private val config: EngineConfig,
    private val policy: TripRulePolicy,
    private val newTripId: () -> String,
) {
    private val rolling = RollingDriveWindow(config.rollingDriveWindowMs)

    /**
     * 저장 이력은 보존하되 마지막 관측을 현재 입력으로 재사용하지 않는다.
     * 저장 시각에서 관측의 유효 구간을 끝내고, 공백은 UNKNOWN으로 전진한다.
     * 이전 정차/OFF의 연속 증명은 끝난다. 호출자는 원본 저장본을 별도로 표시할 수 있다.
     */
    fun resumeWithUnavailableInput(previous: TripRuleState, resumeAtMs: Long): TripRuleState {
        val savedAt = requireNotNull(previous.lastObservation).tMs
        require(resumeAtMs >= savedAt) { "복원 시각이 저장된 논리 시각보다 이릅니다." }
        val suspended = reduce(previous, TripObservation(savedAt, null, null)).state
        return reduce(suspended, TripObservation(resumeAtMs, null, null)).state
    }

    fun reduce(previous: TripRuleState, input: TripObservation): TripRuleResult {
        val before = previous.lastObservation
        require(before == null || input.tMs >= before.tMs) { "운행 관측 시각이 역행했습니다." }
        if (before == input) return TripRuleResult(previous)

        val events = mutableListOf<TripRuleEvent>()
        val previousMode = before?.mode() ?: ObservationMode.UNKNOWN
        val currentMode = input.mode()
        val elapsedMs = before?.let { input.tMs - it.tMs } ?: 0L
        var trip = previous.trip
        var continuousMs = previous.continuousDriveMs
        var segments = previous.driveSegments
        var stopStart = previous.stopStartedAtMs
        var stopAnchor = previous.continuousBeforeStopMs
        var offStart = previous.offStartedAtMs
        var pendingNewTrip = previous.pendingNewTrip
        var resetEmitted = previous.restResetEmitted
        val hadStarted = trip.runStartMs != null

        // P단 정차의 길이와 실제 OFF의 길이는 서로 다른 타이머다.
        if (before?.power == PowerState.OFF && offStart != null &&
            input.tMs - offStart > config.newTripAfterOffMs
        ) {
            pendingNewTrip = true
        }

        if (hadStarted && previousMode == ObservationMode.DRIVING && !previous.pendingNewTrip && elapsedMs > 0) {
            continuousMs = Math.addExact(continuousMs, elapsedMs)
            segments = segments + DriveSegment(before!!.tMs, input.tMs)
        }

        if (hadStarted && previousMode == ObservationMode.STOPPED && stopStart != null && stopAnchor != null) {
            val durationMs = input.tMs - stopStart
            continuousMs = when {
                durationMs >= config.tripRestResetMs -> 0L
                policy.stopTime == StopTimePolicy.LEGACY_FREEZE -> stopAnchor
                durationMs >= config.tripStopPauseMs -> stopAnchor
                else -> Math.addExact(stopAnchor, durationMs)
            }
            if (durationMs >= config.tripRestResetMs && !resetEmitted) {
                events += TripRuleEvent.RestResetReached(Math.addExact(stopStart, config.tripRestResetMs))
                resetEmitted = true
            }
            // 실제 비정차 관측으로 끝난 짧은 정차만 확정 이력에 넣는다.
            if (currentMode == ObservationMode.DRIVING && durationMs < config.tripStopPauseMs &&
                policy.shortStopAccumulation == ShortStopAccumulationPolicy.INCLUDE && durationMs > 0
            ) {
                segments = segments + DriveSegment(stopStart, input.tMs)
            }
        }

        if (currentMode != ObservationMode.STOPPED) {
            stopStart = null
            stopAnchor = null
            resetEmitted = false
        }
        if (input.power == PowerState.OFF) {
            if (before?.power != PowerState.OFF || offStart == null) offStart = input.tMs
        } else {
            offStart = null
        }

        // 새 ID·기준선 세션은 다음 첫 D단에서 한 번만 만든다. 누적 구간·외부 단계는 유지한다.
        if (currentMode == ObservationMode.DRIVING && input.gear == Gear.D &&
            (trip.runStartMs == null || pendingNewTrip)
        ) {
            val id = newTripId()
            require(id.isNotBlank()) { "새 운행 ID는 비어 있을 수 없습니다." }
            require(id != trip.tripId) { "새 운행 ID는 이전 운행 ID와 달라야 합니다." }
            trip = trip.copy(tripId = id, runStartMs = input.tMs, baselineRef = null)
            continuousMs = 0L
            pendingNewTrip = false
            events += TripRuleEvent.NewTrip(id, input.tMs)
            events += TripRuleEvent.BaselineResetRequested(id)
        }

        if (currentMode == ObservationMode.STOPPED && stopStart == null) {
            stopStart = input.tMs
            stopAnchor = continuousMs
            resetEmitted = false
        }

        val history = rolling.summarize(segments, input.tMs)
        val stopDurationMs = stopStart?.let { input.tMs - it } ?: 0L
        val includeProvisionalStop = trip.runStartMs != null && currentMode == ObservationMode.STOPPED &&
            policy.shortStopAccumulation == ShortStopAccumulationPolicy.INCLUDE &&
            stopDurationMs > 0 && stopDurationMs < config.tripStopPauseMs
        val total = if (includeProvisionalStop) {
            rolling.summarize(history.normalizedSegments + DriveSegment(stopStart!!, input.tMs), input.tMs)
        } else history

        val state = previous.copy(
            trip = trip.copy(
                continuousDriveSec = continuousMs / 1_000L,
                last24hDriveSec = total.seconds,
                // 마지막 유효 선택기어는 이력이다. 현재 관측 판정에는 input.gear만 쓴다.
                lastGear = input.gear ?: trip.lastGear,
                driveState = when {
                    currentMode == ObservationMode.STOPPED -> DriveState.PAUSED
                    currentMode == ObservationMode.DRIVING && trip.runStartMs != null && !pendingNewTrip -> DriveState.DRIVING
                    else -> DriveState.UNKNOWN
                },
                stopStartMs = stopStart,
                continuousDriveSecBeforeStop = stopAnchor?.div(1_000L),
                provisionalStopSec = if (trip.runStartMs != null && policy.stopTime == StopTimePolicy.V03_RETROACTIVE &&
                    stopDurationMs < config.tripStopPauseMs
                ) stopDurationMs / 1_000L else 0L,
            ),
            continuousDriveMs = continuousMs,
            driveSegments = history.normalizedSegments,
            lastObservation = input,
            stopStartedAtMs = stopStart,
            continuousBeforeStopMs = stopAnchor,
            offStartedAtMs = offStart,
            pendingNewTrip = pendingNewTrip,
            restResetEmitted = resetEmitted,
        )
        return TripRuleResult(state, events)
    }

    private enum class ObservationMode { DRIVING, STOPPED, UNKNOWN }

    private fun TripObservation.mode(): ObservationMode = when {
        power == PowerState.OFF || gear == Gear.P -> ObservationMode.STOPPED
        gear == Gear.D || gear == Gear.R || gear == Gear.N -> ObservationMode.DRIVING
        else -> ObservationMode.UNKNOWN
    }
}
