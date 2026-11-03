package com.sleepissue.awakedrive.engine.assist

import com.sleepissue.awakedrive.engine.config.AssistConfig
import com.sleepissue.awakedrive.engine.model.AssistReason
import com.sleepissue.awakedrive.engine.model.AssistState
import com.sleepissue.awakedrive.engine.model.DriveFrame

/**
 * 보조 주행 모드 판정, 인계 품질 감시, 조작 공백. (REQ_AST_001·003·004·006·007·008)
 *
 * - 크루즈 작동 중이면 보조 주행(CRUISE). 꺼지면 바로 일반 모드로 돌아가고 30초 인계 감시를 시작한다 (REQ_AST_001)
 * - 조향 값을 못 받으면 보조 주행으로 간주(UNCERTAIN), 다시 들어오면 그 프레임부터 일반 모드 (REQ_AST_003)
 * - 크루즈 값을 못 받으면 일반 모드 (REQ_AST_008 [제안]). 매끈한 조향 추정(REQ_AST_002)은 2차
 * - 보조 주행 중 정기 확인 간격(REQ_AST_005)과 조작 공백 문턱 15분(REQ_AST_006)은 1번 InterventionPolicy가
 *   [AssistState.assistMode]·[AssistState.opGapSec]를 보고 정한다
 *
 * 프레임은 게이트를 거치지 않은 원래 값으로 받는다. 인계 감시에 쓰는 급조향·큰 수정은 FatigueEngine이
 * 보조 주행만 빼고 거른 판정에서 찾아 넣는다.
 */
class AssistModeDetector(private val config: AssistConfig = AssistConfig()) {

    /** 지금 보조 주행 상태 (MSG-04). 값이 바뀔 때만 새 객체가 된다 */
    var state: AssistState = AssistState()
        private set

    private var reason = AssistReason.NONE
    private var nowMs = Long.MIN_VALUE

    // 조작 공백 (REQ_AST_006)
    private var lastOperationMs: Long? = null
    private var prevCruise: Boolean? = null
    private var prevTargetKph: Float? = null
    private var prevVentilation: Int? = null

    // 인계 감시 (REQ_AST_007): (감시 시작, 감시 끝] 안의 사건만 본다
    private var watchStartMs: Long? = null
    private var watchEndMs: Long? = null
    private var watchLargeCount = 0
    private var watchFlagged = false
    private var abnormalUntilMs: Long? = null

    /** 프레임마다 부른다. 이번 프레임의 보조 주행 판정을 돌려준다. */
    internal fun update(frame: DriveFrame): AssistState {
        val t = frame.tMs
        nowMs = maxOf(nowMs, t)
        val next = when {
            frame.cruiseActive == true -> AssistReason.CRUISE
            frame.steeringDeg == null -> AssistReason.UNCERTAIN
            else -> AssistReason.NONE
        }
        // 크루즈로 들어간 보조 주행이 끝나면 인계 감시 시작. 조향 신호가 돌아온 것(UNCERTAIN → NONE)은 인계가 아니다 [해석]
        if (reason == AssistReason.CRUISE && next != AssistReason.CRUISE) startWatch(t)
        // 감시 중에 크루즈를 다시 켜면 감시를 거기서 끝낸다
        if (reason != AssistReason.CRUISE && next == AssistReason.CRUISE) endWatch(t)
        reason = next

        if (lastOperationMs == null || isOperation(frame)) lastOperationMs = t
        frame.cruiseActive?.let { prevCruise = it }
        frame.cruiseTargetKph?.let { prevTargetKph = it }
        frame.ventilationLevel?.let { prevVentilation = it }

        refresh()
        return state
    }

    /**
     * 보조 주행만 빼고 거른 조향에서 멈춤 뒤 급조향이 확인됐을 때 (REQ_AST_007).
     * 인계 감시 안이면 인계 품질 이상이다. 이번 해제에서 처음 이상이 되면 true.
     * @param frameNowMs 지금 프레임 시각. 이상 표시를 이 시각부터 30초 유지한다
     */
    internal fun onSafetySudden(eventMs: Long, frameNowMs: Long): Boolean {
        if (watchFlagged || !inWatch(eventMs)) return false
        return flag(frameNowMs)
    }

    /**
     * 같은 조향에서 큰 수정(5° 이상)이 확인됐을 때 (REQ_AST_007).
     * 감시 안의 큰 수정이 평소 해제 때의 1.5배 이상(기록이 없으면 3회 이상)이 되면 인계 품질 이상.
     * @param usualHandoverLargeCount 평소 해제 직후 30초 큰 수정 횟수. 기록이 없으면 null
     */
    internal fun onSafetyLargeSwing(endMs: Long, frameNowMs: Long, usualHandoverLargeCount: Float?): Boolean {
        if (watchFlagged || !inWatch(endMs)) return false
        watchLargeCount++
        val enough = if (usualHandoverLargeCount == null) {
            watchLargeCount >= config.handoverLargeCountNoRecord
        } else {
            // 평소가 0회면 1회만 나와도 늘어난 것 [해석]
            watchLargeCount >= 1 &&
                watchLargeCount.toDouble() >= config.handoverLargeRatio * usualHandoverLargeCount.toDouble() - RATIO_EPS
        }
        return if (enough) flag(frameNowMs) else false
    }

    /** 새 운행이나 데모 처음부터 다시 재생할 때 모두 지운다. */
    internal fun reset() {
        reason = AssistReason.NONE
        nowMs = Long.MIN_VALUE
        lastOperationMs = null
        prevCruise = null
        prevTargetKph = null
        prevVentilation = null
        watchStartMs = null
        watchEndMs = null
        watchLargeCount = 0
        watchFlagged = false
        abnormalUntilMs = null
        state = AssistState()
    }

    /** 방향지시등·브레이크·크루즈 켜고 끄기·크루즈 속도·통풍 조작. 공조(온도 설정) 조작은 신호가 정해지면 추가 [해석] */
    private fun isOperation(frame: DriveFrame): Boolean {
        val cruise = frame.cruiseActive
        val target = frame.cruiseTargetKph
        val ventilation = frame.ventilationLevel
        val cruiseToggled = cruise != null && prevCruise != null && cruise != prevCruise
        val targetChanged = target != null && prevTargetKph != null && target != prevTargetKph
        val ventilationChanged = ventilation != null && prevVentilation != null && ventilation != prevVentilation
        return frame.turnSignalOn == true || frame.brakeOn == true || cruiseToggled || targetChanged || ventilationChanged
    }

    private fun startWatch(tMs: Long) {
        watchStartMs = tMs
        watchEndMs = tMs + config.handoverWatchMs
        watchLargeCount = 0
        watchFlagged = false
    }

    private fun endWatch(tMs: Long) {
        val end = watchEndMs ?: return
        watchEndMs = minOf(end, tMs)
    }

    /** 해제 뒤 30.0 s까지 포함: 29 s → 감시 안, 31 s → 밖 (REQ_AST_007) [해석: 30.0 s 포함] */
    private fun inWatch(eventMs: Long): Boolean {
        val start = watchStartMs ?: return false
        val end = watchEndMs ?: return false
        return eventMs > start && eventMs <= end
    }

    private fun flag(frameNowMs: Long): Boolean {
        watchFlagged = true
        // 받는 쪽이 놓치지 않게 30초 동안 true로 둔다 [해석]
        abnormalUntilMs = frameNowMs + config.handoverWatchMs
        refresh()
        return true
    }

    private fun refresh() {
        val lastOperation = lastOperationMs
        val gapSec = if (lastOperation == null) {
            0
        } else {
            ((nowMs - lastOperation) / MS_PER_SECOND).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
        val until = abnormalUntilMs
        val next = AssistState(
            assistMode = reason != AssistReason.NONE,
            reason = reason,
            handoverAbnormal = until != null && nowMs < until,
            opGapSec = gapSec,
        )
        if (next != state) state = next
    }

    private companion object {
        /** 1.5배 경계에서 Float 오차로 판정이 뒤집히지 않게 하는 여유 */
        const val RATIO_EPS = 1e-6

        /**
         * MSG-04 opGapSec이 초 단위라 프레임 시각 차(ms)를 초로 센다.
         * 바깥 신호의 단위를 바꾸는 것(REQ_SIG_010이 막는 것)이 아니라 엔진이 잰 경과 시간을 메시지 단위로 내는 것이다.
         */
        const val MS_PER_SECOND = 1_000L
    }
}
