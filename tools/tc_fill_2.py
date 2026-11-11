#!/usr/bin/env python3
"""2번(졸음 엔진) TC 42건을 SRS·TC 엑셀 Test Case 시트 H~O열에 기록한다.

- 표준 라이브러리만 쓴다(openpyxl 필요 없음).
- 엑셀의 다른 부분(수식·드롭다운·조건부 서식·RTM·다른 사람 행)은 건드리지 않고, 2번 TC 행의 H~O 칸만 바꾼다.
- 판정은 JUnit XML(Gradle 시험 결과)에서 대응 시험이 모두 통과했는지로 정한다. 숫자를 손으로 적지 않는다.
- 같은 날 다시 돌리면 같은 칸을 덮어쓴다(재시험 새 회차는 SRS 가이드대로 맨 아래 새 행에 사람이 추가).

사용:
  python3 tc_fill_2.py --xlsx docs/design/AwakeDrive_SRS_TC_v0.2.xlsx \
      --results src/android/engine/build/test-results/test \
      --results src/android/app/build/test-results/testDebugUnitTest \
      --commit abc1234 --env "JVM 단위 시험 · Gradle 9.6.0 · JDK 21" \
      --evidence docs/devlog/evidence/2026-11-11-tc-2번/TC_결과.md
"""
import argparse
import datetime
import glob
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}

# TC → (대응 시험 [클래스.메서드], 측정값 요약). 요약은 시험이 단언하는 값 그대로다.
MAP = {
    "TC_FAT_001": (["SteeringGateTest.speedBoundaryAt60Kph", "SteeringGateTest.onlyDriveGearPasses"],
                   "59.9 km/h 제외·60.0 km/h 포함, P·R·N 제외·D 포함"),
    "TC_FAT_002": (["SteeringGateTest.turnSignalExcludesTenSecondsBeforeAndAfter"],
                   "작동 100.0~103.0 s → 90.0~113.0 s 제외, 89.9·113.1 s 포함"),
    "TC_FAT_003": (["SteeringGateTest.brakeExcludesFiveSecondsBeforeAndAfter"],
                   "작동 200.0~202.0 s → 195.0~207.0 s 제외, 194.9·207.1 s 포함"),
    "TC_FAT_004": (["SteeringGateTest.knownTurnDistanceBoundaryAt300m", "SteeringGateTest.unknownTurnDistanceNeedsFiveMinutesAt60Kph",
                    "SteeringGateTest.shortSlowdownKeepsFiveMinuteCount", "SteeringGateTest.longSlowdownRestartsFiveMinuteCount"],
                   "299.9 m 제외·300.0 m 포함, 거리 null 4:59 제외·5:00 포함, 60 km/h 미만 29 s 유지·31 s 다시 셈"),
    "TC_FAT_005": (["SteeringMetricsTest.updatesEveryThirtySecondsAfterFirstMinute", "SteeringMetricsTest.scalesToPerMinuteWhenOnlyPartOfWindowPassed",
                    "SteeringMetricsTest.tooLittleGatedTimeIsNotValid", "FatigueEngineTest.emitsEveryThirtySecondsTenSecondsBehindInput"],
                   "300 s 통과 → 9회(60·90…300 s), 30 s 간격, 60 s 창, 통과 샘플만 분당 환산"),
    "TC_FAT_006": (["SteeringReversalDetectorTest.microCountsSwingsFromHalfToThreeDegrees", "SteeringMetricsTest.countsMicroCorrectionsPerMinute"],
                   "0.4·0.5·2.0·3.0·3.1° → 미세 3건(0.5·2.0·3.0)"),
    "TC_FAT_007": (["SteeringReversalDetectorTest.largeCountsSwingsFromFiveDegrees", "SteeringReversalDetectorTest.betweenThreeAndFiveDegreesIsNeither"],
                   "4.9·5.0·8.0° → 큰 2건(5.0·8.0), 3.1~4.9°는 어느 쪽도 아님"),
    "TC_FAT_008": (["SuddenSteerDetectorTest.stillOnlyFourSecondsIsNotCounted", "SuddenSteerDetectorTest.stillOverFourSecondsThenSixDegreesWithinASecondIsCounted",
                    "SuddenSteerDetectorTest.turnBelowSixDegreesIsNotCounted", "SuddenSteerDetectorTest.turnSlowerThanOneSecondIsNotCounted",
                    "SuddenSteerDetectorTest.rampTailDoesNotCutTheStillApart", "SuddenSteerDetectorTest.rampTailIsNotCountedAsStill"],
                   "정지 4.0 s → 0건, 4.1 s + 0.9 s 안 6° → 1건, 5.9° → 0건, 1.1 s → 0건, 꺾임 끝 샘플 뒤 4.4 s 멈춤 → 1건(11/10 고침)"),
    "TC_FAT_009": (["FatigueFactorsTest.fMicroMatchesSrs"], "기준 20: 현재 20→0, 15→0.5, 10→1.0, 5→1.0, 25→0"),
    "TC_FAT_010": (["FatigueFactorsTest.fLargeMatchesSrs"], "기준 4: 현재 4→0, 6→0.5, 8→1.0, 12→1.0, 2→0"),
    "TC_FAT_011": (["FatigueFactorsTest.fSuddenMatchesSrs", "SteeringMetricsTest.suddenSteerCountKeepsOnlyLastFiveMinutes"],
                   "0회→0, 1회→0.5, 2회→1.0, 3회→1.0, 5분 1초 전 급조향 제외"),
    "TC_FAT_012": (["RiskScoreTest.behaviorAndRiskMatchSrsVectors", "RiskScoreTest.scenarioOneGivesSixtyOneBeforeSmoothing", "RiskScoreTest.behaviorScoreTopsOutAtHundred"],
                   "(1.0, 0.5, 0.5) → B 67.5, K 1.2 → R 81.0, B 80·K 1.35 → R 100, 시나리오 1 → B 53.0·R 61.0"),
    "TC_FAT_013": (["ContextWeightTest.kStartsAtOneAndIsCappedAtOnePointThreeFive", "FatigueConfigTest.allContextWeightsAddUpToKMax"],
                   "가중 없음 1.00, 연속 2 h + 혼자 1.15, 5개 전부 1.35, 넘으면 1.35"),
    "TC_FAT_014": (["ContextWeightTest.longDriveCountsFromTwoHours"], "연속 119 min → +0, 120 min → +0.10"),
    "TC_FAT_015": (["ContextWeightTest.vulnerableTimeIncludesStartAndExcludesEnd", "FatigueConfigTest.defaultVulnerableWindowsMatchSrsBoundaries",
                    "ContextWeightTest.ageBandRangesAddToBaseRanges"],
                   "01:59 +0, 02:00·06:59 +0.10, 07:00·13:59 +0, 14:00 +0.10, 30·40대 22:00 +0.10"),
    "TC_FAT_016": (["ContextWeightTest.soloDriveNeedsKnownEmptySeats"], "동승 0명 +0.05, 1명 이상 +0, 착석 null +0"),
    "TC_FAT_017": (["ContextWeightTest.warmCabinCountsFromTwentyFiveDegrees"], "24.9 ℃ +0, 25.0 ℃ +0.05, null +0"),
    "TC_FAT_018": (["ContextWeightTest.longDayDriveCountsFromFourHours"], "24시간 누적 239 min +0, 240 min +0.05"),
    "TC_FAT_019": (["RiskScoreTest.noBehaviorMeansNoRiskWhateverTheContext", "FatigueEngineTest.awakeSteeringScoresZeroEvenInRiskyContext"],
                   "B 0·K 1.35 → R 0, 맥락은 1단계 조건으로만"),
    "TC_FAT_020": (["RiskSmootherTest.stepToHundredReachesSixtyThreeAfterThreeMinutes", "FatigueConfigTest.smoothingAlphaMatchesSrs"],
                   "0→100 고정 입력 180 s 뒤 평활 63.2 ±1, α 0.1535"),
    "TC_BSL_001": (["BaselineStoreTest.usualIsMedianOfLastTenAwakeTrips"], "12회 중 최근 10회만, 중앙값 오차 0, 깨어 있던 구간 없는 운행 제외"),
    "TC_BSL_002": ([], "2차(Should) — 깨어 있던 구간 4조건 저장은 아직 구현 안 함"),
    "TC_BSL_003": (["BaselineStoreTest.usesDefaultUntilThreeAwakeTripsThenUsual"], "운행 0·1·2회 → 기본값, 3회 → 평소의 나"),
    "TC_BSL_004": (["BaselineStoreTest.todayIsConfirmedAtFifteenGatedMinutes", "FatigueEngineTest.todayBaselineIsConfirmedAtFifteenGatedMinutes"],
                   "유효 누적 14 min 59 s 미확정, 15 min 0 s 확정"),
    "TC_BSL_005": (["BaselineStoreTest.departureFatigueNeedsTwoOfThreeCriteria", "FatigueEngineTest.drowsyFirstFifteenMinutesMeansDepartureFatigue"],
                   "미세 −30.0%·큰 1.50배 → 출발 피로, −29.9%·1.50배 → 아님, 3개 → 출발 피로"),
    "TC_BSL_006": (["BaselineStoreTest.departureFatigueKeepsUsualBaselineAndSchedulesFirstCheck",
                    "FatigueBridgeTest.sleepyConditionAsksFirstCheckFifteenMinutesAfterDeparture",
                    "CheckDueInputTest.firstCheckDueGoesToStageTwoAndAsksTheDriverOnce",
                    "ForecastCoordinatorTest.출발 피로가 false에서 true로 바뀔 때 한 번만 재계산, 120분 → 60분 (FCS_004)"],
                   "기준선 = 평소의 나(없으면 기본값) 고정, 첫 음성 확인 15:00(+0~0.1 s, CheckDue 1회), 예보 재계산 1회"),
    "TC_BSL_007": (["BaselineStoreTest.sleepyConditionTurnsOnSensitiveModeAndKeepsBaselineFixed", "FatigueBridgeTest.sleepyConditionTurnsOnSensitiveModeOnce"],
                   "엔진·연결기: '졸려요' → 민감 모드 ON 1회, '개운해요'·'보통' → OFF"),
    "TC_PAT_001": (["PatternClassifierTest.abnormalMinuteNeedsFewMicroAndMoreLargeOrSudden"],
                   "미세 0.70·큰 1.50 이상, 0.71 아님, 0.50·1.49·급조향 0 아님, 0.50·급조향 1 이상"),
    "TC_PAT_002": (["PatternClassifierTest.trendNeedsSixAbnormalMinutesOutOfTen"], "이상 분 5 → 아님, 6·10 → 추세형"),
    "TC_PAT_003": (["PatternClassifierTest.spikeNeedsRecoveryWithinOneMinuteEveryTime"],
                   "이상 분 3 + 모두 복귀 → 튐형, 4·5 → 애매, 3 + 복귀 실패 1회 → 애매"),
    "TC_PAT_004": (["PatternClassifierTest.normalCheckOnSpikeIsOneDistraction", "PatternClassifierTest.normalCheckRightAfterSuddenWaitsUntilSpikeIsConfirmed",
                    "FatigueBridgeTest.normalAnswerAfterSuddenSteerIsRecordedAsDistractionOnceSpikeIsConfirmed",
                    "PatternCheckResultFlowTest.normalAnswerWithSpikeComesDownAfterTenLowMinutes",
                    "InterventionPolicyTest.lowScoreRequiresTenContinuousValidMinutesAndNormalConfirmation"],
                   "(정상, 튐형) → 딴짓 기록 1건, 단계 하강 = 점수 60 미만 10분 뒤 1단계(INT_007)"),
    "TC_PAT_005": (["PatternClassifierTest.safetyBypassNeedsThreeSuddenSteersWithinFiveMinutes", "FatigueEngineTest.threeSuddenSteersWithinFiveMinutesTriggerSafetyBypass"],
                   "300 s 안 3회 → 신호 1건, 2회 → 0건, 3회째가 300.1 s 뒤 → 0건"),
    "TC_PAT_006": (["PatternCheckResultFlowTest.normalAnswerWithTrendKeepsStageTwoAndAsksAgainTenMinutesLater",
                    "PatternCheckResultFlowTest.normalAnswerWithTrendDoesNotComeDownEvenWhenScoreDrops"],
                   "(정상, 추세형) → 2단계 유지, 600 s 뒤 재확인(599.999 s엔 아님)"),
    "TC_PAT_007": (["InterventionPolicyTest.twoSlowResponsesAndNoResponseBypassGeneralStepDelay",
                    "PolicyInterventionManagerTest.duplicatedSlowResultIsIgnoredAndRealSecondSlowEscalatesImmediately"],
                   "느림 2회 연속 → 3단계, 무응답 1회 → 3단계, 느림 1회 → 30 s 뒤 재확인 (1번 시험)"),
    "TC_AST_001": (["AssistModeDetectorTest.cruiseSwitchesAssistModeOnTheSameFrame"], "크루즈 ON → 다음 프레임(≤ 110 ms) 모드 ON, OFF → 모드 OFF + 30 s 인계 감시"),
    "TC_AST_002": ([], "2차(Should) — 조향 모양으로 보조 주행 추정은 아직 구현 안 함"),
    "TC_AST_003": (["AssistModeDetectorTest.missingSteeringMeansAssistUntilItComesBack"], "조향 null → 모드 ON, 값 재개 → 다음 프레임 OFF"),
    "TC_AST_004": (["FatigueEngineTest.assistModeKeepsSmoothedScoreUnchanged", "FatigueBridgeTest.cruiseGivesUnavailableAssessment"],
                   "엔진·연결기: 보조 주행 중 valid=false, 위험 점수 갱신 0회, UNAVAILABLE"),
    "TC_AST_005": (["PolicyInterventionManagerTest.regularAssistedCheckOccursAgainWhileRemainingAtStageTwo",
                    "PolicyInterventionManagerTest.vulnerableAssistedCheckUsesTenMinutesInsteadOfTwenty"],
                   "보조 주행 정기 확인 1200 s, 취약 시간대 600 s (1번 시험)"),
    "TC_AST_006": (["AssistModeDetectorTest.operationGapCountsSecondsSinceLastOperation"],
                   "엔진: 조작 공백 900 s → 900, 901 s → 901, 조작 1회 → 0. 901 s 확인 당김은 1번 매니저 몫이고 시험 없음(2차 Should)"),
    "TC_AST_007": (["AssistModeDetectorTest.suddenSteerWithinThirtySecondsAfterReleaseIsUnsafe", "AssistModeDetectorTest.largeCorrectionsAreComparedWithUsualHandover"],
                   "해제 후 29 s 급조향 → 2단계, 31 s → 아님, 기록 2회·현재 3회 → 2단계, 기록 없음·3회 → 2단계, 2회 → 아님"),
    "TC_AST_008": (["AssistModeDetectorTest.unknownCruiseMeansNormalMode"],
                   "엔진: 크루즈 null → 일반 모드. 20분 정기 확인은 아직 없음(2차 Should)"),
}

# 판정을 Pass로 하지 않는 TC
ET_BLOCKED = {  # 에뮬레이터 시험(ET)인데 앱 조립 전이라 못 함
    "TC_BSL_007": "화면 컨디션 버튼·음성 '졸려요'가 앱에 아직 안 붙어 에뮬레이터 시험 못 함(조립 대기)",
    "TC_AST_004": "UI 게이지 회색은 화면 연결 전이라 에뮬레이터 시험 못 함(조립 대기)",
}
NOT_RUN = {"TC_BSL_002", "TC_AST_002", "TC_AST_006", "TC_AST_008"}  # 2차(Should): 일부 또는 전부 미구현 → 판정 비움

COLS = ["H", "I", "J", "K", "L", "M", "N", "O"]


def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def read_results(dirs):
    results = {}
    suites = []
    for d in dirs:
        for path in sorted(glob.glob(os.path.join(d, "*.xml"))):
            root = ET.parse(path).getroot()
            for suite in ([root] if root.tag == "testsuite" else root.findall("testsuite")):
                cls = suite.get("name", "").rsplit(".", 1)[-1]
                suites.append((os.path.basename(path), cls, int(suite.get("tests", 0)), int(suite.get("failures", 0)),
                               int(suite.get("errors", 0)), int(suite.get("skipped", 0))))
                for case in suite.findall("testcase"):
                    c = (case.get("classname") or suite.get("name", "")).rsplit(".", 1)[-1]
                    status = "pass"
                    if case.find("failure") is not None or case.find("error") is not None:
                        status = "fail"
                    elif case.find("skipped") is not None:
                        status = "skip"
                    results[f"{c}.{case.get('name')}"] = status
    return results, suites


def shared_strings(z):
    try:
        root = ET.fromstring(z.read("xl/sharedStrings.xml"))
    except KeyError:
        return []
    return ["".join(t.text or "" for t in si.iter("{%s}t" % NS["m"])) for si in root.findall("m:si", NS)]


def sheet_path(z, name):
    wb = ET.fromstring(z.read("xl/workbook.xml"))
    rels = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
    rid = next(s.get("{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id")
               for s in wb.find("m:sheets", NS) if s.get("name") == name)
    target = next(r.get("Target") for r in rels if r.get("Id") == rid)
    return "xl/" + target.lstrip("/").replace("xl/", "", 1) if not target.startswith("xl/") else target


def cell_text(cell_xml, strings):
    m = re.search(r'<v>([^<]*)</v>', cell_xml)
    if 't="s"' in cell_xml and m:
        return strings[int(m.group(1))]
    m2 = re.search(r'<t[^>]*>([^<]*)</t>', cell_xml)
    return m2.group(1) if m2 else (m.group(1) if m else "")


def set_cell(row_xml, ref, text):
    """row_xml 안의 ref 칸을 인라인 문자열로 바꾼다. 원래 서식(s)은 그대로 둔다. 칸이 없으면 열 순서에 맞춰 넣는다."""
    pat = re.compile(r'<c r="%s"((?:\s+[a-zA-Z:]+="[^"]*")*)\s*(/>|>.*?</c>)' % ref, re.S)
    m = pat.search(row_xml)
    style = ""
    if m:
        sm = re.search(r'\ss="(\d+)"', m.group(1))
        style = f' s="{sm.group(1)}"' if sm else ""
    if text == "":
        new = f'<c r="{ref}"{style}/>'
    else:
        new = f'<c r="{ref}"{style} t="inlineStr"><is><t xml:space="preserve">{esc(text)}</t></is></c>'
    if m:
        return row_xml[:m.start()] + new + row_xml[m.end():]
    col = re.match(r"[A-Z]+", ref).group(0)
    for cm in re.finditer(r'<c r="([A-Z]+)\d+"', row_xml):
        if (len(cm.group(1)), cm.group(1)) > (len(col), col):
            return row_xml[:cm.start()] + new + row_xml[cm.start():]
    close = row_xml.rfind("</row>")
    return row_xml[:close] + new + row_xml[close:]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--xlsx", required=True)
    ap.add_argument("--results", action="append", required=True, help="JUnit XML 폴더 (여러 번)")
    ap.add_argument("--commit", required=True)
    ap.add_argument("--env", required=True)
    ap.add_argument("--evidence", required=True, help="결과 요약 Markdown 경로 (저장소 기준, O열에 적음)")
    ap.add_argument("--date", default=datetime.date.today().isoformat())
    ap.add_argument("--tester", default="2번")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    results, suites = read_results(a.results)
    if not results:
        sys.exit("!! 시험 결과 XML이 없습니다. 먼저 Gradle 시험을 돌리세요: " + ", ".join(a.results))
    missing = sorted({t for tests, _ in MAP.values() for t in tests if t not in results})
    if missing:
        sys.exit("!! 대응 시험 결과가 없습니다(시험을 안 돌렸거나 이름이 바뀜):\n  " + "\n  ".join(missing))

    with zipfile.ZipFile(a.xlsx) as z:
        infos = z.infolist()
        data = {i.filename: z.read(i.filename) for i in infos}
        strings = shared_strings(z)
        tc_sheet = sheet_path(z, "Test Case")
        dc_sheet = sheet_path(z, "Doc Control")
    sheet = data[tc_sheet].decode("utf-8")
    version = "v0.2"
    dm = re.search(r'<c r="B6"[^>]*>.*?</c>', data[dc_sheet].decode("utf-8"), re.S)
    if dm and re.match(r"v\d+\.\d+$", cell_text(dm.group(0), strings).strip()):
        version = cell_text(dm.group(0), strings).strip()

    rows = {}
    for rm in re.finditer(r'<row r="(\d+)"(?:\s+[a-zA-Z:]+="[^"]*")*\s*(?:/>|>.*?</row>)', sheet, re.S):
        am = re.search(r'<c r="A%s"(?:\s+[a-zA-Z:]+="[^"]*")*\s*(?:/>|>.*?</c>)' % rm.group(1), rm.group(0), re.S)
        if am:
            tc = cell_text(am.group(0), strings).strip()
            if tc in MAP:
                rows[tc] = (int(rm.group(1)), rm.start(), rm.end())  # 같은 TC가 여러 행이면 마지막(최신 회차) 행
    absent = sorted(set(MAP) - set(rows))
    if absent:
        sys.exit("!! 시트에 없는 TC: " + ", ".join(absent))

    lines = []
    summary = {"Pass": 0, "Fail": 0, "Blocked": 0, "": 0}
    for tc in sorted(rows, key=lambda t: rows[t][0]):
        tests, note = MAP[tc]
        status = [results[t] for t in tests]
        passed = status.count("pass")
        if any(s != "pass" for s in status):
            verdict = "Fail"
        elif tc in ET_BLOCKED:
            verdict = "Blocked"
        elif tc in NOT_RUN or not tests:
            verdict = ""
        else:
            verdict = "Pass"
        names = ", ".join(t.split(".", 1)[1] for t in tests)
        if tc in ET_BLOCKED:
            note = f"{note}. {ET_BLOCKED[tc]}"
        measured = note if not tests else f"{note} → 단위 시험 {passed}/{len(tests)}개 통과: {names}"
        summary[verdict] += 1
        values = [measured, verdict, a.date, a.tester, version, a.commit, a.env, a.evidence]
        lines.append((tc, rows[tc][0], verdict, note, tests, status))
        row_no, start, end = rows[tc]
        row_xml = sheet[start:end]
        for col, val in zip(COLS, values):
            row_xml = set_cell(row_xml, f"{col}{row_no}", val)
        rows[tc] = (row_no, start, end, row_xml)

    # 뒤에서부터 바꿔 끼운다(앞 행의 위치가 바뀌지 않게)
    for tc in sorted(rows, key=lambda t: rows[t][1], reverse=True):
        _, start, end, row_xml = rows[tc]
        sheet = sheet[:start] + row_xml + sheet[end:]

    wb = data["xl/workbook.xml"].decode("utf-8")
    if "fullCalcOnLoad" not in wb:
        wb = re.sub(r"<calcPr\b", '<calcPr fullCalcOnLoad="1"', wb, count=1) if "<calcPr" in wb else wb.replace("</workbook>", '<calcPr fullCalcOnLoad="1"/></workbook>')

    print(f"판정: Pass {summary['Pass']}, Fail {summary['Fail']}, Blocked {summary['Blocked']}, 비움(2차) {summary['']} / 행 {len(lines)}개")
    for tc, row_no, verdict, _, tests, status in lines:
        print(f"  {tc} (행 {row_no}) {verdict or '-'}")
    if a.dry_run:
        return

    tmp = a.xlsx + ".tmp"
    with zipfile.ZipFile(tmp, "w") as out:
        for info in infos:
            body = data[info.filename]
            if info.filename == tc_sheet:
                body = sheet.encode("utf-8")
            elif info.filename == "xl/workbook.xml":
                body = wb.encode("utf-8")
            out.writestr(info, body, compress_type=info.compress_type)
    os.replace(tmp, a.xlsx)

    os.makedirs(os.path.dirname(a.evidence), exist_ok=True)
    with open(a.evidence, "w", encoding="utf-8") as f:
        f.write(f"# 2번 TC 결과 ({a.date})\n\n")
        f.write(f"- 시험자: {a.tester} / SRS {version} / SW `{a.commit}` / {a.env}\n")
        f.write(f"- 판정: Pass {summary['Pass']}, Fail {summary['Fail']}, Blocked {summary['Blocked']}, 비움(2차) {summary['']}\n")
        f.write("- 만든 도구: `tc_fill_2.py` (Gradle JUnit XML에서 대응 시험의 통과 여부를 읽어 Test Case 시트 H~O열에 적음)\n\n")
        f.write("| TC | 행 | 판정 | 측정값 | 대응 시험(결과) |\n| --- | ---: | --- | --- | --- |\n")
        for tc, row_no, verdict, note, tests, status in lines:
            t = "<br>".join(f"{x} ({s})" for x, s in zip(tests, status)) or "—"
            f.write(f"| {tc} | {row_no} | {verdict or '—'} | {note} | {t} |\n")
        f.write("\n## 시험 묶음 결과\n\n| 파일 | 묶음 | 시험 | 실패 | 오류 | 건너뜀 |\n| --- | --- | ---: | ---: | ---: | ---: |\n")
        for name, cls, n, fl, er, sk in suites:
            f.write(f"| {name} | {cls} | {n} | {fl} | {er} | {sk} |\n")
        tot = [sum(s[i] for s in suites) for i in (2, 3, 4, 5)]
        f.write(f"| 합계 | {len(suites)}개 묶음 | {tot[0]} | {tot[1]} | {tot[2]} | {tot[3]} |\n")
    print("기록 완료:", a.xlsx, "/", a.evidence)


if __name__ == "__main__":
    main()
