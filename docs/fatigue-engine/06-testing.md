# 6. 시험

## 1. 돌리는 법

```bash
cd ~/sleepissue/src/android
./gradlew :engine:test --console=plain                           # 엔진 시험 전부
./gradlew :engine:test --tests '*SteeringGateTest' --console=plain   # 한 묶음만
./gradlew :engine:test --tests '*FatigueEngineTest.drowsy*'          # 이름이 drowsy로 시작하는 것만
```

결과 보기:

```bash
# 묶음마다 한 줄. failures="0" errors="0"이면 통과
grep -h "<testsuite" engine/build/test-results/test/*.xml | sed 's/ timestamp.*//'
# 브라우저로 보기
xdg-open engine/build/reports/tests/test/index.html
```

- `BUILD SUCCESSFUL`만 보고 끝내지 않는다. 시험 파일이 하나도 안 잡히면 `NO-SOURCE`로도 성공이 뜬다. 위 `grep`으로 묶음 수(13)와 시험 수(104)를 확인한다.
- 이미 통과한 뒤 아무것도 안 바꾸면 Gradle이 `UP-TO-DATE`로 건너뛴다. 다시 돌리려면 `./gradlew :engine:test --rerun-tasks`.
- 실패하면 `engine/build/reports/tests/test/index.html`에서 실패한 시험을 누르면 기대값·실제값·줄 번호가 나온다. 시험 첫 줄 주석에 SRS 합격기준이 적혀 있다.

## 2. 지금 결과

| 대상 | 결과 |
| --- | --- |
| `feat/engine-model` `c060214` (GitHub에 올라간 그대로) | 13개 묶음 **104개 통과**, 실패 0 |
| 위 + 1번 `develop-jdw` 시험 병합 | 22개 묶음 187개 통과 (2번 104 + 1번 83). 충돌은 `engine/build.gradle.kts` 하나 |
| 위 + 신호 변환 함수(PR #6 병합 뒤 넣을 것) | 23개 묶음 193개 통과 |
| 11/6: 문서 커밋 `2dba62c` + main `7bc5ffd`(11/4에 #6·#7·#8 병합) | 29개 묶음 **236개 통과** (2번 104 + main 132). 충돌은 `engine/build.gradle.kts` 하나, main 것으로 고름 |
| 위 + 신호 변환 함수 | 30개 묶음 **242개 통과** |
| GitHub 커밋 11개와 보낸 패치 11개 대조 | 모두 같은 내용 (트리 해시 일치) |

이 문서의 시험은 Kotlin 2.0.21 컴파일러와 JUnit 4.13.2로 따로 돌린 결과다. 팀 빌드(Gradle 9.6.0, Kotlin 2.4.21)에서도 같아야 하므로, 올린 뒤 각자 위 명령으로 한 번 확인한다.

## 3. 시험이 정말 규칙을 지키는지 (돌연변이 시험)

시험이 통과해도 시험이 엉성하면 규칙이 틀려도 통과한다. 그래서 규칙을 **일부러 하나씩 틀리게 바꾸고** 시험이 실패하는지 봤다. 21개 모두 잡았다.

| 바꾼 규칙 | 결과 (실패한 묶음과 실패 수) |
|---|---|
| 게이트 속도 60 → 61 km/h | 잡음: SteeringGateTest(1) |
| 방향지시등 여유 10 → 9초 | 잡음: FatigueEngineTest(5) SteeringGateTest(2) |
| 브레이크 여유 5 → 4초 | 잡음: SteeringGateTest(1) |
| 회전 지점 300 → 301 m | 잡음: SteeringGateTest(1) |
| 목적지 없을 때 5 → 4분 유지 | 잡음: SteeringGateTest(3) |
| 미세 수정 상한 3.0 → 3.2° | 잡음: SteeringReversalDetectorTest(2) |
| 큰 수정 문턱 5.0 → 4.8° | 잡음: SteeringReversalDetectorTest(2) |
| 급조향 멈춤 4.0 → 3.9초 | 잡음: SuddenSteerDetectorTest(1) |
| 가중치 미세 0.35 → 0.30 | 잡음: FatigueConfigTest(1) FatigueEngineTest(3) RiskScoreTest(3) |
| K 상한 1.35 → 1.40 | 잡음: FatigueConfigTest(1) ContextWeightTest(1) |
| 지수평균 시정수 180 → 170초 | 잡음: FatigueConfigTest(1) FatigueEngineTest(2) RiskSmootherTest(3) |
| R 상한 100 없앰 | 잡음: RiskScoreTest(1) |
| 오늘의 나 15 → 14분 | 잡음: BaselineStoreTest(1) FatigueEngineTest(2) |
| 출발 피로 조건 2 → 3개 | 잡음: BaselineStoreTest(4) FatigueEngineTest(2) |
| 추세형 6 → 5분 | 잡음: FatigueEngineTest(1) PatternClassifierTest(2) |
| 안전 우회 5분 → 4분 창 | 잡음: PatternClassifierTest(1) |
| 인계 감시 30 → 29초 | 잡음: AssistModeDetectorTest(2) |
| 조향 null을 보조 주행으로 안 봄 | 잡음: AssistModeDetectorTest(1) |
| 5 Hz 미만이어도 미세 수정 씀 | 잡음: FatigueEngineTest(4) |
| 보조 주행 중 점수 계속 갱신 | 잡음: FatigueEngineTest(2) |
| 민감 모드여도 오늘의 나 씀 | 잡음: BaselineStoreTest(2) FatigueEngineTest(1) |

## 4. 시험 목록 (104개)

REQ 칸은 시험이 확인하는 SRS 요구사항이다. '확인하는 것'은 시험 첫 주석(대부분 SRS 합격기준을 그대로 옮긴 것)이다.

#### FatigueConfigTest (9개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 1 | `scoreWeightsSumToOne` | FAT_012 | B = 100 × (0.35 + 0.25 + 0.40) 일 때 최대 100 (REQ_FAT_012) |
| 2 | `allContextWeightsAddUpToKMax` | FAT_013 | "5개 전부 → 1.35" (REQ_FAT_013 합격기준) |
| 3 | `smoothingAlphaMatchesSrs` | FAT_020 | 시정수 180 s, 갱신 30 s, α = 0.1535 (REQ_FAT_020 합격기준) |
| 4 | `microAndLargeRangesDoNotOverlap` | FAT_007 | 3.1~4.9°는 어느 쪽에도 세지 않는다 (REQ_FAT_007 합격기준) |
| 5 | `defaultVulnerableWindowsMatchSrsBoundaries` | FAT_015 | 01:59 → 0, 02:00 → +0.10, 06:59 → +0.10, 07:00 → 0, 13:59 → 0, 14:00 → +0.10 (REQ_FAT_015) |
| 6 | `thirtiesFortiesWindowCrossesMidnight` | FAT_015 | 30대 설정 시 22:00 → +0.10 (REQ_FAT_015). 22~02시는 자정을 넘는다 |
| 7 | `unsetAgeGroupAddsNoWindow` | FAT_015 |  |
| 8 | `defaultFatigueStateIsWaitingForGate` | UI_003 | 처음에는 지표가 없으므로 '측정 대기' (REQ_UI_003) |
| 9 | `hourWindowRejectsEmptyRange` | FAT_015 |  |

#### SteeringGateTest (13개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 10 | `speedBoundaryAt60Kph` | FAT_001 | 59.9 km/h → 제외, 60.0 km/h → 포함 |
| 11 | `onlyDriveGearPasses` | FAT_001 | P·R·N 단 → 제외, D단 → 포함 |
| 12 | `turnSignalExcludesTenSecondsBeforeAndAfter` | FAT_002 | 작동 100.0~103.0 s → 제외 구간 90.0~113.0 s, 89.9 s·113.1 s 샘플은 포함 |
| 13 | `brakeExcludesFiveSecondsBeforeAndAfter` | FAT_003 | 작동 200.0~202.0 s → 제외 구간 195.0~207.0 s, 194.9 s·207.1 s 샘플은 포함 |
| 14 | `knownTurnDistanceBoundaryAt300m` | FAT_004 | 거리 299.9 m → 제외, 300.0 m → 포함 |
| 15 | `unknownTurnDistanceNeedsFiveMinutesAt60Kph` | FAT_004 | null + 60 km/h 이상 4 min 59 s → 제외, 5 min 0 s → 포함 |
| 16 | `shortSlowdownKeepsFiveMinuteCount` | FAT_004 | 60 km/h 미만 29 s 후 회복 → 유지 (처음 고속이 시작된 0 s부터 계속 셈) |
| 17 | `longSlowdownRestartsFiveMinuteCount` | FAT_004 | 60 km/h 미만 31 s → 5분 다시 셈. 151.1 s에 고속 회복 → 451.1 s부터 포함 |
| 18 | `signalGapRestartsFiveMinuteCount` | FAT_004 | 신호가 1초 넘게 끊기면 그 사이 속도를 모르므로 처음부터 다시 셈 [제안값] |
| 19 | `assistModeIsExcluded` | AST_004, PAT_005 | 보조 주행만 빼고 보면 통과 → 안전 우회·인계 품질 감시에는 쓴다 (기획서 5-4) |
| 20 | `unknownSignalIsExcluded` | FAT_001 | null은 "이 신호를 지금 못 받음". 게이트를 통과시키지 않는다 (CLAUDE.md 4장) |
| 21 | `decisionIsFinalizedTenSecondsLater` | FAT_002 |  |
| 22 | `outOfOrderFrameIsDropped` | FAT_005 |  |

#### SteeringReversalDetectorTest (4개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 23 | `microCountsSwingsFromHalfToThreeDegrees` | FAT_006 | 극값 간 변화량 0.4, 0.5, 2.0, 3.0, 3.1° 입력 시 미세 수정 3건(0.5, 2.0, 3.0) (REQ_FAT_006) |
| 24 | `largeCountsSwingsFromFiveDegrees` | FAT_007 | 변화량 4.9, 5.0, 8.0° 입력 시 큰 수정 2건(5.0, 8.0), 3.1~4.9°는 어느 쪽에도 계수하지 않음 (REQ_FAT_007) |
| 25 | `betweenThreeAndFiveDegreesIsNeither` | FAT_007 |  |
| 26 | `resetForgetsLastExtremum` | FAT_005 | 게이트를 못 넘은 구간을 건너 각도를 이으면 가짜 조향이 생기므로 reset 뒤에는 새로 시작한다 |

#### SuddenSteerDetectorTest (5개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 27 | `stillOnlyFourSecondsIsNotCounted` | FAT_008 | 정지 4.0 s + 1 s 안 6° → 0건 (4초를 '넘게' 멈추지 않음) |
| 28 | `stillOverFourSecondsThenSixDegreesWithinASecondIsCounted` | FAT_008 | 정지 4.1 s + 0.9 s 안 6° → 1건 |
| 29 | `turnBelowSixDegreesIsNotCounted` | FAT_008 | 정지 4.1 s + 1 s 안 5.9° → 0건 |
| 30 | `turnSlowerThanOneSecondIsNotCounted` | FAT_008 | 정지 4.1 s + 1.1 s에 6° → 0건 |
| 31 | `suddenJumpInOneSampleIsCounted` | FAT_008 | 한 샘플 만에 6° 꺾인 경우도 1건 |

#### SteeringMetricsTest (6개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 32 | `updatesEveryThirtySecondsAfterFirstMinute` | FAT_005 | 300 s 연속 통과 입력 시 산출 9회(t=60, 90, …, 300 s), 갱신 간격 30 s, 창 길이 60 s (REQ_FAT_005) |
| 33 | `countsMicroCorrectionsPerMinute` | FAT_006 | 1초마다 2° 방향 전환 → 분당 약 60회 |
| 34 | `scalesToPerMinuteWhenOnlyPartOfWindowPassed` | FAT_005 | 창 60초 중 45초만 통과해도 분당으로 환산하면 같은 운전은 같은 값 (REQ_FAT_005 "분당 횟수를 환산") |
| 35 | `tooLittleGatedTimeIsNotValid` | FAT_005 | 창 안에서 통과 시간이 30초 미만이면 이번 갱신은 쓰지 않음 [제안값] (REQ_FAT_005 비고) |
| 36 | `doesNotJoinAnglesAcrossExcludedGap` | FAT_005, FAT_007 | 0°로 달리다 게이트 밖(10~20 s)에서 8°가 되고, 다시 통과 구간에서 7°로 돌아옴. |
| 37 | `suddenSteerCountKeepsOnlyLastFiveMinutes` | FAT_011, PAT_001 | 4.2 s에 멈춤 뒤 급조향 1건. 300 s(295.8 s 전) → 셈, 330 s(325.8 s 전) → 제외 (REQ_FAT_011 "5분 1초 전 급조향은 제외") |

#### FatigueFactorsTest (4개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 38 | `fMicroMatchesSrs` | FAT_009 | 기준 20, 현재 20 → 0, 15 → 0.5, 10 → 1.0, 5 → 1.0(상한), 25 → 0(하한) (REQ_FAT_009) |
| 39 | `fLargeMatchesSrs` | FAT_010 | 기준 4, 현재 4 → 0, 6 → 0.5, 8 → 1.0, 12 → 1.0(상한), 2 → 0(하한) (REQ_FAT_010) |
| 40 | `fSuddenMatchesSrs` | FAT_011 | 0회 → 0, 1회 → 0.5, 2회 → 1.0, 3회 → 1.0(상한) (REQ_FAT_011) |
| 41 | `zeroBaselineIsHandled` | FAT_009, FAT_010 | 기준이 0이면: f미세 0, f큰은 지금 큰 수정이 있으면 1 [해석] |

#### ContextWeightTest (8개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 42 | `kStartsAtOneAndIsCappedAtOnePointThreeFive` | FAT_013 | 가중 없음 → 1.00, 연속 2 h+혼자 → 1.15, 5개 전부 → 1.35, 합이 1.35 초과 시 1.35 (REQ_FAT_013) |
| 43 | `longDriveCountsFromTwoHours` | FAT_014 | 연속 운전 119 min → +0, 120 min → +0.10 (REQ_FAT_014) |
| 44 | `vulnerableTimeIncludesStartAndExcludesEnd` | FAT_015 | 기본: 01:59 → +0, 02:00 → +0.10, 06:59 → +0.10, 07:00 → +0, 13:59 → +0, 14:00 → +0.10 (REQ_FAT_015) |
| 45 | `soloDriveNeedsKnownEmptySeats` | FAT_016 | 동승자 0명 → +0.05, 1명 이상 → +0, 착석 null → +0 (REQ_FAT_016) |
| 46 | `warmCabinCountsFromTwentyFiveDegrees` | FAT_017 | 24.9 ℃ → +0, 25.0 ℃ → +0.05, null → +0 (REQ_FAT_017) |
| 47 | `longDayDriveCountsFromFourHours` | FAT_018 | 최근 24시간 누적 239 min → +0, 240 min → +0.05 (REQ_FAT_018) |
| 48 | `factorsShowWhichWeightsAreOn` | FAT_013 | 연속 2시간 10분, 오후 3시 30분, 동승자 1명 → 연속 운전·취약 시간대만 켜짐 |
| 49 | `minuteOfDayMustBeWithinOneDay` | FAT_015 |  |

#### RiskScoreTest (5개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 50 | `behaviorAndRiskMatchSrsVectors` | FAT_012 | (1.0, 0.5, 0.5) → B=67.5, K=1.2 → R=81.0. B=80, K=1.35 → R=100(상한) (REQ_FAT_012) |
| 51 | `scenarioOneGivesSixtyOneBeforeSmoothing` | FAT_012 | 기획서 시나리오 1(연령대 미설정: f미세 0.8, f큰 1.0, f급 0, K=1.15) → B=53.0, R=61.0 ±0.5 (REQ_FAT_012) |
| 52 | `noBehaviorMeansNoRiskWhateverTheContext` | FAT_019 | B=0, K=1.35 → R=0 (REQ_FAT_019) |
| 53 | `behaviorScoreWithoutMicroRenormalizesTheOtherTwoWeights` | SIG_009 | 조향 원천 5 Hz 미만: B = 100 × (0.25·f큰 + 0.40·f급조향) ÷ 0.65 (REQ_SIG_009 [제안값]) |
| 54 | `behaviorScoreTopsOutAtHundred` | FAT_012 | f값 세 개가 모두 1이면 가중치 합(1.00) × 100 = 100 |

#### RiskSmootherTest (3개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 55 | `stepToHundredReachesSixtyThreeAfterThreeMinutes` | FAT_020 | R을 0→100으로 고정 입력 시 180 s 후 평활값 63.2 ±1 (시정수 180 s, 갱신 30 s, α=0.1535) (REQ_FAT_020) |
| 56 | `steadySixtyOneCrossesSixtyOnlyAfterTwelveAndAHalfMinutes` | FAT_020 | SRS REQ_FAT_020 비고: 순간 점수 61이 계속 나와도 평활값이 60을 넘기까지 약 12~13분 걸림 |
| 57 | `resetStartsFromZeroAgain` | FAT_020, RES_011 |  |

#### FatigueEngineTest (21개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 58 | `emitsEveryThirtySecondsTenSecondsBehindInput` | FAT_005 | 60초 창, 30초 갱신 (REQ_FAT_005, MSG-02 주기 30000 ms). |
| 59 | `awakeSteeringScoresZeroEvenInRiskyContext` | FAT_019 | 조향이 기준선(미세 20/분, 큰 4/분)보다 나쁘지 않으면 B=0 → K=1.35여도 R=0 (REQ_FAT_019) |
| 60 | `gateFailingInputGivesZeroRiskInRiskyContext` | FAT_019 | 게이트 불통과 입력(시속 50 km라 조향 지표 없음) + K=1.35 → R=0 (TC_FAT_019) |
| 61 | `drowsySteeringRaisesRiskThroughThreeMinuteAverage` | FAT_012, FAT_020 | 미세 수정 0(기준 20) → f미세 1, 큰 수정 9/분(기준 4) → f큰 1, 급조향 0 → B = 100 × (0.35 + 0.25) = 60. |
| 62 | `assistModeKeepsSmoothedScoreUnchanged` | AST_004 | 모드 ON 300 s 동안 FatigueState.valid=false, 위험 점수 갱신 0회 (REQ_AST_004) |
| 63 | `usualBaselineDecidesWhatCountsAsTooFewCorrections` | FAT_009 | 같은 운전(미세 수정 분당 약 59회)도 평소 분당 240회인 사람에게는 크게 줄어든 것 → f미세 1, B = 35 (REQ_FAT_009) |
| 64 | `todayBaselineIsConfirmedAtFifteenGatedMinutes` | BSL_004 | 유효 누적 14 min 59.9 s → 미확정(기본값 사용), 15 min 0 s → 확정 (REQ_BSL_004) |
| 65 | `drowsyFirstFifteenMinutesMeansDepartureFatigue` | BSL_005, BSL_006, DMO_006 | 처음 15분이 미세 수정 0(기본값 20의 −100%), 큰 수정 분당 약 10회(기본값 4의 2.5배) → 조건 2개 → 출발 피로 (REQ_BSL_005) |
| 66 | `sleepyConditionCountsFirstCheckFromDeparture` | BSL_007 | '졸려요' → 바로 민감 모드 (REQ_BSL_007). 첫 음성 확인은 출발(D단)부터 15분 뒤 [해석] |
| 67 | `restResetsOnlyTheSmoothedScore` | RES_011 | 휴식 인증 뒤: 위험 점수 평활값은 0부터 다시, 오늘의 나는 유지 (REQ_RES_011) |
| 68 | `drowsySteeringBecomesTrendAfterSixAbnormalMinutes` | PAT_001, PAT_002 | 미세 수정 0(기준 20의 70% 이하) + 큰 수정 분당 9회(기준 4의 1.5배 이상) → 매분 이상 분 (REQ_PAT_001) |
| 69 | `oneDistractionBecomesSpikeOnceSteeringRecovers` | PAT_001, PAT_003 | 평소 미세 60/분인 운전자가 35초 동안 운전대를 그대로 두다가 급조향 1회 → 그 1분은 이상 분 (REQ_PAT_001) |
| 70 | `threeSuddenSteersWithinFiveMinutesTriggerSafetyBypass` | PAT_005 | 급조향 105.4 s·205.4 s·305.4 s → 3회째에서 안전 우회 신호 1건 (REQ_PAT_005). |
| 71 | `safetyBypassStillWorksDuringAssistMode` | AST_004, PAT_005 | 보조 주행 중에도 5분 안 급조향 3회면 안전 우회 (기획서 5-4 "안전 우회는 그대로 씁니다", REQ_PAT_005) |
| 72 | `suddenSteerWithinThirtySecondsAfterCruiseOffIsUnsafeHandover` | AST_007, INT_002 | 크루즈를 200 s에 끄고 29 s 뒤 급조향 → 인계 품질 이상 + 급조향 1회 (REQ_AST_007, REQ_INT_002) |
| 73 | `slowSteeringSourceTurnsMicroOffAndRenormalizesB` | SIG_009 | 원천 2 Hz(5 Hz 미만) → 미세 수정 지표 비활성, B는 큰 수정·급조향 가중치만 재정규화 (TC_SIG_009) |
| 74 | `microComesBackOnceSlowFramesLeaveTheWindow` | SIG_009 | 0~99.9 s만 원천이 느림 → 그 프레임이 60초 창 안에 있는 동안만 미세 수정을 끈다 [해석] |
| 75 | `rateMeterWarmUpDoesNotSpoilDepartureFatigue` | SIG_009, DMO_006 | 1번 RateMeter는 시작 뒤 10초를 '측정 중'(→ 느림으로 넣음)으로 준다. 데모 '출발 피로'(REQ_DMO_006)가 |
| 76 | `slowFramesThatDoNotPassTheGateDoNotTurnMicroOff` | SIG_009 | 주차 중(시속 0)에 조향 원천이 느렸던 것은 지표와 상관없음 → 출발 뒤 창에서는 미세 수정을 씀 [해석] |
| 77 | `slowSteeringSourceDoesNotMakeDrowsyStartTheNormal` | SIG_009, BSL_005 | 처음 15분이 원천 2 Hz: 미세 수정 조건을 빼고 보면 큰 수정만 늘었으므로 출발 피로는 아님 [해석]. |
| 78 | `resetStartsANewTrip` | TRP_008 | 새 운행: 첫 갱신은 다시 60초 뒤, 평활값 0부터, 출발 전 컨디션도 지움 (REQ_TRP_008) |

#### BaselineStoreTest (10개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 79 | `usesDefaultUntilThreeAwakeTripsThenUsual` | BSL_003 | 운행 0·1·2회 → 기본값, 3회 → 평소의 나 (REQ_BSL_003) |
| 80 | `usualIsMedianOfLastTenAwakeTrips` | BSL_001 | 운행 12회 입력 시 최근 10회만 사용, 중앙값 오차 0, 깨어 있던 구간이 없는 운행은 제외 (REQ_BSL_001) |
| 81 | `todayIsConfirmedAtFifteenGatedMinutes` | BSL_004 | 유효 누적 14 min 59 s → 미확정(기본값·평소의 나 사용), 15 min 0 s → 확정 (REQ_BSL_004) |
| 82 | `departureFatigueNeedsTwoOfThreeCriteria` | BSL_005 | 미세 −30.0%·큰 1.50배 → 출발 피로(2개), 미세 −29.9%·큰 1.50배 → 아님(1개), 3개 충족 → 출발 피로 (REQ_BSL_005) |
| 83 | `slowSteeringSourceLeavesMicroOutOfDepartureFatigue` | SIG_009, BSL_005 | 처음 15분 동안 조향 원천이 5 Hz 미만이면 미세 수정 조건은 세지 않음 → 남은 두 조건을 모두 만족해야 출발 피로 [해석] |
| 84 | `departureFatigueKeepsUsualBaselineAndSchedulesFirstCheck` | BSL_006 | 민감 모드 진입 후 기준선 소스 = 평소의 나(없으면 기본값), 첫 음성 확인 15 min (REQ_BSL_006) |
| 85 | `sleepyConditionTurnsOnSensitiveModeAndKeepsBaselineFixed` | BSL_007 | '졸려요' → 민감 모드 ON, '개운해요'·'보통' → OFF (REQ_BSL_007) |
| 86 | `newTripStartsTodayOverButKeepsHistory` | TRP_008 | 꺼져 있던 시간 2시간 초과 → 새 운행, 오늘의 나 초기화 (REQ_TRP_008). 평소의 나는 유지 |
| 87 | `usualHandoverCountIsMedianOfAwakeRecords` | AST_007 | 평소 해제 직후 30초 큰 수정 횟수 (REQ_AST_007 '평소 해제 때'). 값이 있는 기록이 없으면 null → '3회 이상' 기준 |
| 88 | `stateObjectStaysTheSameWhenNothingChanges` | BSL_006 | 값이 그대로면 같은 객체를 유지 → StateFlow에 넣어도 바뀔 때만 알림이 감 |

#### PatternClassifierTest (8개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 89 | `abnormalMinuteNeedsFewMicroAndMoreLargeOrSudden` | PAT_001 | 미세 0.70·큰 1.50 → 이상, 미세 0.71 → 아님, 미세 0.50·큰 1.49·급조향 0 → 아님, 미세 0.50·급조향 1 → 이상 (REQ_PAT_001) |
| 90 | `withoutMicroAbnormalMinuteIsLargeOrSuddenOnly` | SIG_009 | 조향 원천 5 Hz 미만이면 미세 수정 조건을 빼고 '큰 수정 1.5배 이상 또는 급조향'만 본다 (REQ_SIG_009) [해석] |
| 91 | `trendNeedsSixAbnormalMinutesOutOfTen` | PAT_002 | 이상 분 5 → 추세형 아님, 6 → 추세형, 10 → 추세형 (REQ_PAT_002) |
| 92 | `spikeNeedsRecoveryWithinOneMinuteEveryTime` | PAT_003 | 이상 분 3 + 모두 1분 안 복귀 → 튐형, 이상 분 4·5 → 애매, 이상 분 3 + 복귀 실패 1회 → 애매 (REQ_PAT_003) |
| 93 | `minutesUseOneWindowPerMinuteAndLookBackTen` | PAT_001, PAT_002 | 30초마다 60초 창이 오지만 앞 창과 절반이 겹치는 창은 건너뛰고, 1분 칸마다 처음 온 창만 쓴다 |
| 94 | `unmeasuredMinutesAreNeitherAbnormalNorRecovery` | PAT_003 | 게이트 대기·보조 주행 칸은 이상 분으로 세지 않음. 창이 아예 오지 않은 칸도 측정 못 함으로 채움 [해석] |
| 95 | `safetyBypassNeedsThreeSuddenSteersWithinFiveMinutes` | PAT_005 | 급조향 시각 (0, 100, 299.9) → 신호 1건, (0, 100) → 0건, (0, 100, 300.1) → 0건 (REQ_PAT_005) |
| 96 | `safetyBypassStaysOnForFiveMinutes` | PAT_005 | 상태를 30초마다 내보내므로 3회째 급조향부터 5분 동안 켜 둔다 [제안값] |

#### AssistModeDetectorTest (8개)

| # | 시험 | REQ | 확인하는 것 |
|---|---|---|---|
| 97 | `cruiseSwitchesAssistModeOnTheSameFrame` | AST_001 | 크루즈 ON → 다음 프레임(≤ 110 ms)부터 모드 ON, OFF → 모드 OFF + 30 s 인계 감시 시작 (REQ_AST_001) |
| 98 | `suddenSteerWithinThirtySecondsAfterReleaseIsUnsafe` | AST_007 | 해제 후 29 s 급조향 1회 → 2단계(인계 품질 이상), 31 s → 아님 (REQ_AST_007) |
| 99 | `largeCorrectionsAreComparedWithUsualHandover` | AST_007 | 큰 수정 기록 2회·현재 3회(1.5배) → 2단계, 기록 없음·3회 → 2단계, 2회 → 아님 (REQ_AST_007) |
| 100 | `handoverIsFlaggedOncePerReleaseAndShownForThirtySeconds` | AST_007 | 한 번 해제에 사건은 한 번. 받는 쪽이 놓치지 않게 이상 표시는 30초 유지 [해석] |
| 101 | `reEngagingCruiseEndsTheWatch` | AST_007 | 해제 5초 뒤 크루즈를 다시 켜면 인계 감시는 거기서 끝남. 판정이 늦게 오는 그 전 사건은 그대로 봄 |
| 102 | `missingSteeringMeansAssistUntilItComesBack` | AST_003 | 조향 null → 모드 ON, 조향 값 수신 재개 → 다음 프레임(≤ 110 ms)에 모드 OFF(크루즈 꺼짐일 때) (REQ_AST_003) |
| 103 | `unknownCruiseMeansNormalMode` | AST_008 | 크루즈 null 입력 → 보조 주행 모드 OFF(일반 모드), 조향 계산 유지 (REQ_AST_008 [제안]) |
| 104 | `operationGapCountsSecondsSinceLastOperation` | AST_006 | 조작 공백 900 s → 900, 901 s → 901(15분 넘음은 1번이 판단), 조작 1회 발생 시 공백 리셋 (REQ_AST_006) |


## 5. 요구사항별로 보면

| REQ | 시험 수 | 비고 |
| --- | ---: | --- |
| FAT_001~020 | 각 1~7개 | 20건 모두 단위 시험 있음 |
| BSL_001·003·004·005·006·007 | 1~4개 | BSL_007의 '버튼·말 입력'은 화면·음성 연결 뒤 에뮬레이터 시험(ET) |
| BSL_002 | 0 | 2차 (운행 기록 저장) |
| PAT_001·002·003·005 | 3~5개 | |
| PAT_004·006·007 | 0 | 1번 InterventionPolicy가 판단 → 1번 시험 |
| AST_001·003·004·006·007·008 | 1~6개 | AST_006·008은 2번 몫(공백 초·일반 모드)만 |
| AST_002 | 0 | 2차 |
| AST_005 | 0 | 1번 InterventionPolicy → 1번 시험 |
| SIG_009 (엔진 쪽) | 8개 | 측정 쪽은 1번 `RateMeterTest` |
| RES_011, TRP_008, DMO_006, INT_002, UI_003 | 1~2개 | 2번이 맡은 부분만 |

## 6. 아직 안 한 시험

| 시험 | 언제 | 무엇이 필요 |
| --- | --- | --- |
| 신호 변환 함수 6개 | main 합친 직후 | 준비 끝 (`SignalAdaptersTest`). 11/6 합친 트리에서 통과 확인 |
| 엔진 출력 연결기 (DrivingAssessment·SafetyEvent 대응) | 11/8 | 연결기 코드, 1번과 대응표 합의 ([08 4-1](08-team-handoff.md#4-1-1번-차량-신호단계-전환)) |
| TC_BSL_007 (ET): '졸려요' 버튼·말 → 민감 모드 + 권유 발화 | 11/9 통합 | 5번 버튼, 3번 의도(COND_SLEEPY) |
| TC_AST (ET): Virtual Console 크루즈 → 보조 주행 회색 | 11/9 통합 | 1번 SDK 연결(DrivingMode) |
| E2E TC_PRD_002·003, DMO_006 | 11/9 리허설 | 5번 시나리오 JSON, 1번 DemoPlayer |
| 실제 SDK 조향 | 미정 | SDK 조향 값의 뜻(전륜각) 정리 뒤 ([09](09-schedule-todo-limits.md#4-위험과-대응)) |

## 7. TC 시트 기록

SRS·TC 엑셀 `Test Case` 시트 H~O열을 2번 TC 42행(FAT 20, BSL 7, PAT 7, AST 8)에 채운다.

| 열 | 넣을 값 |
| --- | --- |
| H 측정값 | 위 시험 목록의 대응 시험 이름 + 결과 (예: `speedBoundaryAt60Kph` 59.9 제외·60.0 통과) |
| I 판정 | Pass / Fail. 1번으로 넘긴 PAT_004·006·007, AST_005는 '1번 시험으로 확인' 메모, 2차 항목은 Not Run |
| J 시험일 | 시험 돌린 날 |
| K 시험자 | 2번 (실명 대신 역할. 접수양식 블라인드 안내) |
| L SRS 버전 | v0.2 |
| M SW 버전 | 커밋 해시 (예: `c060214`, 병합 뒤엔 main 해시) |
| N 실행 환경 | JVM 단위 시험, Gradle 9.6.0 |
| O 증거 파일 | `engine/build/test-results/test/TEST-*.xml` 파일명 |

새 시험을 SRS 시트에 추가할 때는 표 **맨 아래**에 붙인다. 중간에 끼우면 RTM 연결이 깨진다.

## 8. 시험을 고치거나 더할 때

- 판단 규칙을 바꾸면 그 규칙의 시험도 같이 바꾼다 (CLAUDE.md 12장). 숫자만 바꾸려면 `config/` 기본값과 그 값을 확인하는 시험 기대값을 함께 바꾼다.
- 엔진 전체 흐름은 `FatigueEngineTest`의 `drive(시작, 끝) { t -> 조향각 }` 도우미로 시나리오처럼 쓴다. 조향 모양은 `SteeringTestPaths.kt`의 `triangleDeg`(삼각파)·`steeringPath`(꼭짓점 잇기)를 쓴다.
- 결과 시각이 10초 늦다는 점을 기대값에 넣는다 (60초 값은 70초 프레임에서 나옴).
