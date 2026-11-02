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
- 남은 작업: 개입 단계 규칙, 원천 Hz·주기 발행·SDK/JSON 중재, JSON 재생, Playground SDK 입력 조립·Room·전원·서비스 연결. 기기 시험·Playground SDK 수신·화면 실행은 미수행이다.

2026-11-02: `develop-jdw`에서 운행 규칙 계산과 메모리 연결을 추가했다. `TripRules`의 첫 D단·정차·OFF 새 운행, `RollingDriveWindow`의 최근 24시간 합산, `TripTracker`의 품질·출처 확인과 정밀 상태 갱신을 구현했다. [운행 규칙 가이드](docs/setup/ROLE1_TRIP_RULES.md), [검증 기록](docs/devlog/2026-11-02.md) 참고.

운행 규칙 추가 당시 검증: engine 41개·app 39개 단위 시험(합계 80개, 기존 템플릿 1개 포함)과 `assembleDebug` 성공. 실패·오류·건너뜀 0개. 당시 다음 작업은 개입 단계 정책·요청 관리였으며, 이후 결과는 아래에 기록한다.

정차 정책과 짧은 정차의 누적 포함은 호출자가 명시적으로 선택한다. 외부 실행 계획과 현재 기획서의 차이를 승인 없이 자동 개정하지 않았고, 앱 기본 정책·SDK 수신·영속 복원까지 완료로 표시하지 않는다. 현재 개발 대상은 **Pleos Playground 가상 차량**이며 `REAL`은 SDK·Virtual Console 입력, `DEMO`는 JSON 입력이다.

2026-11-02 개입 동작 보완: 기존 미커밋 `InterventionPolicy`·`PolicyInterventionManager`를 보존해 SRS v0.2 INT_004·006·008·015와 해당 TC를 대조하고 수정했다. [연결 가이드](docs/setup/ROLE1_INTERVENTION.md), [실행 기록](docs/devlog/2026-11-02.md) 참고.

- INT_006: 휴식 인증 시 과거 시간·예보의 1단계 유지 조건을 풀고 현재 점수·취약 시간대·확인 조건을 재평가한다. 인증 전 운전 시간·예보 캐시는 만료시키며 실제 Store 초기화 성공으로 표시하지 않는다.
- INT_004: 기본·제품 조립 기준은 IMMEDIATE. 이후 일반 상승은 30초 한 칸이며 확인 결과·안전 우회는 즉시 3단계다. 실제 앱 조립 지점은 없으므로 시험과 가이드에 명시했다.
- INT_008: 3단계는 유효한 휴식 인증과 정상 회복 확인을 모두 요구한다. 인증만 완료하거나 회복이 느림·무응답·장치 실패이면 유지한다.
- INT_015: 팀 미확정 Should로 비활성. 기존 2단계 쉼터 안내 선택 경로를 제거했으며 필수 구현 완료로 세지 않는다.
- 실제 실행: `:engine:test` **61개**, `:app:testDebugUnitTest` **69개**, 합계 **130개 통과**(템플릿 1개 포함). 실패·오류·건너뜀 0개. `assembleDebug`와 `git diff --check` 성공. 이번 신규 회귀 시험은 13개다.
- 남은 작업: 외부 실행 담당 조립, INT_010 추가 완료 후 경유지 제거, RES_011 실제 상태 초기화·예보 연결, SDK/JSON 입력·Room·서비스·화면. 실제 Playground SDK·음성·내비 연동은 미검증이다.
- `develop-jdw` 로컬 미커밋 상태를 유지했다. 기존 보조 작업은 사용하지 않았고, Room·SDK·데모·targetSdk 설정·브랜치·커밋·원격은 변경하지 않았다.

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
