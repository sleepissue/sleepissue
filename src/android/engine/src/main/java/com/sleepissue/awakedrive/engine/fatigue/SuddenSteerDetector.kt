package com.sleepissue.awakedrive.engine.fatigue

import com.sleepissue.awakedrive.engine.config.FatigueConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 멈춤 뒤 급조향을 찾는다. (REQ_FAT_008)
 *
 * 4초를 '넘게' 조향각 변화(최댓값 − 최솟값)가 0.5° '미만'이다가, 멈춤이 끝난 뒤 1초 '안에'
 * 멈춰 있던 각도에서 6° 이상 꺾이면 1건이다.
 * - 정지 4.0 s + 1 s 안 6° → 0건 (4초를 넘지 않음)
 * - 정지 4.1 s + 0.9 s 안 6° → 1건
 * - 정지 4.1 s + 1 s 안 5.9° → 0건
 * - 정지 4.1 s + 1.1 s에 6° → 0건
 *
 * '멈춤이 끝난 시각'은 변화가 0.5° 미만이던 마지막 샘플의 시각이고, 1초는 그 시각부터 잰다(경계 포함) [해석].
 */
internal class SuddenSteerDetector(config: FatigueConfig) {

    private val stillOverMs = config.suddenStillMs
    private val stillMaxDeltaDeg = config.suddenStillMaxDeltaDeg.toDouble()
    private val turnWithinMs = config.suddenTurnWithinMs
    private val turnMinDeg = config.suddenTurnMinDeg.toDouble()

    // 지금 이어지고 있는 멈춤 구간
    private var stillStartMs: Long? = null
    private var stillEndMs = 0L
    private var stillMinDeg = 0.0
    private var stillMaxDeg = 0.0
    private var lastStillDeg = 0.0

    // 4초 넘는 멈춤이 끝난 뒤 꺾임을 지켜보는 중이면 기준 각도, 아니면 null
    private var watchReferenceDeg: Double? = null
    private var watchDeadlineMs = 0L

    /** 샘플 하나를 넣는다. 이 샘플에서 멈춤 뒤 급조향이 확인되면 true. */
    fun add(tMs: Long, angleDeg: Float): Boolean {
        val x = angleDeg.toDouble()
        var detected = checkWatch(tMs, x)

        val start = stillStartMs
        if (start == null) {
            beginStill(tMs, x)
            return detected
        }
        val newMin = min(stillMinDeg, x)
        val newMax = max(stillMaxDeg, x)
        if (newMax - newMin < stillMaxDeltaDeg) {
            // 아직 멈춰 있음
            stillMinDeg = newMin
            stillMaxDeg = newMax
            stillEndMs = tMs
            lastStillDeg = x
        } else {
            // 멈춤이 끝났다. 4초를 넘게 멈췄으면 1초 동안 꺾임을 지켜본다
            if (!detected && stillEndMs - start > stillOverMs) {
                watchReferenceDeg = lastStillDeg
                watchDeadlineMs = stillEndMs + turnWithinMs
                detected = checkWatch(tMs, x)
            }
            beginStill(tMs, x)
        }
        return detected
    }

    fun reset() {
        stillStartMs = null
        watchReferenceDeg = null
    }

    private fun checkWatch(tMs: Long, x: Double): Boolean {
        val reference = watchReferenceDeg ?: return false
        if (tMs > watchDeadlineMs) {
            watchReferenceDeg = null
            return false
        }
        if (abs(x - reference) >= turnMinDeg - ANGLE_EPS_DEG) {
            watchReferenceDeg = null
            return true
        }
        return false
    }

    private fun beginStill(tMs: Long, x: Double) {
        stillStartMs = tMs
        stillEndMs = tMs
        stillMinDeg = x
        stillMaxDeg = x
        lastStillDeg = x
    }
}
