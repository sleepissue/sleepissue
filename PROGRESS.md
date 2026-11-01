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
| VehicleSignalHub | `app/.../signal/` | 진행중 |  |
| TripStateStore | `app/.../signal/` | 진행중 |  |
| InterventionManager | `app/.../signal/` (단계 전환 규칙은 `engine/.../intervention/`) | 진행중 |  |
| DemoPlayer | `app/.../demo/` | 진행중 |  |

2026-11-01: 로컬 `feat/signal-scaffold`에서 **A 기본 틀 완료**. 공통 모델·Clock·EngineConfig·입출력 계약, 단일 Source의 최소 Hub, 메모리 운행 상태, 개입·재생의 명시적 미연결 구현과 시험 전용 Fake를 추가했다. 모듈 전체가 완료된 것은 아니므로 상태는 진행중으로 유지한다.

- 실제 실행: `:engine:test` 10개, `:app:testDebugUnitTest` 27개(기존 예제 1개 포함), `assembleDebug` 모두 성공. 실패·오류·건너뜀 0개.
- 사용 예시·연결 계약: [A 기본 틀 가이드](docs/setup/ROLE1_SCAFFOLD.md).
- 분기 기준·최초 빌드 실패·공식 호환 근거·검증 결과: [개발 기록](docs/devlog/2026-11-01.md). A 구현 후 개인 개발 브랜치 `develop-jdw`로 변경을 보존해 전환했으며, 저장 대상은 `origin/develop-jdw`다. **공용 원격 develop 존재 여부는 미확인**이며 공용 develop 생성·PR은 진행하지 않는다. 개인 브랜치 원격 반영 결과는 Git 원격 참조와 별도로 확인한다.
- 남은 작업: 정차·최근 24시간·첫 D단·단계 규칙, 원천 Hz·주기 발행·실제/데모 중재, 실제 JSON 재생, SDK·Room·전원·서비스 연결. 기기 시험·실제 차량 API 호출·화면 실행은 미수행이다.

### 2번 — 졸음 엔진 (`feat/engine-*`)

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| FatigueEngine | `engine/.../fatigue/` | 대기 |  |
| BaselineStore | `engine/.../baseline/` | 대기 |  |
| PatternClassifier | `engine/.../pattern/` | 대기 |  |
| AssistModeDetector | `engine/.../assist/` | 대기 |  |

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
