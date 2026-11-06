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

2026-11-02 디버그 수동 검증 화면: `develop-jdw`의 기존 작업을 보존하고 `app/src/debug/`에 개발 전용 Activity·런처·조립기를 추가했다. 최종 서비스 화면과 구별되는 배지를 표시하며 `release`에는 포함하지 않는다. [사용 가이드](docs/setup/DEBUG_HARNESS.md) 참고.

- D/P·시동 ON/OFF, 30초·1분·5분·15분 가상 시간, 위험 점수·추세형, 정상·느림·무응답, 휴식 인증·정상 회복, 전체 초기화를 수동 조작한다.
- 기존 `VehicleSignalHub → TripTracker → PolicyInterventionManager`를 재사용하고, 시간 진행은 1초 관측으로 나눠 중간 단계·요청도 기록한다. 운전 시간·운행/정차·적용 점수·단계/사유·요청 처리 상태·시간순 기록을 표시한다.
- 실제 음성·차량·내비 SDK는 실행하지 않는다. 가상 실행 완료와 실제 실행을 구별하고, `RestRecoveryResetRequest`는 미연결로 유지한다. 위치 기반 휴식 자동 인증·보상·최종 서비스 화면 완료를 뜻하지 않는다.
- 검증용 정차 정책은 [가정] `V03_RETROACTIVE`·`EXCLUDE`다. 전체 초기화는 세션 전체를 교체하고, 화면 재생성은 ViewModel로 세션을 보존하며 프로세스 종료 시 메모리 상태는 사라진다.
- 검증: engine 61개·app 81개, 합계 **142개 통과**(신규 12개). debug/release 빌드·Lint 오류 0개·차이 공백 검사 성공. release DEX·매니페스트에서 개발 화면 제외를 확인했고, Pleos 에뮬레이터 버튼으로 운행·느림 두 번·휴식/회복·전체 초기화 흐름을 확인했다. [검증 결과와 한계](docs/setup/DEBUG_HARNESS.md#2026-11-02-검증-결과) 참고. 기존 운행·개입 판단 코드·Gradle 설정·브랜치·커밋·원격은 변경하지 않았다.

2026-11-03 인계 4절 결함 수정: 점수 90·추세형으로 60초에 3단계가 될 때 대기 중 확인 요청이 사유를 `SAFETY_EVENT`로 덮던 정책을 수정했다. `SCORE`와 일반 `REST_RECOMMENDED` 요청으로 연결하며, 명시적 안전 우회·확인 결과·시간/예보·인증/회복은 보존한다. 기존 142개 통과 기록에는 잘못된 사유 기대값이 있었으므로 이번 회귀 결과와 구별한다.

- 잘못된 디버그 시험 기대값을 수정하고 정책·관리자·디버그 조립 회귀 시험 7개를 추가했다. 관련 68개 및 전체 engine 65개·app 84개, 합계 **149개 통과**. `assembleDebug`·release 매니페스트 처리 성공.
- 수정 APK로 동일한 0/30/60초 버튼 시험의 단계·사유·음성 요청 종류와 P단 15분·인증/정상 회복을 확인했다. [누를 버튼·기대 결과](docs/setup/DEBUG_HARNESS.md#높은-점수의-단계별-상승), [개발 기록·증거](docs/devlog/2026-11-03.md) 참고. 실제 음성·SDK 검증 완료로 확대하지 않는다.
- 메인에서 직접 진행했고 기존 보조 작업을 재사용하지 않았다. `develop-jdw`·HEAD `84db8c9` 및 기존 미커밋 디버그 구현을 보존했다. 브랜치 전환·커밋·push·빌드 설정 변경 없음. 다음 작업은 [저장·복원 → Hub → SDK/targetSdk → DemoClock·JSON → 서비스·담당 통합](docs/devlog/2026-11-03.md#후속-구현-순서)이다.

2026-11-03 Room 저장·복원: 기존 미커밋 운행·개입·디버그 구현을 보존하고, `app/data/RoomTripRepository`와 상태·구간 원자 저장을 연결했다. 공개 10종뿐 아니라 정밀 ms·정차 전 기준·마지막 관측·OFF·새 운행 대기·개입 정책/인증/확인 재개 근거를 저장한다. `engine`은 Android에 의존하지 않는다. [저장·복원 사용 가이드](docs/setup/ROLE1_PERSISTENCE.md), [개발 기록·기기 증거](docs/devlog/2026-11-03.md#room-저장복원-구현) 참고.

- 가상/실제 경과 10초 주기와 즉시 저장, 마지막 성공 시각·실패·빈 DB·잘못된 복원 구별, DB 삭제를 추가했다. 전체 메모리 초기화는 DB를 보존하고, DB 삭제는 메모리를 보존한다. 두 동작 모두 자동 저장을 중지한다.
- 재실행은 저장한 수동 가상 시각·운행 ID에서 Tracker·Store·개입 상태를 함께 재개한다. 종료를 시동 OFF로 만들거나 닫힌 실제 시간을 운전 시간에 더하지 않는다. 과거 음성·경유지 요청은 재실행하지 않으며, 중단 확인은 기한 이후 명시적으로 새 요청을 시작한다.
- 검증: 전체 **engine 69개·app 99개 = 168개**, 전체 기기 시험 **9개**(실제 Room 8개·기존 앱 문맥 1개) 통과. `assembleDebug` 성공. Pleos 에뮬레이터의 실제 저장·강제 종료·재실행에서 D 2분/P 3분의 시각·정차 근거·누적과 이후 정차 5분/15분 계산을 확인했다. 상세 수동 검증 결과는 개발 기록에 남긴다.
- Room 2.8.5·KSP2 2.3.13만 추가하고 기존 AGP/Kotlin/min/compile/target 버전은 유지했다. SDK 수집·FGS·부팅 자동 실행·JSON 재생·실제 재부팅 시간축은 미구현이다. **TRP_003 전체 완료가 아니며** 역할 모듈은 진행중으로 유지한다. 메인에서 직접 작업했고 브랜치 전환·커밋·push는 하지 않았다.

2026-11-03 VehicleSignalHub 주기·품질·중재: 정책을 명시하는 주기 Hub와 순수 Kotlin `RateMeter`를 추가했다. 수동 운행은 기존 관측 경로를 `observations()`로 명시해 Room/개입 시계를 보존했다. [Hub 연결·버튼 가이드](docs/setup/ROLE1_SIGNAL_HUB.md), [개발 기록](docs/devlog/2026-11-03.md#vehiclesignalhub-주기품질중재-구현) 참고.

- 100ms 발행과 원천 관측을 분리한다. 원천 10초 이동 창·5Hz 미만/측정 전/측정 불가/0Hz·중단 상태를 노출하고 선택 조향의 `sourceRateInfo`를 프레임에 함께 전달한다. 미세 수정 비활성·B 재정규화는 2번 담당으로 남긴다.
- 필드별 관측/수신 시각·출처·가용 상태·최신성·선택 이유, 완전 관측의 null 및 명시적 부분 갱신, 최신 REAL 우선/상실 후 대체 정책, DEMO 위경도 쌍 우선 예외를 구현했다. 미확정 TTL·우선권 유지시간은 필수 정책으로 주입한다.
- 느린 소비자는 최신 대기 프레임을 받고 세션/순번으로 누락을 관찰한다. 원천 수집을 늦추지 않으며 중지/재시작은 이전 캐시·버퍼 세대를 차단한다. 수집 완료·실패도 품질로 구별한다.
- 기존 디버그 화면에서 독립 **가짜 REAL/DEMO Hub 검증** 패널을 연다. 별도 시험 시계·입력·캐시를 사용하며 수동 운행/Room에 합산하지 않는다. 실제 SDK 수신 화면이 아니다.
- 전체 engine **83개**·app **120개 = 203개**, 기기 **9개** 통과. 신규 단위 회귀 35개와 기존 168개를 포함한다. `assembleDebug`·release 매니페스트 처리 성공. SDK 어댑터·JSON·DemoClock·FGS·targetSdk 변경은 없으며 실제 Playground SDK가 필요한 TC는 완료로 표시하지 않는다. `develop-jdw`·HEAD `84db8c9` 유지, 메인 직접 작업·브랜치 전환/커밋/push 없음.

2026-11-03 Playground SDK 입력 연결: 실제 `Vehicle 2.3.2`·`NaviHelper 3.0.5`·AAOS 읽기 어댑터와 별도 **Playground SDK 실시간 검증** 화면을 추가했다. [API/권한/단위·버튼 가이드](docs/setup/ROLE1_PLAYGROUND_SDK.md), [개발 기록](docs/devlog/2026-11-03.md) 참고.

- SDK 호출은 `app/.../pleos/`에만 두고 기존 주기 Hub에 연결했다. 실제 SDK 화면에는 가짜 REAL 입력·Tracker·Room을 연결하지 않는다. 기존 가짜 Hub·운행·SCORE 사유 수정·저장/복원 코드는 보존했다.
- capability/읽기 권한 확인·최초 조회/콜백 분리·해제/release·재연결, 원시/정규화/최종 값·품질·수신/원천/Hub Hz·변경 기록을 제공한다. 원천 시각 없는 Vehicle/Navi의 센서 Hz는 알 수 없음이다. SDK 화면 TTL 3초는 검증용 가정이다.
- SDK 공개 주석의 조향은 **전륜각**이다. 운전대 회전각에 대입하지 않고 null/UNSUPPORTED로 보류한다. 좌석 SDK의 Boolean 축약은 UNKNOWN/null을 구별할 수 없어 SDK 값만 표시하고 착석 정규화도 보류한다. 문턱값 조정·Boolean 뒤집기로 보정하지 않는다.
- SRS PLT_012 원문에 따라 **targetSdk만 34**로 변경했다. compileSdk 37·AGP/Gradle·기존 SDK 의존성 버전·Room 스키마는 유지했다. 플랫폼 android.car와 필요한 읽기 권한을 선언했다.
- 실제 기기의 수신 결과와 미검증 항목은 개발 기록에 구분한다. 이 이미지의 주행 UI 제한 때문에 P/0에서 연결하고 주행 입력 뒤 P/0으로 돌아와 수신 기록을 읽는다. 주행 허용 메타데이터 실험은 최종 변경에서 제거했다.
- 전체 **engine 83개 + app 139개 = 222개** 통과(신규 SDK 회귀 19개), `assembleDebug`·release main manifest 처리 성공. 실제 SDK에서 D(raw8), 22.222223m/s→80km/h, 크루즈20m/s→72km/h, LEFT·brake true·실내19.1°C·통풍0·위치 쌍을 확인했다. 조향/좌석은 원시 수신과 의미 보류를 구분했다. 실제 수신·중지/재연결·최종 APK 증거를 개발 기록에 연결했다.
- 앱 제거/UTP 없이 교체 설치했다. 전후 Room 모든 논리 행 동일, 같은 가상7분·운행ID·연속/최근2분·P/ON 복원 확인. SDK 시간을 수동 운행에 합산하지 않았다.
- DemoClock/JSON·FGS·부팅·실제 운행 저장 통합은 후속이다. **TRP_003 전체·SIG_001 실제 60초 엄격 TC·SIG_005 착석 의미·SIG_009 센서 원천 Hz·실제 경로 안내 TC는 완료로 표시하지 않는다.** `develop-jdw`·시작 HEAD `3d135fb` 유지, 이번 작업에서 브랜치 전환/커밋/push 없음.

### 2번 — 졸음 엔진 (`feat/engine-*`)

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| FatigueEngine | `engine/.../fatigue/` | 완료 (PR 리뷰 대기) |  |
| BaselineStore | `engine/.../baseline/` | 완료 (PR 리뷰 대기) |  |
| PatternClassifier | `engine/.../pattern/` | 완료 (PR 리뷰 대기) |  |
| AssistModeDetector | `engine/.../assist/` | 완료 (PR 리뷰 대기) |  |
| 엔진 출력 연결기 (FatigueBridge·FatigueAssessmentRunner) | `app/.../fatigue/` | 완료 (PR 리뷰 대기, 1번 앱 조립에서 띄워야 돎) |  |

2026-11-04: `feat/engine-model`에서 **1차 계산 규칙 구현·시험 끝** (main 병합 전). SRS 1차 Must 37건 중 33건 구현, PAT_004·006·007·AST_005는 1번 InterventionPolicy가 2번 출력으로 판단. `:engine:test` 104개 통과, 1번 `develop-jdw`와 합쳐도 187개 통과.

2026-11-06: main(11/4 병합 #6·#7·#8)과 합치기 시험 — 충돌 1곳(`engine/build.gradle.kts`, main 것으로), 엔진 시험 236개 통과, 변환 함수까지 242개. 2차 확장(가정 신호) 설계 문서 추가.

2026-11-07: main 합치기, 신호 변환 함수, 4번 `AgeBand`·`VulnerableTimeRange`로 교체(취약 시간대 값은 `VulnerableTimeDefaults` 한 곳), 팀 `EngineConfig`에 2번 묶음 4개 연결, 고칠 것 1(딴짓 1분 칸 정렬)·2(큰 수정 기준 하한 2/분 [제안값]), app 엔진 출력 연결기(1번 `DrivingAssessmentSource` 구현). `:engine:test` 257개(2번 125) + 연결기 8개 통과, 돌연변이 11개 모두 잡음. 데모 시나리오 형식 2번 제안 작성.

- 남은 작업: PR 리뷰·머지, 1번 앱 조립에서 연결기 띄우기·'15분 뒤 첫 확인' 입력·TTL 3초 초과(1번), 시나리오 형식 확정(1·5번), TC 시트 기록(11/10~12).
- 설명 문서: [docs/fatigue-engine/](docs/fatigue-engine/README.md) — 구조·알고리즘·해석·동작 예·시험·작업 기록·팀 전달·일정·2차 확장 설계·시나리오 형식 제안.

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
| ForecastEngine | `engine/.../forecast/` | 완료 | #7 |
| RestAreaRepository / NaviBridge | `app/.../navi/`, `app/.../data/` (선택 규칙은 `engine/.../rest/RestAreaSelector`) | 진행중 |  |
| ClimateController | `app/.../climate/` (규칙은 `engine/.../climate/`) | 진행중 |  |
| RestSession / NapSession | `app/.../session/` (휴식 인증·보상 규칙은 `engine/.../rest/`) | 진행중 |  |
| RewardLedger | `app/.../session/`, Room은 `app/.../data/RestRecordDatabase` | 진행중 |  |

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
