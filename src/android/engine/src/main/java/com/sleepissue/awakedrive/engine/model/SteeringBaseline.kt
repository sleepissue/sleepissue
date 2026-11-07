package com.sleepissue.awakedrive.engine.model

/**
 * f미세·f큰을 계산할 때 비교하는 기준 조향 지표. (REQ_FAT_009·010)
 *
 * 기본값·평소의 나·오늘의 나 가운데 무엇을 쓸지는 BaselineStore가 정한다 (REQ_BSL_003~007).
 */
data class SteeringBaseline(
    /** 미세 수정 분당 횟수 */
    val microPerMin: Float,
    /** 큰 수정 분당 횟수 */
    val largePerMin: Float,
) {
    init {
        require(microPerMin >= 0f && largePerMin >= 0f) { "기준선은 0 이상: $microPerMin, $largePerMin" }
    }
}
