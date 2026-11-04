# 1번 담당 A 기본 틀 연결 가이드

A는 차량 입력을 공통 자료형으로 전달하고 다른 담당이 연결할 수 있는 통로다. 이 문서의 예시는 최초 관측 전달 모드다. 이후 [운행 계산](ROLE1_TRIP_RULES.md)·[개입](ROLE1_INTERVENTION.md)·[Room](ROLE1_PERSISTENCE.md)과 [10Hz·품질·중재](ROLE1_SIGNAL_HUB.md)를 추가했다. 실제 SDK·졸음 판단·음성·휴식 자동 인증·경유지 실행·JSON 재생은 후속 범위다.

개발 대상은 Pleos Playground의 가상 차량이다. `Source.REAL`은 Playground SDK·Virtual Console 입력이며, `Source.DEMO`는 앱의 JSON 재생 입력이다. 물리 차량 연결이나 검증을 뜻하지 않는다.

## 실행 가능한 사용 예시

전체 예시는 [`ScaffoldUsageExampleTest.kt`](../../src/android/app/src/test/java/com/sleepissue/awakedrive/signal/ScaffoldUsageExampleTest.kt)에 있다. Android 기기나 실제 대기를 사용하지 않으며, 시험용 `FakeSignalSource`와 `FakeClock`은 `app/src/test`에만 있다. 배포 코드가 Fake를 자동 선택하지 않는다.

먼저 [Android README](../../src/android/README.md)의 JDK·SDK 환경을 준비한 뒤 아래 명령을 실행한다.

```bash
cd src/android
./gradlew :app:testDebugUnitTest --tests '*ScaffoldUsageExampleTest'
```

연결 순서는 다음과 같다. 아래 코드는 위 시험의 핵심 부분이며 `runTest` 안에서 실행한다.

```kotlin
val source = FakeSignalSource()
val clock = FakeClock(elapsedMs = 1_000)
val hub = VehicleSignalHub(source, clock)
val tripStore = TripStateStore()

// 2번: 분석에는 연속 샘플을 사용한다. 실제 엔진 호출을 붙일 자리다.
backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
    hub.samples.collect { frame ->
        tripStore.update { it.copy(lastGear = frame.gear) }
    }
}

// 5번: 화면은 최신 상태를 사용한다. null과 품질 상태를 함께 표시한다.
backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
    hub.latestFrame.collect { frame ->
        // 화면 상태에 반영한다. 졸음 위험이 없다는 뜻으로 해석하지 않는다.
    }
}

hub.start(backgroundScope)
source.emit(VehicleSignalInput(
    tMs = 0,
    source = Source.REAL,
    speed = 22.22f,
    speedUnit = SpeedUnit.METERS_PER_SECOND,
    gear = Gear.D,
))
runCurrent()
// Hub 출력 speedKph는 약 79.992이며 TripStateStore의 lastGear는 D다.
hub.stop()
```

이 예시의 분석 구독은 전달 내용을 확인하는 연결 지점이다. `FatigueEngine`이 동작하거나 졸음을 판정한 것으로 해석하지 않는다. `TripStateStore.update`도 메모리 상태만 갱신한다.

## 입력·단위·품질 계약

| 계약 | 의미 |
| --- | --- |
| `VehicleSignalInput` | 어댑터가 공급하는 완전한 관측 묶음. null은 이전 값 유지 명령이 아니다. |
| `speed`·`speedUnit` | 원시 속도와 명시적 단위. Hub가 m/s만 ×3.6하고 이미 km/h인 입력은 그대로 전달한다. |
| `SignalFrame.speedKph` | 정규화된 km/h. 엔진·화면에서 다시 변환하지 않는다. |
| 거리·각도·온도 | 거리 m, 각도·위경도 °, 온도 ℃. 나머지 어댑터의 원단위는 공식 확인 후 매핑한다. |
| `qualityOf(field)` | 필드별 가용성·출처·원천 관측 시각과 시간축·Hub 수신 경과 시각·원천 Hz. 혼합 출처를 전체 REAL로 단정하지 않는다. |
| null·미지원·권한 없음 | 실제 `0`·`false`와 다르다. 사용 불가 필드는 null로 전달하며 이유는 품질에 남긴다. |
| `NaviState` | 위치·방향·목적지 남은 시간/거리·다음 회전 거리를 별도 전달한다. `SignalFrame` 안에 회전 거리를 중복 보관하지 않는다. |
| `PowerEvent` | 관측된 ON/OFF/RESUME/UNKNOWN과 품질. 앱 종료를 시동 OFF로 추정하지 않는다. |

차량 입력의 품질을 생략하면 Hub는 해당 값의 존재 여부와 입력 출처로 AVAILABLE/UNAVAILABLE을 만든다. 이미 PERMISSION_DENIED·UNSUPPORTED·ERROR·STALE이면 값이 있더라도 사용 가능 값으로 내보내지 않는다. 원천 관측 시각이 없으면 수신 시각으로 꾸며 넣지 않는다.

내비는 입력 품질을 명시한다. 예를 들어 다음 회전 거리만 확인되었다면 다음처럼 공급한다.

```kotlin
NaviState(
    tMs = 1_000,
    distToNextTurnM = 500f,
    quality = mapOf(
        NaviField.DIST_TO_NEXT_TURN_M to SignalQuality(
            availability = SignalAvailability.AVAILABLE,
            source = Source.REAL,
        ),
    ),
)
```

## 세 시간축

`Clock`은 세 시각을 분리해 주입한다. 판단·데모 코드는 시스템 날짜를 직접 읽지 않는다.

| 메서드 | 사용처 |
| --- | --- |
| `scenarioTimeMs()` | 운행·시나리오 논리 시각. 프레임의 `tMs`·운행 구간이 사용하는 시간축. |
| `elapsedRealtimeMs()` | 배속 없는 실제 경과 시각. Hub 수신 시각·실제 반응 시간 측정의 시간축. |
| `wallTimeMs()` | 저장용 날짜 시각. 재실행·재부팅을 넘는 기록의 시간축. |

센서 원천 시각은 `SignalQuality.observedAtMs`로 별도 보존하고, `observedTimeBasis`로 SCENARIO·ELAPSED_REALTIME·WALL_TIME·UNKNOWN을 구분한다. 시간축을 확인하지 못한 UNKNOWN 시각을 실제 센서 Hz 계산의 근거로 사용하지 않는다. A의 관측 모드는 주어진 관측을 전달한다. 현재 주기 발행·원천 RateMeter는 명시적 정책을 받는 생성자에 연결했고, `TripSnapshot`의 정밀 근거를 사용하는 Room 저장·복원도 별도로 구현했다. 배속 시계는 후속 범위다.

## 구독과 시작·중지 책임

이 절은 기존 관측 모드의 계약이다. 새 주기 모드의 최신값 버퍼·세션 필터·누락 순번·입력 완료 정책은 [Hub 연결 가이드](ROLE1_SIGNAL_HUB.md#주기와-생명주기)를 따른다.

- Hub 인스턴스 하나가 입력 수집을 소유한다. 화면마다 실제 차량 입력을 직접 구독하지 않는다.
- `samples`·`naviSamples`·`powerEvents`는 활성 구독자에게 연속 입력을 전달한다. 분석 구독을 먼저 시작하고 `hub.start(ownerScope)`를 호출한다.
- 연속 통로는 재생 버퍼나 추가 버퍼가 없다. 구독자가 없던 과거 샘플은 저장하지 않으며, 느린 활성 구독자가 있으면 발행을 기다린다. 분석을 장시간 막는 작업은 구독 콜백에서 실행하지 않는다.
- `latestFrame`·`latestNavi`·`latestPower`는 최신값용 `StateFlow`다. 빠른 갱신을 합칠 수 있으므로 분석용 전체 샘플 통로로 사용하지 않는다. ROS에 비유하면 연속 통로는 관측 토픽이고 최신 상태는 마지막 값을 보관한 캐시다.
- 소유자는 생명주기가 끝날 때 `hub.stop()`을 호출한다. 실제 어댑터는 Flow 수집 취소를 받으면 차량 콜백을 해제해야 한다. 소유 코루틴 범위가 취소되어도 Hub 입력 수집이 함께 취소된다.
- `start`의 중복 호출은 `false`를 반환한다. 완료·실패 후 재연결은 `stop` 다음 `start` 순서다.
- 입력 완료·실패·취소·중지 시 해당 최신 값은 null로 비운다. 화면은 `collectionState`로 COMPLETED·FAILED·STOPPED와 미연결을 구분한다. 내비·전원 입력 실패가 다른 입력을 자동 종료하지 않는다.
- `stop`은 Hub의 값을 비우지만 `TripStateStore`에 저장한 운행 상태를 임의 초기화하지 않는다. 운행 종료·복원·휴식 후 초기화는 후속 규칙의 책임이다.

코루틴 범위는 입력을 기다리는 작업들의 소유자다. 소유자를 취소하면 그 아래 작업도 정리된다. Android 생명주기에 이 범위를 연결하는 실제 서비스·조립 코드는 C 범위다.

## 운행 상태·외부 기능 연결

`TripStateStore`는 `state` 관찰, `replace`, `update`를 제공한다. 운행 규칙 연결 단계에서 단일 잠금으로 갱신을 직렬화해 콜백을 한 번만 실행하도록 보강했다. 갱신 안에서 저장·음성 발화·대기 같은 작업을 실행하지 않는다. `TripRepository`는 Loaded·Empty·Failed와 저장/삭제 성공·실패를 구분하며 현재 [Room 구현](ROLE1_PERSISTENCE.md)이 연결돼 있다. A 이후 구현은 [운행 규칙 가이드](ROLE1_TRIP_RULES.md)를 본다.

`CheckPort`, `SpeechPort`, `RestPort`, `ClimatePort`는 요청 접수와 실제 완료 결과를 나눈다. `requestId`로 시작·취소·결과를 연결한다. `REQUESTED`·`STARTED`는 실행 성공이 아니며, 경유지 요청을 취소했다는 사실도 실제 경유지 제거 성공이 아니다. 응답 없음은 사람에게 정상 질문한 뒤 대답이 없던 결과이고, 권한 없음·장치 오류와 구분한다.

`CheckPort.start`의 즉시 반환은 요청 접수 결과이며, 이후의 비동기 진행은 `executionResults`로 관찰한다. 조작 때문에 확인이 보류되면 DEFERRED, 실제 질문 안내 음성이 시작되면 STARTED를 같은 `requestId`로 보낸다. 최종 사람 확인 결과는 별도 `results`의 `CheckResult`다.

```text
start(CheckRequest) → 즉시 REQUESTED
executionResults   → DEFERRED → STARTED
results            → CheckResult(NORMAL / SLOW / NO_RESPONSE 등)
```

후속 재생 구현에서는 실제 STARTED 시점부터 최종 확인 판정 종료까지 질문 구간을 연결한다. 단순 요청 접수나 DEFERRED만으로 안내가 시작됐다고 간주하지 않는다. 실패·취소로 끝난 경우에도 같은 요청의 질문 구간을 해제한다. 취소 요청과 실행 취소 진행 이벤트, 최종 CANCELLED 확인 결과를 정상 무응답으로 바꾸지 않는다. A는 이 진행 통로와 결정적 Fake 시험까지만 제공하며 배속·타이머·취소 판정 로직을 실행하지 않는다.

`UnconnectedInterventionManager`는 기본 단계 0과 별도로 `NOT_EVALUATED`를 표시한다. 시작·입력·결과·취소·중지 호출은 `NOT_CONNECTED`이며 실행 요청을 생성하지 않는다. 아래 호출은 단계 평가 성공이 아니다.

A 이후 추가한 실제 순수 정책·요청 관리의 조립 기준은 [개입 정책 연결 가이드](ROLE1_INTERVENTION.md)에 있다. SRS v0.2는 최초 일반 상승 IMMEDIATE, 시간·예보 1단계의 인증 시 재평가, 3단계의 인증+정상 회복을 구분한다. INT_015는 미확정 Should로 비활성이다.

```kotlin
val manager: InterventionManager = UnconnectedInterventionManager()
val result = manager.start(RequestId("monitor-1"))
// result.status == NOT_CONNECTED
// manager.state.value.evaluationStatus == NOT_EVALUATED
```

`DemoPlayer`는 차량·내비·전원 Source 계약을 구현하므로 실제 재생도 Hub로 공급할 수 있다. `UnconnectedDemoPlayer`는 로드·시작·일시정지·재개·질문 시작/종료·중지·초기화 명령에 모두 `NOT_CONNECTED`를 반환한다. 배속이나 가짜 프레임을 생성하지 않는다. 시험용 Fake와 5번 담당의 6종 JSON은 별개다.

## 후속 범위와 미정 정책

| 범위 | 남은 일 |
| --- | --- |
| B 순수 규칙·재생 | 운행·개입·원천 Hz는 별도 가이드 기준 구현. JSON 로드·배속·질문 중 1배속은 남음. |
| C 실제 연결 | Room 수동 복원은 구현. 공식 SDK 서명·권한·원단위/시간축·지원 확인·실제 전원·서비스/부팅 연결은 남음. |
| 공동 결정 | 관측 최신성·REAL 상실 유지시간은 정책 주입까지 구현했으며 제품 수치는 미정. 위치는 SRS의 유효 DEMO 좌표 쌍 우선 예외 적용. |

저장소 기획서와 외부 구현 계획의 정차·누적·다른 곳 출발 정책 차이는 후속 규칙 구현 전에 정합성을 맞춘다. A의 자료형에 항목이 있다는 사실은 그 정책이 확정되거나 계산이 구현됐다는 뜻이 아니다. 원음·PCM·STT 문장·비밀값을 저장하는 필드는 만들지 않는다.
