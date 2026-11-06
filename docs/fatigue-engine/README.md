# 2번 졸음 판단 엔진 문서

> 대상 코드: `src/android/engine`의 2번 모듈 (FatigueEngine · BaselineStore · PatternClassifier · AssistModeDetector)와 app `fatigue/` 엔진 출력 연결기
> 기준: 브랜치 `feat/engine-model` 11/7 (main 합침 + 11~13단계), SRS·TC v0.2, 기획서 v4 현재기능
> 기획서와 이 문서가 다르면 기획서가 맞다 (CLAUDE.md). 숫자 옆 `[해석]`·`[제안값]`·`[가정]`은 팀 확정 전 값이다.

## 한 줄 요약

10 Hz 주행 신호에서 **운전대 조향 모양만 보고** 30초마다 졸음 의심 점수(0~100)를 낸다. 짧게 튀는 딴짓과 계속 나빠지는 졸음을 나누고, 크루즈(보조 주행) 중에는 조향을 판단에서 뺀다.
단계(0~3)를 정하거나 음성으로 묻는 일은 하지 않는다. 그건 1번(단계 전환)·3번(음성)이 이 엔진의 출력을 받아서 한다.

## 지금 상태 (11/7)

| 항목 | 상태 |
| --- | --- |
| SRS 1차 Must (2번 몫 37건) | **33건 구현**. 4건(PAT_004·006·007, AST_005)은 1번 단계 전환이 판단하며 main에 들어 있음. 단 PAT_004의 '딴짓 기록'은 아무 데도 없음 |
| SRS 2차 Should (5건) | BSL_001 중앙값 계산·AST_006 조작 공백 초·AST_008 크루즈 null 처리는 미리 구현. BSL_002·AST_002는 2차 |
| 코드 | 엔진 main 27개 파일 2,534줄, 시험 15개 파일 2,059줄 (Android 의존 없음) + app 연결기 2개 파일 302줄, 시험 1개 파일 |
| 시험 | 엔진 30개 묶음 **257개 통과** (2번 125 + 1·3·4번 132), app 연결기 **8개 통과** |
| 11/7 한 일 | main 합치기, 신호 변환·AgeBand 교체·EngineConfig 연결, 고칠 것 1·2, 엔진 출력 연결기, 시나리오 형식 제안 ([07](07-history.md) 11~13단계) |
| main 병합 | **PR 리뷰·머지 남음** ([08 3절](08-team-handoff.md#3-pr-여는-법)) |
| 실제 차 신호 연결 | 연결기 있음. 1번이 앱 조립 코드에서 띄워야 돎. SDK 조향 값 문제는 [09](09-schedule-todo-limits.md#4-위험과-대응) |
| Pleos 메일 답 (11/5) | ADAS SDK 불가. 속도·조향 기본 1 Hz, 같은 값이면 3초 안 중복 콜백 생략 → 1번 TTL을 3초보다 길게. 확장 기능은 2차에 시뮬레이션 ([10](10-extension-design.md)) |

## 문서 목록

| 파일 | 내용 | 이런 때 읽는다 |
| --- | --- | --- |
| [01-overview.md](01-overview.md) | 2번이 하는 일, 입출력, 한 프레임이 지나가는 길, 10초 지연 | 처음 볼 때 |
| [02-code-map.md](02-code-map.md) | 엔진 파일 27개와 app 연결기 하나씩 설명, 공개 API, 초기화 범위, Kotlin 읽는 법 | 코드를 열기 전에 |
| [03-algorithms.md](03-algorithms.md) | 게이트·지표·점수·기준선·패턴·보조 주행 계산을 숫자 예와 함께 | 규칙이 궁금할 때 |
| [04-decisions.md](04-decisions.md) | `[해석]`·`[제안값]`·`[가정]` 전체와 이유, 설정값 전체 표 | 값을 바꾸거나 팀 회의 때 |
| [05-behavior.md](05-behavior.md) | 시나리오별로 엔진을 돌린 실제 출력표, 5번 데모 시나리오 값 | 동작을 확인하거나 데모를 만들 때 |
| [06-testing.md](06-testing.md) | 시험 돌리는 법, 시험 목록과 REQ 대응, 돌연변이 시험, 아직 안 한 시험 | 시험할 때 |
| [07-history.md](07-history.md) | 1~13단계 작업 기록: 커밋, 바뀐 파일, 이유 | 무엇이 언제 왜 들어갔는지 볼 때 |
| [08-team-handoff.md](08-team-handoff.md) | PR이란 무엇이고 어떻게 여나, 팀원별로 알릴 것 | PR 올릴 때, 팀에 공유할 때 |
| [09-schedule-todo-limits.md](09-schedule-todo-limits.md) | 간트 대비 진척과 고칠 칸, 남은 일, 한계·위험 | 일정을 잡을 때 |
| [10-extension-design.md](10-extension-design.md) | 2차 확장 설계: SDK에 없는 LFA·LKA·핸즈온·DAW 신호를 시나리오로 넣어 판단 규칙을 보여 주는 방법 | 2차 계획, PT 준비 |
| [11-scenario-format-proposal.md](11-scenario-format-proposal.md) | 데모 시나리오 형식(MSG-11) 2번 제안: 회전 지점 거리·시작 시각·정수 ms, 시나리오별 기대 결과 | 1·5번과 시나리오를 정할 때 |

## 바로 할 일 (11/7~11/9)

1. **PR 열기·리뷰·머지**: `feat/engine-model` → `main`. 방법은 [08 3절](08-team-handoff.md#3-pr-여는-법).
2. **1번**: 연결기 리뷰(대응표), 앱 조립 코드에서 `FatigueAssessmentRunner.run(...)` 띄우기, '15분 뒤 첫 확인' 입력, TTL 3초 초과. [08 4-1](08-team-handoff.md#4-1-1번-차량-신호단계-전환)
3. **1·5번과 데모 시나리오 형식 정하기** (간트 [7], 이미 지남). 2번 제안은 [11](11-scenario-format-proposal.md).
4. **팀장**: SRS 고칠 것 ([08 4-5](08-team-handoff.md#4-5-팀장srs-담당)), 간트 진척 ([09 2절](09-schedule-todo-limits.md#2-간트에서-고칠-것)).
5. **11/9 통합 뒤** TC 시트 H~O열 기록 (11/10~12).
