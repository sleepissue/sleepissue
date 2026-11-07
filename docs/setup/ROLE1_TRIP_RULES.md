# 1번 담당 운행 규칙 연결 가이드

2026-11-07: 실제 Playground SDK 연결은 [실제 SDK 운행·Room 가이드](ROLE1_REAL_TRIP.md)에서 별도 REAL 세션/시계/DB로 조립했다. 아래 수동 예시와 과거 남은 작업 기록은 당시 계약으로 보존한다.

A 기본 틀 다음 단계로 운행 시간·정차·최근 24시간 누적 계산과 메모리 연결을 구현했다. 개발 대상은 Pleos Playground의 가상 차량이며, `REAL`은 SDK·Virtual Console 입력이고 `DEMO`는 앱의 JSON 재생 입력이다.

## 구현 위치와 책임

| 구성 | 책임 |
| --- | --- |
| `engine/session/TripRules` | 관측과 정밀 상태를 받아 다음 운행 상태·전환 이벤트를 계산한다. Android·SDK·DB를 호출하지 않는다. |
| `engine/session/RollingDriveWindow` | 같은 논리 시간축의 운전 구간을 최근 창으로 절단하고, 겹침·중복을 합집합으로 합산한다. |
| `app/signal/TripTracker` | Hub의 필드별 품질·출처를 확인해 규칙으로 전달하고 메모리 상태를 갱신한다. |
| `TripStateStore` | 상태 읽기·교체·원자 갱신을 제공한다. 운행 규칙과 영속 저장 자체를 수행하지 않는다. |

`TripRuleState`는 밀리초 정밀도와 운전 구간·정차·OFF 정보를 보관한다. 화면용 `TripState`의 초 단위 값만 다음 계산에 재사용하지 않는다. 100ms마다 들어오는 입력에서도 밀리초를 합산한 뒤 초로 변환하므로 매 프레임의 소수 초가 버려지지 않는다.

## 명시적으로 선택할 정책

저장소 기획서와 외부 실행 계획의 차이를 숨기지 않도록 `TripRulePolicy`의 두 항목 모두 기본값 없이 받는다. 기획서·SRS 원문을 자동 개정하거나 팀 결정으로 승인한 것은 아니다.

| 선택 | 동작 |
| --- | --- |
| `V03_RETROACTIVE` | 연속 운전 시간에 정차 5분 미만은 잠정 포함, 5분부터 정차분을 소급 제외해 정차 전 값 유지, 15분부터 0. |
| `LEGACY_FREEZE` | 정차 중 직전 연속 시간을 유지하고 15분부터 0. 현재 기획서의 미명시 부분을 비교하기 위한 해석이며 [가정]이다. |
| 짧은 정차 누적 `EXCLUDE` | 정차 구간은 최근 24시간 누적에 넣지 않는다. 예시에서 선택한 [가정]이며 연속 시간의 잠정 포함과 별개다. |
| 짧은 정차 누적 `INCLUDE` | 5분 미만은 누적에도 잠정 포함하고, 5분에 도달하면 제외한다. 짧은 정차 후 확인된 주행으로 돌아오면 확정 구간으로 남긴다. |

숫자 경계와 최근 합산 창은 `EngineConfig`에 모았다. 누적 창은 잠정 짧은 정차 전체를 보관할 수 있도록 짧은 정차 경계 이상이어야 한다. 앱을 자동으로 시작하거나 위 선택을 배포 기본값으로 연결하는 코드는 없다.

## 계산과 관측 계약

- 첫 D단에서 새 운행 ID·시작 시각을 만든다. P단 목적지 설정은 시작이 아니다. [가정] 첫 D단 전의 R/N은 이번 운행·누적에서 제외하고, 시작한 뒤의 D/R/N은 P/OFF가 아닌 운행 상태로 센다. 속도 0이라는 이유만으로 P단 정차로 바꾸지 않는다.
- P단과 전원 OFF는 하나의 정차로 이어서 합산한다. OFF가 계속된 시간은 별도 보관해, 오래 주차했다가 잠깐 OFF가 된 것을 2시간 OFF로 오해하지 않는다.
- OFF 정확히 2시간은 같은 운행, 초과하면 새 운행 대기다. 새 ID와 기준선 초기화 요청은 다음 첫 D단에 한 번만 생성한다. 새 운행에서도 최근 24시간 구간은 유지한다.
- `RestResetReached`는 연속 시간 초기화 통지다. 휴식 인증·스탬프·단계 해제를 실행하지 않는다. 단계와 기준선·위치 등 다른 담당 상태는 유지하며, 새 운행의 기준선 초기화만 별도 이벤트로 알린다.
- 같은 시각의 같은 관측은 중복 누적·이벤트가 없다. 같은 시각의 다른 상태 변화는 처리하고, 시간 역행은 오류로 거부한다.
- [가정] 입력은 상태 변화 모델이다. 직전 관측 상태가 다음 관측 시각까지 유효한 구간을 정산한다. 관측 중단을 알릴 책임은 소유자에게 있으며, `markInputUnavailable`로 실제 유효 구간이 끝난 논리 시각을 적시에 전달해야 한다. 뒤늦은 장애 발견 시각까지 유효했던 것처럼 만들면 안 된다. 최신성 타이머는 후속 범위다.
- null/UNKNOWN 뒤에는 시간 누적과 정차/OFF 증명을 멈춘다. 앱이 닫혔다는 사실로 OFF를 생성하지 않는다. 마지막 기어는 이력이며 현재 관측의 대체값이 아니다.
- 최근 24시간은 자정·휴식·새 운행으로 초기화하지 않는다. [시작, 끝) 구간을 창에 절단하고 중복·겹침을 합산하지 않는다. 모든 구간은 같은 논리 시간축이어야 한다.

## Hub 연결 예시

실행 가능한 예시는 [TripTrackerTest](../../src/android/app/src/test/java/com/sleepissue/awakedrive/signal/TripTrackerTest.kt)의 Hub 연결 시험에 있다. 아래는 연결 구조다. Hub·scope와 관측의 논리 시각은 소유자가 준비한 것을 주입한다.

```kotlin
val config = EngineConfig()
val rules = TripRules(
    config,
    TripRulePolicy(
        stopTime = StopTimePolicy.V03_RETROACTIVE,
        shortStopAccumulation = ShortStopAccumulationPolicy.EXCLUDE,
    ),
) { java.util.UUID.randomUUID().toString() }
val store = TripStateStore()
val tracker = TripTracker(rules, store, sessionSource = Source.REAL)

// 먼저 구독한다. 화면에는 tracker.state를 전달한다.
val observationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    hub.samples.collect { frame -> tracker.accept(frame) }
}
hub.start(scope)

// inputValidUntilMs는 소유자가 판단한 마지막 유효 구간의 끝이다.
// 중단을 늦게 발견한 현재 시각으로 대체하지 않는다.
tracker.markInputUnavailable(inputValidUntilMs)
hub.stop()
observationJob.cancel()
```

다른 세션 출처의 관측은 무시하며 논리 시각도 전진시키지 않는다. 같은 출처지만 미지원·권한 없음·STALE인 기어는 미관측으로 전달한다. 품질·출처 자체가 없는 기어를 관측 성공으로 바꾸지 않는다. Hub의 실패·중지 상태를 감시하는 소유자는 `markInputUnavailable` 호출도 연결해야 한다.

위 짧은 예시는 차량 프레임만 전달한다. 전원까지 사용할 때 `accept(frame, power)`는 **같은 논리 시각의 완전한 관측**을 받으며, `observePower(event)`는 해당 시각에 기어가 없는 독립 관측이다. 단 한 번 받은 OFF 이벤트를 다음 모든 프레임에 자동 유지하지 않는다. 후속 입력 조립 계층이 SDK 상태의 유효성을 판단하고, 원천 시각·출처를 보존한 현재 관측을 만들어야 한다. 서로 다른 시각의 프레임·전원을 그대로 결합하면 오류로 거부한다.

Store의 `transaction`은 공개 상태와 정밀 상태를 함께 바꾸기 위해 콜백을 정확히 한 번 실행한다. 운행 ID를 CAS 재시도 안에서 반복 생성하지 않는다. 외부 모듈이 단계·기준선·위치를 갱신해도 다음 운행 계산에서 보존한다. 운행 소유 카운터를 외부에서 변경하면 정밀 상태와 불일치하므로 거부한다.

`preciseState()`는 정밀 상태를 반환한다. 초기 Store와 정밀 상태가 다르거나 구간 없이 카운터만 복원하려는 경우를 정상 복원으로 처리하지 않는다. 현재 [Room 저장·복원](ROLE1_PERSISTENCE.md)은 이 상태·구간·정책과 개입 근거를 함께 저장하고 검증해 Tracker를 다시 구성한다. 실제 재부팅 시간 변환·부팅 자동 실행·서비스는 후속 범위다.

## 남은 작업

운행·개입 정책과 수동 세션의 Room 저장·프로세스 재실행은 연결했다. [원천 Hz·10Hz 발행·최신성·중재](ROLE1_SIGNAL_HUB.md)도 명시적 정책의 독립 경로로 추가했다. 기존 수동 운행은 `VehicleSignalHub.observations()`를 사용한다. 주기 Hub의 혼합 입력을 Tracker에 자동 연결하지 않으며, 후속 제품 조립은 Tracker의 세션 출처·유효 구간 종료 계약을 함께 적용해야 한다. 현재 실제 SDK 입력 조립은 `PlaygroundTripSession`의 REAL 전용 경로로 연결했다([가이드](ROLE1_REAL_TRIP.md)). 기존 JSON 데모 구현은 5번 인계 대상으로 보존한다. 혼합 제품 세션·감시 서비스·부팅은 남아 있다. 순수 규칙·Room 기기 시험과 Playground SDK 수신 시험을 구별한다.
