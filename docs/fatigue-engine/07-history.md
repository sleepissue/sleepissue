# 7. 작업 기록 (1~13단계)

## 1. 코드가 남아 있는 곳

단계마다 **패치 파일 하나 = 커밋 하나**로 올렸다. 그래서 각 단계의 코드는 git 기록에 그대로 남아 있고, 언제든 그 단계만 꺼내 볼 수 있다. 11개 커밋 모두 보낸 패치와 내용이 같다는 것을 트리 해시로 확인했다 (11/4).

```bash
git log --oneline main..feat/engine-model     # 2번 커밋 목록 (오래된 것이 아래)
git show --stat c03e0b7                        # 그 단계에서 바뀐 파일과 줄 수
git show c03e0b7                               # 그 단계에서 바뀐 코드 전체
git show c03e0b7:src/android/engine/src/main/java/com/sleepissue/awakedrive/engine/fatigue/SteeringGate.kt   # 그 때의 파일
```

GitHub에서는 `https://github.com/sleepissue/sleepissue/commit/<커밋 해시>`로 같은 내용을 볼 수 있다.

## 2. 한눈에

| 단계 | 커밋 | 시각 (11월) | 한 일 | 바뀐 파일 | 시험 (누적) |
| --- | --- | --- | --- | --- | --- |
| 1 | `ba28846` | 3일 20:05 | `:engine` 빌드 설정 (Kotlin JVM 11), 빌드 확인 시험 | 2개, +27 −3 | 1 |
| 2 | `56c02ab` | 3일 22:49 | 내보내는 data class(MSG-02·03·04·13), 설정 묶음 | 13개, +523 −15 | 9 |
| 2b | `d53f4f2` | 3일 22:52 | 1번 단계 전환과 겹치는 설정 7개 삭제 | 2개, +11 −18 | 9 |
| 3 | `c03e0b7` | 3일 22:58 | 조향 게이트 (FAT_001~004) | 3개, +400 | 22 |
| 3b | `6dedf65` | 3일 23:13 | `EngineConfig` 본체 삭제 (1번 본체와 충돌 방지) | 3개, +10 −29 | 22 |
| 4 | `6e2a73a` | 3일 23:13 | 지표 3개·분당 환산·f값 (FAT_005~011) | 9개, +635 | 41 |
| 5 | `7dbb723` | 3일 23:37 | B·K·R·3분 지수평균, `FatigueEngine` (FAT_012~020) | 12개, +704 −2 | 63 |
| 6 | `97012a5` | 3일 23:54 | 기준선·출발 피로·민감 모드 (BSL_003~007) | 7개, +610 −31 | 75 |
| 7 | `e0365d2` | 4일 00:08 | 이상 분·추세형·튐형·안전 우회 (PAT_001~003·005) | 8개, +416 −5 | 85 |
| 8 | `ebae06f` | 4일 00:30 | 보조 주행·인계 품질·조작 공백 (AST), 입력을 `DriveFrame`으로, 조향 사건 | 12개, +651 −27 | 96 |
| 9 | `e086ae5` | 4일 00:53 | 조향 원천 5 Hz 미만 처리 (SIG_009 엔진 쪽) | 10개, +180 −10 | 102 |
| 10 | `c060214` | 4일 01:15 | 통합 준비: 게이트 통과 프레임만 5 Hz 판단, 운전 시간 초 단위 | 9개, +119 −49 | 104 |
| 문서 | `2dba62c` | 5일 21:05 | `docs/fatigue-engine/` 설명 문서 10개 | 12개 | 104 |
| 11 | (적용 뒤 해시) | 7일 | main 합치기, 신호 변환 함수, AgeBand 교체, EngineConfig 연결 | 12개, +331 −85 | 114 |
| 12 | (적용 뒤 해시) | 7일 | 고칠 것 1(급조향 칸)·2(큰 수정 하한) | 9개, +263 −2 | 125 |
| 13 | (적용 뒤 해시) | 7일 | app 엔진 출력 연결기 (`FatigueBridge`·`FatigueAssessmentRunner`) | 3개, +524 | 125 + app 8 |

간트에서 11/3~11/8로 잡은 2번 구현(FAT·BSL·PAT·AST)을 11/3~4에 끝냈다. 대신 통합(11/9) 전에 남은 일이 있다 ([09](09-schedule-todo-limits.md)).

## 3. 단계별 상세

### 1단계 `ba28846` — 빌드 설정

- **바꾼 것:** `engine/build.gradle.kts`에 Kotlin 바이트코드를 Java와 같은 11로 고정. 빌드 확인용 시험 1개.
- **왜:** main의 이 파일에는 "Kotlin 버전 지정 필요" TODO가 있었다. 엔진 모듈에서 버전을 적으면 app 모듈의 AGP 9.4.0이 이미 classpath에 넣은 Kotlin 플러그인과 부딪혀서, 버전 없이 `id("org.jetbrains.kotlin.jvm")`로 두고 `jvmTarget 11`만 지정했다. 지정하지 않으면 Kotlin이 Gradle 데몬 JDK(25)를 따라가 "Kotlin does not yet support 25 JDK target" 경고가 나고 Java 11과 어긋난다.
- **알릴 것:** 1번·3번 브랜치도 같은 파일을 고쳤다. 1번 방식(루트에 `kotlin.jvm` 2.4.21 `apply false` + 엔진에서 `alias`)이 더 정석이라 **머지 때는 1번 것으로 고른다**.

### 2단계 `56c02ab` — 내보내는 자료형과 설정 묶음

- **바꾼 것:** `FatigueState`(MSG-02), `PatternState`(MSG-03), `AssistState`(MSG-04), `BaselineRecord`(MSG-13), `AgeGroup`, 설정 묶음 4개(`FatigueConfig`·`BaselineConfig`·`PatternConfig`·`AssistConfig`)와 `HourWindow`, 설정값 시험 9개.
- **왜:** 간트 [8] "각자 내보내는 메시지를 먼저 커밋 → 받는 쪽은 가짜 값으로 개발 시작". 기획서 숫자를 코드에 직접 쓰지 않고 설정 한 곳에 두는 규칙(CLAUDE.md 4장).
- **알릴 것:** `FatigueState.invalidReason` 기본값을 Data Interface의 NONE 대신 GATE_WAIT로 둠. `AgeGroup`은 4번 `AgeBand`가 들어오면 바꿀 임시 타입.

### 2b단계 `d53f4f2` — 1번과 겹치는 설정 삭제

- **바꾼 것:** 보조 주행 정기 확인 20분·10분·야간·연속 2시간·조작 공백 15분, 추세형 재확인 10분·느림 2회 — 7개를 2번 설정에서 지움.
- **왜:** 1번 `develop-jdw`의 `EngineConfig`에 같은 값이 이미 있었다(`assistedCheckIntervalMs`, `trendCheckRetryMs`, `slowCheckCountForRest` 등). 같은 숫자가 두 곳에 있으면 한쪽만 바뀌는 사고가 난다. 그래서 **음성 확인 결과로 단계를 바꾸는 판단(PAT_004·006·007)과 보조 주행 확인 주기(AST_005)는 1번이 2번 출력을 보고 한다**로 나눴다.
- **알릴 것:** SRS 담당자 칸(PAT_004·006·007, AST_005)을 1번으로.

### 3단계 `c03e0b7` — 조향 게이트

- **바꾼 것:** `SteeringGate`, 신호 끊김 1초 설정, 시험 13개 (59.9/60.0 km/h, 89.9/90.0/113.0/113.1초, 299.9/300.0 m, 저속 29/31초 등 SRS 경계값 그대로).
- **왜:** 기획서 4-1 원칙 2 "조향은 게이트를 통과한 구간에서만". 방향지시등 '앞' 10초를 빼려고 판정을 10초 늦게 확정한다.
- **알릴 것:** 방향지시등·브레이크가 null이면 게이트를 못 넘는다. 이 신호를 못 받는 차에서는 조향 감지가 꺼진다 → 1번이 에뮬레이터에서 두 신호가 오는지 확인해 달라.

### 3b단계 `6dedf65` — `EngineConfig` 본체 삭제

- **바꾼 것:** 2번이 만든 `EngineConfig` 본체를 지우고 시험 이름을 `FatigueConfigTest`로 바꿈.
- **왜:** 1번 브랜치에도 `EngineConfig` 본체가 있어서 둘 다 main에 들어가면 충돌한다. 2번은 묶음만 두고, 팀 본체가 들어오면 `val fatigue = FatigueConfig()`처럼 한 줄씩 붙인다.

### 4단계 `6e2a73a` — 지표 3개·분당 환산·f값

- **바꾼 것:** `SteeringReversalDetector`(방향 전환), `SuddenSteerDetector`(멈춤 뒤 급조향), `SteeringMetrics`(60초 창·30초 갱신), `FatigueFactors`(f값), 시험 19개와 시험용 조향 경로 도우미.
- **[해석]:** 방향 전환은 반대로 0.5° 이상 되돌아와야 인정, 급조향 '1초 안'은 마지막 멈춤 샘플부터 1.0초 포함, 기준이 0이면 f미세 0·f큰은 큰 수정 있으면 1.
- **일정:** 간트 11/3~4 일을 11/3에 끝냄.

### 5단계 `7dbb723` — 위험 점수와 엔진 본체

- **바꾼 것:** `RiskScore`(B·R), `ContextWeight`(K), `RiskSmoother`(3분 지수평균), `FatigueEngine`(지금까지를 묶어 30초마다 `FatigueState`), `SteeringBaseline`, 시험 22개.
- **알릴 것:**
  - `valid = false`면 f·B·R은 0, 평활값은 마지막 값 유지 (AST_004 "갱신 0회" 해석). 1번은 `valid`일 때만 `riskSmoothed`를 쓴다.
  - 평활값은 0에서 시작한다 → 순간 점수 61이 이어져도 60을 넘기까지 12분 30초. 5번 '졸음' 시나리오는 순간 점수 70 이상으로.
  - `FatigueState.tMs`는 넣은 신호보다 10초 늦다. 1번·5번이 그래프 시각을 맞출 때 알아야 한다.
  - 취약 시간대 정의는 `ContextWeight.factorsOf(...).vulnerableTime` 한 곳에. 1번이 1단계 조건(INT_001)에 같은 함수를 쓰면 좋다.
  - 기획서 시나리오 1의 64점은 같은 입력으로 계산하면 61점 (SRS FAT_012는 이미 61).

### 6단계 `97012a5` — 기준선·출발 피로·민감 모드

- **바꾼 것:** `BaselineStore`, `BaselineState`(새 메시지), 지표 누적 합계, 엔진 연결, `resetRiskAfterRest()`, 시험 12개.
- **알릴 것:**
  - 1번: `baselineState.sensitive`면 문턱 30/50/70·1단계 1시간, `firstCheckDueMs`에 첫 음성 확인. 회복 '느림' 뒤 민감 모드(4번 RES_006)와는 OR.
  - 1번: 휴식 인증 뒤에는 `reset()`이 아니라 `resetRiskAfterRest()` (reset은 오늘의 나까지 지움).
  - 4번: `departureFatigue`가 false → true로 바뀌는 순간 예보 재계산. 운행 중 한 번만 바뀐다.
  - 3번·5번: '졸려요'를 받으면 `baselineStore.reportCondition(sleepy = true, …)`. 권유 문장은 그쪽에서 한 번.
  - 5번: 출발 피로는 시속 60 km 이상 주행이 15분 쌓이고 10초 뒤에 판정.
  - SRS: `BaselineState`를 Data Interface MSG-16으로 추가.
  - (6단계 때 말한 '커밋 작성자 이메일' 건은 [08](08-team-handoff.md#4-5-팀장srs-담당) 참고.)

### 7단계 `e0365d2` — 딴짓·졸음 패턴과 안전 우회

- **바꾼 것:** `PatternClassifier`, 창 안 급조향 수, 엔진 연결, 안전 우회 유지 5분 설정, 시험 10개.
- **알릴 것:**
  - 1번: `trending = (patternType == TREND)`, `safetyBypass`면 3단계. 안전 우회는 3회째 급조향 10초 뒤에 켜진다.
  - 3번: 3번 `PatternTrend`에는 애매(AMBIGUOUS)가 없다. MSG-03은 4가지라 맞춰야 한다.
  - 5번 '딴짓': 급조향이 있는 그 1분에 미세 수정이 기준의 70% 이하여야 이상 분.
  - 5번 '졸음': 추세형은 졸린 조향이 6분 쌓여야 나온다.

### 8단계 `ebae06f` — 보조 주행·인계 품질·조작 공백

- **바꾼 것:** `AssistModeDetector`, `SafetySteering`, 엔진 입력을 `GateInput`에서 `DriveFrame`으로 바꿈(크루즈·설정 속도·통풍 추가, 보조 주행을 2번이 직접 판정), `SteeringEvent`와 `drainEvents()`, 게이트의 `reasonWithoutAssist`, 평소 인계 횟수, 시험 11개.
- **고친 것:** 7단계 때 남긴 문제(보조 주행 중에는 게이트가 프레임을 통째로 빼서 '5분 안 급조향 3회'를 못 셈)를 안전 감시용 감지기를 따로 두어 해결.
- **알릴 것:**
  - 1번: 매 프레임 `push(DriveFrame)` 뒤 `drainEvents()`를 불러 1번 `SafetyEvent`로 넘김. 사건은 10초 늦게 나옴.
  - 1번: `assistState.assistMode`면 20분·10분 정기 확인(AST_005), `opGapSec`가 900을 넘으면 확인 당김(AST_006).
  - 팀: AST_005 '야간' 시간 정의가 없음.
  - 한계: 브레이크로 크루즈를 끄면 브레이크 앞뒤 5초는 게이트가 빼서 끈 직후 5초 안 급조향은 못 잡음. 1차에는 평소 인계 횟수 기록이 없어 늘 '3회' 기준.

### 9단계 `e086ae5` — 조향 원천 5 Hz 미만

- **바꾼 것:** `DriveFrame.steeringRateOk`, `FatigueState.microEnabled`(14번째 필드), `RiskScore.behaviorScoreWithoutMicro`, 이상 분·출발 피로에서 미세 조건 빼기, 시험 6개.
- **왜:** SRS SIG_009는 1번 담당이지만 "미세 수정 지표를 끄고 B를 재정규화"하는 부분은 엔진 안이라 2번이 했다. 1번 기록에도 "미세 수정 비활성·B 재정규화는 2번 담당"으로 적혀 있다.
- **바로잡기:** 이때 PR 설명에 "에뮬레이터 실제 조향이 2 Hz"라고 썼는데, 그 2 Hz는 1번 디버그 패널의 **가짜 입력** 시험값이었다(1번 기록: "이 패널의 REAL은 SDK가 아니다"). 실제 SDK 조향은 원천 시각을 주지 않아 Hz를 잴 수 없다. PR 설명을 고쳤다.

### 10단계 `c060214` — 통합 준비

- **바꾼 것:**
  - 5 Hz 표시를 게이트 판정 순서에 맞춰 꺼내고, **게이트를 통과한 프레임만** 따짐. 처음 15분은 느린 비율이 10% 이상일 때만 미세 수정을 못 믿음 [제안값].
  - 맥락 입력의 운전 시간을 밀리초에서 **초**로 바꿈 (`continuousDriveSec`·`last24hDriveSec`).
  - 조작 공백 초 계산의 1000을 이름 붙인 상수로. 시험 2개.
- **왜:** 1번 브랜치와 시험 삼아 합쳐 보니 두 가지가 나왔다. ① RateMeter는 시작 뒤 10초를 '측정 중'으로 주는데, 9단계 방식이면 이 10초 때문에 운행 전체에서 미세 수정을 못 믿게 되어 데모 '출발 피로'(DMO_006)가 판정되지 않는다. ② 1번 `TripState`가 운전 시간을 초로 주는데 엔진이 밀리초를 받으면 엔진 안에서 단위를 바꿔야 한다(SIG_010 위반).

### 11단계 — main 합치기·신호 변환·AgeBand·EngineConfig (11/7)

- **바꾼 것:**
  - main(11/4에 1·3·4번 PR #6·#7·#8 병합)을 이 브랜치로 합침. 충돌은 `engine/build.gradle.kts` 하나, main 것을 고름.
  - 신호 변환 함수 `SignalAdapters.kt` + 시험 6개 (11/4에 준비해 둔 것).
  - 임시 `AgeGroup`·`HourWindow`를 지우고 4번 `AgeBand?`·`VulnerableTimeRange`로. 취약 시간대 값을 `VulnerableTimeDefaults` 한 곳으로 모아 팀 `EngineConfig`(예보·1단계)와 `ContextWeightConfig`(K)가 같이 씀.
  - 팀 `EngineConfig`에 `fatigue`·`baseline`·`pattern`·`assist` 칸, `FatigueEngine(EngineConfig)` 생성자, `EngineConfig.resolvedFatigueConfig()`.
- **왜:** CLAUDE.md 4장 "문턱값은 EngineConfig 한 곳에". 취약 시간대 정의가 둘이면 예보와 K가 서로 다른 시간을 취약하다고 볼 수 있다.
- **알릴 것:** 4번 — `EngineConfig`의 취약 시간대 기본값을 `VulnerableTimeDefaults`로 옮김(값은 같음). 쉼터 브랜치를 합칠 때 `EngineConfig` 줄이 겹치면 둘 다 남긴다. 1번 — `EngineConfig` 가운데(예보 칸 뒤)에 2번 칸 4개가 들어감.

### 12단계 — 고칠 것 1·2 (11/7)

- **바꾼 것:**
  - 고칠 것 1: 급조향이 확인되면 그 급조향까지의 60초로 이상 분을 보고 그 급조향이 든 칸만 이상 분 (`PatternClassifier.onSuddenWindow`, `SteeringMetrics.windowEndingAt`). 시험 7개.
  - 고칠 것 2: 비교 기준의 큰 수정 하한 2/분 [제안값] (`BaselineStore.comparable`, `minReferenceLargePerMin`). 시험 4개.
- **왜:** 딴짓 시작 시각 60가지 중 18가지에서 딴짓 1분을 놓쳤고(데모 '딴짓' 위험), 처음 15분에 큰 수정이 없던 운전자는 큰 수정 하나에 B가 25 뛰었다.
- **확인:** 60가지 모두 튐형. 시나리오 A~G는 C(딴짓)만 바뀌고 나머지는 숫자까지 같음. 돌연변이 11개 모두 잡음 ([06](06-testing.md#3-시험이-정말-규칙을-지키는지-돌연변이-시험)).

### 13단계 — 엔진 출력 연결기 (11/7)

- **바꾼 것:** app `fatigue/FatigueBridge.kt`(계산), `FatigueAssessmentRunner.kt`(실행, 1번 `DrivingAssessmentSource` 구현), 시험 8개.
- **왜:** 1번 `InterventionManager`가 받을 자리(`DrivingAssessment`·`Safety`·`SensitiveModeChanged`·`VulnerableTimeChanged`)는 main에 있었지만, 엔진 결과를 그 형식으로 넘기는 코드가 아무 데도 없었다. 없으면 11/9 통합에서 엔진이 단계 전환에 붙지 않는다.
- **남은 것:** 1번이 앱 조립 코드에서 `runner.run(hub.samples, …, interventionManager)`을 띄워야 실제로 돈다. '15분 뒤 첫 확인'은 1번 입력이 생기면 연결 ([08 4-1](08-team-handoff.md#4-1-1번-차량-신호단계-전환)).

## 4. 준비만 하고 아직 안 올린 것

11/7에 모두 올렸다 (11~13단계). 남은 일은 [09](09-schedule-todo-limits.md#3-남은-일).
