# 4. 해석·제안값·가정과 설정값

## 1. 표기

| 표기 | 뜻 | 확정 방법 |
| --- | --- | --- |
| `[해석]` | 기획서·SRS 문장이 여러 뜻으로 읽혀서 하나를 고름 | 팀 확인 |
| `[제안값]` | 기획서에 숫자가 없어 SRS에서 새로 정한 값 | 팀 확인, 시연 데이터로 조정 |
| `[가정]` | 근거 없이 임시로 둔 값 | 시연 데이터로 확정 |
| `[가설]` | 근거가 약한 설계 판단 | 실차 검증 |

모든 값은 `config/`에 있어 코드 한 줄을 바꾸지 않고 `FatigueConfig(…)`처럼 넣어 바꿀 수 있다. 바꾸면 해당 시험의 기대값도 같이 바꿔야 한다.

## 2. 정한 것 전체 목록

### 게이트·지표 (FAT_001~008)

| # | 무엇 | 정한 것 | 이유 | 코드 |
| --- | --- | --- | --- | --- |
| G1 | 거리 모를 때 | 시속 60 km 이상 5분 유지 뒤부터 계산 [제안값] | FAT_004. 목적지 없으면 고속도로 진입 여부를 모름 | `unknownTurnHoldMs` |
| G2 | 5분 유지 중 감속 | 60 km 미만이 30초를 **넘게** 이어지면 다시 셈, 30초 이하면 이어서 [제안값]·[해석] | 톨게이트·짧은 정체로 매번 처음부터 세지 않게 | `unknownTurnLowSpeedGraceMs` |
| G3 | 신호 끊김 | 프레임 사이 1초 초과 → 끊김 [제안값] | 신호는 100 ms마다. 1초 넘게 없으면 그 사이를 모름 | `gateMaxFrameGapMs` |
| G4 | 방향지시등·브레이크 null | 게이트 불통과 | CLAUDE.md 4장 "null은 게이트 불통과" | `SteeringGate.baseReason` |
| G5 | 앞쪽 제외 방법 | 판정을 10초 늦게 확정 [해석] | FAT_002 비고 '소급 제외'를 실시간으로 하려면 미래 10초를 봐야 함 | `SteeringGate` |
| G6 | 방향 전환 인정 | 반대로 0.5° 이상 되돌아와야 [해석] | 미세 수정 하한(0.5°)과 같은 크기의 이력으로 센서 떨림 제거 | `SteeringReversalDetector` |
| G7 | 미세 수정 경계 | 0.5°·3.0° 포함 [해석] | 합격기준 "0.5, 2.0, 3.0 → 3건" | `swingClassOf` |
| G8 | 각도 비교 여유 | 0.001° [해석] | Float 반올림 오차 때문 | `ANGLE_EPS_DEG` |
| G9 | 급조향 '1초 안' | 마지막 멈춤 샘플부터, 1.0초 포함 [해석] | 합격기준 "0.9초 안 → 셈, 1.1초 → 안 셈" 사이 | `SuddenSteerDetector` |
| G10 | 창을 쓰는 조건 | 60초 중 게이트 통과 30초 이상 [제안값] | FAT_005 비고. 몇 초만 통과한 창은 분당 환산이 튐 | `minGatedMsPerWindow` |

### 점수 (FAT_009~020)

| # | 무엇 | 정한 것 | 이유 | 코드 |
| --- | --- | --- | --- | --- |
| S1 | 기준이 0일 때 | f미세 0, f큰은 지금 큰 수정 있으면 1 [해석] | 0에서 줄어들 수 없음 / 0에서 생겼으면 늘어난 것 | `FatigueFactors` |
| S2 | 3분 지수평균 | 시정수 180초, α 0.1535, 처음 값 0 [해석] | SRS 합격기준 "180 s 뒤 63.2". 0에서 시작하면 운행 초반 오경보가 없음 | `smoothingTimeConstantMs` |
| S3 | 무효 창 | f·B·R 0, 평활값은 마지막 값 유지 [해석] | AST_004 "위험 점수 갱신 0회" | `FatigueEngine.evaluate` |
| S4 | 보조 주행 창 | 갱신 시각의 판정이 보조 주행이면 그 창 전체를 무효 [해석] | 크루즈를 켠 동안 점수가 움직이지 않게 | 같음 |
| S5 | 취약 시간대 경계 | 시작 포함·끝 제외, 시 단위 [해석] | FAT_015 비고 "01:59 → 0, 02:00 → +0.10, 07:00 → 0" | `HourWindow` |
| S6 | 운전 시간 단위 | 초로 받음 | 1번 TripState가 초. 엔진 안 단위 변환 금지(SIG_010) | `ContextInput` |
| S7 | 시나리오 1 점수 | 61.0 | 같은 입력 (0.8, 1.0, 0, K 1.15)을 계산하면 61. 기획서·CLAUDE.md의 64는 고쳐야 함 | `RiskScoreTest` |

### 기준선 (BSL)

| # | 무엇 | 정한 것 | 이유 | 코드 |
| --- | --- | --- | --- | --- |
| B1 | 기본 기준선 | 미세 20/분, 큰 4/분 [가정] | SRS FAT_009·010 합격기준의 예시값. 시연 데이터로 정해야 함 | `defaultMicroPerMin` 등 |
| B2 | 평소의 나 '최근' | `tripId`가 큰 쪽이 최근 [가정] | 운행 번호가 늘어난다고 봄 (1번 tripId는 문자열이라 확인 필요) | `usualOf` |
| B3 | 평소의 나 중앙값을 1차에 | BSL_001(2차)의 계산만 미리 | BSL_003 "3회부터 평소의 나"가 돌아가려면 필요 | `usualOf` |
| B4 | 오늘의 나 값 | 게이트 통과 누적 15분의 전체 평균 [해석] | "처음 15분" = 통과 시간 15분 (BSL_004 '유효 구간') | `updateToday` |
| B5 | 출발 피로의 급조향 | 처음 15분 누적 횟수 [해석] | 같은 15분 비교 | 같음 |
| B6 | 민감 모드 기준선 | 오늘의 나가 있어도 평소의 나(없으면 기본값)로 고정 | 기획서 5-1 "비교 기준은 평소의 나로 고정" | `buildState` |
| B7 | '졸려요' 첫 확인 | 출발(첫 D단)부터 15분 [해석] | RES_006 "느림인데 출발하면 15분 뒤"와 같은 방식 | `firstCheckDueMs` |
| B8 | '개운해요'·'보통' | '졸려요'로 켠 민감 모드만 끔 [해석] | 출발 피로는 조향으로 판정한 것이라 유지 | `reportCondition` |
| B9 | 휴식 뒤 | 평활값만 0, 오늘의 나 유지 | RES_011 설계 근거 "쉬고 난 직후로 다시 잡으면 덜 깬 상태가 기준" | `resetRiskAfterRest` |
| B10 | 비교 기준의 큰 수정 하한 (11/7) | 2/분 [제안값]. 비교 기준과 출발 피로 비교에만, 오늘의 나 기록은 잰 값 그대로. 미세 수정에는 두지 않음 | 처음 15분 큰 수정 0 → 그 뒤 큰 수정 하나에 f큰 1(B +25)이던 과민 반응(고칠 것 2). 미세에 하한을 두면 원래 미세가 적은 운전자가 늘 졸음 쪽으로 보임 | `BaselineStore.comparable`, `minReferenceLargePerMin` |

### 패턴·안전 우회 (PAT)

| # | 무엇 | 정한 것 | 이유 | 코드 |
| --- | --- | --- | --- | --- |
| P1 | 1분 칸 | 칸마다 첫 창 하나 [해석] | 30초마다 오는 60초 창은 절반씩 겹쳐 그대로 세면 1분을 두 번 셈. 칸 정렬 약점은 P8로 보완 (03 3-12) | `onWindow` |
| P2 | 측정 못 한 칸 | 이상 분도 복귀도 아님 [해석] | 모르는 것을 정상으로 보지 않음 | `MinuteStatus.UNMEASURED` |
| P3 | 복귀 확인 | 방금 끝난 이상 분·다음 칸 측정 못 함 → 애매 [해석] | 복귀를 못 봤으면 튐형이라고 단정 못 함 (기획서 4-1 원칙 3 "애매하면 더 묻는 쪽") | `classify` |
| P4 | 기준 큰 수정 0 | 지금 큰 수정이 있으면 1.5배 이상으로 봄 [해석] | S1과 같음 | `isAbnormalMinute` |
| P5 | 5분 경계 | 300.0초 포함 [해석] | 합격기준 299.9 → 신호, 300.1 → 아님의 사이 | `onSudden` |
| P6 | 안전 우회 유지 | 3회째부터 5분 동안 켜 둠 [제안값] | 30초마다 내보내서 순간만 켜면 놓침 | `safetyBypassHoldMs` |
| P7 | 보조 주행 중 급조향 | 안전 우회에는 셈, 급조향 1회 사건은 안 냄 | 기획서 5-4 "안전 우회는 그대로" / AST_004 "조향을 판단에서 뺌" | `SafetySteering` |
| P8 | 급조향 칸 (11/7) | 급조향까지의 60초로 이상 분을 보고, 만족하면 그 급조향이 든 칸 하나만 이상 분 [해석] | 멈춘 구간과 급조향이 칸 경계로 갈리면 딴짓 1분을 놓쳤음(60가지 중 18가지). 고친 뒤 60가지 모두 튐형, 졸음 추세형 시각은 그대로 (고칠 것 1) | `onSuddenWindow`, `windowEndingAt` |
| P9 | 딴짓 기록 (11/11) | 정상 대답 뒤 3분 안에 새 이상 분으로 튐형이 되면 1건, 시각은 대답 시각 [제안값] | 급조향 바로 뒤의 확인은 그 1분 칸이 아직 판정 전이라 패턴이 '없음' | `onCheckResult`, `distractionResolveMs` |
| P10 | 끝까지 애매인 정상 대답 (11/11) | 딴짓으로 기록하지 않음 [해석] | 애매하면 단정하지 않는 쪽(기획서 4-1 원칙 3). 단계 판단은 1번이 정상 대답으로 따로 함 | `resolveDistraction` |

### 보조 주행 (AST)

| # | 무엇 | 정한 것 | 이유 | 코드 |
| --- | --- | --- | --- | --- |
| A1 | 인계 감시 시작 | 크루즈가 꺼질 때만. 조향 신호 복귀는 아님 [해석] | 인계 = 차가 하던 운전을 사람이 넘겨받는 순간 | `update` |
| A2 | 감시 범위 | (해제, 해제 + 30.0초], 크루즈 다시 켜면 끝 [해석] | 합격기준 29초 → 이상, 31초 → 아님 | `inWatch` |
| A3 | 인계 이상 표시 | 30초 유지, 해제 한 번에 사건 한 번 [해석] | 받는 쪽이 놓치지 않게, 중복 경보 막기 | `flag` |
| A4 | 평소 해제 횟수 | 최근 10회 `handoverLargeCount` 중앙값, 0이면 1회만 나와도 늘어남 [해석] | 평소의 나와 같은 방식 | `usualHandoverLargeCountOf` |
| A5 | 조작 공백의 조작 | 방향지시등·브레이크·크루즈 켜고 끄기·설정 속도·통풍. 공조 온도 제외 [해석] | 공조 온도 신호가 신호 묶음에 아직 없음 | `isOperation` |
| A6 | 크루즈 null | 일반 모드 [제안] | AST_008 | `update` |
| A7 | 조향 null | 보조 주행 간주(UNCERTAIN) | AST_003. 출발 직후 순환을 끊기 위해 SRS가 정함 | `update` |

### 조향 원천 5 Hz 미만 (SIG_009)

| # | 무엇 | 정한 것 | 이유 | 코드 |
| --- | --- | --- | --- | --- |
| R1 | 충분하다고 보는 조건 | `MEASURED` + `belowMinimum == false`만 | 1번 요청 "모름을 충분함으로 보지 말 것" | `DriveFrame.steeringRateOk` |
| R2 | 끄는 범위 | 게이트 통과 느린 프레임이 들어간 60초 창 [해석] | 주차 중 느린 신호는 지표와 상관없음 | `microEnabledAt` |
| R3 | B 재정규화 | `100 × (0.25·f큰 + 0.40·f급조향) ÷ 0.65` [제안값] | SIG_009 "큰 수정·급조향 가중치만 재정규화" | `behaviorScoreWithoutMicro` |
| R4 | 이상 분 | 미세 조건 빼고 '큰 1.5배 또는 급조향' [해석] | 느리면 미세가 거의 안 세져서 원래 규칙도 같은 결과 | `isAbnormalMinute` |
| R5 | 출발 피로·오늘의 나 | 처음 15분 중 10% 이상 느리면 미세 조건 빼고, 오늘의 나 안 씀 [해석]·[제안값 10%] | 흐린 신호로 만든 기준에 졸린 조향이 섞이는 것을 막음. 첫 10초 '측정 중'은 1%라 통과 | `todayMaxSlowSteeringRatio` |

### 그 밖

| # | 무엇 | 정한 것 | 이유 |
| --- | --- | --- | --- |
| E1 | `FatigueState` 기본값 | `invalidReason = GATE_WAIT` | Data Interface는 NONE이지만 기본 `valid = false`와 어긋남. SRS를 고쳐 달라고 요청 |
| E2 | 역행 프레임 | 앞 프레임보다 이른 시각은 버림 | 시간 창 계산이 깨지지 않게 |
| E3 | 사건 보관 | 꺼내지 않은 사건은 최대 100개 | 아무도 안 꺼내도 메모리가 계속 늘지 않게 |
| E4 | 비상등 (변환 함수) | 방향지시등 켜짐으로 봄 [해석] | 비상등을 켠 주행은 평소 조향이 아님 |
| E5 | 연결기: 무효 갱신의 평가 상태 (11/7) | 게이트 대기 → `NOT_EVALUATED`, 보조 주행 → `UNAVAILABLE`, 둘 다 점수 null [해석] | 1번 `DrivingAssessment` 설명 "미평가/계산 불가를 0점으로 바꾸지 않는다". 1번 확인 필요 |
| E6 | 연결기: 안전 이벤트 출처 | 조향 값의 출처 → 없으면 차속의 출처 → 없으면 REAL [해석] | 조향에서 나온 사건이므로 조향 출처가 가장 정확 |
| E7 | 연결기: 하루 중 분 | 프레임 시각(epoch ms)을 `Asia/Seoul`로 | 4번 `ForecastEngine`과 같은 해석. 데모 시나리오도 시각이 epoch여야 맞음 ([11](11-scenario-format-proposal.md)) |
| E8 | 취약 시간대 정의 (11/7) | 4번 `VulnerableTimeRange`·`AgeBand`로 통일, 값은 `VulnerableTimeDefaults` 한 곳 | CLAUDE.md 4장 "EngineConfig 한 곳". 예보·1단계·K가 같은 정의 |

## 3. 팀이 정해야 할 것 (회의 안건)

| 안건 | 지금 값 | 누가 | 급한 정도 |
| --- | --- | --- | --- |
| SDK 조향(전륜각)을 엔진에 넣을지, 넣으면 조향비·부호를 어떻게 할지 | 1번이 null로 보류 → 엔진은 '보조 주행(UNCERTAIN)'으로 봄 | 1·2번, Pleos 문의 | **높음** |
| 원천 시각 없는 SDK 조향의 5 Hz 판정 | `UNMEASURABLE` → 미세 수정 늘 꺼짐 | 1·2번 | 높음 |
| 데모 시나리오에 회전 지점 거리 칸을 넣을지 | 없음 → 첫 5분 계산 안 됨. 제안은 [11](11-scenario-format-proposal.md) | 1·2·5번 | **높음** (DMO_006) |
| '15분 뒤 첫 확인'을 받을 1번 입력 | 11/11 `InterventionInput.CheckDue` 추가(1번 파일 2곳), 연결기가 보냄 → **1번 리뷰** | 1번 | **높음** (BSL_006·007) |
| 연결기 무효 갱신 대응 (E5) | GATE_WAIT → NOT_EVALUATED, ASSIST → UNAVAILABLE | 1번 | 중간 |
| 큰 수정 하한 2/분 (B10) | [제안값] | 2·5번 (시연 데이터) | 낮음 |
| 기본 기준선 20/4 | [가정] | 2·5번 (시연 데이터) | 높음 |
| PAT_004·006·007, AST_005 담당을 1번으로 | SRS는 2번 | 팀장(SRS) | 중간 |
| AST_005 '야간' 시간 정의 | 없음 | 1번(구현)·팀 | 중간 |
| MSG-16 BaselineState, SteeringEvent, DriveFrame, MSG-02 `microEnabled`를 Data Interface에 추가 | 코드에만 있음 | 팀장(SRS) | 중간 |
| `FatigueState.invalidReason` 기본값 NONE → GATE_WAIT | 코드는 GATE_WAIT | 팀장(SRS) | 낮음 |
| 시나리오 1 점수 64 → 61 (기획서·CLAUDE.md·AGENTS.md) | 코드·SRS는 61 | 팀장 | 낮음 |
| `tripId` 타입 (SRS Long, 1번 String) | 2번 `BaselineRecord.tripId: Long` | 1·2번 | 중간 (2차 기록 저장 전) |
| G1 5분, G10 30초, S2 α, P6 5분, R5 10% | [제안값] | 팀 | 낮음 |

## 4. 설정값 전체 표

11/7부터 아래 네 묶음은 팀 `EngineConfig`의 `fatigue`·`baseline`·`pattern`·`assist` 칸에 들어 있다. 앱은 `FatigueEngine(EngineConfig)`로 엔진을 만들고, 값을 바꿀 때는 `EngineConfig(fatigue = FatigueConfig(...))`처럼 한 곳에서 바꾼다 (CLAUDE.md 4장).

### `FatigueConfig` (게이트·지표·점수)

| 이름 | 기본값 | 근거 |
| --- | --- | --- |
| `gateMinSpeedKph` | 60 km/h | FAT_001 |
| `turnSignalMarginMs` | 10,000 | FAT_002 |
| `brakeMarginMs` | 5,000 | FAT_003 |
| `minDistToNextTurnM` | 300 m | FAT_004 |
| `unknownTurnHoldMs` | 300,000 (5분) | FAT_004 [제안값] |
| `unknownTurnLowSpeedGraceMs` | 30,000 | FAT_004 [제안값] |
| `gateMaxFrameGapMs` | 1,000 | [제안값] |
| `windowMs` / `updateIntervalMs` | 60,000 / 30,000 | FAT_005 |
| `minGatedMsPerWindow` | 30,000 | FAT_005 [제안값] |
| `microMinDeg` / `microMaxDeg` | 0.5° / 3.0° | FAT_006 |
| `largeMinDeg` | 5.0° | FAT_007 |
| `suddenStillMs` / `suddenStillMaxDeltaDeg` | 4,000 / 0.5° | FAT_008 |
| `suddenTurnWithinMs` / `suddenTurnMinDeg` | 1,000 / 6.0° | FAT_008 |
| `suddenCountWindowMs` | 300,000 (5분) | FAT_011 |
| `fMicroDropForFull` / `fLargeRiseForFull` / `fSuddenCountForFull` | 0.5 / 1.0 / 2 | FAT_009~011 |
| `weightMicro` / `weightLarge` / `weightSudden` | 0.35 / 0.25 / 0.40 | FAT_012 |
| `riskMax` | 100 | FAT_012 |
| `smoothingTimeConstantMs` | 180,000 (α 0.1535) | FAT_020 [해석] |

### `ContextWeightConfig` (K, `FatigueConfig.context`)

| 이름 | 기본값 | 근거 |
| --- | --- | --- |
| `kBase` / `kMax` | 1.00 / 1.35 | FAT_013 |
| `longDriveSec` / `longDriveWeight` | 7,200초 / +0.10 | FAT_014 |
| `vulnerableBaseRanges` / `vulnerableTimeWeight` | 02~07시, 14~17시 / +0.10 | FAT_015. 값은 `VulnerableTimeDefaults`, 앱에서는 `EngineConfig.vulnerableBaseRanges`가 들어감 |
| `vulnerableAgeRanges` | 50대+ 14~18, 30~40대 22~02, 20대 04~08 | FAT_015, 기획서 1장. 앱에서는 `EngineConfig.vulnerableAgeRanges` |
| `soloDriveWeight` | +0.05 | FAT_016 |
| `warmCabinMinC` / `warmCabinWeight` | 25 ℃ / +0.05 | FAT_017 |
| `longDayDriveSec` / `longDayDriveWeight` | 14,400초 / +0.05 | FAT_018 |

### `BaselineConfig`

| 이름 | 기본값 | 근거 |
| --- | --- | --- |
| `defaultMicroPerMin` / `defaultLargePerMin` | 20 / 4 | BSL_003 [가정] |
| `minTripsForUsual` / `usualRecentTrips` | 3 / 10 | BSL_003 / BSL_001 |
| `awakeMaxDriveMs` | 7,200,000 (2시간) | BSL_002 (2차, 아직 안 씀) |
| `todayBaselineGatedMs` | 900,000 (15분) | BSL_004 |
| `departureMicroDropRatio` / `departureLargeRiseRatio` / `departureSuddenMinCount` / `departureMinCriteria` | 0.30 / 1.5 / 1 / 2 | BSL_005 |
| `sensitiveFirstCheckDelayMs` | 900,000 (15분) | BSL_006 |
| `todayMaxSlowSteeringRatio` | 0.10 | SIG_009 [제안값] |
| `minReferenceLargePerMin` | 2 | 비교 기준 큰 수정 하한 [제안값] (B10) |

### `PatternConfig`

| 이름 | 기본값 | 근거 |
| --- | --- | --- |
| `abnormalMicroRatioMax` / `abnormalLargeRatioMin` | 0.70 / 1.5 | PAT_001 |
| `lookbackMinutes` | 10 | PAT_002 |
| `trendMinAbnormalMinutes` / `spikeMaxAbnormalMinutes` | 6 / 3 | PAT_002·003 |
| `spikeRecoverWithinMs` | 60,000 | PAT_003 |
| `safetyBypassSuddenCount` / `safetyBypassWindowMs` | 3 / 300,000 | PAT_005 |
| `safetyBypassHoldMs` | 300,000 | PAT_005 [제안값] |
| `distractionResolveMs` | 180,000 | PAT_004 [제안값] (11/11) |

### `AssistConfig`

| 이름 | 기본값 | 근거 |
| --- | --- | --- |
| `handoverWatchMs` | 30,000 | AST_001·007 |
| `handoverLargeRatio` / `handoverLargeCountNoRecord` | 1.5 / 3 | AST_007 |
| `smoothWindowMs` / `smoothMicroRatioMax` | 180,000 / 0.30 | AST_002 (2차, 아직 안 씀) [가설] |

1번 InterventionPolicy 쪽 값(단계 문턱 40/60/80·30/50/70, 재확인 10분, 느림 2회, 보조 주행 확인 20분·10분, 조작 공백 900초)은 1번 `EngineConfig`에 있다. 2번 설정에서는 2b단계에서 겹치는 7개를 지웠다.
