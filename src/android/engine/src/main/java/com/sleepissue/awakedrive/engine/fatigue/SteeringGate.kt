package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.FatigueConfig

/**
 * 게이트가 한 프레임을 판단하는 데 필요한 값만 모은 입력.
 *
 * 1번의 SignalFrame(MSG-01)·NaviState(MSG-14)와 2번의 AssistState(MSG-04)에서 만든다.
 * 그 클래스들이 main에 들어오면 변환 함수 하나(SignalFrame → GateInput)를 추가한다.
 * null은 "지금 이 신호를 모른다"는 뜻이다.
 */
data class GateInput(
    /** 프레임 시각(ms). 주입된 Clock 기준, 데모 중에는 시나리오 시계 */
    val tMs: Long,
    /** 조향각(°) */
    val steeringDeg: Float?,
    /** 차속(km/h). 1번이 m/s → km/h로 바꿔서 준다 (REQ_SIG_002) */
    val speedKph: Float?,
    /** 기어가 D단인지 */
    val inDrive: Boolean?,
    /** 방향지시등이 켜져 있는지 (좌·우, 비상등을 켜짐으로 볼지는 변환 함수에서 정함) */
    val turnSignalOn: Boolean?,
    /** 브레이크를 밟고 있는지 */
    val brakeOn: Boolean?,
    /** 다음 회전 지점까지 거리(m). 목적지가 없거나 내비가 없으면 null (REQ_FAT_004) */
    val distToNextTurnM: Float?,
    /** 보조 주행 모드인지 (AssistState.assistMode) */
    val assistMode: Boolean,
)

/** 게이트 판정 이유. PASS만 조향 지표 계산에 쓴다. */
enum class GateReason {
    /** 통과 */
    PASS,

    /** 보조 주행 모드 */
    ASSIST,

    /** 조향각·차속·기어·방향지시등·브레이크 중 모르는 값(null)이 있음 */
    NO_SIGNAL,

    /** 시속 60 km 미만 (REQ_FAT_001) */
    LOW_SPEED,

    /** D단이 아님 (REQ_FAT_001) */
    NOT_DRIVE,

    /** 회전 지점까지 300 m 미만 (REQ_FAT_004) */
    NEAR_TURN,

    /** 회전 지점 거리를 몰라서, 고속 5분 유지를 기다리는 중 (REQ_FAT_004) */
    TURN_UNKNOWN_HOLD,

    /** 방향지시등 작동 앞뒤 10초 (REQ_FAT_002) */
    TURN_SIGNAL,

    /** 브레이크 작동 앞뒤 5초 (REQ_FAT_003) */
    BRAKE,
}

/** 확정된 프레임 판정. */
data class GateDecision(
    val tMs: Long,
    val steeringDeg: Float?,
    val reason: GateReason,
) {
    val passed: Boolean get() = reason == GateReason.PASS
}

/**
 * 조향 게이트: 조향 지표를 계산해도 되는 프레임만 통과시킨다. (REQ_FAT_001~004)
 *
 * 통과 조건 (모두 만족해야 함)
 * - 보조 주행 모드가 아님
 * - 조향각·차속·기어·방향지시등·브레이크를 모두 알고 있음
 * - 시속 60 km 이상, D단
 * - 회전 지점 거리를 알면 300 m 이상, 모르면 시속 60 km 이상을 5분 연속 유지한 뒤
 * - 방향지시등 작동 앞뒤 10초, 브레이크 작동 앞뒤 5초가 아님
 *
 * **판정이 늦게 확정된다.** 방향지시등을 켜기 10초 '전' 프레임도 빼야 하므로(REQ_FAT_002 비고: 소급 제외),
 * 각 프레임은 그 뒤 10초를 보고 나서야 확정된다. [push]는 그때 확정된 프레임만 돌려준다.
 *
 * 프레임은 시각 순서대로 넣어야 한다. 앞 프레임보다 이른 프레임은 버린다.
 * ROS로 치면 신호 토픽을 구독해 쓸 수 있는 프레임만 10초 늦게 다시 발행하는 필터 노드다.
 */
class SteeringGate(private val config: FatigueConfig = FatigueConfig()) {

    private class Pending(val input: GateInput, val immediateReason: GateReason)

    /** 아직 확정하지 않은 프레임 (최대 10초 분량) */
    private val pending = ArrayDeque<Pending>()

    /** 판정을 확정하기 전에 기다리는 시간 = 방향지시등·브레이크 여유 중 긴 쪽 */
    private val decisionDelayMs = maxOf(config.turnSignalMarginMs, config.brakeMarginMs)

    private var lastFrameMs: Long? = null

    /** 이미 확정한 프레임 가운데 마지막으로 방향지시등·브레이크가 켜져 있던 시각 */
    private var lastTurnOnMs: Long? = null
    private var lastBrakeOnMs: Long? = null

    /** '고속 5분 유지' 상태 (REQ_FAT_004) */
    private var fastSinceMs: Long? = null
    private var slowSinceMs: Long? = null

    /**
     * 프레임 하나를 넣고, 그 결과 확정된 판정을 시각 순서대로 돌려준다.
     * 처음 10초 동안은 빈 목록이 나온다.
     */
    fun push(input: GateInput): List<GateDecision> {
        val last = lastFrameMs
        if (last != null && input.tMs < last) return emptyList()
        if (last != null && input.tMs - last > config.gateMaxFrameGapMs) resetSpeedHold()
        lastFrameMs = input.tMs

        updateSpeedHold(input)
        pending.addLast(Pending(input, immediateReason(input)))
        return finalizeUpTo(input.tMs - decisionDelayMs)
    }

    /**
     * 남은 프레임을 모두 확정한다. 운행이 끝날 때만 쓴다.
     * 뒤 10초를 보지 못한 프레임은 그 사이 방향지시등·브레이크가 없었다고 보고 확정한다.
     */
    fun flush(): List<GateDecision> = finalizeUpTo(Long.MAX_VALUE)

    /** 새 운행이나 데모 처음부터 다시 재생할 때 모든 상태를 지운다. */
    fun reset() {
        pending.clear()
        lastFrameMs = null
        lastTurnOnMs = null
        lastBrakeOnMs = null
        resetSpeedHold()
    }

    /** 그 프레임만 보고 바로 알 수 있는 판정. 방향지시등·브레이크 앞뒤 여유는 확정할 때 본다. */
    private fun immediateReason(input: GateInput): GateReason {
        val speed = input.speedKph
        val inDrive = input.inDrive
        val dist = input.distToNextTurnM
        return when {
            input.assistMode -> GateReason.ASSIST
            input.steeringDeg == null || speed == null || inDrive == null ||
                input.turnSignalOn == null || input.brakeOn == null -> GateReason.NO_SIGNAL
            speed < config.gateMinSpeedKph -> GateReason.LOW_SPEED
            !inDrive -> GateReason.NOT_DRIVE
            dist != null && dist < config.minDistToNextTurnM -> GateReason.NEAR_TURN
            dist == null && !speedHoldSatisfied(input.tMs) -> GateReason.TURN_UNKNOWN_HOLD
            else -> GateReason.PASS
        }
    }

    /**
     * 고속 유지 시간을 센다 (REQ_FAT_004).
     * 시속 60 km 미만이 30초 '넘게' 이어지면 처음부터 다시 센다. 30초 이하면 그대로 이어서 센다 [해석].
     */
    private fun updateSpeedHold(input: GateInput) {
        val speed = input.speedKph
        val fast = speed != null && speed >= config.gateMinSpeedKph
        if (fast) {
            if (fastSinceMs == null) fastSinceMs = input.tMs
            slowSinceMs = null
        } else {
            val slowSince = slowSinceMs ?: input.tMs.also { slowSinceMs = it }
            if (input.tMs - slowSince > config.unknownTurnLowSpeedGraceMs) fastSinceMs = null
        }
    }

    private fun speedHoldSatisfied(tMs: Long): Boolean {
        val since = fastSinceMs ?: return false
        return tMs - since >= config.unknownTurnHoldMs
    }

    private fun resetSpeedHold() {
        fastSinceMs = null
        slowSinceMs = null
    }

    private fun finalizeUpTo(limitMs: Long): List<GateDecision> {
        val out = ArrayList<GateDecision>()
        while (pending.isNotEmpty() && pending.first().input.tMs <= limitMs) {
            val current = pending.removeFirst()
            val t = current.input.tMs
            if (current.input.turnSignalOn == true) lastTurnOnMs = t
            if (current.input.brakeOn == true) lastBrakeOnMs = t

            val reason = when {
                current.immediateReason != GateReason.PASS -> current.immediateReason
                nearActivation(t, lastTurnOnMs, config.turnSignalMarginMs) { it.turnSignalOn == true } ->
                    GateReason.TURN_SIGNAL
                nearActivation(t, lastBrakeOnMs, config.brakeMarginMs) { it.brakeOn == true } ->
                    GateReason.BRAKE
                else -> GateReason.PASS
            }
            out += GateDecision(t, current.input.steeringDeg, reason)
        }
        return out
    }

    /**
     * 시각 [t]가 작동 시각에서 [marginMs] 이내인지. 경계 포함:
     * 작동 100.0~103.0 s, 여유 10 s → 90.0~113.0 s 제외, 89.9 s·113.1 s 포함 (REQ_FAT_002 합격기준)
     */
    private inline fun nearActivation(
        t: Long,
        lastOnMs: Long?,
        marginMs: Long,
        isOn: (GateInput) -> Boolean,
    ): Boolean {
        // 앞쪽: 이미 확정한 프레임(지금 프레임 포함) 가운데 마지막 작동
        if (lastOnMs != null && t - lastOnMs <= marginMs) return true
        // 뒤쪽: 아직 확정하지 않은 프레임 가운데 여유 안의 작동
        for (later in pending) {
            if (later.input.tMs - t > marginMs) break
            if (isOn(later.input)) return true
        }
        return false
    }
}
