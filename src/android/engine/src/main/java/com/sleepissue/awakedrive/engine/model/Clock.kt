package com.sleepissue.awakedrive.engine.model

// 엔진 전체가 쓰는 시간 공급원. System.currentTimeMillis()를 직접 부르지 않고 항상 이걸 주입받는다.
// 이유: 데모 배속(1·10·60배), 시동 꺼짐 재현, 단위 테스트에서 시간을 마음대로 돌려야 하기 때문 (CLAUDE.md 4장).
//
// TODO(1번 협의): 실제 구현체 소유권
//  - RealClock: 실제 차량 운행용, 시스템 시각을 그대로 반환
//  - DemoClock: DemoPlayer 배속에 맞춰 시각을 빠르게 진행
//  - FakeClock: :engine 테스트용, 테스트가 직접 시각을 지정
//  RealClock·DemoClock은 TripStateStore·DemoPlayer(1번)와 엮이므로 어디에 둘지 1번과 정한다.
interface Clock {
    // epoch 밀리초
    fun nowMs(): Long
}
