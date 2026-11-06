# 2. 코드 지도

경로 앞부분 `src/android/engine/src/main/java/com/sleepissue/awakedrive/engine/`는 줄여서 `engine/`으로 쓴다.

## 2-1. 폴더 구조

```
engine/
├── config/      설정 묶음: 모든 숫자 문턱값 (코드 안에 숫자를 직접 쓰지 않음)
├── model/       모듈끼리 주고받는 data class (MSG-02·03·04·13 + 새 메시지)
├── fatigue/     게이트 → 지표 → 점수, 엔진 본체 FatigueEngine
├── baseline/    기준선: 기본값·평소의 나·오늘의 나, 출발 피로, 민감 모드
├── pattern/     이상 분·추세형·튐형·애매, 안전 우회
└── assist/      보조 주행 판정, 인계 품질, 조작 공백
```

`intervention/`, `forecast/`, `rest/`, `signal/`, `time/`, `check/`, `voice/`, `session/` 같은 다른 패키지는 1·3·4번 몫이다 (11/4에 PR #6·#7·#8로 main에 들어감).

## 2-2. 파일별 설명 (main 27개)

### config/ — 설정 묶음

| 파일 | 줄 | 하는 일 |
| --- | ---: | --- |
| `FatigueConfig.kt` | 135 | 게이트·창·지표 3개·f값·가중치·지수평균 문턱값 (`FatigueConfig`), 맥락 가중 K 문턱값 (`ContextWeightConfig`). `smoothingAlpha`(α = 1 − e^(−30/180) = 0.1535)는 시정수에서 계산 |
| `BaselineConfig.kt` | 51 | 기본 기준선 20/4 [가정], 평소의 나 3회·10회, 오늘의 나 15분, 출발 피로 조건, 첫 확인 15분, 5 Hz 미만 비율 10% [제안값] |
| `PatternConfig.kt` | 35 | 이상 분 0.70·1.5배, 10분 돌아보기, 추세형 6·튐형 3, 복귀 1분, 안전 우회 3회·5분·유지 5분 |
| `AssistConfig.kt` | 25 | 인계 감시 30초, 1.5배, 기록 없으면 3회. 매끈한 조향(2차) 값 |
| `HourWindow.kt` | 21 | 하루 중 시간대(시작 포함·끝 제외, 자정 넘는 구간 지원). 취약 시간대에 씀. 4번 `VulnerableTimeRange`가 들어오면 바꿀 임시 타입 |

팀 공통 `EngineConfig` 본체는 1번 브랜치에 있다. 2번은 본체를 만들지 않고 묶음 4개만 둔다. 본체가 main에 들어오면 거기에 `val fatigue: FatigueConfig = FatigueConfig()`처럼 한 줄씩 붙인다 (3b단계 결정).

### model/ — 주고받는 자료

| 파일 | 줄 | 하는 일 |
| --- | ---: | --- |
| `DriveFrame.kt` | 36 | **2번 입력** 한 프레임. 1번 SignalFrame 병합 뒤 변환 함수로 채움 |
| `FatigueState.kt` | 64 | **MSG-02** 30초 결과 14필드 + `InvalidReason{NONE, GATE_WAIT, ASSIST}`. 14번째 `microEnabled`는 새로 추가 제안 |
| `PatternState.kt` | 37 | **MSG-03** 이상 분 수, `PatternType{NONE, TREND, SPIKE, AMBIGUOUS}`, 안전 우회 |
| `AssistState.kt` | 32 | **MSG-04** 보조 주행 여부, `AssistReason{NONE, CRUISE, SMOOTH_STEERING, UNCERTAIN}`, 인계 이상, 조작 공백 초 |
| `BaselineRecord.kt` | 25 | **MSG-13** 운행 하나의 기준선 기록 (Room 저장용 원본, 저장 자체는 2차) |
| `BaselineState.kt` | 47 | **새 메시지(MSG-16 제안)** 기준선 출처·값, 오늘의 나, 출발 피로, '졸려요', 민감 모드 시작·첫 확인 시각. `BaselineSource{DEFAULT, USUAL, TODAY}` |
| `SteeringBaseline.kt` | 17 | 비교 기준 한 쌍(미세/분, 큰/분). 음수 금지 |
| `SteeringEvent.kt` | 20 | **새 메시지** 바로 반응해야 하는 사건 3종. 1번 `SafetyEventType`과 이름을 맞춤 |
| `AgeGroup.kt` | 15 | 연령대(UNSET·20대·30~40대·50대 이상). 4번 `AgeBand`로 바꿀 임시 타입 |

### fatigue/ — 게이트·지표·점수·엔진

| 파일 | 줄 | 하는 일 |
| --- | ---: | --- |
| `SteeringGate.kt` | 234 | 게이트 (FAT_001~004). `GateInput` → 10초 뒤 `GateDecision(tMs, 조향각, reason, reasonWithoutAssist)`. 이유 9가지 `GateReason` |
| `SteeringReversalDetector.kt` | 85 | 조향각 흐름에서 방향이 바뀐 점(극값)을 찾아 극값 사이 크기를 돌려줌. 0.5° 되돌아와야 방향 전환으로 인정 (`internal`) |
| `SuddenSteerDetector.kt` | 93 | 멈춤 뒤 급조향 (FAT_008) (`internal`) |
| `SteeringMetrics.kt` | 180 | 60초 창·30초 갱신 지표 (FAT_005~008). `SteeringIndicators` 반환. 오늘의 나용 누적 합계도 셈 |
| `FatigueFactors.kt` | 39 | f미세·f큰·f급조향 (FAT_009~011) |
| `ContextWeight.kt` | 110 | 맥락 가중 K (FAT_013~018). `ContextInput`(입력), `ContextFactors`(켜진 항목), 취약 시간대 판단 |
| `RiskScore.kt` | 50 | B·R (FAT_012·019), 미세 수정을 뺀 B 재정규화 (SIG_009) |
| `RiskSmoother.kt` | 38 | 3분 지수평균 (FAT_020) |
| `SafetySteering.kt` | 61 | 보조 주행 중에도 급조향·큰 수정을 보는 두 번째 감지기 (PAT_005·AST_007) (`internal`) |
| `FatigueEngine.kt` | 300 | **2번 전체를 묶는 본체.** 다른 모듈은 이 클래스만 쓰면 된다 |

### baseline/ · pattern/ · assist/

| 파일 | 줄 | 하는 일 |
| --- | ---: | --- |
| `baseline/BaselineStore.kt` | 239 | 기준 고르기, 평소의 나 중앙값, 오늘의 나 확정, 출발 피로, 민감 모드, 첫 확인 시각 (BSL_001·003~007) |
| `pattern/PatternClassifier.kt` | 182 | 1분 칸 이상 분, 추세형·튐형·애매, 안전 우회 (PAT_001~003·005) |
| `assist/AssistModeDetector.kt` | 175 | 보조 주행 판정, 인계 감시, 조작 공백 (AST_001·003·004·006·007·008) |

## 2-3. 클래스 관계

```mermaid
flowchart TB
  FE["FatigueEngine (공개)"]
  FE --> AMD["AssistModeDetector"]
  FE --> SG["SteeringGate"]
  FE --> SM["SteeringMetrics"]
  FE --> SS["SafetySteering"]
  FE --> RS["RiskSmoother"]
  FE --> PC["PatternClassifier"]
  FE --> BS["BaselineStore (공개 필드 baselineStore)"]
  SM --> SRD["SteeringReversalDetector"]
  SM --> SSD["SuddenSteerDetector"]
  SS --> SRD2["SteeringReversalDetector (따로 한 벌)"]
  SS --> SSD2["SuddenSteerDetector (따로 한 벌)"]
  FE -. "계산 함수" .-> FF["FatigueFactors"]
  FE -. "계산 함수" .-> RSC["RiskScore"]
  FE -. "계산 함수" .-> CW["ContextWeight"]
```

- `FatigueEngine`이 나머지를 모두 만들어 들고 있다. 다른 모듈은 `FatigueEngine` 하나만 만들면 된다.
- `FatigueFactors`·`RiskScore`·`ContextWeight`는 상태가 없는 계산 함수 모음(`object`)이라 시험에서 따로 불러 쓸 수 있다.
- 감지기를 두 벌 둔 이유: 점수용(`SteeringMetrics`)은 보조 주행 중 프레임을 빼야 하고, 안전 감시용(`SafetySteering`)은 넣어야 해서 상태를 따로 가져야 한다.

## 2-4. 다른 모듈이 쓰는 법 (공개 API)

```kotlin
val engine = FatigueEngine()                          // 설정을 바꾸려면 FatigueEngine(FatigueConfig(gateMinSpeedKph = 50f))
engine.baselineStore.loadHistory(records)             // 앱 시작 때 지난 운행 기록 (BSL_001·003)
engine.baselineStore.reportCondition(sleepy, nowMs)   // 출발 전 컨디션 (BSL_007)
engine.context = ContextInput(continuousDriveSec = …, last24hDriveSec = …,
    minuteOfDay = …, ageGroup = …, passengerCount = …, cabinTempC = …)   // 바뀔 때마다

val states: List<FatigueState> = engine.push(frame)   // 100 ms마다. 30초마다 1개, 나머지는 빈 목록
val events: List<SteeringEvent> = engine.drainEvents() // push 뒤마다. 꺼낸 사건은 지워짐(최대 100개 보관)
engine.baselineState   // source, reference, today, departureFatigue, reportedSleepy, sensitive, sensitiveSinceMs, firstCheckDueMs
engine.patternState    // abnormalMinutes10m, patternType, safetyBypass
engine.assistState     // assistMode, reason, handoverAbnormal, opGapSec

engine.flush()               // 운행 끝: 게이트에 남은 10초를 확정해 마지막 갱신까지 냄
engine.reset()               // 새 운행 (꺼져 있던 시간 2시간 초과, TRP_008)
engine.resetRiskAfterRest()  // 휴식 인증 + 회복 확인 뒤 (RES_011)
```

- **한 스레드(코루틴 하나)에서만** 부른다. 안에 잠금(lock)이 없다.
- 상태 객체(`baselineState`·`patternState`·`assistState`)는 값이 바뀔 때만 새 객체가 된다. Kotlin `StateFlow`에 그대로 넣으면 바뀔 때만 알림이 간다.

### 초기화 범위

| 들고 있는 것 | `reset()` (새 운행) | `resetRiskAfterRest()` (휴식 뒤) |
| --- | --- | --- |
| 게이트 대기 프레임, 방향지시등·브레이크 기억, 5분 유지 | 지움 | 유지 |
| 지표 창, 오늘의 나용 누적 합계 | 지움 | 유지 |
| 평활값 | 0 | **0** |
| 오늘의 나, 출발 피로, '졸려요', 출발 시각 | 지움 | 유지 (RES_011 "기준선 불변") |
| 평소의 나, 평소 인계 횟수 (`loadHistory`) | 유지 | 유지 |
| 이상 분·패턴·안전 우회 | 지움 | 유지 |
| 보조 주행·인계 감시·조작 공백 | 지움 | 유지 |
| 꺼내지 않은 사건, 5 Hz 표시 | 지움 | 유지 |
| `context`(맥락) | 유지 | 유지 |

## 2-5. 시험 파일 (test 14개)

| 파일 | 시험 수 | 무엇을 |
| --- | ---: | --- |
| `config/FatigueConfigTest.kt` | 9 | 설정 기본값이 SRS 숫자와 맞는지 |
| `fatigue/SteeringGateTest.kt` | 13 | 게이트 경계값 |
| `fatigue/SteeringReversalDetectorTest.kt` | 4 | 미세·큰 수정 경계 |
| `fatigue/SuddenSteerDetectorTest.kt` | 5 | 급조향 경계 |
| `fatigue/SteeringMetricsTest.kt` | 6 | 창·갱신·분당 환산 |
| `fatigue/FatigueFactorsTest.kt` | 4 | f값 |
| `fatigue/ContextWeightTest.kt` | 8 | K |
| `fatigue/RiskScoreTest.kt` | 5 | B·R |
| `fatigue/RiskSmootherTest.kt` | 3 | 지수평균 |
| `fatigue/FatigueEngineTest.kt` | 21 | 엔진 전체 흐름 (시나리오 시험) |
| `baseline/BaselineStoreTest.kt` | 10 | 기준선·출발 피로·민감 모드 |
| `pattern/PatternClassifierTest.kt` | 8 | 이상 분·패턴·안전 우회 |
| `assist/AssistModeDetectorTest.kt` | 8 | 보조 주행·인계·조작 공백 |
| `fatigue/SteeringTestPaths.kt` | — | 시험용 조향 경로를 만드는 도우미 (삼각파 등) |

목록과 REQ 대응은 [06-testing.md](06-testing.md).

## 2-6. Kotlin 읽는 법 (이 코드에 나오는 것만)

| 보이는 것 | 뜻 |
| --- | --- |
| `data class FatigueState(val tMs: Long, …)` | 값만 담는 클래스. `==`가 필드 비교, `copy(…)`로 일부만 바꾼 새 값 |
| `val` / `var` | 바꿀 수 없는 값 / 바꿀 수 있는 값 |
| `Float?` | null일 수 있는 Float. `x ?: 0f`는 "x가 null이면 0f" |
| `x?.let { … }` | x가 null이 아닐 때만 블록 실행 |
| `when { 조건 -> 값 … }` | if-else 사슬 |
| `object RiskScore { … }` | 하나만 있는 객체(정적 함수 모음 같은 것) |
| `companion object { … }` | 클래스에 붙은 정적 멤버 (`BaselineStore.usualOf(…)`처럼 부름) |
| `internal` | 같은 모듈(`:engine`) 안에서만 보임. 시험은 같은 모듈이라 볼 수 있음 |
| `var state … private set` | 밖에서 읽기만, 쓰기는 안에서만 |
| `fun FatigueConfig.swingClassOf(a: Double)` | 확장 함수: `config.swingClassOf(2.0)`처럼 부름 |
| `5 * 60_000L`, `0.35f` | `_`는 자릿수 구분, `L`은 Long, `f`는 Float |
| `require(조건) { "메시지" }` | 조건이 거짓이면 예외 (잘못된 입력 막기) |
| `coerceIn(0f, 1f)` | 0~1로 자르기 (clamp) |
| `ArrayDeque` | 앞뒤로 넣고 빼는 큐. 시간 창에서 오래된 것을 앞에서 버릴 때 씀 |
| `@Test fun 이름() { assertEquals(기대, 실제, 허용오차) }` | JUnit 4 시험 하나 |
