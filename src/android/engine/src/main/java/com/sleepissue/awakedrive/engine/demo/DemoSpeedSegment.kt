package com.sleepissue.awakedrive.engine.demo

/** JSON의 [fromSec, toSec) 구간을 정수 ms로 변환한 값이다. */
data class DemoSpeedSegment(val fromMs: Long, val toMs: Long, val factor: Int) {
    init {
        require(fromMs >= 0 && toMs > fromMs) { "배속 구간은 0 이상의 시작과 더 큰 끝이 필요합니다." }
        require(factor == 1 || factor == 10 || factor == 60) { "배속은 1·10·60만 지원합니다." }
    }
}
