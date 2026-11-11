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
 *
 * 멈춤 구간은 '지금 샘플로 끝나는 가장 긴 구간'으로 잡는다. 앞에서부터 한 번 자른 구간을 그대로 이어 가면,
 * 멈추기 직전 꺾임의 끝 샘플(예: 0.6°) 하나가 구간 맨 앞에 남아 뒤의 0.1°대 멈춤을 중간에 끊고,
 * 4.4초 멈춤이 1.8초 + 2.6초로 나뉘어 급조향을 놓친다(11/10 drowsy 데모 17:30·22:30·27:30).
 * 그래서 구간이 깨질 때마다 앞쪽을 필요한 만큼만 잘라 낸다.
 */
internal class SuddenSteerDetector(config: FatigueConfig) {

    private val stillOverMs = config.suddenStillMs
    private val stillMaxDeltaDeg = config.suddenStillMaxDeltaDeg.toDouble()
    private val turnWithinMs = config.suddenTurnWithinMs
    private val turnMinDeg = config.suddenTurnMinDeg.toDouble()

    private class Sample(val tMs: Long, val deg: Double)

    // 지금 샘플로 끝나는 가장 긴 멈춤 구간: 샘플 시각, 그리고 최솟값·최댓값 후보(앞이 구간의 최솟값·최댓값)
    private val stillTimes = ArrayDeque<Long>()
    private val minQueue = ArrayDeque<Sample>()
    private val maxQueue = ArrayDeque<Sample>()
    private var lastDeg = 0.0

    // 4초 넘는 멈춤이 끝난 뒤 꺾임을 지켜보는 중이면 기준 각도, 아니면 null
    private var watchReferenceDeg: Double? = null
    private var watchDeadlineMs = 0L

    /** 샘플 하나를 넣는다. 이 샘플에서 멈춤 뒤 급조향이 확인되면 true. */
    fun add(tMs: Long, angleDeg: Float): Boolean {
        val x = angleDeg.toDouble()
        var detected = checkWatch(tMs, x)

        if (stillTimes.isNotEmpty()) {
            val range = max(maxQueue.first().deg, x) - min(minQueue.first().deg, x)
            if (range >= stillMaxDeltaDeg) {
                // 멈춤이 끝났다. 바로 앞 샘플까지 4초를 넘게 멈췄으면 1초 동안 꺾임을 지켜본다
                val start = stillTimes.first()
                val end = stillTimes.last()
                if (!detected && end - start > stillOverMs) {
                    watchReferenceDeg = lastDeg
                    watchDeadlineMs = end + turnWithinMs
                    detected = checkWatch(tMs, x)
                }
            }
        }
        push(tMs, x)
        return detected
    }

    fun reset() {
        stillTimes.clear()
        minQueue.clear()
        maxQueue.clear()
        watchReferenceDeg = null
    }

    private fun push(tMs: Long, x: Double) {
        stillTimes.addLast(tMs)
        while (minQueue.isNotEmpty() && minQueue.last().deg >= x) minQueue.removeLast()
        minQueue.addLast(Sample(tMs, x))
        while (maxQueue.isNotEmpty() && maxQueue.last().deg <= x) maxQueue.removeLast()
        maxQueue.addLast(Sample(tMs, x))
        // 앞에서부터 필요한 만큼만 잘라 '이 샘플로 끝나는 가장 긴 멈춤 구간'을 남긴다
        while (maxQueue.first().deg - minQueue.first().deg >= stillMaxDeltaDeg) {
            val cut = min(maxQueue.first().tMs, minQueue.first().tMs)
            while (stillTimes.first() <= cut) stillTimes.removeFirst()
            if (maxQueue.first().tMs <= cut) maxQueue.removeFirst()
            if (minQueue.first().tMs <= cut) minQueue.removeFirst()
        }
        lastDeg = x
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
}
