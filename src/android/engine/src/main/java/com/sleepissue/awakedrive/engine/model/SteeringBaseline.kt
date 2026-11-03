package com.sleepissue.awakedrive.engine.model

/**
 * f미세·f큰을 계산할 때 비교하는 기준 조향 지표. (REQ_FAT_009·010)
 *
 * 1차 지금은 운행 기록 3회 전 기본값(BaselineConfig.defaultBaseline, REQ_BSL_003)으로 시작한다.
 * BaselineStore(REQ_BSL_003~007)가 들어오면 '평소의 나'·'오늘의 나' 가운데 하나를 골라 이 값으로 넘긴다.
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
