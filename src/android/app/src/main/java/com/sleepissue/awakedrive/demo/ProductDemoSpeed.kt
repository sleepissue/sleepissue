package com.sleepissue.awakedrive.demo

import com.sleepissue.awakedrive.engine.demo.DemoSpeedSegment

/** 제품 시연에서 명시적으로 고르는 재생 정책이다. 원본 JSON과 관측값은 수정하지 않는다. */
enum class ProductDemoSpeed(val label: String, val factor: Int?) {
    ORIGINAL("JSON 구간", null),
    ONE("1배", 1),
    TEN("10배", 10),
    SIXTY("60배 · 시험용", 60),
}

/** 선택 배속은 이번 재생 사본에만 적용한다. 질문 중 1배속은 기존 DemoClock이 우선한다. */
fun DemoScenario.withPlaybackSpeed(speed: ProductDemoSpeed): DemoScenario {
    val factor = speed.factor ?: return this
    return copy(speedSegments = listOf(DemoSpeedSegment(0, durationMs, factor)))
}
