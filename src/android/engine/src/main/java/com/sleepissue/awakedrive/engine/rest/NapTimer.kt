package com.sleepissue.awakedrive.engine.rest

import com.sleepissue.awakedrive.engine.config.EngineConfig
import com.sleepissue.awakedrive.engine.model.Gear

enum class NapRejectReason { NOT_PARKED, ALREADY_RUNNING }

sealed interface NapStart {
    /**
     * @param needsSafetyNotice 쉼터·휴게소가 아닌 곳 → "안전한 곳에 세웠는지 확인해 주세요"를 먼저 안내
     */
    data class Started(val startedAtMs: Long, val endsAtMs: Long, val needsSafetyNotice: Boolean) : NapStart
    data class Rejected(val reason: NapRejectReason) : NapStart
}

enum class NapEndReason {
    ALARM_STOPPED,  // 알람이 울린 뒤 운전자가 껐다
    WOKE_EARLY,     // 알람 전에 [지금 깨우기]
    ALARM_TIMEOUT,  // 아무도 안 꺼서 한도가 지나 멈췄다
    DROVE_OFF,      // P단이 아닌 단으로 바꿨다
}

sealed interface NapEvent {
    data object AlarmDue : NapEvent
    /** @param recoveryCheck true면 앱이 회복 확인("출발 전에 확인할게요")을 이어서 한다 */
    data class Ended(val reason: NapEndReason, val recoveryCheck: Boolean) : NapEvent
}

sealed interface NapState {
    data object Idle : NapState
    data class Running(val startedAtMs: Long, val endsAtMs: Long) : NapState
    data class Alarming(val startedAtMs: Long, val alarmAtMs: Long) : NapState
}

/**
 * [4번] 파워냅 15분 타이머 (기획서 7-1, SRS RES 파워냅).
 *
 * 운전자가 "잠깐 잘게"라고 말하거나 버튼을 누를 때만 시작한다. P단(정차)이 아니면 거절한다.
 * 앱은 실제로 잤는지 판단하지 않는다. 쉼터에서 P단이면 휴식 인증은 [RestCertifier]가 따로 한다.
 */
class NapTimer(private val config: EngineConfig = EngineConfig()) {

    var state: NapState = NapState.Idle
        private set

    fun start(nowMs: Long, gear: Gear?, atRestArea: Boolean): NapStart {
        if (state !is NapState.Idle) return NapStart.Rejected(NapRejectReason.ALREADY_RUNNING)
        if (gear != Gear.P) return NapStart.Rejected(NapRejectReason.NOT_PARKED)
        val endsAt = nowMs + config.napDurationMs
        state = NapState.Running(nowMs, endsAt)
        return NapStart.Started(nowMs, endsAt, needsSafetyNotice = !atRestArea)
    }

    /** 주기적으로 부른다. gear가 null(시동 꺼짐 등 못 받음)이면 단을 바꾼 것으로 보지 않는다. */
    fun tick(nowMs: Long, gear: Gear?): NapEvent? {
        val drove = gear != null && gear != Gear.P
        return when (val s = state) {
            NapState.Idle -> null
            is NapState.Running -> when {
                drove -> end(NapEndReason.DROVE_OFF)
                nowMs >= s.endsAtMs -> {
                    state = NapState.Alarming(s.startedAtMs, nowMs)
                    NapEvent.AlarmDue
                }
                else -> null
            }
            is NapState.Alarming -> when {
                drove -> end(NapEndReason.DROVE_OFF)
                nowMs - s.alarmAtMs >= config.napAlarmMaxMs -> end(NapEndReason.ALARM_TIMEOUT)
                else -> null
            }
        }
    }

    /** [지금 깨우기] 또는 알람 끄기. */
    fun wake(): NapEvent? = when (state) {
        NapState.Idle -> null
        is NapState.Running -> end(NapEndReason.WOKE_EARLY)
        is NapState.Alarming -> end(NapEndReason.ALARM_STOPPED)
    }

    fun remainingMs(nowMs: Long): Long? = (state as? NapState.Running)?.let { (it.endsAtMs - nowMs).coerceAtLeast(0) }

    private fun end(reason: NapEndReason): NapEvent {
        state = NapState.Idle
        // 떠나 버린 경우에는 정차 중 회복 확인을 할 수 없다. 1번의 주행 중 확인이 이어받는다.
        return NapEvent.Ended(reason, recoveryCheck = reason != NapEndReason.DROVE_OFF)
    }
}
