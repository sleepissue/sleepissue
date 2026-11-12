# DemoClock·JSON 재생과 debug 검증

> **2026-11-11 제품 경로:** 이 문서의 독립 메모리·가짜 질문 설명은 기존 debug 검증 화면의 범위다. 제품 앱에는 원본6종 assets→동일 Hub→기존 판단 파이프라인, 실제 TTS 배속 잠금, 제품 배지, 격리 Room의 ‘시동 끔→새 실행+22분’ 경로가 추가됐다. [제품 실행 기록](../devlog/2026-11-11-product-demo-release.md)과 [인계 후 최종 검증/README 순서](../devlog/2026-11-11-resume-final.md)를 우선한다. 최종773개 JVM·빌드는 이번 실행이고 기기3건·새PID 복원은 기존 근거 재사용이다. DMO006 전체·60배 성능·내부 학습창 복원은 여전히 별도다.

이번 구현은 **JSON → DemoPlayer → 기존 VehicleSignalHub** 입력 경로다. `develop-jdw`의 `7bc5ffd`를 기준으로 추가했으며, 기존 수동 운행/Room·가짜 Hub·Playground SDK·음성 검증 화면은 유지한다. 데모 화면은 독립 메모리 세션이며 Room을 열거나 저장·삭제하지 않는다.

JSON 작성 규칙·Schema·최소 예제는 [5번 담당 작성 가이드](ROLE1_DEMO_JSON.md)를 따른다. 기준 요구사항은 [SRS v0.2](../design/AwakeDrive_SRS_TC_v0.2.xlsx)의 Sys Req Spec/Test Case 152~155행 `DMO_001~004`, Data Interface 75~85행 `MSG-11`, 22행 `SIG_008`이다. 원본 Excel은 수정하지 않았다. 6종 시나리오 완성과 기기 E2E 전체 통과를 뜻하지 않는다.

## 시간축과 PR #7·#8 계약

| 시각 | 공급·의미 | 배속 |
| --- | --- | --- |
| JSON `t` | 시나리오 시작 후 초. 로더가 정수 상대 ms로 변환 | 원본 관측 시각 자체는 바꾸지 않음 |
| `DemoClock.scenarioTimeMs()` | JSON 상대 ms. 운행·Hub·단계/데모 스케줄 | 구간별 1·10·60, 질문 중 1, 일시정지 0 |
| `DemoClock.elapsedRealtimeMs()` | 주입한 실제 단조 시계 그대로. 음성 반응·오디오·Hub 수신 시각 | 항상 실제 시간 |
| `DemoClock.wallTimeMs()` | 주입한 기기 날짜 시계 그대로. 저장 기록용 | 항상 기기 날짜 |
| `EpochScenarioClock.scenarioTimeMs()` | `startEpochMs + 상대 ms`. **예보 전용 투영** | 운행과 같은 날짜 진행 |

실제 시계는 Android 조립부에서 `SystemClock.elapsedRealtime()`·`java.time.Clock.systemUTC()`로 제공한다. 엔진은 시스템 시각을 직접 읽지 않는다. 수동 `DebugHarnessController`의 시계나 저장 시간 기준을 일괄 교체하지 않는다.

`DemoClock`은 경계까지 소요한 실제 시간을 계산하고 남은 실제 시간은 다음 구간의 배속으로 적분한다. tick이 늦게 깨어도 지난 구간 속도를 새 구간에 일괄 곱하지 않는다. 실제 시계가 역행하면 재생은 `FAILED`로 멈추고 마지막 안전 시각의 null 무효화를 내보낸다. 중지한 후 다시 로드한다.

화면의 **실제 경과**는 시작부터의 실제 시간이며 일시정지 중 기다린 시간도 포함한다. 운행 시각은 일시정지 중 증가하지 않는다. 종료 뒤 실제 경과 표시는 고정된다. 응답 시간은 이 표시 전체가 아니라 **신호음 끝 → 첫 목소리 시작**의 실제 차이다. 판정/STT 처리가 늦어져도 첫 응답 시간을 늘리지 않는다.

### 예보 재사용

PR #7 `ForecastEngine`은 `scenarioTimeMs()`를 `Instant.ofEpochMilli()`로 해석한다. 상대 0을 그대로 주면 1970년이 되므로 예보 생성자에만 다음 어댑터를 넣는다.

```kotlin
val mapping = ScenarioEpochMapping(scenario.startEpochMs)
val forecastClock = EpochScenarioClock(demoClock, mapping)
val forecastEngine = ForecastEngine(forecastClock) // 기본 Asia/Seoul
// 입력의 연속/누적 운전 분은 기존 운행 담당이 제공한다.
val result = forecastEngine.calculate(input, ForecastRecalcTrigger.TRIP_START)
val assessment = ForecastAssessment(
    tMs = demoClock.scenarioTimeMs(),
    evaluationStatus = EvaluationStatus.EVALUATED,
    dueAtMs = mapping.toRelativeMs(result.expectedDrowsyAtMs),
)
```

이 코드는 예보 소유자의 조립 계약이다. `DemoPlayer`는 예보/졸음 엔진을 호출하지 않는다. 이번 debug 화면에도 예보·개입/TripTracker는 연결하지 않았다. 어댑터와 실제 `ForecastEngine`을 함께 쓰는 날짜·취약 시간대·결과 왕복 시험을 추가했다.

### 음성 재사용과 아직 부족한 연결

PR #8 `CheckManager`는 이미 `elapsedRealtimeMs()`만 사용한다. `DemoClock`을 주입해도 실제 반응은 배속되지 않는다. debug는 이 판정기를 그대로 사용하고, 마이크/TTS/STT 대신 버튼으로 이벤트를 보낸다.

실제 연결은 [CheckPort 계약](ROLE1_SCAFFOLD.md#운행-상태외부-기능-연결)의 `STARTED`와 최종 결과를 동일 ID로 연결해야 한다.

1. 요청 접수 `REQUESTED`·보류 `DEFERRED`에서는 질문 잠금을 걸지 않는다.
2. **실제 안내 음성 시작** 시 현재 `playbackState.sessionId`와 새 질문 `RequestId`로 `QuestionStarted`를 보낸다. 즉시 1배속이 된다. 안내·신호음·응답 대기·판정 완료까지 같은 잠금을 유지한다.
3. 최종 확인 판정이면 `QuestionFinished(..., FINISHED)`, 장치 실패이면 `FAILED`, 실행 취소이면 `CANCELLED`를 보낸다. 이벤트 처리 시 즉시 현재 위치의 구간 배속으로 돌아온다. 1초 뒤에 복원하는 타이머는 없다.
4. `finally`를 포함한 모든 종료 경로에서 해제한다. 느림 뒤 30초 재질문 예약은 잠금 밖이며, 다음 질문은 새 ID다.

한 세션의 같은 시작 ID는 멱등이며 다른 ID의 중첩은 거부한다. 종료된 ID로 다시 시작할 수 없다. 시작 없는 종료는 거부하고, 중복 종료는 재적용하지 않는다. 이전 재생 세션의 시작/종료는 현재 질문을 해제하지 않는다. 종료·초기화는 활성 질문도 취소한다. 일시정지 중에는 0배, 질문을 유지한 채 재개하면 1배다.

질문 도중 파일 끝에 도달하면 재생과 가짜 질문을 함께 취소한다. 향후 실제 음성을 조립할 때는 소유자가 이 재생 종료와 음성 실행 취소도 함께 연결해야 한다.

**실제 음성 연결은 미완료다.** 현재 `VoiceAgent`/`VoiceCheckCoordinator`에는 이 요청 ID·세션·실제 안내 시작 통보가 없다. `PleosTtsOutput.onStart()`도 상위로 전달하지 않는다. 이 통보를 연결하고 취소·실패·늦은 콜백 세대를 검증해야 한다. `engine.check.CheckResult.ts`는 실제 경과 시각이며 JSON 상대 시각과 빼지 않는다. 기존 개입 관리자의 `engine.model.CheckResult`에는 requestId/context와 latency/verdict/intent를 변환하는 연결도 필요하다. 이번에는 이 API를 임의로 바꾸거나 실제 음성 완료로 표시하지 않았다.

## Player 상태·구독·Hub 조립

| 명령 | 결과 |
| --- | --- |
| Load | Repository의 검증된 파일을 로드해 `READY`·상대0·새 sessionId. 실패는 코드/JSON 위치를 가진 `FAILED` |
| Start | `READY`에서 처음부터 `PLAYING`. 소유 scope가 종료됐으면 거부 |
| Pause / Resume | 명령 처리 시 시계를 먼저 동결하고 그 위치까지 관측을 전달 / 멈춘 위치에서 재개 |
| Stop | 상대 위치 보존·질문 취소·`STOPPED`·DEMO 값 무효화. 재생하려면 다시 Load→Start |
| Reset | 파일·위치·질문을 비우고 `NOT_LOADED`·0ms·새 세션. DB 삭제가 아님 |
| 자연 종료 | 마지막 샘플+100ms 도달 및 전체 원본 전달 후 `COMPLETED`·무효화·재생 배지 종료 |

`ScenarioDemoPlayer(clock, repository, scope)`는 자동 ticker를 소유한다. 결정적 시험/별도 조립은 `automaticTicks=false`와 `pump()`를 사용할 수 있다. Source 출력은 replay 없는 Flow이며 구독이 없는 과거 자료를 재생하지 않는다. 느린 구독자는 전달을 지연시킬 수 있다. 플레이어는 늦게 전달하더라도 원본 관측 tMs·순서를 보존한다. 후단 Hub의 발행 deadline 누락/느린 소비자 버퍼 계약은 [기존 가이드](ROLE1_SIGNAL_HUB.md)를 따른다. 실제 60배 처리 성능을 JVM 가상 시계 결과만으로 보증하지 않는다.

```kotlin
val demoClock = DemoClock(realClock)
val player = ScenarioDemoPlayer(demoClock, repository, ownerScope)
val hub = VehicleSignalHub(
    sources = listOf(
        HubSourceSet(Source.REAL, vehicle, navi, power, realObservationBasis),
        HubSourceSet(Source.DEMO, player, player, player, ObservationTimeBasis.SCENARIO),
    ), clock = demoClock, policy = agreedPolicy,
)
// 결과 소비자를 구독하고 Load한 뒤 Hub.start → Player.Start 순서로 연결한다.
```

차량/내비/전원 품질의 출처는 `DEMO`, 원천 시각은 JSON 상대 ms/`SCENARIO`다. Hub가 실제 수신 경과 시각을 붙인다. Player가 원천 시각을 현재 tick으로 바꾸거나 REAL 우선·DEMO 위치 쌍 예외를 덮어쓰지 않는다. 차속은 km/h로 공급해 다시 ×3.6하지 않는다. DEMO의 10Hz는 시나리오 시간 기준이며 실제 센서 Hz가 아니다.

**소유자는 Stop 후 Hub도 중지한다. Load/Reset으로 시각을 0으로 돌리기 전에도 Hub를 먼저 중지하고 새 세션으로 시작한다.** Player의 null 무효화는 입력 캐시를 지우지만 SCENARIO 시계가 멈추면 Hub 최신 출력은 다음 tick 전까지 남을 수 있다. debug는 `hub.stop()`으로 출력과 대기 세대도 함께 비운다. 입력만 제거하고 REAL 감시를 계속할 제품 조립은 `stopInput(DEMO)`와 실제 발행 시계 정책을 별도로 선택해야 한다. 수동 운행 DB·운행 상태를 이 생명주기에 합치지 않는다.

## 직접 누를 순서

debug APK의 **AwakeDrive 개발 검증 → JSON 데모·배속 검증**으로 들어간다. 이 화면이 보이는 동안만 세션을 유지하고 다른 화면으로 나가면 정리한다. OS의 기존 주행 중 화면 제한을 우회하는 메타데이터는 추가하지 않았다.

1. **검증 예제 로드 → 시작**. `READY → PLAYING`, **데모 재생 중** 배지, JSON 차속40과 좌표35.5/129.0을 본다. 예제는 3,630샘플·시나리오363초다. 질문이 없으면 실제 약3초에10배, 약9초에60배, 약14초에 완료된다.
2. 10배 또는60배에서 **일시정지**를 누르고 기다린다. 시나리오 ms는 고정, 실제 경과만 증가한다. **재개**하면 같은 위치부터 진행한다.
3. 새로 로드·시작하고 10배에서 **① 가짜 안내 시작 → ② 신호음 끝 → 실제 약2.3초 후 ③ 목소리 시작 → 약0.7초 후 ④ 판정 종료**를 누른다. 안내 시작부터 실효1배, 실제 응답은 약2300ms·느림이고 판정을 기다린0.7초는 응답 시간에 더해지지 않는다. 종료 직후 현재 구간의10/60배로 돌아온다. 손으로 누르므로 정확한2300ms 일치는 자동시험에서 확인한다.
4. **① → 질문 실패**, 다시 **① → 질문 취소**를 눌러 모두 배속이 복구되는지 본다. **① → ②** 뒤 목소리 없이 실제5초를 기다리면 `NO_RESPONSE`로 자동 판정 종료한다. **중복 안내 시작**은 같은 질문을 유지한다. 질문A 종료 뒤 새B를 시작하고 **이전 판정 재전송**을 눌러 B의 ID와1배 잠금이 남는지 본다.
5. 재생 중 **가짜 REAL 켜기**. 차속은80/`REAL_FRESH`, 위경도는 계속35.5/129.0/`DEMO_LOCATION`이다. **가짜 REAL 끄기** 뒤 차속40/`DEMO_FALLBACK`으로 돌아온다. SDK 수신 시험이 아니다. `[가정]` 화면용 TTL1초·REAL 상실 유지0을 적용한다.
6. **중지** 후 재생 배지가 꺼지고 Hub 최종 값이 null인지 본다. **검증 예제 로드 → 시작**으로 재시작한다. **초기화**는 `NOT_LOADED`·0ms·Hub 중지이며 기존 Room 저장본은 남는다.
7. 오른쪽 JSON 입력에서 `t:0.1`을 `t:0`으로 바꿔 **입력 JSON 로드**하면 `INVALID_TIME`, 배속을2로 바꾼 파일은 `INVALID_SPEED`, JSON 문법을 깨면 `INVALID_JSON`과 위치가 표시된다. **Hub 표시 고정/재개**는 값만 고정하며 재생 배지·질문·시간은 계속 갱신한다.

## 검증 범위

자동시험은 구간 경계·큰 tick·질문 중 경계 이동·pause/resume·실제2300ms 응답·늦은 판정·중복/중첩/실패/취소/세대·stop/reset·null 무효화와 기존 Hub의 REAL 우선/DEMO 위치 예외를 확인한다. 기존 단위시험도 함께 실행한다. 최종 건수와 빌드 결과는 [2026-11-05 개발 기록](../devlog/2026-11-05.md)에 남긴다.

**미수행:** 기기 버튼/스크린샷 확인, 실제 SDK와 JSON 동시 수신, 실제 TTS/STT 질문 lifecycle, 실제 처리속도 ±1%/REAL 전환≤110ms 측정. 확인 당시 `adb devices -l`에 연결 기기가 없었다. APK 설치·제거·Wipe·`pm clear`·`connectedDebugAndroidTest`는 실행하지 않았다. Room 데이터/스키마와 기존 저장 코드는 변경하지 않았다.

남은 통합은 제품용 Hub/TripTracker/개입/예보·실제 음성 연결, 5번 담당의 6종 시나리오, `DMO_005~006` 저장/재실행·출발 피로 E2E, 서비스/부팅 수명이다. 이번 기반 재생 구현과 구분한다.

## 2026-11-05 후속 — 60배 장기 재생 성능 점검

위의 연결 기기 없음·324개 시험 기록은 최초 구현 당시 결과다. 이후 기기에서 60배 장기 재생을 추가 점검했고, 재생 중 입력이 `STALE`이 되는 현상을 확인했다. 길이1,800,000ms 시나리오의 `PLAYING` 시점191,186ms에서 마지막 원본은189,200ms, 관측 나이는1,986ms, 실제 수신 나이는3ms였다. 원본 수신 직후에도 배속된 관측 시계에서는 검증용 TTL1,000ms를 넘을 수 있다.

수정 전에는 전체null54tick·전체null 상세35행(질문 취소 뒤22행), 원본 전달18,000개·Hub 차량 콜백18,001회·발행6,241회·누락deadline11,758회를 기록했다. 콜백에는 종료 무효화1회가 포함된다. 원본 전달 수, Hub 수신 수, 발행deadline 누락은 별개다. Player만 고친 중간 실험은 전체null205tick/`PLAYING`5,995tick·전체null 상세59행·누락deadline12,453회로 성능 개선을 확정하지 못했다. 이어서 debug 재생·수신을 화면 그리기와 분리했다.

**최종 수정본도 이 기기에서 전체null과 deadline 누락이 남는다.** worker 분리 후 첫 재생은 전체null85tick/재생7,801tick·상세27행·누락deadline10,831회, 같은 프로세스 반복은66tick/재생9,655tick·상세24행·누락deadline9,227회였다. 두 실행 모두 원본18,000개·Hub 콜백18,001회·`COMPLETED`·Hub 중지를 확인했다. 최초 설치본에는 재생tick 분모가 없고, 최종 실행은 창 없는 같은 AVD에서 측정했으므로 단순 수치 차이를 성능 개선율로 해석하지 않는다. [근거 요약](../devlog/2026-11-05-demo-performance.json)과 [로컬 원본 trace](../../src/android/app/build/reports/demo-performance/)에 자료를 보존했다. 60배의 Hub 발행 완전성은 미달 상태다.

### 추가한 처리와 한계

- `pump()`는 세 채널의 완전 관측을 전달할 때마다 현재 시각을 다시 읽어 도달한 원본까지 따라잡는다. 원본의 순서·관측 시각을 유지한다.
- 한 번의 작업은 실제 경과25ms를 기술 예산으로 삼고, 완전 관측 경계에서 반환한다. 세 채널 전달을 마친 원본만 전달 수에 포함하며 잔여 원본은 다음 `pump()`에서 이어간다. 이 예산은 단일 `emit`의 강제 시간상한이 아니다.
- Pause 처리 시 먼저 시계를 동결한다. 예산 때문에 남은 원본은 `PAUSED`에서도 동결 시각까지 전달한다. 종료 시각에 도달해도 원본이 남으면 잠시 `PLAYING`·실효0배일 수 있으며, 전체 전달 후에 무효화하고 `COMPLETED`로 바꾼다.
- debug Activity의 Main scope는 화면·Toast를 처리한다. 같은 부모 Job의 `Dispatchers.Default.limitedParallelism(1)` 작업 scope가 재생·Hub 수신·명령·trace 복사·종료를 처리한다. trace 요약도 불변 snapshot으로 화면에 전달한다.

다른 `pump()`가 명령 잠금을 점유 중이면 Pause 처리가 기다릴 수 있다. 여기서 동결 시각은 명령 잠금을 얻은 뒤 시각이며, 버튼을 누른 입구 시각을 별도로 보장하는 계약은 아니다. 앱 내부 시나리오/단조시계 비율은 최종 두 실행에서60.000배였지만 독립 시계 교정이나 실제초당 Hub600프레임 보장은 아니다. 실제 SDK+JSON 동시 수신·실제 TTS/STT·REAL 전환110ms는 이번 결과에 포함하지 않는다.

TTL, JSON 관측 시각, Hub의 REAL 우선·DEMO 위치 쌍 선택, 원천 Hz 계산 정책은 그대로다. 실제 SDK 수신·TTS/STT 연결을 추가한 작업은 아니다. 후속 회귀시험은 기존 지연 재현2개를 갱신하고 지속 지연·PAUSED 잔여 전달·처리 중 종료3개를 더한 총5개다. 전체 재실행 결과는 engine146개+app183개=329개 통과이며 [개발 기록](../devlog/2026-11-05.md#후속-성능-점검과-수정)을 따른다. 시험의20ms·표본당10ms 처리 시간 주입은 가상값이다.

### 장기 재생과 trace 확인 순서

1. **60배 장기 예제 로드**를 누르고 `READY`를 확인한 뒤 **시작**한다. 예제는 시나리오1,800,000ms·원본18,000개다.
2. **① 가짜 안내 시작**에서 실효1배를 확인하고 **질문 취소**를 누른다. 질문 잠금이 해제되고 실효60배로 돌아오는지 본다.
3. 가짜 REAL이 꺼진 상태에서 차속40km/h·좌표35.5/129.0이 공급되는지 확인한다. 재생 중 전체null이 보이면 단계·원본 관측 나이·실제 수신 나이를 trace에서 함께 확인한다.
4. `COMPLETED`·원본 전달18,000개와 재생 배지 종료를 확인한 뒤 **성능 trace 저장**을 누른다. 저장 완료 Toast 뒤 파일을 가져온다. 종료 뒤 Hub 최종 값은 null로 정리된다.

trace는 앱의 `cache/demo-perf-trace.jsonl`에 저장한다. 요약 집계는 매tick, 상세행은 실제100ms 간격 또는 상태 변화 때 보관하며 최근 실제120초·최대2,400행으로 제한한다. 전체nulltick 수와 상세행 수는 같지 않다. 이 화면의 **가짜 REAL**과 별도 Playground SDK 실시간 검증 화면의 `REAL` 수신을 구분한다.

`classification=PLAYING_ALL_EXPECTED_SIGNALS_NULL` 행에서 `inputs.DEMO.quality.SPEED_KPH`의 `availability`·`observedAgeMs`·`receiptAgeRealMs`를 비교한다. 가짜 REAL이 꺼져 있어도 최종 `NO_VALID_INPUT` 품질에는 REAL/UNAVAILABLE이 표시될 수 있으므로, 실제 제외 원인은 DEMO 입력 품질에서 확인한다. 검증에 사용한 창 없는 에뮬레이터는 종료했다. 직접 확인할 때는 Android Studio에서 같은 Pleos AVD를 Cold boot한다.

이번 기기에서는 Android 사용자10의 앱 자료를 읽어야 했다. 먼저 현재 사용자를 확인하고, 아래 `10`을 실제 결과에 맞춰 실행한다.

```bash
adb shell am get-current-user
adb exec-out run-as com.sleepissue.awakedrive --user 10 cat cache/demo-perf-trace.jsonl > /tmp/awakedrive-demo-perf-trace.jsonl
```

## 2026-11-06 후속 — Hub 수집 경로·짧은 재생 묶음·종료 trace

수신마다 하던 전체 진단을 발행/상태변경으로 옮기고, 발행 후보·Hz 재계산과 RateMeter 할당을 줄였다. debug 재생은2ms 묶음의 완전 관측 경계에서 발행 기회를 주며, 잔여 원본이 있으면 yield 후 바로 이어간다. 종료 trace는 동결하여 자동 보존하고 저장 버튼의 진행·완료·실패는 화면에 남긴다.

**350개 시험·assembleDebug와 같은 창 있는 AVD의 반복 재검증을 수행했지만 예상외null0·600Hz 발행 완전성은 미달이다.** 원본3채널 누락·중복·역행0, 발행deadline누락 감소와 초기/반복 STALE 잔여를 구분한 수치·한계·직접 누를 순서는 [11월6일 개발 기록](../devlog/2026-11-06.md)과 [측정 JSON](../devlog/2026-11-06-demo-performance.json)을 따른다. Room 논리 데이터는 전후 동일함을 확인했다. 앞 절의11월5일 결과와 합산하지 않는다.

## 2026-11-06 후속 — 전달·원본 준비·명령 양보

완전 관측 경로와 로드 시 원본 준비를 추가하고, 짧은 묶음 양보로 대기 명령의 기아를 방지했다. 기본 실행 통로는 `DEFAULT_LANE`이다. 이전350개 시험 기록에 이어 현재 **engine149개+app227개=376개·assembleDebug 통과**를 확인했지만, 같은 AVD의 **실제 예상외null0 목표는 미달**이다. 조건별 전후 측정·종료 trace·저장 상태·직접 누를 버튼과 남은 한계는 [후속 개발 기록](../devlog/2026-11-06-demo-transport.md)을 따른다.

## 2026-11-06 시간축·처리완료 경과 검토

기존376개 변경과 장기 측정은 보존하고 **385개·assembleDebug**를 확인했다. 생산 `DemoClock`과 Hub의SCENARIO100ms 계약은 유지한다. `DemoClock.realElapsedMs`는 목표duration 도달에서 고정될 수 있어 원본 처리완료 시간으로 쓰지 않는다. `pumpFinishedScenarioMs`도 Clock 위치이지 완료watermark가 아니다. 새trace의 `actualProcessingElapsedMs`는 독립 실제 시작→COMPLETED 감지 차이이며, 화면은 요청/질문 반영 목표/전체 달성배속을 구분한다. 질문·정지 포함 평균이므로 의도한1배 구간을 처리성능 실패와 혼동하지 않는다.

짧은 동일원본1·10·60배 비교, 완료시계 교체의 반례·미적용 이유, 분석소비 누락과 발행누락의 구분, 최종 장기 결과와 버튼 순서는 [시간축 개발 기록](../devlog/2026-11-06-demo-timeaxis.md)과 [요구 검토](../devlog/2026-11-06-demo-timeaxis-requirements.md)를 따른다. 실제 예상외null0 목표는 미달이며 새 구조가 완성됐다는 뜻이 아니다.
