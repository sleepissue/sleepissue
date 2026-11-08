# 8. PR과 팀 전달

## 1. PR이란

**PR(Pull Request)** 은 "내 브랜치의 커밋을 main에 합쳐 달라"는 요청이다. GitHub 화면에서 바뀐 코드 전체를 보여 주고, 다른 사람이 읽고(리뷰) 승인하면 합친다(머지).

```
feat/engine-model ──●──●──●── … ──●  (2번 커밋 12개)
                    \
main ───────────────●────────────────────●  ← PR이 승인되면 여기서 합쳐짐
```

- 우리 팀 규칙 (CLAUDE.md 11장): `main`에 직접 push 금지 → **브랜치 + PR + 1명 리뷰 뒤 merge**.
- PR을 열어도 코드는 바뀌지 않는다. 열어 둔 뒤 브랜치에 push하면 PR에 자동으로 더해진다.
- 리뷰어는 "이 코드가 main에 들어가도 되는지"를 본다. 질문이나 수정 요청은 PR 화면에 댓글로 단다.

## 2. 지금 상태 (11/7)

| PR | 브랜치 | 상태 |
| --- | --- | --- |
| 2번 (우리) | `feat/engine-model` → `main` | **리뷰 대기.** 11/7 00:24 main을 합치고(충돌 1곳) 패치 01~05를 커밋해 push. 리뷰 1명 뒤 머지 |
| #6 (1번) | `develop-jdw` → `main` | 11/4 병합 |
| #7 (4번) | `feat/rest-forecast-engine` → `main` | 11/4 병합 (예보 엔진) |
| #8 (3번) | `feat/voice-check-sync` → `main` | 11/4 병합 (#5는 닫고 다시 올림) |
| #9 (4번) | `feat/rest-area-selector` → `main` | 11/7 병합 (쉼터 데이터·앞쪽 쉼터 선택·경유지 추가. 1번 `RestPort` 구현 `navi/RestAreaRestPort`) |
| #10 (4번) | `feat/rest-climate` → `main` | 11/7 병합 (운전석 통풍. `ClimatePort` 구현 `climate/DriverVentilationController`) |
| #11 (4번) | `feat/rest-session` → `main` | 11/7 병합 (휴식 자동 인증·파워냅·보상. `RestCertificationSource` 구현 `session/RestSessionMonitor`) |

### 머지 순서: main을 먼저 이 브랜치로 합친다 (11/6)

1번 신호 자료형(`SignalFrame`·`NaviState`·`TripState`)과 팀 `EngineConfig`·`Clock`, 4번 `AgeBand`·`VulnerableTimeRange`가 11/4에 main에 들어왔다. 11/6에 main을 이 브랜치에 시험 삼아 합쳐 보니 충돌은 `src/android/engine/build.gradle.kts` 하나였고(main 것으로 고르면 됨), 합친 엔진 시험 236개(2번 104 + main 132)가 통과했다. 변환 함수까지 넣으면 242개다.

11/7 00:24에 4번 #9·#10·#11이 main에 더 들어와 다시 합쳤다. 충돌은 여전히 그 파일 하나였고, 4번이 `EngineConfig` 끝에 더한 칸과 2번 칸 4개(가운데)는 겹치지 않았다. 패치 01~04까지 넣은 엔진 시험 **308개(2번 125 + main 183)** 와 연결기 8개가 통과했다.

## 3. PR 여는 법

### 웹에서

1. `https://github.com/sleepissue/sleepissue` → **Pull requests** → **New pull request**
2. **base: `main`** ← **compare: `feat/engine-model`** 로 고른다 (방향이 반대면 안 됨)
3. 제목: `2번 졸음 판단 엔진 1차 (FAT·BSL·PAT·AST, SIG_009 엔진 쪽)`
4. 본문: 받은 `PR_feat-engine-model_v4.md` 내용을 그대로 붙여 넣는다
5. 오른쪽 **Reviewers**에 1번을 고른다
6. 아래 'PR 열기 전에 할 일'을 끝내고 push한 뒤 **Create pull request**. 리뷰 전에 먼저 보여만 주려면 **Create draft pull request**(초안)

### 터미널에서 (gh가 설치·로그인돼 있으면)

```bash
gh pr create --base main --head feat/engine-model \
  --title "2번 졸음 판단 엔진 1차 (FAT·BSL·PAT·AST, SIG_009 엔진 쪽)" \
  --body-file <압축 푼 폴더>/PR_feat-engine-model_v4.md --draft
```

### PR 열기 전에 할 일 (main 합치기)

```bash
cd ~/sleepissue && git checkout feat/engine-model && git fetch origin
git merge origin/main                    # 충돌: src/android/engine/build.gradle.kts
git checkout --theirs src/android/engine/build.gradle.kts   # main 것으로
git add src/android/engine/build.gradle.kts && git commit --no-edit
cd src/android && ./gradlew :engine:test --console=plain     # 엔진 시험 실패 0 확인
cd ~/sleepissue && git push
```

11/7에 그다음 단계를 패치로 만들었다: ① 신호 변환·AgeBand 교체·EngineConfig 연결 ② 고칠 것 1·2 ③ app 엔진 출력 연결기(app 시험 8개) ④ 문서 갱신·시나리오 형식 제안 ⑤ 팀원 안내([README](README.md#팀원이-먼저-볼-것-주말-117119)의 주말 할 일, [Pleos 메일 답변](../meetings/2026-11-05_Pleos_메일답변.md)). 스크립트 `apply_1107.sh`가 위 main 합치기부터 ⑤까지 순서대로 커밋하고 시험까지 돌린다(11/7 00:24 main 기준 엔진 308개). push와 PR 열기는 사람이 한다.

### 머지할 때

- **Create a merge commit**으로 합친다. Squash로 합치면 단계별 커밋 12개가 하나로 뭉쳐 [07](07-history.md)의 커밋 해시를 main에서 찾을 수 없다.
- 머지 뒤 브랜치를 지워도 커밋은 main 기록에 남는다.

### 1번 코드(main)에서 2번이 확인한 것 (11/6)

- `SignalFrame`·`NaviState`에 게이트가 쓰는 값(조향각, 차속, 기어, 방향지시등, 브레이크, 회전 지점 거리, 크루즈·설정 속도, 통풍)이 다 있는지 → 있음 (변환 함수 시험 통과)
- 2번 출력을 받을 자리 → 있음: app `signal/SignalPorts.kt`의 `DrivingAssessment`·`DrivingAssessmentSource`, `InterventionInput.Safety`·`SensitiveModeChanged`. 다만 **'출발 피로 15분 뒤 첫 확인'(`firstCheckDueMs`)을 받을 입력은 없다**
- `SafetyEventType` 이름이 2번 `SteeringEventType`과 같은지 → 같음
- `DrivingAssessmentSource`를 구현한 곳 → main에는 없음. 이 PR의 연결기 `FatigueAssessmentRunner`가 구현한다 (4-1 대응표)

## 4. 팀원별로 알릴 것

### 4-1. 1번 (차량 신호·단계 전환)

| # | 내용 | 급함 |
| --- | --- | --- |
| 1 | **SDK 조향이 전륜각이라 지금 엔진에는 null로 들어간다.** 엔진은 조향 null을 '보조 주행으로 간주(UNCERTAIN)'하므로 에뮬레이터 실제 입력으로는 점수가 안 나오고 게이지가 계속 회색이다. 데모(시나리오 재생)는 조향 값이 있어 괜찮다. 1차는 이대로 두고 Pleos 답을 기다릴지, 조향비·부호를 정해 바꿔 넣을지 정하자. 엔진 문턱(0.5~3°, 5°, 6°)은 **운전대 각도** 기준이다 | 높음 |
| 2 | **원천 시각 없는 SDK 조향은 RateMeter가 `UNMEASURABLE`** → 2번 매핑(`MEASURED`·미만 아님일 때만 충분)대로면 실제 SDK에서는 미세 수정이 늘 꺼진다. 1차는 이대로(보수적) 두자고 제안. 데모는 `SCENARIO` 시간축이라 `MEASURED`가 된다 | 높음 |
| 3 | 데모 시나리오 JSON(MSG-11)에 회전 지점 거리가 없다 → 거리 null이면 게이트가 5분 고속 유지 뒤에 열려 모든 시나리오 첫 5분이 계산 안 되고, 출발 피로가 15:10이 아니라 20:10에 판정된다(DMO_006 불합격). 2번 형식 제안: [11](11-scenario-format-proposal.md) (`distToNextTurnM`·`startAt`·`tMs`·`tripPreset`) | 높음 |
| 4 | **엔진 출력 연결기를 만들었다 (11/7, app `fatigue/`).** `FatigueAssessmentRunner`가 1번 `DrivingAssessmentSource`를 구현하고 아래 대응표대로 `InterventionManager.submit`까지 한다. 1번이 앱 조립 코드에서 `scope.launch { runner.run(hub.samples, { hub.latestNavi.value }, { tripStore.state.value }, interventionManager) }`로 띄우면 된다. 대응표가 맞는지 리뷰 부탁 | 높음 |
| 5 | 연결 규칙: 매 프레임 `push(frame)` 뒤 `drainEvents()`, `trending = (patternType == TREND)`, `safetyBypass`는 사건 `REPEATED_RAPID_STEERING`으로 이미 감, `baselineState.sensitive` OR RES_006 → 문턱 30/50/70, `assistState.assistMode` → AST_005 확인 주기, `opGapSec > 900` → 확인 당김, `HANDOVER_UNSAFE` → 2단계 | 중간 |
| 5b | **'15분 뒤 첫 확인' 입력이 없다.** 출발 피로·'졸려요'(BSL_006·007) 때 `baselineState.firstCheckDueMs`에 첫 음성 확인을 해야 하는데 `InterventionInput`에 받을 자리가 없다. 입력 하나 추가 부탁 | 높음 |
| 5c | **TTL을 3초보다 길게** (예: 3.5초 [제안값]). Pleos 답(11/5): 같은 값이면 3초 안 중복 콜백을 생략한다. TTL이 3초 이하면 차속이 그대로일 때 null(STALE)이 되어 게이트가 닫힌다 | 높음 |
| 6 | 새 운행(TRP_008)이면 `reset()`(컨디션 받기 전에), 휴식 인증 + 회복 정상(RES_011)이면 `resetRiskAfterRest()`. 거꾸로 부르면 오늘의 나가 지워진다. 연결기로는 `runner.onNewTrip(...)`·`runner.onRestCertified()`. 1번 `PolicyInterventionManager`가 인증 + 회복 정상이면 `RestRecoveryResetRequest`를 `requests`로 내므로, 그 요청을 실행하는 곳에서 `runner.onRestCertified()`를 부르면 된다. 4번 `feat/rest-wiring`(11/7, PR 전)의 `InterventionRouter`에 그 자리(`recoveryReset`)가 있다. `requests`는 한 곳에서만 받으므로 연결기가 따로 구독하지 않는다 | 중간 |
| 7 | SRS 담당자 칸 PAT_004·006·007, AST_005 → 1번 (InterventionPolicy에 이미 값이 있음) | 중간 |
| 8 | `tripId` 타입: SRS MSG-08·13은 Long, 1번 `TripState.tripId`는 String. 2번 `BaselineRecord.tripId`를 어디에 맞출지 (2차 기록 저장 전에) | 낮음 |
| 9 | 비상등을 방향지시등 켜짐으로 보는 것(변환 함수 [해석]), AST_005 '야간' 시간 정의 | 낮음 |

**엔진 출력 → 1번 입력 대응표 (11/7, app `fatigue/FatigueBridge.kt`에 구현)**

연결기는 app 쪽에서 Hub 프레임을 받아 `toDriveFrame()` → `push()` → 결과를 아래처럼 바꿔 1번 `InterventionManager.submit()`으로 넘긴다. 요청 ID는 `fatigue-종류-시각-순번`. ROS로 치면 엔진 노드의 토픽을 1번 노드가 구독하는 메시지 형식으로 바꿔 다시 발행하는 브리지 노드다. 자세한 설명은 [03 3-16](03-algorithms.md#3-16-엔진-출력-연결기-app--fatiguebridge).

| 2번 출력 | 1번 입력 (main) | 바꾸는 법 |
| --- | --- | --- |
| `FatigueState`(30초) + `PatternState` + `AssistState` | `InterventionInput.Assessment(DrivingAssessment)` | `tMs` = `FatigueState.tMs` (넣은 신호보다 10초 늦음) |
| 〃 | 〃 `evaluationStatus` | `valid` → `EVALUATED`, `invalidReason` `GATE_WAIT` → `NOT_EVALUATED`, `ASSIST` → `UNAVAILABLE` [해석, 1번 확인] |
| 〃 | 〃 `riskScore` | `valid`일 때 `riskSmoothed`, 아니면 `null` (0으로 바꾸지 않음) |
| 〃 | 〃 `pattern` | `TREND` → `TRENDING`, `SPIKE` → `TRANSIENT`, `AMBIGUOUS` → `UNCERTAIN`, `NONE` → `null` |
| 〃 | 〃 `assistedDriving`, `operationGapSec` | `assistState.assistMode`, `assistState.opGapSec` |
| `drainEvents()`의 `SteeringEvent` | `InterventionInput.Safety(SafetyEvent)` | 이름이 같아 `SafetyEventType.valueOf(type.name)`, 출처는 조향 값의 출처(없으면 차속, 그것도 없으면 REAL) [해석] |
| `baselineState.sensitive` | `InterventionInput.SensitiveModeChanged` | 바뀔 때만 보냄 |
| (K의 취약 시간대) | `InterventionInput.VulnerableTimeChanged` | 같은 `EngineConfig` 취약 시간대로 연결기가 계산 (AgeBand 교체 뒤 한 정의) |
| `baselineState.firstCheckDueMs` | **없음** | 위 5b. 연결기는 `FatigueBridge.firstCheckDueMs`로만 내놓음. 1번 입력이 생기면 연결 |
| `baselineState.departureFatigue` | 4번 `ForecastEngine` 입력 `startFatigueDetected`·`START_FATIGUE_DETECTED` | 연결기 `snapshots`의 `baseline.departureFatigue`가 false → true인 순간 (FCS_004). 4번 조립 코드에서 읽음 |

### 4-2. 3번 (음성·실내)

| # | 내용 |
| --- | --- |
| 1 | 출발 전 컨디션 말(COND_SLEEPY)을 받으면 연결기 `runner.reportCondition(sleepy = true, tMs, interventionManager)`, COND_FRESH·NORMAL이면 `false`. 민감 모드 변화는 연결기가 1번에 알린다. "출발 전에 15분 쉬었다 가는 건 어때요?" 권유는 3번이 한 번 말함 (BSL_007) |
| 2 | 3번 `PatternTrend`는 NONE·SPIKE·TREND 3가지인데 MSG-03 `PatternType`은 AMBIGUOUS(애매)까지 4가지. 애매는 "음성 확인 결과로 정함"(PAT_003)이라 확인을 거는 쪽이 받아야 한다. 이름을 2번 것으로 맞추자 |
| 3 | 음성 확인 결과(정상·느림·무응답)는 2번이 아니라 1번 InterventionPolicy가 받는다 (2b단계 결정) |
| 4 | 간트에는 단계 전환(INT, [32])이 3번으로 되어 있지만 1번이 #6에 이미 구현했다. 누가 맡을지 확인 |

### 4-3. 4번 (예보·쉼터·휴식)

| # | 내용 |
| --- | --- |
| 1 | 연결기 `runner.snapshots`의 `baseline.departureFatigue`가 false → true로 바뀌는 순간 예보 다시 계산 (`startFatigueDetected = true`, 트리거 `START_FATIGUE_DETECTED`, FCS_004). 운행 중 한 번만 바뀐다 |
| 2 | 휴식 인증 + 회복 정상 뒤 연결기 `runner.onRestCertified()` — 평활값만 0, 기준선은 그대로 (RES_011 표와 같음) |
| 3 | 회복 '느림' 뒤 민감 모드(RES_006)는 4번이 내고 1번이 2번 `sensitive`와 OR로 합친다 |
| 4 | **2번 임시 `AgeGroup`·`HourWindow`를 지우고 4번 `AgeBand`·`VulnerableTimeRange`로 바꿨다 (11/7).** 값이 둘로 갈리지 않게 `EngineConfig`의 `vulnerableBaseRanges`·`vulnerableAgeRanges` 기본값을 `VulnerableTimeDefaults` 한 곳으로 옮겼다 (값은 그대로, 예보 시험 그대로 통과). 시간대를 바꿀 때는 `EngineConfig` 값만 바꾸면 예보·K·1단계가 같이 바뀐다 |
| 5 | 쉼터·통풍·휴식 인증(#9·#10·#11)이 11/7 main에 들어왔다. 2번 브랜치에 합쳐 보니 `EngineConfig` 충돌은 없었다(4번 칸은 끝, 2번 칸 4개는 가운데). 휴식 인증 뒤 엔진 초기화는 4번 `InterventionRouter`(`feat/rest-wiring`)의 `recoveryReset`에서 `runner.onRestCertified()`로 (4-1 6번). 예보를 1번에 넘기는 `ForecastSource` 구현은 아직 없다 |

### 4-4. 5번 (화면·시연)

| # | 내용 |
| --- | --- |
| 1 | 게이지: `riskSmoothed` 0~39.9 초록, 40~59.9 노랑, 60~79.9 주황, 80~100 빨강. `valid = false`면 `invalidReason`으로 GATE_WAIT → '측정 대기', ASSIST → 회색 (UI_003) |
| 2 | 회색일 때 `assistState.reason`으로 CRUISE('보조 주행')와 UNCERTAIN('조향 신호 없음')을 나눠 보여 주면 좋다. 지금 실제 SDK 입력은 UNCERTAIN이다 |
| 3 | 모드 표시: `assistState.assistMode`(보조 주행), `baselineState.sensitive`(민감 모드). `microEnabled = false`면 미세 수정 칸은 '측정 안 함' |
| 4 | 그래프 시각: `FatigueState.tMs`·사건 시각은 넣은 신호보다 10초 늦다 |
| 5 | 출발 전 컨디션 버튼 → 연결기 `runner.reportCondition(…)` |
| 6 | 화면 값은 연결기 `runner.snapshots`(StateFlow) 하나에서 읽는다: `fatigue`(게이지·지표), `pattern`, `assist`, `baseline`(민감 모드·출발 피로), `vulnerableTime` |
| 7 | **데모 시나리오 형식 제안**은 [11](11-scenario-format-proposal.md), 엔진 쪽 값은 [05 3절](05-behavior.md#3-5번-데모-시나리오에-넣을-값). 회전 지점 거리, 시작 시각(`startAt`), 정상 조향을 기본값(미세 20/분·큰 4/분) 근처로, 졸음은 급조향이나 맥락을 섞어 순간 점수 70 이상, 딴짓은 15:10 뒤에(시작 초는 상관없음, 11/7 고침) |

### 4-5. 팀장·SRS 담당

| # | 고칠 곳 | 내용 |
| --- | --- | --- |
| 1 | SRS 담당자 칸 | PAT_004·006·007, AST_005 → 1번 |
| 2 | Data Interface | 추가: MSG-16 `BaselineState`, `SteeringEvent`, 2번 입력 `DriveFrame`, MSG-02 14번째 `microEnabled`. 수정: MSG-02 `invalidReason` 기본값 NONE → GATE_WAIT |
| 3 | 기획서·CLAUDE.md·AGENTS.md | 시나리오 1 "23:10 추세형 64점" → 61점 (같은 입력으로 계산한 값, SRS FAT_012는 이미 61) |
| 4 | SRS AST_005 | '야간' 시간 정의 |
| 5 | SRS DMO_001·006, MSG-11 | 2번 형식 제안 [11](11-scenario-format-proposal.md): `tMs`·`startAt`·`distToNextTurnM`·선택 칸, DMO_006 합격기준을 "게이트 통과 15분 + 10초"로 |
| 10 | SRS 새 [제안값]·[해석] (11/7) | 비교 기준 큰 수정 하한 2/분(B10), 급조향 칸(P8), 연결기 대응(E5~E7). [04](04-decisions.md) |
| 6 | 간트 | [09 2절](09-schedule-todo-limits.md#2-간트에서-고칠-것) |
| 7 | Pleos 메일 | 11/5 답을 받았다(간트 [9] 100%). 갱신 주기(기본 1 Hz, 같은 값 3초 생략)는 답에 있지만 "SteeringWheel 값이 전륜각인지 운전대각인지, 부호, 원천 시각"은 없다. 다시 물을지 정하자 |
| 8 | 블라인드 | 10/27 PR #3에서 커밋 noreply·실명 금지 규칙을 뺐지만, 접수양식 02장에는 "공유 링크와 첨부자료에도 개인정보·계정정보·개발자 실명이 노출되지 않도록"이라고 적혀 있다. GitHub 링크를 낸다면 커밋 작성자 이름·이메일을 어떻게 할지 제출 전에 한 번 정하자 |
| 9 | SRS 구현 위치 칸 | 2번 42건이 모두 '(예정)'이다. 실제 경로(`engine/fatigue/`·`baseline/`·`pattern/`·`assist/`)로, PAT_004·006·007·AST_005는 1번 `InterventionPolicy`·`PolicyInterventionManager`로 |

## 5. 단계별로 누구에게 무엇을

| 단계 | 1번 | 3번 | 4번 | 5번 | 팀장·SRS |
| --- | --- | --- | --- | --- | --- |
| 1 빌드 | 머지 때 빌드 파일은 1번 것 | | | | |
| 2 자료형 | 받을 자료형 공유 | | | | `invalidReason` 기본값 |
| 2b 설정 정리 | PAT_004·006·007, AST_005를 1번이 판단 | 확인 결과는 1번으로 | | | 담당자 칸 |
| 3 게이트 | 방향지시등·브레이크 신호가 오는지 | | | | |
| 3b 본체 삭제 | `EngineConfig`에 2번 묶음 한 줄씩 | | | | |
| 5 점수 | `valid`만 사용, 10초 지연, 취약 시간대 함수 | | | 졸음 순간 70 이상, 10초 지연 | 64 → 61 |
| 6 기준선 | `sensitive`·`firstCheckDueMs`·`resetRiskAfterRest` | `reportCondition` | 출발 피로 → 예보 | 컨디션 버튼, 출발 피로 15:10 | MSG-16 |
| 7 패턴 | `trending`·`safetyBypass` | AMBIGUOUS 맞추기 | | 딴짓 미세 70%, 추세형 6분 | |
| 8 보조 주행 | `push`+`drainEvents`, `assistMode`, `opGapSec` | | | 회색 게이지 | 야간 정의 |
| 9 5 Hz | `steeringRateOk` 매핑 | | | `microEnabled` 표시 | 14번째 필드 |
| 10 통합 준비 | TripState 초 그대로, 머지 순서 | | | | `tripId` 타입 |

## 6. 바로 보낼 수 있는 메시지

**1번에게**

> 2번 엔진 PR 올렸어 (11/7 main 합침, 엔진 시험 308개 + 연결기 8개 통과). 리뷰 부탁해. 같이 볼 게 다섯 가지야. ① 엔진 결과를 네 InterventionManager로 넘기는 연결기를 app/fatigue/에 만들었어. FatigueAssessmentRunner가 DrivingAssessmentSource를 구현하고 submit까지 해. 앱 조립하는 곳에서 scope.launch { runner.run(hub.samples, { hub.latestNavi.value }, { tripStore.state.value }, interventionManager) } 한 줄이면 돌아. 대응표(GATE_WAIT → NOT_EVALUATED, ASSIST → UNAVAILABLE, SPIKE → TRANSIENT, 점수는 무효면 null)가 맞는지 봐 줘. ② 출발 피로·'졸려요'면 15분 뒤 첫 음성 확인을 해야 하는데 InterventionInput에 받을 자리가 없어. 입력 하나 추가해 주면 연결기에서 보낼게. ③ Pleos 답에 같은 값이면 3초 안 중복 콜백을 생략한대. TTL이 3초 이하면 차속이 그대로일 때 null이 돼서 게이트가 닫혀. 3.5초쯤으로 해 줘. ④ 시나리오 형식 제안(docs/fatigue-engine/11-scenario-format-proposal.md) 봐 줘. 회전 지점 거리·시작 시각·tMs(정수 ms)가 핵심이고, DemoPlayer가 1초마다 NaviState를 내 주면 돼. ⑤ 휴식 인증 + 회복 정상 때 네 PolicyInterventionManager가 RestRecoveryResetRequest를 내잖아. 그걸 실행하는 곳(4번 feat/rest-wiring InterventionRouter의 recoveryReset)에서 runner.onRestCertified()도 불러 주면 엔진 평활값이 0이 돼(기준선은 그대로). EngineConfig 가운데에 2번 칸 4개가 들어갔고 취약 시간대 기본값은 VulnerableTimeDefaults로 옮겼어(값 같음). 자세한 건 08-team-handoff.md 4-1.

**5번에게**

> 데모 시나리오 형식 제안 올려 뒀어: docs/fatigue-engine/11-scenario-format-proposal.md (SRS MSG-11에 회전 지점 거리·시작 시각·정수 ms 시각만 더함). 엔진 쪽 값은 05-behavior.md 3절. 결과는 10초 늦게 나오고, 정상 조향은 미세 수정 분당 20회 이상·큰 수정 4회 이하, 졸음은 급조향(2~3분마다)이나 맥락을 섞어야 2·3단계까지 가. 딴짓은 출발 15분 10초 뒤면 아무 초에 넣어도 잡히게 고쳤어. 화면 값은 연결기 runner.snapshots 하나에서 읽으면 되고, 게이지 회색일 때 assist.reason으로 '보조 주행'과 '조향 신호 없음'을 나눠 주면 좋겠어.

**3번에게**

> '졸려요' 받으면 연결기 runner.reportCondition(true, 시각, interventionManager) 불러 줘 (개운해요·보통은 false). 민감 모드 변화는 연결기가 1번에 알려. 그리고 네 PatternTrend에 AMBIGUOUS(애매)가 없는데 MSG-03은 4가지야. 애매는 음성 확인으로 정하는 경우라 맞춰야 할 것 같아.

**4번에게**

> 내 임시 AgeGroup·HourWindow 지우고 네 AgeBand·VulnerableTimeRange로 바꿨어. 값이 갈리지 않게 EngineConfig의 취약 시간대 기본값을 VulnerableTimeDefaults로 옮겼고 값은 같아(예보 시험 그대로 통과). 네 #9·#10·#11 들어온 main도 합쳐 봤는데 EngineConfig 충돌 없었고 네 시험 포함 엔진 308개 다 통과했어. 출발 피로는 연결기 runner.snapshots의 baseline.departureFatigue가 false→true 되는 순간 startFatigueDetected로 예보 다시 계산하면 돼. 휴식 인증 뒤 엔진 초기화는 네 InterventionRouter의 recoveryReset 자리에서 runner.onRestCertified()를 부르면 돼(기준선은 그대로, 평활값만 0). 1번한테도 말해 뒀어.
