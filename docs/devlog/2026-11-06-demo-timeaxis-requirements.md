# 2026-11-06 검토 초안 — 데모 원본·Hub·분석 소비·UI의 시간축

## 검토 범위와 현재 판정

1번의 DemoPlayer·VehicleSignalHub·검증 화면 계약을 검토했다. 다른 담당의 엔진을 새로 연결하거나 계산 규칙을 바꾸는 제안은 포함하지 않는다. 이 문서는 요구 검토 초안이며, 기존 실제 예상외null0·Hub 발행 완전성의 미달 판정을 유지한다. 코드·설정·기기·Gradle은 변경하지 않았다.

[AGENTS.md](../../AGENTS.md) 10~13행은 현재기능 기획서를 구현의 단일 출처로 정한다. 아래에서는 **기획에 명시된 요구**, **현 문서·코드의 구체 계약**, **그 계약에서 계산한 처리량**, **아직 명확하지 않은 제품 계약**을 구분한다. 행 번호는 이 초안 작성 시점의 로컬 파일 기준이다.

## 원문에서 확인한 요구

| 근거 | 명시한 내용 | 이 근거만으로 확정되지 않는 것 |
| --- | --- | --- |
| [현재기능 기획서](../plan/깨어있는드라이브_상세기획서_v4_현재기능.md) 504행 | 데모도 같은 VehicleSignalHub에 들어가 같은 판단 로직을 거침 | 분석 입력의 버퍼·손실 정책, UI 표시 주기 |
| 같은 기획서 508행 | Hub가 SDK 입력을 10Hz 신호 묶음으로 정리 | 데모 때 이 10Hz의 시간축을 실제/시나리오 중 무엇으로 정할지 |
| 같은 기획서 554행 | 시나리오 JSON에 신호를 10Hz로 수록 | Android 호출의 정확한 실시간 지터·처리 지연 허용치 |
| 같은 기획서 544·455행 | 졸음 데모 60배, 데모 화면의 1·10·60배 선택·그래프·재생 표시 | UI를 초당600번 그리라는 요구 |
| 같은 기획서 556행 | 데모 재생 중 Virtual Console의 실제 값 우선 | REAL 원천 센서의 콜백을 60배로 늘리는 요구 |
| 같은 기획서 172·178행 | 조향 지표는 1분 창·30초 갱신, 급조향 정의에는 1초 구간 사용 | 30초마다 결과를 갱신하므로 그 사이 원본을 버려도 된다는 허용 |

**기획서에는 `실제600Hz`라는 숫자가 직접 쓰여 있지 않다.** 현재의 `시나리오10Hz + 60배 + SCENARIO 발행` 계약을 함께 적용하면 필요한 처리량이 실제초당600개/600시한으로 계산된다. 숫자가 산술 결과라는 사실은 현재 성능 기준을 낮추는 근거가 아니다.

## 현재 구현에서 각각의 주기가 뜻하는 것

일반60배가 유지되는 구간에서 실제1초는 시나리오60초다. 시나리오100ms는 실제 약1.667ms다. 질문 중에는 실효1배, 일시정지에는0배다. [Demo 가이드](../setup/ROLE1_DEMO_PLAYER.md) 11~15·49~53행과 [Clock.kt](../../src/android/engine/src/main/java/com/sleepissue/awakedrive/engine/time/Clock.kt) 7~15행이 시나리오/실제 경과/날짜를 나눈다.

| 경로 | 기준·현재 계약 | 실효60배 구간에서 필요한 처리 |
| --- | --- | --- |
| JSON 원본 시각 | 시나리오100ms마다 하나. [DemoScenario.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/demo/DemoScenario.kt) 8~9·28~29행, Demo 가이드84행 | 원본을 누락 없이 실시간으로 따라가려면 실제초당600개의 완전 원본을 전달·수신해야 함 |
| 완전 관측 Source→Hub | 차량·내비·전원을 가진 원본 하나를 한 번 인계. [HubContracts.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/signal/HubContracts.kt) 19~34행 | 완전 관측600회/실제초. 각 원본 안의 세 채널 정규화·캐시·감사는 모두 수행 |
| 기존 세 Source→Hub | 같은 원본을 vehicle/navi/power Flow로 각각 인계. [ScenarioDemoPlayer.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/demo/ScenarioDemoPlayer.kt) 312~333행 | 각 채널600회/실제초, 세 Flow emit 합계는1800회/실제초. 원본 수를1800개로 세지 않음 |
| Hub 차량·전원 주기 출력 | `signalFrameIntervalMs=100`, 현재 debug 정책의 발행축은SCENARIO. [EngineConfig.kt](../../src/android/engine/src/main/java/com/sleepissue/awakedrive/engine/config/EngineConfig.kt) 22행, [DebugDemoController.kt](../../src/android/app/src/debug/java/com/sleepissue/awakedrive/debug/DebugDemoController.kt) 445~451행 | 실제초당600개의 발행 시한. 현재 계약의 완전 발행 목표를 유지하려면 이 시한을 처리해야 함 |
| Hub 내비 주기 출력 | `naviStateIntervalMs=1000`. [Hub 가이드](../setup/ROLE1_SIGNAL_HUB.md) 24행, EngineConfig50행 | `naviSamples`는60회/실제초. 입력 원본의 내비600회/초와 다른 수치 |
| Hub `latestFrame/Navi/Power` | 각 발행에서 갱신하는 최신 상태, StateFlow는 중간 갱신을 합칠 수 있음. Hub 가이드26행 | 생산자는 현재 발행률로 갱신하지만 UI가 모든 갱신을 읽을 의무는 없음 |
| 원천 관찰 `rawVehicleObservations` | 정규화된 원천 콜백별 출력. Hub 가이드24~25행 | DEMO의 원천 관측을 전부 소비하려면600개/실제초를 처리해야 함. 현재 버퍼가 무손실을 보장하지는 않음 |
| 분석용 `samples` | 주기 Hub에서는 선택·최신성 평가를 거친 차량 발행 프레임. Hub 가이드24~27행 | 모든 발행 프레임을 소비하는 분석 연결은600개/실제초를 처리할 수 있어야 함. 결과 계산/표시 주기와 별개 |
| debug 화면 렌더링 | Activity의 실제 `delay(100)` 폴링. [DebugDemoActivity.kt](../../src/android/app/src/debug/java/com/sleepissue/awakedrive/debug/DebugDemoActivity.kt) 168~175행 | 약10회/실제초의 최신 상태 표시.600회/초 렌더링은 현재 계약에 없음 |
| 상세 trace | 실제100ms 또는 상태 변화 시 보관. Demo 가이드134행 | 기본 상세 표집은 약10행/실제초. 원본·발행 전체 로그와 다르며 전환 시 추가 행 가능 |

위 수치는 완전한 내부 구간의 처리량이다. 시작 원본`t=0`과 첫 Hub 시한`start+100ms`, 종료 무효화·최종 시한의 순서 때문에 전체 원본 수와 전체 발행 수를 항상1:1로 대조하지 않는다. [Hub 가이드](../setup/ROLE1_SIGNAL_HUB.md) 22·27행, Demo 가이드68행에 따라 원본 감사와 발행 시한 감사를 따로 계산한다.

### 600회/초와 원천 Hz 표시의 구분

DEMO의 `sourceRateInfo`는 **원본 SCENARIO 시간 기준10Hz**다. 실제 수신 처리량이600회/초여도10Hz를600Hz로 바꾸지 않는다. REAL의 원천 Hz는 해당 어댑터가 제공한 실제 시간축을 따른다. [Hub 가이드](../setup/ROLE1_SIGNAL_HUB.md) 80~84행은 DEMO 원천 Hz·실제 센서 Hz·관측 번호·콜백 수를 구분한다.

마찬가지로 SCENARIO 축의 Hub 발행률은 정상 시10Hz로 표시될 수 있다. 실제초당 발행량600이라는 처리량과 모순되지 않는다. 계측 결과에는 시간축을 함께 적어야 한다.

## 분석 소비 API에서 확인한 주의점

[VehicleSignalHub.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/signal/VehicleSignalHub.kt) 12~22행은 서로 다른 두 생성 경로를 제공한다. 같은 `samples` 이름이 모든 경로에서 같은 버퍼 계약을 뜻하지 않는다.

| API·경로 | 현재 보장·제약 | 이번 검토의 판정 |
| --- | --- | --- |
| `observations()`의 `samples` | 입력당 전달, 활성 소비자가 느리면 대기, replay/추가 버퍼 없음. [ObservationSignalHub.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/signal/ObservationSignalHub.kt) 33~40·60~70행 | 수동 운행 호환 경로의 계약. 새 주기 경로의 무손실 근거로 일반화하지 않음 |
| 주기 Hub의 `samples`·`rawVehicleObservations` | replay0·대기1·`DROP_OLDEST`. [PeriodicSignalHub.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/signal/PeriodicSignalHub.kt) 67~82행 | 느린 분석 소비자는 프레임/관측을 잃을 수 있음. 생산자의 원본 감사 성공만으로 분석 소비 무손실을 증명할 수 없음 |
| 주기 Hub의 `latest*` | 화면용 최신 상태. Hub 가이드26행 | 원본 개수·미세 조향 횟수·분석 전체 샘플의 근거로 사용하지 않음 |
| 주기 Hub의 원천 캐시·RateMeter | 수신 시 갱신하고 원천 관찰 Flow는 활성 소비자가 있을 때만 발행. PeriodicSignalHub208~226행 | 원천 수신 계수와 소비자 수신 계수를 별도로 계측해야 함 |
| 기존 분석 예시 | [ROLE1_SCAFFOLD.md](../setup/ROLE1_SCAFFOLD.md) 28~31행은 `hub.samples.collect`를 제시하지만, 101~106행은 관측 모드 예시라고 명시 | 예시가 최신 주기 경로의 `DROP_OLDEST`보다 강한 무손실 계약을 부여하지 않음 |

주기 Hub는 늦게 깨어도 현재 프레임 한 장만 발행하고 빠진 시한을 `skippedDeadlines`에 남긴다. [PeriodicSignalHub.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/signal/PeriodicSignalHub.kt) 281~311행의 구현은 과거 프레임 보충을 하지 않는다. 따라서 **원본 누락0·발행 시한 누락0·소비자 순번 누락0은 서로 다른 검증 항목**이다.

현재 debug 데모는 `latest*`를 읽어 검증 snapshot을 만들며([DebugDemoController.kt](../../src/android/app/src/debug/java/com/sleepissue/awakedrive/debug/DebugDemoController.kt) 410~418행), 예보·개입·TripTracker를 붙이지 않았다는 범위를 [Demo 가이드](../setup/ROLE1_DEMO_PLAYER.md) 40·106행에 명시한다. 1번에서 원본·Hub를 검증한 결과를 다른 담당의 분석600회/초 E2E 성공으로 확대하지 않는다.

조향 지표의 결과를 시나리오30초마다 갱신한다는 요구는 입력30초마다 하나만 읽어도 된다는 뜻으로 해석할 수 없다. 원본100ms 해상도를 유지하는 연결은 그 사이 원본/선택 프레임을 소비해야 한다. 특히 기획서178행의1초 내 급조향 정의는 60배에서 실제 약16.667ms 구간에 해당한다. 이것은 입력 보존의 필요를 설명하는 시간 변환이며, 2번의 엔진 계산·버퍼 방식을 이번 검토에서 결정하지 않는다.

## 명시·가정·불명확점과 유지할 기준

- **명시:** JSON10Hz, Hub10Hz 묶음, 60배 데모, 같은 Hub·같은 판단, REAL 우선은 현재기능 기획서에 있다. UI600Hz 요구는 해당 원문에서 확인하지 못했다.
- **현 구체 계약:** DEMO 원본10Hz는SCENARIO 기준이고, debug Hub의100ms 발행축도SCENARIO다. 이 조합의 원본·차량 발행 목표는600회/실제초다. 현재 성능 미달을 실제 UI10Hz로 상쇄하지 않는다.
- **구현 선택:** 일반 `HubPolicy`는SCENARIO/ELAPSED_REALTIME을 둘 다 받을 수 있다([HubContracts.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/signal/HubContracts.kt) 36~49행). 이 API의 존재만으로 현재 debug 정책을 실제10Hz로 바꾸거나 기획의 분석 해상도를 낮출 수 있다고 판정하지 않는다.
- **[가정]:** debug의 관측/수신TTL1,000ms·REAL 상실 유지0은 Controller446~451행에 표시한 화면 검증용 정책이다. SCENARIO 관측TTL1,000ms는60배에서 실제 약16.667ms지만, 실제 수신TTL은 실제1,000ms다. 시간축이 다르므로 수신 직후에도 원본이STALE일 수 있다.
- **불명확:** 최종 제품의 데모 발행축, 분석 소비자의 필수 통로·무손실 보관/역압 정책·손실 시 처리, 실제600회/초 지터·지연 허용치, 최종 UI 표시 주기는 검토한 현재기능 원문만으로 모두 확정되지 않는다. 기존 Hub 문서25행도 무손실 분석 원본 보관이 필요하면 별도 수집 경계를 합의해야 한다고 명시한다.
- **유지:** 이 불명확점이 정리되기 전에도 이번 검증의 기존 계약은 유지한다. TTL 확대·원본 시각 변경·원본 삭제·마지막 값 덮어쓰기·과거 프레임 생성으로 처리량/최신성 문제를 숨기지 않는다. 기존 미달 결과는 [전달·스케줄 후속 기록](2026-11-06-demo-transport.md)에 남긴다.

후속1번 검증에서는 원본3채널 감사, Hub 발행 시한·선택 품질, 활성 분석 구독자의 세대/순번과 실제 소비 개수, UI 실제 표시 횟수를 분리해 보고하면 된다. UI 표시 빈도를 낮게 유지하면서도600원본/초·600발행시한/초·필요한 분석 소비의 계약은 각각 보존해야 한다.
