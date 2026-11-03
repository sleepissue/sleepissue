package com.sleepissue.awakedrive.engine.fatigue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 조향 게이트 시험. 기대값은 SRS v0.2 REQ_FAT_001~004 합격기준을 그대로 옮겼다.
 * 프레임은 실제 신호처럼 100 ms 간격으로 넣는다.
 */
class SteeringGateTest {

    /** 기본은 "통과하는 프레임": 시속 80 km, D단, 방향지시등·브레이크 꺼짐, 회전 지점 1 km 앞 */
    private fun frame(
        tMs: Long,
        speedKph: Float? = 80f,
        inDrive: Boolean? = true,
        turnSignalOn: Boolean? = false,
        brakeOn: Boolean? = false,
        distToNextTurnM: Float? = 1_000f,
        assistMode: Boolean = false,
        steeringDeg: Float? = 0f,
    ) = GateInput(tMs, steeringDeg, speedKph, inDrive, turnSignalOn, brakeOn, distToNextTurnM, assistMode)

    /** 0 ~ [endMs]까지 100 ms 간격 시각 */
    private fun times(endMs: Long, startMs: Long = 0L): List<Long> = (startMs..endMs step 100L).toList()

    /** 프레임을 모두 넣고 끝에 남은 것까지 확정한 뒤 시각별 판정으로 돌려준다 */
    private fun run(frames: List<GateInput>): Map<Long, GateReason> {
        val gate = SteeringGate()
        val decisions = frames.flatMap { gate.push(it) } + gate.flush()
        assertEquals("프레임마다 판정이 정확히 하나씩 나와야 함", frames.size, decisions.size)
        return decisions.associate { it.tMs to it.reason }
    }

    // ── REQ_FAT_001: 시속 60 km 이상 + D단 ──

    @Test
    fun speedBoundaryAt60Kph() {
        // 59.9 km/h → 제외, 60.0 km/h → 포함
        val result = run(listOf(frame(0, speedKph = 59.9f), frame(100, speedKph = 60.0f)))
        assertEquals(GateReason.LOW_SPEED, result[0L])
        assertEquals(GateReason.PASS, result[100L])
    }

    @Test
    fun onlyDriveGearPasses() {
        // P·R·N 단 → 제외, D단 → 포함
        val result = run(listOf(frame(0, inDrive = false), frame(100, inDrive = true)))
        assertEquals(GateReason.NOT_DRIVE, result[0L])
        assertEquals(GateReason.PASS, result[100L])
    }

    // ── REQ_FAT_002: 방향지시등 앞뒤 10초 ──

    @Test
    fun turnSignalExcludesTenSecondsBeforeAndAfter() {
        // 작동 100.0~103.0 s → 제외 구간 90.0~113.0 s, 89.9 s·113.1 s 샘플은 포함
        val frames = times(130_000).map { t -> frame(t, turnSignalOn = t in 100_000L..103_000L) }
        val result = run(frames)
        assertEquals(GateReason.PASS, result[89_900L])
        assertEquals(GateReason.TURN_SIGNAL, result[90_000L])
        assertEquals(GateReason.TURN_SIGNAL, result[101_000L])
        assertEquals(GateReason.TURN_SIGNAL, result[113_000L])
        assertEquals(GateReason.PASS, result[113_100L])
    }

    // ── REQ_FAT_003: 브레이크 앞뒤 5초 ──

    @Test
    fun brakeExcludesFiveSecondsBeforeAndAfter() {
        // 작동 200.0~202.0 s → 제외 구간 195.0~207.0 s, 194.9 s·207.1 s 샘플은 포함
        val frames = times(220_000, startMs = 180_000).map { t -> frame(t, brakeOn = t in 200_000L..202_000L) }
        val result = run(frames)
        assertEquals(GateReason.PASS, result[194_900L])
        assertEquals(GateReason.BRAKE, result[195_000L])
        assertEquals(GateReason.BRAKE, result[207_000L])
        assertEquals(GateReason.PASS, result[207_100L])
    }

    // ── REQ_FAT_004: 회전 지점 ──

    @Test
    fun knownTurnDistanceBoundaryAt300m() {
        // 거리 299.9 m → 제외, 300.0 m → 포함
        val result = run(listOf(frame(0, distToNextTurnM = 299.9f), frame(100, distToNextTurnM = 300.0f)))
        assertEquals(GateReason.NEAR_TURN, result[0L])
        assertEquals(GateReason.PASS, result[100L])
    }

    @Test
    fun unknownTurnDistanceNeedsFiveMinutesAt60Kph() {
        // null + 60 km/h 이상 4 min 59 s → 제외, 5 min 0 s → 포함
        val frames = times(310_000).map { t -> frame(t, distToNextTurnM = null) }
        val result = run(frames)
        assertEquals(GateReason.TURN_UNKNOWN_HOLD, result[299_000L])
        assertEquals(GateReason.PASS, result[300_000L])
    }

    @Test
    fun shortSlowdownKeepsFiveMinuteCount() {
        // 60 km/h 미만 29 s 후 회복 → 유지 (처음 고속이 시작된 0 s부터 계속 셈)
        val frames = times(310_000).map { t ->
            frame(t, distToNextTurnM = null, speedKph = if (t in 120_000L..149_000L) 50f else 80f)
        }
        val result = run(frames)
        assertEquals(GateReason.LOW_SPEED, result[130_000L])
        assertEquals(GateReason.PASS, result[300_000L])
    }

    @Test
    fun longSlowdownRestartsFiveMinuteCount() {
        // 60 km/h 미만 31 s → 5분 다시 셈. 151.1 s에 고속 회복 → 451.1 s부터 포함
        val frames = times(460_000).map { t ->
            frame(t, distToNextTurnM = null, speedKph = if (t in 120_000L..151_000L) 50f else 80f)
        }
        val result = run(frames)
        assertEquals(GateReason.TURN_UNKNOWN_HOLD, result[300_000L])
        assertEquals(GateReason.TURN_UNKNOWN_HOLD, result[451_000L])
        assertEquals(GateReason.PASS, result[451_100L])
    }

    @Test
    fun signalGapRestartsFiveMinuteCount() {
        // 신호가 1초 넘게 끊기면 그 사이 속도를 모르므로 처음부터 다시 셈 [제안값]
        val beforeGap = times(200_000).map { t -> frame(t, distToNextTurnM = null) }
        val afterGap = times(520_000, startMs = 210_000).map { t -> frame(t, distToNextTurnM = null) }
        val result = run(beforeGap + afterGap)
        assertEquals(GateReason.TURN_UNKNOWN_HOLD, result[300_000L])
        assertEquals(GateReason.TURN_UNKNOWN_HOLD, result[509_900L])
        assertEquals(GateReason.PASS, result[510_000L])
    }

    // ── 그 밖의 통과 조건 ──

    @Test
    fun assistModeIsExcluded() {
        val result = run(listOf(frame(0, assistMode = true)))
        assertEquals(GateReason.ASSIST, result[0L])
    }

    @Test
    fun unknownSignalIsExcluded() {
        // null은 "이 신호를 지금 못 받음". 게이트를 통과시키지 않는다 (CLAUDE.md 4장)
        val result = run(
            listOf(
                frame(0, steeringDeg = null),
                frame(100, speedKph = null),
                frame(200, inDrive = null),
                frame(300, turnSignalOn = null),
                frame(400, brakeOn = null),
            ),
        )
        assertTrue(result.values.all { it == GateReason.NO_SIGNAL })
    }

    // ── 확정 시점 ──

    @Test
    fun decisionIsFinalizedTenSecondsLater() {
        val gate = SteeringGate()
        val early = times(9_900).flatMap { gate.push(frame(it)) }
        assertTrue("10초 전에는 확정된 판정이 없어야 함", early.isEmpty())

        val atTenSeconds = gate.push(frame(10_000))
        assertEquals(listOf(0L), atTenSeconds.map { it.tMs })
    }

    @Test
    fun outOfOrderFrameIsDropped() {
        val gate = SteeringGate()
        gate.push(frame(1_000))
        assertTrue(gate.push(frame(500)).isEmpty())
        assertEquals(listOf(1_000L), gate.flush().map { it.tMs })
    }
}
