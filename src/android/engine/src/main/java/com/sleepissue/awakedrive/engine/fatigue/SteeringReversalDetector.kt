package com.sleepissue.awakedrive.engine.fatigue

import kotlin.math.abs

/**
 * 각도 비교의 허용 오차(°). Float 조향각을 비교할 때 생기는 반올림 오차(0.0000001° 수준) 때문에
 * 경계값(0.5°, 3.0°, 5.0°, 6.0°)이 어긋나지 않게 한다. 센서 분해능(0.1° 수준)보다 훨씬 작다. [해석]
 */
internal const val ANGLE_EPS_DEG = 1e-3

/** 방향이 바뀐 조향 한 번: 직전 극값에서 이번 극값까지 움직인 크기. */
internal data class SteeringSwing(
    /** 이번 극값(방향이 바뀐 점)의 시각 */
    val endMs: Long,
    /** 직전 극값에서 이번 극값까지의 각도 차이(°), 항상 양수 */
    val amplitudeDeg: Double,
)

/**
 * 조향각 흐름에서 '방향이 바뀌는 조향'을 찾는다. (REQ_FAT_006·007)
 *
 * 극값(돌던 방향이 바뀌는 점)은 반대쪽으로 [reversalMinDeg](0.5°) 이상 되돌아왔을 때 확정한다 [해석].
 * 조향 반전율(steering reversal rate) 계산에서 쓰는 방식이며, 0.5°보다 작은 흔들림은 방향 전환으로 보지 않는다.
 * 확정될 때마다 직전 극값에서 이번 극값까지의 크기를 돌려준다. 그 크기로 미세·큰 수정을 나눈다.
 *
 * 게이트를 못 넘은 구간을 건너 각도를 이으면 가짜 조향이 생기므로, 그때는 [reset]한다.
 */
internal class SteeringReversalDetector(private val reversalMinDeg: Double) {

    /** 직전에 확정한 극값. null이면 아직 시작 전 */
    private var lastExtremum: Double? = null

    /** 지금 돌고 있는 방향: +1 오른쪽(각도 증가), -1 왼쪽, 0 아직 모름 */
    private var direction = 0

    /** 지금 방향으로 가장 멀리 간 점 (다음 극값 후보) */
    private var candidate = 0.0
    private var candidateMs = 0L

    /** 샘플 하나를 넣는다. 이 샘플로 극값이 확정되면 그 조향 한 번을 돌려준다. */
    fun add(tMs: Long, angleDeg: Float): SteeringSwing? {
        val x = angleDeg.toDouble()
        val anchor = lastExtremum
        if (anchor == null) {
            lastExtremum = x
            moveCandidate(x, tMs)
            return null
        }
        val threshold = reversalMinDeg - ANGLE_EPS_DEG
        when (direction) {
            0 -> if (abs(x - anchor) >= threshold) {
                direction = if (x > anchor) 1 else -1
                moveCandidate(x, tMs)
            }
            1 -> when {
                x >= candidate -> moveCandidate(x, tMs)
                candidate - x >= threshold -> return confirm(x, tMs, newDirection = -1)
            }
            else -> when {
                x <= candidate -> moveCandidate(x, tMs)
                x - candidate >= threshold -> return confirm(x, tMs, newDirection = 1)
            }
        }
        return null
    }

    fun reset() {
        lastExtremum = null
        direction = 0
    }

    private fun moveCandidate(x: Double, tMs: Long) {
        candidate = x
        candidateMs = tMs
    }

    private fun confirm(x: Double, tMs: Long, newDirection: Int): SteeringSwing {
        val previous = lastExtremum ?: candidate
        val swing = SteeringSwing(endMs = candidateMs, amplitudeDeg = abs(candidate - previous))
        lastExtremum = candidate
        direction = newDirection
        moveCandidate(x, tMs)
        return swing
    }
}
