# 2번 TC 결과 (2026-11-11)

- 시험자: 2번 / SRS v0.2 / SW `27ed683` / JVM 단위 시험 · Gradle 9.6.0 · JDK 21.0.13.0.3
- 판정: Pass 36, Fail 0, Blocked 2, 비움(2차) 4
- 만든 도구: `tc_fill_2.py` (Gradle JUnit XML에서 대응 시험의 통과 여부를 읽어 Test Case 시트 H~O열에 적음)

| TC | 행 | 판정 | 측정값 | 대응 시험(결과) |
| --- | ---: | --- | --- | --- |
| TC_FAT_001 | 46 | Pass | 59.9 km/h 제외·60.0 km/h 포함, P·R·N 제외·D 포함 | SteeringGateTest.speedBoundaryAt60Kph (pass)<br>SteeringGateTest.onlyDriveGearPasses (pass) |
| TC_FAT_002 | 47 | Pass | 작동 100.0~103.0 s → 90.0~113.0 s 제외, 89.9·113.1 s 포함 | SteeringGateTest.turnSignalExcludesTenSecondsBeforeAndAfter (pass) |
| TC_FAT_003 | 48 | Pass | 작동 200.0~202.0 s → 195.0~207.0 s 제외, 194.9·207.1 s 포함 | SteeringGateTest.brakeExcludesFiveSecondsBeforeAndAfter (pass) |
| TC_FAT_004 | 49 | Pass | 299.9 m 제외·300.0 m 포함, 거리 null 4:59 제외·5:00 포함, 60 km/h 미만 29 s 유지·31 s 다시 셈 | SteeringGateTest.knownTurnDistanceBoundaryAt300m (pass)<br>SteeringGateTest.unknownTurnDistanceNeedsFiveMinutesAt60Kph (pass)<br>SteeringGateTest.shortSlowdownKeepsFiveMinuteCount (pass)<br>SteeringGateTest.longSlowdownRestartsFiveMinuteCount (pass) |
| TC_FAT_005 | 50 | Pass | 300 s 통과 → 9회(60·90…300 s), 30 s 간격, 60 s 창, 통과 샘플만 분당 환산 | SteeringMetricsTest.updatesEveryThirtySecondsAfterFirstMinute (pass)<br>SteeringMetricsTest.scalesToPerMinuteWhenOnlyPartOfWindowPassed (pass)<br>SteeringMetricsTest.tooLittleGatedTimeIsNotValid (pass)<br>FatigueEngineTest.emitsEveryThirtySecondsTenSecondsBehindInput (pass) |
| TC_FAT_006 | 51 | Pass | 0.4·0.5·2.0·3.0·3.1° → 미세 3건(0.5·2.0·3.0) | SteeringReversalDetectorTest.microCountsSwingsFromHalfToThreeDegrees (pass)<br>SteeringMetricsTest.countsMicroCorrectionsPerMinute (pass) |
| TC_FAT_007 | 52 | Pass | 4.9·5.0·8.0° → 큰 2건(5.0·8.0), 3.1~4.9°는 어느 쪽도 아님 | SteeringReversalDetectorTest.largeCountsSwingsFromFiveDegrees (pass)<br>SteeringReversalDetectorTest.betweenThreeAndFiveDegreesIsNeither (pass) |
| TC_FAT_008 | 53 | Pass | 정지 4.0 s → 0건, 4.1 s + 0.9 s 안 6° → 1건, 5.9° → 0건, 1.1 s → 0건, 꺾임 끝 샘플 뒤 4.4 s 멈춤 → 1건(11/10 고침) | SuddenSteerDetectorTest.stillOnlyFourSecondsIsNotCounted (pass)<br>SuddenSteerDetectorTest.stillOverFourSecondsThenSixDegreesWithinASecondIsCounted (pass)<br>SuddenSteerDetectorTest.turnBelowSixDegreesIsNotCounted (pass)<br>SuddenSteerDetectorTest.turnSlowerThanOneSecondIsNotCounted (pass)<br>SuddenSteerDetectorTest.rampTailDoesNotCutTheStillApart (pass)<br>SuddenSteerDetectorTest.rampTailIsNotCountedAsStill (pass) |
| TC_FAT_009 | 54 | Pass | 기준 20: 현재 20→0, 15→0.5, 10→1.0, 5→1.0, 25→0 | FatigueFactorsTest.fMicroMatchesSrs (pass) |
| TC_FAT_010 | 55 | Pass | 기준 4: 현재 4→0, 6→0.5, 8→1.0, 12→1.0, 2→0 | FatigueFactorsTest.fLargeMatchesSrs (pass) |
| TC_FAT_011 | 56 | Pass | 0회→0, 1회→0.5, 2회→1.0, 3회→1.0, 5분 1초 전 급조향 제외 | FatigueFactorsTest.fSuddenMatchesSrs (pass)<br>SteeringMetricsTest.suddenSteerCountKeepsOnlyLastFiveMinutes (pass) |
| TC_FAT_012 | 57 | Pass | (1.0, 0.5, 0.5) → B 67.5, K 1.2 → R 81.0, B 80·K 1.35 → R 100, 시나리오 1 → B 53.0·R 61.0 | RiskScoreTest.behaviorAndRiskMatchSrsVectors (pass)<br>RiskScoreTest.scenarioOneGivesSixtyOneBeforeSmoothing (pass)<br>RiskScoreTest.behaviorScoreTopsOutAtHundred (pass) |
| TC_FAT_013 | 58 | Pass | 가중 없음 1.00, 연속 2 h + 혼자 1.15, 5개 전부 1.35, 넘으면 1.35 | ContextWeightTest.kStartsAtOneAndIsCappedAtOnePointThreeFive (pass)<br>FatigueConfigTest.allContextWeightsAddUpToKMax (pass) |
| TC_FAT_014 | 59 | Pass | 연속 119 min → +0, 120 min → +0.10 | ContextWeightTest.longDriveCountsFromTwoHours (pass) |
| TC_FAT_015 | 60 | Pass | 01:59 +0, 02:00·06:59 +0.10, 07:00·13:59 +0, 14:00 +0.10, 30·40대 22:00 +0.10 | ContextWeightTest.vulnerableTimeIncludesStartAndExcludesEnd (pass)<br>FatigueConfigTest.defaultVulnerableWindowsMatchSrsBoundaries (pass)<br>ContextWeightTest.ageBandRangesAddToBaseRanges (pass) |
| TC_FAT_016 | 61 | Pass | 동승 0명 +0.05, 1명 이상 +0, 착석 null +0 | ContextWeightTest.soloDriveNeedsKnownEmptySeats (pass) |
| TC_FAT_017 | 62 | Pass | 24.9 ℃ +0, 25.0 ℃ +0.05, null +0 | ContextWeightTest.warmCabinCountsFromTwentyFiveDegrees (pass) |
| TC_FAT_018 | 63 | Pass | 24시간 누적 239 min +0, 240 min +0.05 | ContextWeightTest.longDayDriveCountsFromFourHours (pass) |
| TC_FAT_019 | 64 | Pass | B 0·K 1.35 → R 0, 맥락은 1단계 조건으로만 | RiskScoreTest.noBehaviorMeansNoRiskWhateverTheContext (pass)<br>FatigueEngineTest.awakeSteeringScoresZeroEvenInRiskyContext (pass) |
| TC_FAT_020 | 65 | Pass | 0→100 고정 입력 180 s 뒤 평활 63.2 ±1, α 0.1535 | RiskSmootherTest.stepToHundredReachesSixtyThreeAfterThreeMinutes (pass)<br>FatigueConfigTest.smoothingAlphaMatchesSrs (pass) |
| TC_BSL_001 | 66 | Pass | 12회 중 최근 10회만, 중앙값 오차 0, 깨어 있던 구간 없는 운행 제외 | BaselineStoreTest.usualIsMedianOfLastTenAwakeTrips (pass) |
| TC_BSL_002 | 67 | — | 2차(Should) — 깨어 있던 구간 4조건 저장은 아직 구현 안 함 | — |
| TC_BSL_003 | 68 | Pass | 운행 0·1·2회 → 기본값, 3회 → 평소의 나 | BaselineStoreTest.usesDefaultUntilThreeAwakeTripsThenUsual (pass) |
| TC_BSL_004 | 69 | Pass | 유효 누적 14 min 59 s 미확정, 15 min 0 s 확정 | BaselineStoreTest.todayIsConfirmedAtFifteenGatedMinutes (pass)<br>FatigueEngineTest.todayBaselineIsConfirmedAtFifteenGatedMinutes (pass) |
| TC_BSL_005 | 70 | Pass | 미세 −30.0%·큰 1.50배 → 출발 피로, −29.9%·1.50배 → 아님, 3개 → 출발 피로 | BaselineStoreTest.departureFatigueNeedsTwoOfThreeCriteria (pass)<br>FatigueEngineTest.drowsyFirstFifteenMinutesMeansDepartureFatigue (pass) |
| TC_BSL_006 | 71 | Pass | 기준선 = 평소의 나(없으면 기본값) 고정, 첫 음성 확인 15:00(+0~0.1 s, CheckDue 1회), 예보 재계산 1회 | BaselineStoreTest.departureFatigueKeepsUsualBaselineAndSchedulesFirstCheck (pass)<br>FatigueBridgeTest.sleepyConditionAsksFirstCheckFifteenMinutesAfterDeparture (pass)<br>CheckDueInputTest.firstCheckDueGoesToStageTwoAndAsksTheDriverOnce (pass)<br>ForecastCoordinatorTest.출발 피로가 false에서 true로 바뀔 때 한 번만 재계산, 120분 → 60분 (FCS_004) (pass) |
| TC_BSL_007 | 72 | Blocked | 엔진·연결기: '졸려요' → 민감 모드 ON 1회, '개운해요'·'보통' → OFF. 화면 컨디션 버튼·음성 '졸려요'가 앱에 아직 안 붙어 에뮬레이터 시험 못 함(조립 대기) | BaselineStoreTest.sleepyConditionTurnsOnSensitiveModeAndKeepsBaselineFixed (pass)<br>FatigueBridgeTest.sleepyConditionTurnsOnSensitiveModeOnce (pass) |
| TC_PAT_001 | 73 | Pass | 미세 0.70·큰 1.50 이상, 0.71 아님, 0.50·1.49·급조향 0 아님, 0.50·급조향 1 이상 | PatternClassifierTest.abnormalMinuteNeedsFewMicroAndMoreLargeOrSudden (pass) |
| TC_PAT_002 | 74 | Pass | 이상 분 5 → 아님, 6·10 → 추세형 | PatternClassifierTest.trendNeedsSixAbnormalMinutesOutOfTen (pass) |
| TC_PAT_003 | 75 | Pass | 이상 분 3 + 모두 복귀 → 튐형, 4·5 → 애매, 3 + 복귀 실패 1회 → 애매 | PatternClassifierTest.spikeNeedsRecoveryWithinOneMinuteEveryTime (pass) |
| TC_PAT_004 | 76 | Pass | (정상, 튐형) → 딴짓 기록 1건, 단계 하강 = 점수 60 미만 10분 뒤 1단계(INT_007) | PatternClassifierTest.normalCheckOnSpikeIsOneDistraction (pass)<br>PatternClassifierTest.normalCheckRightAfterSuddenWaitsUntilSpikeIsConfirmed (pass)<br>FatigueBridgeTest.normalAnswerAfterSuddenSteerIsRecordedAsDistractionOnceSpikeIsConfirmed (pass)<br>PatternCheckResultFlowTest.normalAnswerWithSpikeComesDownAfterTenLowMinutes (pass)<br>InterventionPolicyTest.lowScoreRequiresTenContinuousValidMinutesAndNormalConfirmation (pass) |
| TC_PAT_005 | 77 | Pass | 300 s 안 3회 → 신호 1건, 2회 → 0건, 3회째가 300.1 s 뒤 → 0건 | PatternClassifierTest.safetyBypassNeedsThreeSuddenSteersWithinFiveMinutes (pass)<br>FatigueEngineTest.threeSuddenSteersWithinFiveMinutesTriggerSafetyBypass (pass) |
| TC_AST_001 | 78 | Pass | 크루즈 ON → 다음 프레임(≤ 110 ms) 모드 ON, OFF → 모드 OFF + 30 s 인계 감시 | AssistModeDetectorTest.cruiseSwitchesAssistModeOnTheSameFrame (pass) |
| TC_AST_002 | 79 | — | 2차(Should) — 조향 모양으로 보조 주행 추정은 아직 구현 안 함 | — |
| TC_AST_003 | 80 | Pass | 조향 null → 모드 ON, 값 재개 → 다음 프레임 OFF | AssistModeDetectorTest.missingSteeringMeansAssistUntilItComesBack (pass) |
| TC_AST_004 | 81 | Blocked | 엔진·연결기: 보조 주행 중 valid=false, 위험 점수 갱신 0회, UNAVAILABLE. UI 게이지 회색은 화면 연결 전이라 에뮬레이터 시험 못 함(조립 대기) | FatigueEngineTest.assistModeKeepsSmoothedScoreUnchanged (pass)<br>FatigueBridgeTest.cruiseGivesUnavailableAssessment (pass) |
| TC_AST_005 | 82 | Pass | 보조 주행 정기 확인 1200 s, 취약 시간대 600 s (1번 시험) | PolicyInterventionManagerTest.regularAssistedCheckOccursAgainWhileRemainingAtStageTwo (pass)<br>PolicyInterventionManagerTest.vulnerableAssistedCheckUsesTenMinutesInsteadOfTwenty (pass) |
| TC_AST_006 | 83 | — | 엔진: 조작 공백 900 s → 900, 901 s → 901, 조작 1회 → 0. 901 s 확인 당김은 1번 매니저 몫이고 시험 없음(2차 Should) | AssistModeDetectorTest.operationGapCountsSecondsSinceLastOperation (pass) |
| TC_AST_007 | 84 | Pass | 해제 후 29 s 급조향 → 2단계, 31 s → 아님, 기록 2회·현재 3회 → 2단계, 기록 없음·3회 → 2단계, 2회 → 아님 | AssistModeDetectorTest.suddenSteerWithinThirtySecondsAfterReleaseIsUnsafe (pass)<br>AssistModeDetectorTest.largeCorrectionsAreComparedWithUsualHandover (pass) |
| TC_AST_008 | 189 | — | 엔진: 크루즈 null → 일반 모드. 20분 정기 확인은 아직 없음(2차 Should) | AssistModeDetectorTest.unknownCruiseMeansNormalMode (pass) |
| TC_PAT_006 | 194 | Pass | (정상, 추세형) → 2단계 유지, 600 s 뒤 재확인(599.999 s엔 아님) | PatternCheckResultFlowTest.normalAnswerWithTrendKeepsStageTwoAndAsksAgainTenMinutesLater (pass)<br>PatternCheckResultFlowTest.normalAnswerWithTrendDoesNotComeDownEvenWhenScoreDrops (pass) |
| TC_PAT_007 | 195 | Pass | 느림 2회 연속 → 3단계, 무응답 1회 → 3단계, 느림 1회 → 30 s 뒤 재확인 (1번 시험) | InterventionPolicyTest.twoSlowResponsesAndNoResponseBypassGeneralStepDelay (pass)<br>PolicyInterventionManagerTest.duplicatedSlowResultIsIgnoredAndRealSecondSlowEscalatesImmediately (pass) |

## 시험 묶음 결과

| 파일 | 묶음 | 시험 | 실패 | 오류 | 건너뜀 |
| --- | --- | ---: | ---: | ---: | ---: |
| TEST-com.sleepissue.awakedrive.engine.assist.AssistModeDetectorTest.xml | AssistModeDetectorTest | 8 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.baseline.BaselineStoreTest.xml | BaselineStoreTest | 13 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.check.CheckPolicyTest.xml | CheckPolicyTest | 8 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.check.PassengerCheckPolicyTest.xml | PassengerCheckPolicyTest | 3 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.check.VoiceBaselineSamplesTest.xml | VoiceBaselineSamplesTest | 2 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.check.VoiceBaselineTest.xml | VoiceBaselineTest | 3 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.climate.VentilationRulesTest.xml | VentilationRulesTest | 8 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.config.EngineConfigTest.xml | EngineConfigTest | 4 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.config.FatigueConfigTest.xml | FatigueConfigTest | 11 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.fatigue.ContextWeightTest.xml | ContextWeightTest | 9 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.fatigue.FatigueEngineTest.xml | FatigueEngineTest | 24 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.fatigue.FatigueFactorsTest.xml | FatigueFactorsTest | 4 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.fatigue.RiskScoreTest.xml | RiskScoreTest | 5 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.fatigue.RiskSmootherTest.xml | RiskSmootherTest | 3 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.fatigue.SignalAdaptersTest.xml | SignalAdaptersTest | 6 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.fatigue.SteeringGateTest.xml | SteeringGateTest | 13 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.fatigue.SteeringMetricsTest.xml | SteeringMetricsTest | 8 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.fatigue.SteeringReversalDetectorTest.xml | SteeringReversalDetectorTest | 4 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.fatigue.SuddenSteerDetectorTest.xml | SuddenSteerDetectorTest | 7 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.forecast.ForecastEngineTest.xml | ForecastEngineTest | 24 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.intervention.InterventionPolicyTest.xml | InterventionPolicyTest | 23 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.model.ModelContractTest.xml | ModelContractTest | 6 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.model.TripModelsTest.xml | TripModelsTest | 2 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.pattern.PatternClassifierTest.xml | PatternClassifierTest | 18 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.rest.HeadingEstimatorTest.xml | HeadingEstimatorTest | 8 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.rest.NapTimerTest.xml | NapTimerTest | 6 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.rest.RestAreaCsvTest.xml | RestAreaCsvTest | 5 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.rest.RestAreaSelectorTest.xml | RestAreaSelectorTest | 13 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.rest.RestCertifierSnapshotTest.xml | RestCertifierSnapshotTest | 5 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.rest.RestCertifierTest.xml | RestCertifierTest | 9 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.rest.RewardRulesTest.xml | RewardRulesTest | 7 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.rest.WaypointMatcherTest.xml | WaypointMatcherTest | 3 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.session.RollingDriveWindowTest.xml | RollingDriveWindowTest | 12 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.session.TripRulesTest.xml | TripRulesTest | 18 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.session.TripSnapshotValidatorTest.xml | TripSnapshotValidatorTest | 4 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.session.TripUnavailableResumeTest.xml | TripUnavailableResumeTest | 4 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.signal.RateMeterTest.xml | RateMeterTest | 11 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.signal.SignalSelectionTest.xml | SignalSelectionTest | 6 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.time.DemoClockTest.xml | DemoClockTest | 14 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.voice.DistancePhraseResolverTest.xml | DistancePhraseResolverTest | 2 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.voice.SpeechIntentParserTest.xml | SpeechIntentParserTest | 7 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.engine.voice.SpeechOnsetDetectorTest.xml | SpeechOnsetDetectorTest | 4 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.debug.DebugHarnessControllerTest.xml | DebugHarnessControllerTest | 13 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.fatigue.FatigueBridgeTest.xml | FatigueBridgeTest | 13 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.session.ForecastCoordinatorTest.xml | ForecastCoordinatorTest | 5 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.signal.CheckDueInputTest.xml | CheckDueInputTest | 3 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.signal.InterventionContractTest.xml | InterventionContractTest | 1 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.signal.InterventionPersistenceTest.xml | InterventionPersistenceTest | 2 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.signal.PatternCheckResultFlowTest.xml | PatternCheckResultFlowTest | 3 | 0 | 0 | 0 |
| TEST-com.sleepissue.awakedrive.signal.PolicyInterventionManagerTest.xml | PolicyInterventionManagerTest | 32 | 0 | 0 | 0 |
| 합계 | 50개 묶음 | 426 | 0 | 0 | 0 |
