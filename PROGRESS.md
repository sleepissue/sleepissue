# 진행 현황판

한 눈에 "누가 뭘 맡고 지금 어디까지 왔는지" 보는 보드입니다. 작업할 때마다 상태 칸만 바꾸세요. 상태: `대기` → `진행중` → `완료` / 막히면 `블로킹`.

세부 할 일을 쪼개서 추적하고 싶으면 GitHub Issues를 만들고 여기 "이슈" 칸에 번호(`#12`)만 적어 링크하세요. 매일 이 파일을 열 때마다 어제와 달라진 칸이 있는지만 봐도 전체 진행이 보입니다.

> 역할·모듈·아키텍처의 단일 출처는 [`CLAUDE.md`](CLAUDE.md)(3장·4장·10장)입니다. 이 보드는 그 역할표를 "지금 상태"로 갱신하는 용도입니다. 어디에 뭘 두는지는 [`src/android/README.md`](src/android/README.md) 참고.

## 마일스톤

| 날짜 | 마일스톤 | 상태 |
| --- | --- | --- |
| 10/28 | 설명회 확인 | 진행중 |
| 11/5 | 화면 시안 3장 | 대기 |
| 11/10 | **MVP 동결** | 대기 |
| 11/15 | 1차 접수 마감 | 대기 |
| 11/23 | 1차 결과 발표 | 대기 |
| 12/3~12/4 | 2차 PT | 대기 |

## 담당별 현황

브랜치 접두어는 CLAUDE.md 3장 기준. `engine/`은 Android 비의존 순수 Kotlin(`:engine`), 나머지는 `:app`.

### 1번 — 차량 신호·상태 (`feat/signal-*`)

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| VehicleSignalHub | `app/.../signal/` | 대기 |  |
| TripStateStore | `app/.../signal/` | 대기 |  |
| InterventionManager | `app/.../signal/` (단계 전환 규칙은 `engine/.../intervention/`) | 대기 |  |
| DemoPlayer | `app/.../signal/` 또는 `app/.../demo/` | 대기 |  |

### 2번 — 졸음 엔진 (`feat/engine-*`)

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| FatigueEngine | `engine/.../fatigue/` | 진행중 |  |
| BaselineStore | `engine/.../baseline/` | 진행중 |  |
| PatternClassifier | `engine/.../pattern/` | 진행중 |  |
| AssistModeDetector | `engine/.../assist/` | 진행중 |  |

2026-11-04: `feat/engine-model`에서 **1차 계산 규칙 구현·시험 끝** (main 병합 전). SRS 1차 Must 37건 중 33건 구현, PAT_004·006·007·AST_005는 1번 InterventionPolicy가 2번 출력으로 판단. `:engine:test` 104개 통과, 1번 `develop-jdw`와 합쳐도 187개 통과.

2026-11-06: main(11/4 병합 #6·#7·#8)과 합치기 시험 — 충돌 1곳(`engine/build.gradle.kts`, main 것으로), 엔진 시험 236개 통과, 변환 함수까지 242개. 2차 확장(가정 신호) 설계 문서 추가.

- 남은 작업: main 합치기·변환 함수·AgeBand 교체·EngineConfig 연결 → PR·머지, **엔진 출력 연결기**(1번 `DrivingAssessmentSource` 구현, 아직 없음), 딴짓 1분 칸 정렬·기준선 하한 수정, 데모 시나리오 형식(1·5번), 1번과 '15분 뒤 첫 확인' 입력·TTL 3초 초과.
- 설명 문서: [docs/fatigue-engine/](docs/fatigue-engine/README.md) — 구조·알고리즘·해석·동작 예·시험·작업 기록·팀 전달·일정·2차 확장 설계.

### 3번 — 음성·실내 (`feat/voice-*`)

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| VoiceAgent (TTS) | `app/.../voice/` | 대기 |  |
| SpeechAgent (STT) | `app/.../voice/` | 대기 |  |
| CabinMonitor | `app/.../cabin/` | 대기 |  |
| CheckManager | `app/.../voice/` 또는 `app/.../cabin/` | 대기 |  |

### 4번 — 예보·쉼터·휴식 (`feat/rest-*`)

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| ForecastEngine | `engine/.../forecast/` | 대기 |  |
| RestAreaRepository / NaviBridge | `app/.../navi/`, `app/.../data/` | 대기 |  |
| ClimateController | `app/.../climate/` | 대기 |  |
| RestSession / NapSession | `app/.../session/` (휴식 인증·보상 규칙은 `engine/.../rest/`) | 대기 |  |
| RewardLedger | `app/.../session/` 또는 `app/.../data/` | 대기 |  |

### 5번 — 화면·시연 (`feat/ui-*`, `demo/*`)

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| Compose 화면 | `app/.../ui/` | 대기 |  |
| 데모 시나리오 6종(JSON) | `data/scenarios/` (합성은 `src/tools-py/`) | 대기 |  |
| 쉼터 CSV 전처리 | `data/rest_areas/` | 대기 |  |
| 시연 영상·README | — | 대기 |  |

엔진 쪽 패키지 경로(`engine/.../intervention`, `engine/.../rest`)와 그 패키지를 쓰는 app 쪽 클래스의 정확한 경계는 1주차(1·2·4번)에 확정합니다.

## 이번 주 막힌 것 (블로커)

-
