package com.sleepissue.awakedrive.engine.time

/**
 * 모든 시간은 외부에서 주입한다. Android나 시스템 시각을 직접 읽지 않는다.
 * 구현체는 날짜 변경·재부팅 때 세 시간축을 해석하는 정책을 명시해야 한다.
 */
interface Clock {
    /** 운행·정차·예보에 쓰는 논리 시각. 데모에서는 배속·가상 이동을 허용한다. */
    fun scenarioTimeMs(): Long

    /** 실제 반응·오디오 처리에 쓰는 단조 경과 시각. 데모 배속을 적용하지 않는다. */
    fun elapsedRealtimeMs(): Long

    /** 저장·재실행 기록에 쓰는 날짜 시각. 단조 경과 시각과 서로 빼지 않는다. */
    fun wallTimeMs(): Long
}
