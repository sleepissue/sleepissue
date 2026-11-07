package com.sleepissue.awakedrive.engine.fatigue

import kotlin.math.abs
import kotlin.math.ceil

/**
 * 시험용 조향각 경로를 만든다.
 * 꼭짓점 사이를 [stepDeg]씩 직선으로 잇고, 꼭짓점 각도는 정확히 그 값이 되게 한다.
 */
internal fun steeringPath(vararg points: Double, stepDeg: Double = 0.1): List<Float> {
    val out = ArrayList<Float>()
    out += points.first().toFloat()
    for (i in 1 until points.size) {
        val from = points[i - 1]
        val to = points[i]
        val steps = maxOf(1, ceil(abs(to - from) / stepDeg - 1e-9).toInt())
        for (s in 1..steps) out += (from + (to - from) * s / steps).toFloat()
    }
    return out
}

/**
 * 시각 [tMs]의 삼각파 조향각. −[amplitudeDeg]에서 시작해 [halfPeriodMs]마다 방향을 바꾼다.
 * 방향이 바뀔 때마다 2 × amplitudeDeg 크기의 조향이 하나 생긴다.
 */
internal fun triangleDeg(tMs: Long, halfPeriodMs: Long, amplitudeDeg: Double): Float {
    val phase = (tMs % (2 * halfPeriodMs)).toDouble() / halfPeriodMs // 0 이상 2 미만
    val unit = if (phase < 1.0) -1.0 + 2.0 * phase else 3.0 - 2.0 * phase // −1 → +1 → −1
    return (amplitudeDeg * unit).toFloat()
}

/** 각도 목록을 100 ms 간격으로 감지기에 넣고 확정된 조향의 크기 목록을 돌려준다. */
internal fun swingsOf(angles: List<Float>): List<Double> {
    val detector = SteeringReversalDetector(reversalMinDeg = 0.5)
    return angles.mapIndexedNotNull { i, angle -> detector.add(i * 100L, angle)?.amplitudeDeg }
}
