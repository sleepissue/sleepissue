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

## 2. 지금 상태 (11/4)

| PR | 브랜치 | 상태 |
| --- | --- | --- |
| 2번 (우리) | `feat/engine-model` → `main` | **아직 안 열림.** 커밋 12개, main이 그대로라 지금은 충돌 없이 합쳐짐 |
| #6 (1번) | `develop-jdw` → `main` | 열림, 충돌 없음, 리뷰 0건. 커밋 4개, 파일 242개 |
| #5 (3번) | `feat/voice-check` | 닫힘 (머지 안 됨) |
| 4번 | `feat/rest-forecast-engine` | PR 없음 |

### 머지 순서 제안: 1번 #6 → 2번

1번 PR에 신호 자료형(`SignalFrame`·`NaviState`·`TripState`)과 팀 `EngineConfig`·`Clock`이 들어 있다. 이게 먼저 들어가야 2번이 변환 함수를 넣어 실제로 연결할 수 있다. 두 브랜치를 시험 삼아 합쳐 보니 충돌은 `src/android/engine/build.gradle.kts` 하나였고, 합친 엔진 시험 187개가 통과했다.

## 3. PR 여는 법

### 웹에서

1. `https://github.com/sleepissue/sleepissue` → **Pull requests** → **New pull request**
2. **base: `main`** ← **compare: `feat/engine-model`** 로 고른다 (방향이 반대면 안 됨)
3. 제목: `2번 졸음 판단 엔진 1차 (FAT·BSL·PAT·AST, SIG_009 엔진 쪽)`
4. 본문: 받은 `PR_feat-engine-model.md` 내용을 그대로 붙여 넣는다
5. 오른쪽 **Reviewers**에 1번을 고른다
6. #6을 먼저 넣을 거면 **Create draft pull request**(초안), 바로 리뷰받을 거면 **Create pull request**

### 터미널에서 (gh가 설치·로그인돼 있으면)

```bash
gh pr create --base main --head feat/engine-model \
  --title "2번 졸음 판단 엔진 1차 (FAT·BSL·PAT·AST, SIG_009 엔진 쪽)" \
  --body-file ~/Downloads/PR_feat-engine-model.md --draft
```

### #6이 먼저 들어간 뒤 할 일

```bash
cd ~/sleepissue && git checkout feat/engine-model && git fetch origin
git merge origin/main                    # 충돌: src/android/engine/build.gradle.kts
git checkout --theirs src/android/engine/build.gradle.kts   # main(=1번) 것으로
git add src/android/engine/build.gradle.kts && git commit --no-edit
cd src/android && ./gradlew :engine:test --console=plain     # 엔진 시험 187개 통과 확인
cd ~/sleepissue && git push              # PR에 자동 반영
```

그다음 신호 변환 함수 패치(준비됨)를 넣어 push → 193개.

### 머지할 때

- **Create a merge commit**으로 합친다. Squash로 합치면 단계별 커밋 12개가 하나로 뭉쳐 [07](07-history.md)의 커밋 해시를 main에서 찾을 수 없다.
- 머지 뒤 브랜치를 지워도 커밋은 main 기록에 남는다.

### 1번 PR #6을 리뷰할 때 2번이 볼 것

- `SignalFrame`·`NaviState`에 게이트가 쓰는 값(조향각, 차속, 기어, 방향지시등, 브레이크, 회전 지점 거리, 크루즈·설정 속도, 통풍)이 다 있는지 → 있음 (변환 함수 시험 통과)
- 1번 `EngineConfig`·InterventionPolicy가 2번 출력(MSG-02·03·04, BaselineState, SteeringEvent)을 받을 자리가 있는지
- `SafetyEventType` 이름이 2번 `SteeringEventType`과 같은지 → 같음

## 4. 팀원별로 알릴 것

### 4-1. 1번 (차량 신호·단계 전환)

| # | 내용 | 급함 |
| --- | --- | --- |
| 1 | **SDK 조향이 전륜각이라 지금 엔진에는 null로 들어간다.** 엔진은 조향 null을 '보조 주행으로 간주(UNCERTAIN)'하므로 에뮬레이터 실제 입력으로는 점수가 안 나오고 게이지가 계속 회색이다. 데모(시나리오 재생)는 조향 값이 있어 괜찮다. 1차는 이대로 두고 Pleos 답을 기다릴지, 조향비·부호를 정해 바꿔 넣을지 정하자. 엔진 문턱(0.5~3°, 5°, 6°)은 **운전대 각도** 기준이다 | 높음 |
| 2 | **원천 시각 없는 SDK 조향은 RateMeter가 `UNMEASURABLE`** → 2번 매핑(`MEASURED`·미만 아님일 때만 충분)대로면 실제 SDK에서는 미세 수정이 늘 꺼진다. 1차는 이대로(보수적) 두자고 제안. 데모는 `SCENARIO` 시간축이라 `MEASURED`가 된다 | 높음 |
| 3 | 데모 시나리오 JSON(MSG-11)에 회전 지점 거리가 없다 → 거리 null이면 게이트가 5분 고속 유지 뒤에 열려 모든 시나리오 첫 5분이 계산 안 되고, 출발 피로가 15:10이 아니라 20:10에 판정된다(DMO_006 불합격). DemoPlayer 담당으로서 선택 필드 `distToNextTurnM`을 넣을지 같이 정하자 | 높음 |
| 4 | 머지 순서 #6 → 2번. `engine/build.gradle.kts`는 1번 것으로 | 높음 |
| 5 | 연결: 매 프레임 `push(frame)` → 반환된 `FatigueState`는 `valid`일 때만 `riskSmoothed`를 EVALUATED로, `drainEvents()` → 이름이 같은 `SafetyEventType`, `trending = (patternType == TREND)`, `safetyBypass` → 3단계, `baselineState.sensitive` OR RES_006 → 문턱 30/50/70, `firstCheckDueMs`에 첫 확인, `assistState.assistMode` → AST_005 확인 주기, `opGapSec > 900` → 확인 당김, `handoverAbnormal`·`HANDOVER_UNSAFE` → 2단계 | 중간 |
| 6 | 새 운행(TRP_008)이면 `reset()`(컨디션 받기 전에), 휴식 인증 + 회복 정상(RES_011)이면 `resetRiskAfterRest()`. 거꾸로 부르면 오늘의 나가 지워진다 | 중간 |
| 7 | SRS 담당자 칸 PAT_004·006·007, AST_005 → 1번 (InterventionPolicy에 이미 값이 있음) | 중간 |
| 8 | `tripId` 타입: SRS MSG-08·13은 Long, 1번 `TripState.tripId`는 String. 2번 `BaselineRecord.tripId`를 어디에 맞출지 (2차 기록 저장 전에) | 낮음 |
| 9 | 비상등을 방향지시등 켜짐으로 보는 것(변환 함수 [해석]), AST_005 '야간' 시간 정의 | 낮음 |

### 4-2. 3번 (음성·실내)

| # | 내용 |
| --- | --- |
| 1 | 출발 전 컨디션 말(COND_SLEEPY)을 받으면 `engine.baselineStore.reportCondition(sleepy = true, tMs)`, COND_FRESH·NORMAL이면 `false`. "출발 전에 15분 쉬었다 가는 건 어때요?" 권유는 3번이 한 번 말함 (BSL_007) |
| 2 | 3번 `PatternTrend`는 NONE·SPIKE·TREND 3가지인데 MSG-03 `PatternType`은 AMBIGUOUS(애매)까지 4가지. 애매는 "음성 확인 결과로 정함"(PAT_003)이라 확인을 거는 쪽이 받아야 한다. 이름을 2번 것으로 맞추자 |
| 3 | 음성 확인 결과(정상·느림·무응답)는 2번이 아니라 1번 InterventionPolicy가 받는다 (2b단계 결정) |
| 4 | 간트에는 단계 전환(INT, [32])이 3번으로 되어 있지만 1번이 #6에 이미 구현했다. 누가 맡을지 확인 |

### 4-3. 4번 (예보·쉼터·휴식)

| # | 내용 |
| --- | --- |
| 1 | `baselineState.departureFatigue`가 false → true로 바뀌는 순간 예보 다시 계산 (FCS_001, BSL_006). 운행 중 한 번만 바뀐다 |
| 2 | 휴식 인증 + 회복 정상 뒤 2번 쪽은 `resetRiskAfterRest()` — 평활값만 0, 기준선은 그대로 (RES_011 표와 같음) |
| 3 | 회복 '느림' 뒤 민감 모드(RES_006)는 4번이 내고 1번이 2번 `sensitive`와 OR로 합친다 |
| 4 | 4번 `AgeBand`·`VulnerableTimeRange`가 main에 들어오면 2번 임시 `AgeGroup`·`HourWindow`를 바꾼다. 시간대는 시작 포함·끝 제외, 연령대별 추가 시간대(50대+ 14~18, 30~40대 22~02, 20대 04~08)가 FAT_015와 같은지 봐 달라. 취약 시간대 정의를 한 곳에 둘지(예보·K·1단계) 정하자 |

### 4-4. 5번 (화면·시연)

| # | 내용 |
| --- | --- |
| 1 | 게이지: `riskSmoothed` 0~39.9 초록, 40~59.9 노랑, 60~79.9 주황, 80~100 빨강. `valid = false`면 `invalidReason`으로 GATE_WAIT → '측정 대기', ASSIST → 회색 (UI_003) |
| 2 | 회색일 때 `assistState.reason`으로 CRUISE('보조 주행')와 UNCERTAIN('조향 신호 없음')을 나눠 보여 주면 좋다. 지금 실제 SDK 입력은 UNCERTAIN이다 |
| 3 | 모드 표시: `assistState.assistMode`(보조 주행), `baselineState.sensitive`(민감 모드). `microEnabled = false`면 미세 수정 칸은 '측정 안 함' |
| 4 | 그래프 시각: `FatigueState.tMs`·사건 시각은 넣은 신호보다 10초 늦다 |
| 5 | 출발 전 컨디션 버튼 → `reportCondition(…)` |
| 6 | **데모 시나리오 값**은 [05 3절](05-behavior.md#3-5번-데모-시나리오에-넣을-값). 특히 회전 지점 거리, 정상 조향을 기본값(미세 20/분·큰 4/분) 근처로, 졸음은 급조향이나 맥락을 섞어 순간 점수 70 이상, 딴짓은 15:10 뒤에 |

### 4-5. 팀장·SRS 담당

| # | 고칠 곳 | 내용 |
| --- | --- | --- |
| 1 | SRS 담당자 칸 | PAT_004·006·007, AST_005 → 1번 |
| 2 | Data Interface | 추가: MSG-16 `BaselineState`, `SteeringEvent`, 2번 입력 `DriveFrame`, MSG-02 14번째 `microEnabled`. 수정: MSG-02 `invalidReason` 기본값 NONE → GATE_WAIT |
| 3 | 기획서·CLAUDE.md·AGENTS.md | 시나리오 1 "23:10 추세형 64점" → 61점 (같은 입력으로 계산한 값, SRS FAT_012는 이미 61) |
| 4 | SRS AST_005 | '야간' 시간 정의 |
| 5 | SRS DMO_001·006 | 시나리오에 회전 지점 거리를 넣거나, DMO_006 합격기준을 "게이트 통과 15분 + 10초"로 |
| 6 | 간트 | [09 2절](09-schedule-todo-limits.md#2-간트에서-고칠-것) |
| 7 | Pleos 메일 | 간트 [5]는 10/30 발송 100%로 돼 있는데 실제로 보냈는지 확인. 조향 질문에 "SteeringWheel 값이 전륜각인지 운전대각인지, 부호, 원천 시각(타임스탬프)·갱신 주기"를 더하자 |
| 8 | 블라인드 | 10/27 PR #3에서 커밋 noreply·실명 금지 규칙을 뺐지만, 접수양식 02장에는 "공유 링크와 첨부자료에도 개인정보·계정정보·개발자 실명이 노출되지 않도록"이라고 적혀 있다. GitHub 링크를 낸다면 커밋 작성자 이름·이메일을 어떻게 할지 제출 전에 한 번 정하자 |

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

> 2번 엔진 1차 끝났어. #6 먼저 머지하고 내 PR 넣는 순서로 가자 (충돌은 engine/build.gradle.kts 하나, 네 것으로 고름). 맞출 게 세 가지 있어. ① SDK 조향을 null로 보류하면 엔진이 '조향 없음 = 보조 주행 간주(AST_003)'로 봐서 실제 입력에서는 게이지가 계속 회색이야. 1차는 데모 재생만 점수가 나오게 두고 Pleos 답 기다릴지 정하자. ② 원천 시각 없는 조향은 RateMeter가 UNMEASURABLE이라 내 쪽에서 미세 수정을 끄게 돼 있어. 1차는 그대로 두자고 제안. ③ 시나리오 JSON에 회전 지점 거리가 없어서 첫 5분이 계산 안 되고 출발 피로가 20:10에 나와. DemoPlayer에서 거리 선택 필드를 넣을지 같이 정하자. 자세한 건 docs/fatigue-engine/08-team-handoff.md 4-1.

**5번에게**

> 데모 시나리오 만들 때 엔진 쪽 값 정리해 뒀어: docs/fatigue-engine/05-behavior.md 3절. 결과는 10초 늦게 나오고, 정상 조향은 미세 수정 분당 20회 이상·큰 수정 4회 이하로 고르게, 졸음은 급조향(2~3분마다)이나 맥락을 섞어야 2·3단계까지 가. 딴짓은 출발 15분 10초 뒤에 넣어 줘. 게이지 회색일 때 assistState.reason으로 '보조 주행'과 '조향 신호 없음'을 나눠 주면 좋겠어.

**3번에게**

> '졸려요' 받으면 engine.baselineStore.reportCondition(true, 시각) 불러 줘 (개운해요·보통은 false). 그리고 네 PatternTrend에 AMBIGUOUS(애매)가 없는데 MSG-03은 4가지야. 애매는 음성 확인으로 정하는 경우라 맞춰야 할 것 같아.

**4번에게**

> baselineState.departureFatigue가 false→true 되는 순간 예보 다시 계산하면 돼 (운행 중 한 번). 휴식 인증 뒤엔 엔진 resetRiskAfterRest()라 기준선은 그대로야. AgeBand 들어오면 내 임시 AgeGroup 바꿀게. 시간대가 시작 포함·끝 제외인지 봐 줘.
