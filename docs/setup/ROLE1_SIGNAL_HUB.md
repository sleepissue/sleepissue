# VehicleSignalHub 주기·품질·입력 중재 연결 가이드

이번 범위는 **가짜 입력으로 검증한 Hub**다. 실제 Playground SDK 어댑터·JSON 로더·DemoClock·FGS·targetSdk 변경은 포함하지 않는다. `Source.REAL`을 쓰는 시험은 실제 SDK 수신 성공이 아니다. 기존 운행·개입·수동 Room 세션은 그대로 사용할 수 있다.

후속 실제 SDK 연결과 targetSdk 34 검증은 [Playground SDK 가이드](ROLE1_PLAYGROUND_SDK.md)에 별도로 기록한다. 이 문서의 가짜 입력·TTL 1초를 실제 SDK 결과·제품 기본값으로 바꾸지 않는다. 실제 어댑터가 제공한 필드별 수신 시각은 공유 정규화기에서 보존한다.

2026-11-05 추가한 [DemoClock·JSON 재생](ROLE1_DEMO_PLAYER.md)은 이 Hub를 그대로 사용한다. Player 종료/시간 초기화 때의 Hub 중지·새 세션 계약과 가짜 REAL 대비 실제 SDK 동시 수신의 검증 범위는 해당 문서에서 구분한다.

2026-11-08: SDK의 여섯 상태는 `confirmationFields`로 재조회 확인을 전달한다. NaviHelper는 조회 응답과 자발 알림을 구분하지 못하므로 `NaviSignalSource.stateReportsOnly=true`로 전달하며 Hub는 값·품질만 갱신하고 센서 관측 순번을 만들지 않는다. 기존 원천의 기본값은 false다. SDK 원천 Hz는 UNKNOWN이다. `emissionObserver`는 실제 버스 emit 직전 elapsed 시각을 별도로 감사하며 기존 프레임/원천 시각을 덮지 않는다. [최신 판정](ROLE1_COMPLETION_STATUS.md).

## 생성 경로와 책임

| 경로 | 용도·동작 |
| --- | --- |
| `VehicleSignalHub(sources, clock, policy, config)` | 명시한 최신성·중재 정책과 주입 시계로 100ms 발행. REAL/DEMO 입력을 필드별 선택 |
| `VehicleSignalHub.observations(source, clock, ...)` | 수동 운행의 입력당 한 번 전달. 실제 시간 타이머·중재 캐시 없음. 기존 단일 Source 생성자도 이 호환 경로 |

기존 `DebugHarnessController`는 명시적으로 `observations()`를 사용한다. 1초씩 가상 관측을 진행하는 운행 시계·개입 Tick·10초 Room 저장은 Hub 시험 시계와 독립이다. SDK/제품 세션은 정책 생성자로 새 Hub를 구성해야 하며, 디버그 수동 DB나 Hub 시험 상태를 가져오지 않는다.

`SignalNormalizer`의 기존 null/단위 정규화를 두 경로에서 공유한다. m/s→km/h는 app의 `UnitConverter`에서 한 번만 수행한다. 내비 입력은 이미 m·°·ms·sec로 정규화된 `NaviState`이며, 외부 km가 확인된 어댑터는 `distanceMeters(value, KILOMETERS)`로 변환한다. SDK의 미확인 원단위를 추측해 연결하지 않는다.

## 주기와 생명주기

- `EngineConfig.signalFrameIntervalMs=100`을 사용한다. 첫 발행은 시작 시각+100ms다. 프레임 `tMs`는 현재 `Clock.scenarioTimeMs()`, 실제 발행 경과 시각은 `publication.publishedAtElapsedMs`다.
- 정책의 `publicationTimeBasis`는 SCENARIO 또는 ELAPSED_REALTIME이다. `AUTOMATIC`은 코루틴이 깨어 주입 시계를 확인하고, `MANUAL`은 소유자가 `publishDue()`를 호출한다. 시계가 진행하지 않으면 타이머가 깨어도 발행하지 않는다.
- `samples`·`powerEvents`는 차량 프레임 주기 출력이고 `naviSamples`는 MSG-14의 `EngineConfig.naviStateIntervalMs=1_000`을 따른다. 내비 최신 캐시는 각 차량 Tick에서 품질을 재평가하므로 `latestNavi`와 1초 출력 통로의 용도를 구별한다. `rawVehicleObservations`는 정규화된 원천 콜백과 `updatedFields`다. 원천 RateMeter는 콜백 수집 때 갱신하므로 재발행과 무관하다. 동일 값의 새로운 시각도 관측으로 받는다.
- 주기 출력/원천 관찰 통로는 **replay 0·대기 1·DROP_OLDEST**다. 느린 소비자가 수집·발행을 막지 않으며, 해당 소비자는 오래된 대기 프레임을 잃을 수 있다. 프레임의 `publication.sessionId/sequence` 차이로 누락을 확인한다. 원천 콜백·고유 관측 계수는 소비자 누락과 별개다. 분석에 누락 없는 원본 보관이 필요하면 별도 수집 경계를 합의해야 한다.
- `latestFrame/Navi/Power`는 화면용 최신 상태다. `StateFlow`의 합쳐진 갱신을 원천 관측 횟수로 세지 않는다. [SharedFlow 공식 버퍼 계약](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/)을 따른다. 기존 Coroutines 1.10.2 의존성을 유지했다.
- 늦게 깬 Tick은 현재 한 장만 발행하고 놓친 deadline을 `skippedDeadlines`로 표시한다. 과거 시각·센서 관측을 만들어 채우지 않는다. 정상 주입 시계 60초 시험에서는 600장·간격 100ms·누락 0을 확인한다. Android/SDK 실시간 60초 성능 시험은 별도다.
- 입력 Flow 완료·실패는 그 채널 캐시를 다음 프레임부터 무효화한다. 다른 출처·내비·전원 수집은 계속한다. 모든 입력이 끝나도 Hub를 중지하기 전까지 null 품질 프레임을 발행한다.
- `stopInput(source)`는 해당 구독을 취소·대기하고 무효화한다. `startInput(source)`는 캐시·원천 계수를 지운 새 수집이다. 완료/실패 뒤 재연결도 stopInput→startInput 순서다.
- `stop()` 반환 후 새 발행은 없고 최신 값·캐시가 비워진다. 재시작은 새 세션/순번이며 이전 버퍼는 세션 필터에서 제거한다. 이미 소비자 콜백에 전달돼 처리 중인 작업까지 되돌리지는 않는다. 소유 scope 취소도 수집을 취소한다. 실제 SDK 콜백 해제는 Source 어댑터가 Flow 취소에 연결한다.
- 발행/논리/실제 경과 시계 역행은 `clockFailure`로 표시하고 발행을 중지한다. stop→start로 새 시간축을 구성한다.

## 입력·최신성·선택

`VehicleSignalInput`의 기본은 **완전 관측**이다. 누락/null은 기존 값을 지운다. 부분 갱신은 `updatedFields=setOf(...)`를 명시한다. 집합에 없는 필드만 유지하고, 집합 안의 null은 지운다. 내비·전원은 완전 관측 계약을 유지한다.

2026-11-08 실제 SDK의 방향지시등·브레이크·크루즈 상태/목표 속도 재조회에는 `confirmationFields`를 명시한다(기본 빈 집합). 확인 전용 필드도 캐시/최종 품질·확인 lease와 값 변경·명시 null을 반영하지만, `rawVehicleObservations`의 센서 관측·`vehicleCallbacks`·관측 순번에는 추가하지 않는다. 그 외 관측은 기존 통로로 전달한다. `updatedFields`의 부분 유지/명시 null 의미는 변하지 않으며, 재조회는 **새 센서 콜백이나 조작 근거가 아니다**. 같은 값의 재확인에서는 원천/실수신 시각도 유지한다. 기어의 기존 확인 경로·DEMO Player 입력 기본값은 유지한다. [실제 SDK 정책](ROLE1_PLAYGROUND_SDK.md#방향지시등브레이크크루즈-현재값-확인-2026-11-08)과 [실제/자동 시험 구분](../devlog/2026-11-08-state-signals.md)을 참고한다.

```kotlin
VehicleSignalInput(
    tMs = clock.scenarioTimeMs(), source = Source.REAL,
    gear = null, // 현재 기어를 모름. 다른 필드만 유지하는 명시적 부분 갱신
    updatedFields = setOf(SignalField.GEAR),
)
```

출처당 `HubSourceSet` 하나가 차량/내비/전원 Source를 소유한다. 바인딩과 다른 source를 표시한 입력은 ERROR로 격리한다. 필드별 품질은 가용 상태·출처·원천 시각/시간축·Hub 수신 경과 시각·원천 번호·선택 이유를 보관한다. 주기 발행은 원천/수신 시각을 변경하지 않는다. 같은 원천 시각의 AVAILABLE 중복은 값·수신 시각·원천 번호를 갱신하지 않는다. 역행 관측은 ERROR이며 RateMeter도 측정 불가가 된다.

최신성 정책은 모든 차량/내비 필드와 전원에 필수 주입한다. 관측 경과는 관측 시간축끼리, 수신 경과는 실제 경과 시계끼리 비교한다. **경계와 같으면 유효, 초과하면 STALE/null**이다. 미래 관측·역행 수신 시계는 ERROR다. 미지원·권한 거부·오류·미수신을 0/false로 대체하지 않는다.

원천 시각이 없으면 `REQUIRE_TIMESTAMP`는 UNAVAILABLE, 명시적으로 `USE_RECEIPT_AGE`를 선택한 경우만 수신 경과로 유효성을 판단한다. 어느 경우에도 원천 시각을 수신 시각으로 채우지 않으며 원천 Hz를 꾸며 내지 않는다.

| 조건 | 선택 |
| --- | --- |
| 유효·최신 REAL | 필드별 REAL_FRESH |
| REAL 무효, 대체 허용·유효 DEMO | DEMO_FALLBACK |
| REAL 상실 유지시간 설정 | 마지막 REAL 상실을 처음 평가한 시각부터 설정 기간 동안 null/REAL_LOSS_HOLD. 이후 대체 정책 적용 |
| 둘 다 불가/대체 금지 | null/NO_VALID_INPUT. 품질에 실패 사유 보존 |
| 유효 DEMO 위경도 쌍 | DEMO_LOCATION. 일반 REAL 우선·대체 금지보다 우선하는 위치 예외 |
| DEMO 좌표 쌍 없음/불가, 유효 REAL 쌍 | REAL_LOCATION |

위경도는 한 Source의 한 완전 관측에서 나온 쌍이어야 한다. 시각·시간축·수신 세대가 다르거나 한쪽이 null이면 쌍 전체를 사용하지 않는다. 위도 REAL·경도 DEMO 조합은 만들지 않는다. 방향·남은 거리·회전 거리는 좌표 예외와 별도로 일반 REAL 우선이다.

`quality.selectionReason`으로 최종 선택 이유를, `diagnostics.inputs[source].vehicleQuality/naviQuality/powerQuality`로 선택되지 않은 출처의 현재 품질도 관찰한다. 구독의 SecurityException은 PERMISSION_DENIED, 기타 오류는 ERROR, 정상 수집 종료·명시적 중단은 UNAVAILABLE이다. 외부 오류 원문은 로그에 노출하지 않는다.

## 원천 조향 RateMeter와 2번 연결

`engine/signal/RateMeter`는 Android 비의존이다. 창 길이 `EngineConfig.steeringRateWindowMs=10_000`, 기준 `minimumSteeringRateHz=5`는 SRS SIG_009의 **제안값**이다. 미세 수정 비활성·B 재정규화는 2번 담당이며 이번 구현에 넣지 않았다.

| 상황 | 측정 |
| --- | --- |
| 시작 전 | NOT_STARTED, hz/belowMinimum=null |
| 감시 시작 후 10초 미만 | WARMING_UP, hz/belowMinimum=null |
| 온전한 10초 창 | `(now−10초, now]`의 고유 시각 수÷10초. 50회=5Hz/false, 49회=4.9Hz/true |
| 구독 직전 생성돼 늦게 도착한 자료 | BEFORE_START로 창에서 제외. 이후 정상 관측을 막지 않음 |
| 같은 마지막 원천 시각 | DUPLICATE, 관측 수 증가 없음 |
| 원천/측정 시각 역행·미래·시간축 변경 | UNMEASURABLE, 재시작 전까지 hz=null |
| UNKNOWN/WALL 또는 사용 불가 조향 | 원천 Hz 측정 불가. 미수신과 미지원/오류를 구별 |
| 활성 수집의 온전한 창에 원천 관측 0회 | MEASURED, 0Hz/true |
| 수집 중단/완료/실패 | INTERRUPTED, hz=null |

REAL은 어댑터가 확인한 ELAPSED_REALTIME 원천 시각으로, DEMO는 원본 SCENARIO 시각으로 측정한다. DEMO의 시나리오 Hz를 실제 센서 Hz라고 표시하지 않는다. 60배 도착 속도나 Hub 발행 10Hz로 REAL의 10초 창·성능을 앞당기지 않는다.

2번 담당은 선택된 조향의 `frame.qualityOf(STEERING_DEG).sourceRateInfo`에서 **프레임과 같은 세대**의 status·timeBasis·hz·belowMinimum·관측 수를 받는다. 기존 `sourceRateHz`도 같은 측정값이다. 측정 불가/null을 ‘5Hz 이상’으로 해석하지 않는다. 발행 메타데이터의 순번은 센서 번호가 아니며, 보관된 값의 재발행을 새 센서 수정으로 세지 않는다.

화면의 ‘원천 번호’(`observationSequence`)는 Hub가 여러 필드 갱신에 부여하는 식별자다. 조향 관측 횟수는 `sourceRateInfo.acceptedObservations/observationsInWindow`, 콜백 횟수는 입력 진단의 `vehicleCallbacks`를 사용한다.

## 주기 Hub 조립 예시

최신성·우선권 수치는 제품 담당이 확정해 주입한다. 아래 `limits/fallback`에는 임의 기본값을 숨기지 않는다.

```kotlin
val policy = HubPolicy(
    vehicleFreshness = SignalField.entries.associateWith { limits },
    naviFreshness = NaviField.entries.associateWith { limits },
    powerFreshness = limits, fallback = fallback,
    publicationTimeBasis = ObservationTimeBasis.SCENARIO,
    tickDriver = HubTickDriver.AUTOMATIC,
)
val hub = VehicleSignalHub(
    sources = listOf(
        HubSourceSet(Source.REAL, vehicleSource, naviSource, powerSource,
            rateTimeBasis = ObservationTimeBasis.ELAPSED_REALTIME),
        HubSourceSet(Source.DEMO, demoVehicleSource, demoNaviSource, demoPowerSource,
            rateTimeBasis = ObservationTimeBasis.SCENARIO),
    ), clock = clock, policy = policy, config = EngineConfig(),
)
// 분석/화면 구독을 먼저 구성한다. 종료 시 hub.stop()으로 구독을 정리한다.
hub.start(ownerScope)
```

## 직접 조작 순서

`AwakeDrive 개발 검증` 상단의 **Hub 가짜 REAL/DEMO 검증**을 누른다. 독립 패널이며 **운행·Room 검증으로 돌아가기**로 복귀한다. 패널은 실제 시간이 아닌 **Hub +100ms/+1초/+10초** 버튼으로만 진행한다. 표시된 경과 시계도 이 시험의 가짜 경과 시계다.

**[가정] 시험 정책:** 관측/수신 TTL 각각 1,000ms, REAL 상실 유지 0ms·최신 DEMO 즉시 대체. REAL 초기 조향 1°·차속 80km/h·2Hz·좌표(37.5,127.0), DEMO는 8°·40km/h·10Hz·좌표(35.5,129.0)다.

| 순서 | 누를 버튼·입력 | 기대 출력 |
| --- | --- | --- |
| 1 | Hub 시험 초기화 → REAL 시작 → DEMO 시작 → Hub +10초 | 가상 10,000ms·발행 100회/10Hz, REAL 고유 관측 21회/2Hz/5Hz 미만 true, DEMO 101회/10Hz |
| 2 | 상단·오른쪽 품질 확인 | 조향 1°·차속 80·REAL/AVAILABLE/REAL_FRESH, 좌표(35.5,129.0)·DEMO/DEMO_LOCATION. 다음 회전은 REAL 500m |
| 3 | REAL 조향 **15**, 차속 **95** → REAL 값 적용 → Hub +1초 | 조향 15°·차속 95·REAL. 값만 바뀐 편집 상태와 적용된 설정은 구별 |
| 4 | DEMO 좌표 사용 체크 해제 → Hub +1초 | 위경도 모두 REAL (37.5,127.0), REAL_LOCATION. 다음 일시정지 시험을 원천 관측 경계에 맞춤 |
| 5 | REAL 관측만 일시정지 → Hub +1초 → Hub +100ms | 마지막 원천 관측에서 정확히 1,000ms까지 REAL 유지. 1,100ms에서 REAL 입력 STALE, 최신 DEMO 값 8°·40으로 대체. 주기 재발행이 수신 시각을 연장하면 실패 |
| 6 | DEMO 중단 → Hub +100ms | 둘 다 유효하지 않아 최종 값 null. 품질/선택 이유와 중단 상태 확인 |
| 7 | REAL 시작 → REAL 미지원 → Hub +100ms | 조향 null/UNSUPPORTED. 속도는 REAL 설정값 유지. 권한 거부/오류 버튼도 조향 null과 각각 다른 사유 |
| 8 | Hub 시험 초기화 → REAL 5Hz → REAL 시작 → Hub +10초 | 5.00Hz·5Hz 미만 false. 2Hz/10Hz도 초기화 후 같은 방식으로 비교 |
| 9 | Hub 중지 → Hub +1초 | 발행 횟수 증가 없음·최신 값 비움. Hub 재시작 → Hub +100ms는 새 세션·입력 전 null |
| 10 | 운행·Room 검증으로 돌아가기 | 기존 운행 가상 시각·운행 ID·정차 근거·단계·저장본 유지. Hub 시험 시간을 운전에 합산하지 않음 |

좌표/기타 필드의 상세 관측·수신 시각은 오른쪽 출력을 스크롤해 확인한다. REAL 일시정지 상태에서 Hub 시간을 10초 더 진행하면 원천 0Hz가 되며, REAL 중단은 INTERRUPTED/null로 구별한다. 기존 정차·인증·회복·Room 재실행 버튼 순서는 [운행 검증 가이드](DEBUG_HARNESS.md), [저장·복원 가이드](ROLE1_PERSISTENCE.md)를 따른다.

고유 관측 누적 21회/51회에는 시작 시각 0ms의 한 회도 포함된다. 측정 창은 왼쪽 경계를 제외하므로 10초 시점의 창 안에는 각각 20회/50회가 있어 2Hz/5Hz다.

시험 집계·기기 증거·SDK TC 미완료 범위는 [개발 기록의 Hub 절](../devlog/2026-11-03.md#vehiclesignalhub-주기품질중재-구현)에 남긴다.

## 2026-11-06 후속 — 완전 관측 인계와 발행 스케줄

JSON의 차량·내비·전원을 한 번에 인계하는 완전 관측 경로, Player의 원본 준비, 짧은 묶음 양보·명령 기아 방지를 검증했다. 기본 실행 통로는 `DEFAULT_LANE`이며 시간축·최신성·REAL 우선·DEMO 좌표 예외·원천 Hz 계약은 유지한다. 이전350개 시험 기록에 이어 현재 **engine149개+app227개=376개·assembleDebug 통과**를 확인했지만, **실제 예상외null0 목표는 미달**이다. 같은 AVD의 전후 측정·발행 누락과 원본 감사의 구분·미해결 항목은 [후속 개발 기록](../devlog/2026-11-06-demo-transport.md)에 정리했다.

최초 QUERY도 `confirmationFields`로 표시한다. 값이 바뀐 초기 조회/재조회는 상태 캐시에 반영하지만 콜백 계수·raw 센서 관측·관측 순번·조향 원천 Hz를 늘리지 않는다. actual callback은 별개다. AAOS 기어도 이 계약을 따른다.
