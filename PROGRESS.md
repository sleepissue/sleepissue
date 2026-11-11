# 진행 현황판

한 눈에 "누가 뭘 맡고 지금 어디까지 왔는지" 보는 보드입니다. 작업할 때마다 상태 칸만 바꾸세요. 상태: `대기` → `진행중` → `완료` / 막히면 `블로킹`.

세부 할 일을 쪼개서 추적하고 싶으면 GitHub Issues를 만들고 여기 "이슈" 칸에 번호(`#12`)만 적어 링크하세요. 매일 이 파일을 열 때마다 어제와 달라진 칸이 있는지만 봐도 전체 진행이 보입니다.

> 최신 역할 재배정은 [`AwakeDrive_일정_v1.gan`](docs/plan/AwakeDrive_일정_v1.gan)을 따른다(개입 3번, DemoPlayer 5번). 기존 구현 기록은 당시 이력으로 보존한다. 역할·모듈·아키텍처의 기본 맥락은 [`CLAUDE.md`](CLAUDE.md)(3장·4장·10장)입니다. 이 보드는 그 역할표를 "지금 상태"로 갱신하는 용도입니다. 어디에 뭘 두는지는 [`src/android/README.md`](src/android/README.md) 참고.

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

2026-11-10 PR 반영 정리: 추가 기능·성능 실험을 멈추고 현재 검증 결과와 한계를 인계한다. 실제 정차5/15분·30분 수집/저장·capability=false 3종·실제 재부팅2회/10종 복원 근거를 보존했다. SIG016은 값60초/50m와 취소null을 확인했으나 터치→Hub 1초 초과가 남고, 원문의 ‘목적지 있음’과 터치의 동치는 미확정이다. 메모리 정리 전후 각6회도 초과했고 CPU부하는 비슷해 호스트/AVD 영향을 배제할 수 없다. 선택적 내비 경계 계측만 추가했으며 조회1000ms·TTL·Clock·운행/저장 정책은 그대로다. 최신 자동결과는 app415개 실행+engine333개 기존 결과 재사용(합계748개, 실패/오류/skip0)이며 이번 게시 정리에서 재실행하지 않았다. SIG001110ms·SIG002전체200ms 미달과 의미/입력/STR·제품 연결 대기는 유지한다. [최신 조건별 현황](docs/setup/ROLE1_COMPLETION_STATUS.md)·[호스트 비교](docs/devlog/2026-11-10-sig016-host-load.md)·[다른 PC 절차(미실행)](docs/setup/ROLE1_SIG016_OTHER_PC.md).

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| VehicleSignalHub | `app/.../signal/` | 진행중 | 수집/품질 구현. 엄격 주기·의미/입력 제약은 최신 현황표 참조 |
| TripRules | `engine/.../session/` | 완료(순수 계산) | 실제 전원·휴식/기준선 소비 연결은 별도 |
| TripStateStore / Room | `app/.../signal/`, `app/.../data/` | 완료(저장·복원 코어) | 외부 모듈 최신값·제품 복원 연결 대기 |
| RealTripMonitorService | `app/.../service/` | 진행중(구현 완료) | 실제 STR/ignition·시작 거부 기기 조건과 다른 담당 연결은 남음 |

2026-11-09 PLT013: 내비 뒤 **실제1,800.531초,18,005개 발행/서비스 소비·deadline 누락0**, 기어AVAILABLE 전구간, Room180성공/실패0·완료간격최대10.464초 확인. 최초 종료harness timeout과 알림monitor 실패를 보존하고 짧은 후속 알림중지/재진입복원은통과했다. 격리 알림 대상만최소수정, 관련Kotlin31개·집계기9개·빌드성공. SIG001/002미달은그대로다. 기존111개파일/모든DB·설정복구, HEAD68696cf·커밋없음. [조건·실패분리·직접순서](docs/devlog/2026-11-09-plt013.md).

2026-11-09 남은 기기 검증: Perfetto에서 일부 OS Runnable 대기 확인, **SIG001 110ms·SIG002 전체200ms는 미달 유지**. 기어/방향지시등/브레이크/목표속도는 이번 표본200ms, 공식 통풍128/105ms 확인. 격리 서비스 실제20분32초로5분 소급 제외·재출발/15분0·24h누적 유지, 저장 성공 이벤트 최대10.857초, force-stop 복원 차2.3초와10종, 알림/차속 권한 거부·복구 확인. 자동743개 및 빌드 성공·명시적 측정·회귀 JUnit6종 최종 정상 종료(시한 판정 별도)(복원 반복 포함7회). 기존94개 파일/사용자DB 보존·HEAD68696cf·커밋 없음. [근거·남은 조건·버튼 순서](docs/devlog/2026-11-09-role1-remaining.md).

2026-11-09 실제1배속 주기 후속: **SIG_001 엄격110ms 미해결**. 최종 반복 emit 최대130/128ms·초과25/7회, Console 실제 조향 입력 동반131ms·13회(모두60초 이상, 순번/deadline 누락0). 전용 타이머 실험은 개선되지 않아 제외하고 선택적 구간 계측·최소/0ms 감사만 추가했다. 자동 **743개(engine333+app410), 실패/오류/skip0**, assembleDebug·AndroidTest APK·release manifest 성공. 기존83개 파일/수동·REAL DB 보존, HEAD68696cf 유지·커밋 없음. [원인·전후 측정·버튼 순서](docs/devlog/2026-11-09-sig-timing.md). 아래741/492/490개와 기기시험은 각각 이전 근거다.

2026-11-08 최신 기능 확인: [요구별 구현/기기 확인/미검증/환경 미지원/의미 보류/연결 대기 표](docs/setup/ROLE1_COMPLETION_STATUS.md), [실행 기록](docs/devlog/2026-11-08-role1-finish.md). 아래 날짜별 수치는 당시 기록이다. 기존480개와 Room 기기10개는 과거 근거이며 이번 재실행과 구분한다.

- 실제 통풍·현재 실내온도의 공식 현재값 재확인·만료·오류/null을 보완했다. 최초 조회/기어 재조회도 관측에서 제외하고 AAOS·일반/의미 보류 래퍼의 거부 배송 재전달을 차단했다. 내비 보고는 조회/자발 알림 구분 불가를 명시하고 Hub에서도 새 센서 관측 순번을 만들지 않는다. heading은 잘못된 위치·오류·조회 공백을 이어 붙이지 않는다.
- **이번 전체 강제 재실행 engine153+app337=490개**, 실패/오류/skip0, assembleDebug·AndroidTest APK·release manifest 성공. 메모리 Room 장시간 경계3개와 실제 통풍 SDK→Hub 0→1→0·통풍/기어 재조회4회 무중복 기기1개가 통과했다. 순수 Clock/메모리 Room 시험은 실제 장시간 대기가 아니다.
- **엄격100ms 주기는 여전히 미달:** 실행기 대조 기본123ms/초과15회 vs 전용134ms/21회. 전용 실행기 안은 채택하지 않았다. 최종77.079초 실제 emit 직전 감사는 최대129ms/110ms 초과13회. 메타데이터 감사와 실제 emit 감사를 분리하며 타임스탬프/TTL/시계는 보정하지 않았다.
- 활성 내비는 공식 샘플 요청 뒤에도 경로 false여서 조건 미충족으로 분리했다. 현재온도 변경·실제 이동 heading·차량 STR/전원·일부 지연/장애 TC는 남는다. 통풍 API 미지원은 아니다. 개입/음성/쉼터/제품UI/DemoPlayer/60배 통합은 하지 않았다.
- 수동·REAL DB 논리 행, 기존 진단 원본과 실행 의사를 보존했다. 서비스/Room 스키마·운행 규칙·다른 담당 코드는 변경하지 않았다. 브랜치/HEAD 유지, 전환/병합/커밋/push/삭제/초기화 없음. 제출 README·서명 릴리스 APK·PT와 공식 TC 최종 판정은 제외한다. Android 개발 가이드의 target34/서비스 사실관계만 정정했다.

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

2026-11-05 DemoClock·JSON·DemoPlayer: `develop-jdw`·HEAD `7bc5ffd`에서 구간별 1/10/60배 재생·질문 중1배·상대/실제/epoch 시간 분리와 엄격 JSON 로더를 구현했다. [재생·시간·버튼 가이드](docs/setup/ROLE1_DEMO_PLAYER.md), [5번 JSON 작성 가이드](docs/setup/ROLE1_DEMO_JSON.md), [검증 기록](docs/devlog/2026-11-05.md) 참고.

- `engine.time.Clock`을 유지하며 데모 전용 `DemoClock`만 배속한다. 음성 실제 경과·기기 날짜는 배속하지 않고 예보에는 별도 `EpochScenarioClock`으로 `startEpochMs + 상대 ms`를 준다. 기존 수동 검증/Room 시계는 변경하지 않았다.
- `ScenarioDemoPlayer`의 로드/시작/일시정지/재개/중지/초기화·자연 종료, 질문 세대·중복/중첩/늦은 결과·실패/취소와 null 무효화를 구현했다. 출력은 DEMO Source로 기존 Hub에만 공급하며 REAL 우선·DEMO 위경도 쌍 예외를 유지한다.
- SRS MSG-11의 `t`초·`fromSec/toSec/factor`를 유지하고 파일/JSON/스키마/시간/배속 오류를 구별한다. 최소 예제·JSON Schema·작성 가이드를 제공한다. null·OTHER·HAZARD·power 확장은 가이드에 명시했다. 6종 시나리오 및 Python 별도 검증기는 완료가 아니다.
- 독립 debug **JSON 데모·배속 검증** 화면은 상대/실제 시간·구간/실효 배속·질문·Hub 최종 값/출처를 표시한다. PR #8 `CheckManager`를 재사용한 가짜 질문·가짜 REAL 시험이며 실제 음성/SDK 수신과 구별한다. 화면 종료 때 Hub/재생을 중지한다. 기존 운행·가짜 Hub·실제 SDK·음성 화면과 Room 저장 코드/스키마를 보존했다.
- 최종 검증: 기존 JBR25.0.3으로 `:engine:test` **146개** + `:app:testDebugUnitTest` **178개** = **324개 통과**(기존273개+신규51개), 실패/오류/건너뜀0. `assembleDebug`·`git diff --check` 성공. 구간 경계·느린 소비 중pause·실제2300ms응답·질문 종료 즉시 복귀·시계 역행·소유 scope 종료·REAL/DEMO 선택 회귀를 포함한다.
- **미수행:** 연결 기기 없음에 따라 기기 버튼/실제 성능 ±1%·실제 SDK+JSON 동시 수신·실제 TTS/STT lifecycle. APK 설치·제거/UTP/Wipe/DB삭제 없음. 실제 음성 시작/종료 통보와 모델 변환, 제품 Hub/운행/개입/예보 조립, DMO_005~006 E2E·서비스/부팅은 후속이다. 커밋·push·PR은 생성하지 않았다.

2026-11-05 후속 60배 성능 점검: 종료 전 `PLAYING` 전체null의 원인은 전달 중 배속 시계가 앞서 DEMO 원본이 `STALE`로 제외되는 경로였다. `pump()`의 현재 시각 따라잡기·완전 관측 경계25ms 작업 예산·PAUSED 잔여 전달·전체 전달 후 종료를 추가하고 debug 재생/Hub 수신을 Main 화면 처리와 분리했다. 최초 전체324개·수정 전 지연2개 단독 통과와 구분해 **전체329개(engine146+app183)·assembleDebug를 `--rerun-tasks`로 재검증**했다. Room 전후 논리 행 동일·기존 가상7분 저장본 보존·브랜치/HEAD 유지 확인. **기기 60배 성능은 미해결:** 최종 두 재생 모두 원본18,000개 전달·정상 종료했지만 전체null85/66tick과 누락deadline10,831/9,227회가 남았다. [개발 기록](docs/devlog/2026-11-05.md#최종-기기-결과와-남은-성능-한계)·[근거 요약](docs/devlog/2026-11-05-demo-performance.json)·[직접 확인 순서](docs/setup/ROLE1_DEMO_PLAYER.md#장기-재생과-trace-확인-순서)를 따른다. 실제 SDK+JSON·실제 음성·제품 통합 완료를 뜻하지 않는다.

2026-11-07 실제 SDK 운행·Room 연결: `develop-jdw`의 기존 미커밋 작업을 보존하고 **1번 신호 → 운행 계산 → 전용 Room**을 debug 화면에서 연결했다. [연결·직접 버튼 가이드](docs/setup/ROLE1_REAL_TRIP.md), [검증 기록](docs/devlog/2026-11-07-real-trip.md) 참고.

- 기어 ON_CHANGE는 [가정] 1초 재조회·3초 확인 lease를 적용한다. 원천 변경·실제 수신·재조회 확인 시각을 분리하며 Hub 발행/같은 상태 재조회를 새 센서 관측으로 세지 않는다. 부분 누락은 유지, 명시 null/오류·권한 상실·해제는 무효화한다.
- 새 `PlaygroundTripSession`이 REAL 전용 Hub·기존 Tracker/Rules/Store와 `TripSaveCoordinator`를 연결한다. 전원 API는 미확인/미연결이며 ON/OFF를 추정하지 않는다. DEMO·수동 시계/운행은 공유하지 않는다.
- `awakedrive_sdk_trip.db`에서 실제 경과 10초와 상태 전환/정상 중지 즉시 저장을 실행한다. `awakedrive_debug_trip.db`는 보존한다. 원본 정밀 정차·운전 구간·개입 형식/Room 스키마 유지. 복원 원본과 새 입력 대기 UNKNOWN을 구분하고 앱 종료 공백을 합산하지 않는다.
- 자동 회귀 **engine 153 + app 261 = 414개**, 실패/오류/skip 0. `assembleDebug`·AndroidTest APK·release manifest 처리 통과. 재연결 최초 조회가 과거 callback 계수에 막히던 결함을 새 시험으로 발견하고 수정했다. 최종 Room 기기10개 통과. 실제 D15초 유지·D→P→D·강제 종료/재실행(ID·313,009ms·최근47초/현재UNKNOWN)·중복 연결/재연결·차속 권한 철회/복구를 확인했다. 수동 DB 전후 논리 해시 동일, 최종 APK/설치본 일치. 실제15분/24시간 대기·기어 권한/CarService 강제오류·이동 중 제한은 미검증이다.
- **진행중 유지:** 화면 종료 후 감시 서비스·절전·실제 전원·BOOT_COMPLETED/실제 재부팅 복원은 후속이다. 다른 담당 판단/음성/쉼터/휴식 연결 대기. TRP_003 전체 완료가 아니다. 개입은3번·DemoPlayer는5번 인계 대상으로 기존 코드·시험·60배 기록을 그대로 보존하고 성능 재작업은 하지 않았다.
- 브랜치 전환·커밋·push·앱 제거·데이터 초기화 없음. 교체 설치와 직접 instrumentation runner만 사용한다.

2026-11-07 REAL 감시 서비스: 기존 실제 SDK→Hub→운행→전용 Room을 `RealTripMonitorService`로 옮겼다. 화면은 상태와 시작/중지 명령만 담당한다. [서비스 가이드](docs/setup/ROLE1_MONITOR_SERVICE.md), [검증·실제 재부팅 기록](docs/devlog/2026-11-07-monitor-service.md) 참고.

- 비마이크 `specialUse`의 공식 유형/권한·사용자 시작·알림 중지·시스템 sticky 재생성·허용된 BOOT 재개를 연결했다. 사용자 중지 의사는 먼저 저장하고 자동 재시작하지 않는다. SDK 단독 검증 화면과 객체 lease로 소유권을 나눈다.
- 실제 경과10초/필요 즉시 저장·원본 보존/UNKNOWN 복원·수동/DEMO/REAL 분리를 유지한다. 기어 확인 lease가 끊긴 절전/미관측 공백은 운전·정차·휴식에 더하지 않는다. 재부팅 전후 elapsed를 직접 빼지 않는다.
- 최종 자동 **engine153+app280=433개**, 실제 Room 기기 **10개** 통과. debug APK/기기시험 APK/release manifest 빌드 성공. 추가 Lint는 변경하지 않은 3번 음성 `PleosSpeechRecognizer.kt:129`의 기존 권한 처리 오류1건으로 실패했으며 범위 밖이라 보존했다. 실제 시작 연타·화면 종료/꺼짐·OS 화면 제한·재진입/회전·SDK 소유 충돌·명시/알림 중지·SIGKILL 재생성·강제 종료 UNKNOWN 복원·권한 철회·실제 BOOT 재개와 중지 뒤 두 번째 재부팅 비재개를 확인했다. 초기 재생성 미재개 관찰은 최종 합격과 구분해 기록했다.
- 기존 수동 DB 전후 모든 논리 행 동일·integrity ok, REAL ID/구간 보존, 개입·DemoPlayer·60배 구현 보존. 전원 IGNITION_STATE는 공식 의미를 확인했지만 실제 AVD 지원 목록에 없어 UNKNOWN이다. 실제 STR/물리 차량·CarService 강제 장애·기어 권한 철회·15분/24시간 장기 대기·시작 제한 fallback은 미검증이다. 2/3/4/5번 연결은 대기다.
- `develop-jdw` 유지. 브랜치 전환·커밋·push·앱 삭제·사용자 데이터 초기화 없음. 최종 감시는 중지/UNKNOWN이며 사용자가 **감시 시작**으로 재개한다.

2026-11-07 사용자 직접 감시 확인·중간 저장: 중복 시작 방지, 화면 종료 중 감시/저장 지속, 명시적 중지와 재진입 후 같은 운행 ID/누적 복원을 사용자 직접 조작으로 확인했다. `/tmp/awakedrive-user-monitor-20261107.json`의 기어 STALE→UNKNOWN 1회·로그상93ms 뒤 D 복귀를 대조했으며 원인은 미해결이다. [후속 관찰과 한계](docs/devlog/2026-11-07-monitor-service.md#사용자-직접-확인과-중간-커밋-2026-11-07). 기존 자동433개·Room기기10개는 이전 통과 기록이며 이번 커밋 준비에서 재실행한 숫자가 아니다. 60배 기기 성능 목표 미달·기존 음성 Lint 오류·미검증 범위를 유지하고 개입/데모는 인계 자료로 보존한다.

2026-11-07 기어 STALE 후속 분석: 시작 `develop-jdw`·HEAD `6451ed8`·clean을 기록하고, 원본 로그93ms와 실제 운전 누적 제외 **202ms**를 구분했다. 원본에는 조회 단계 시각이 없어 당시 원인은 미확정이다. [분석·수정·검증 기록](docs/devlog/2026-11-07-gear-stale.md) 참고.

- Main 진행에 의존하던 재조회 예약을 IO 직렬 루프로 옮겼고, 늦은 같은/과거 원천 응답이 새 확인 시각을 되돌리는 결함을 실패 회귀로 확인해 차단했다. 실제 새 D→P·오류/권한/세대 무효화와 UNKNOWN을 유지한다. TTL3초/재조회1초·원천/수신/확인 시간 계약·센서 관측 계수는 보존했다.
- 조회 예약/실행/응답/반영·마지막 확인/유효 끝·Hub 판정/이유를 제한된 메모리로 계측한다. 자동 진단은 작은 요약, 수동 **진단 저장**은 별도 `cache/gear-collection-audit.json` 상세 기록이며 파일 I/O는 IO에서 처리한다.
- 이번 전체 강제 재실행 **engine153+app297=450개**, 실제 Room 기기 **10개**, debug/AndroidTest APK·release manifest 빌드 통과. 기존433개/Room10개 과거 기록과 구분한다. AVD D 유지 수정 전120초×2·수정 후120초×2·최종 자동 진단60초×1은 모두 STALE0회지만 **원본 해결 판정은 아니다**. 이번 시작 연타/회전·실제 기어 권한/CarService 장애·STR·재부팅은 재시험하지 않았다.
- 수동 DB 논리 해시 동일·REAL 같은 ID/기존11개 구간·원본 진단 보존. 재실행 후1,798,726ms/최근708초·현재UNKNOWN 복원, 마지막 감시 중지/타이머0 확인. 브랜치 전환·커밋·push·앱 삭제/초기화 없음. 음성 Lint/60배 성능은 미수정·미해결, 다른 담당 연결 대기를 유지한다.

2026-11-08 사용자 직접 기어 검증: D 유지 **129,494ms**가 단일 운전 구간으로 추가되고 최근 누적 **708→838초**가 됐다. 완료 세션의 조회143회·오류0·기어 STALE0, D 중 실제10초 저장 성공12건과 같은 ID/구간을 진단으로 대조했다. 실제 Room의 같은 ID/구간은 사용자 직접 확인 결과다. [세션/시각·수동 검증 기록](docs/devlog/2026-11-07-gear-stale.md#2026-11-08-사용자-직접-검증과-커밋-준비). 상세 파일은 중지 후 세대2, 최신 진단은90,890ms 뒤 재시작 세대3이므로 누계를 혼합하지 않았다. 원본 STALE 원인은 미확정이며 이번에 재발하지 않았다는 결과로 유지한다. 자동450개·Room10개는 앞선 통과 기록으로 이번 커밋 준비 중 재실행하지 않았다. 음성 Lint·60배 성능·미검증 범위는 그대로 보존한다.

2026-11-08 기어 외 상태형 신호 보완: `f5c6ad9`·clean에서 시작해 TURN_SIGNAL/BRAKE_ON/CRUISE_ACTIVE/CRUISE_TARGET_KPH의 공식 현재값 조회와 권한/단위를 확인했다. 수정 전 실제 APK에서는 무변경 콜백 이후 네 신호 모두 Hub STALE인 표본을 확인했다. [계약·신호별 결과표·실제 버튼 순서](docs/devlog/2026-11-08-state-signals.md).

- 네 신호만 IO 직렬 재조회1초/확인 lease3초를 적용하고 전체 TTL은 유지했다. 원천 시각은 UNKNOWN, 실배송/확인 시각과 조회/콜백 수를 분리한다. `confirmationFields`로 재조회 결과가 바뀌어도 Hub 원천 관측 통로·관측 순번/콜백 수를 늘리지 않는다. 부분 유지·명시 null·OFF/false/0, 과거/이전 세션/오류 후 응답 차단과 명시적 재연결을 유지한다.
- 이번 자동 **engine153+app320=473개(신규23개)**, debug/AndroidTest APK·release manifest 빌드 통과. 기준450개·Room10개와 구분하며 Room 기기10개/Lint는 이번에 재실행하지 않았다. 실제 AVD5조건 각19,384~21,511ms의 동일 상태 캡처 구간에서 OFF/LEFT/RIGHT, false/true, 목표4종, SDK ENABLED/false·ACTIVATED/true·null/UNAVAILABLE을 확인했다. 약1초 간격21표본/조건이며 짧은 STALE 부재를 전 프레임으로 입증한 것은 아니다.
- 두 Room DB 전후 모든 논리 행 동일·integrity ok, 기존 사용자 진단 원본 보존. SDK 중지/최종null·감시 서비스 없음·requested=false로 종료했다. 실제 normal 권한 상실/서비스 강제 장애·STR/장기·물리 차량·다른 enum·목표0 실제 수신은 미검증이다. 서비스 수명/운행 규칙/Room 스키마/전원 UNKNOWN·다른 담당/음성 Lint/DemoPlayer/60배 코드는 보존했고 브랜치 전환·커밋·push·앱 삭제/초기화는 하지 않았다.

2026-11-08 상태 배송 후속: 저장소 거부 뒤 직전 CALLBACK을 Hub에 재전달하는 결함을 공유된 실제 배송 판단으로 재현했다. 수정 전 신규6개 중4개에서 callbacks/raw가1→2로 중복됐으며, `receive(): Boolean`과 수락 시에만 emit하는 경계로 수정했다. 정상 재조회(값 변경 포함)·명시 null·오류/권한 무효화는 유지한다. 이번 강제 재실행 **engine153+app326=479개(기존473+신규6)**·assembleDebug 통과. [재현/수정/검증 기록](docs/devlog/2026-11-08-rejected-delivery.md). 두 Room DB 전체 논리 행·진단 원본 보존, 기존 미커밋 상태 신호 작업 보존. 새 기기 경쟁/Room10개/Lint/60배 시험은 재실행하지 않았고 브랜치 전환·커밋·push·설치/삭제/초기화는 하지 않았다.

2026-11-09 1번 남은 SIG 실제 입력 후속: [현황표](docs/setup/ROLE1_COMPLETION_STATUS.md)·[실행/5종 결과표·직접 버튼](docs/devlog/2026-11-09-role1-sig-input.md)을 갱신했다. 기존 미커밋 모드 gate 보완을 보존하고 실제 기본 내비에서 **NaviHelper→어댑터→Hub60초/50m/TBT50m, 시험 경로 취소 후 세 값null**을 확인했다. 이전 `activeObserved=false` 원본에도 `ROUTE_GUIDING/false`가 있었으므로 입력 조건 미충족 서술을 정정한다. 위치 공백/오류 후 이력 단절의 Hub 회귀1개를 추가했으며 **이번 전체 자동492개(engine153+app339)·assembleDebug·AndroidTest APK·release manifest 성공**, 활성 내비 결합 기기1개는 별도다. 기존490개·Room 경계3개·통풍/기어 결합1개는 이전 기록이며 Room/통풍 기기시험은 재실행하지 않았다. geo fix는 Android GPS→내비 앱 배송까지 확인됐으나 NaviHelper 고정 위치로 **이동 heading 미검증**. 현재온도19.1°C·50표본 유지/READ만 확인했고 공개 변경 입력은 미확보다. 실제 교차로 통과·자연 도착·1초 시한은 미시험, **엄격110ms 미달은 별도 성능 과제**다. DB 모든 논리 행·기존 진단81개 파일·다른 담당 소스와 기존 미커밋 작업 보존, main 읽기 전용 비교만 수행. 전환·병합·커밋·push·삭제/초기화/Wipe 없음. 4번에게 확인할 provider/POI/이동·현재온도 입력 질문을 기록했다.

2026-11-09 develop-jdw 최신 main 병합: 기존 미커밋100개 파일을 **08da2b9**에 원본 그대로 선별 커밋한 뒤 `git fetch origin`, `git merge origin/main`으로 **main5195e25 → 병합c39bcca**, 텍스트 충돌0. [SHA·호환 수정·팀 연결 계약·직접 실행 순서](docs/devlog/2026-11-09-main-merge.md)를 기록했다. 모드 gate/상태 재조회/거부 배송/관측 구분의 핵심8개 파일은 선별 커밋과 바이트 동일하며 main의BELT 변경도 보존했다. 새 UI launcher 미등록·SDK 화면 vehicleKeys 미정의·팀 데모 시험의 미존재 로컬 fixture 경로를 최소 보완했다. **병합 전492개와 구분한 병합 후741개(engine333+app408), 실패/오류/skip0·assembleDebug·AndroidTest APK·release manifest 성공**. 새 UI는 기동하지만 실제 신호/판단/음성/휴식의 제품 조립은 대기다. 수동/REAL DB·원본 진단 보존, 기존 성능/입력/STR 한계 유지. push·PR 생성/merge는 하지 않는다.

2026-11-09 1번 최종 점검: [검토·TC 경계·다음 작업](docs/devlog/2026-11-09-role1-final-review.md)·[31개 요구별 최종 분류](docs/setup/ROLE1_COMPLETION_STATUS.md#요구별-최종-상태)를 갱신했다. 기존 미커밋/진단을 보존하고 격리 알림ID·채널 분리, 계측 예외 잠금 해제, 계측OFF 숫자 포장 제거만 보완했다. **최종 전체745개(engine333+app412), 실패/오류/skip0·debug APK/AndroidTest APK/release manifest 성공**. 이전743개/실제30분 근거와 구분하며 이번 설치·기기/성능 재시험은0이다. SIG001110ms·SIG002전체200ms 미달 유지. 실제24h/2h 대기·OOM은 원문 JVM TC의 새 필수조건이 아니다. 실제OFF→ON은환경제한, 정확한BOOT10종TC는실행가능미검증이다. 다음작업은조향·착석·통풍 capability=false 기기모의1건으로 선정만 했고 아직 시작하지 않았다. 브랜치/HEAD 유지·stage/커밋/push/PR·데이터 조작 없음.

2026-11-10 1번 최신 알림·SIG007: [정확한 runner·세 신호 결과·보존](docs/devlog/2026-11-10-role1-capability.md). debug/test APK를 -r -t 교체설치하고 알림ID4102/독립채널·사용자DB 없는 화면·자기서비스중지/SDK해제 기기1개(6.065초), 명시적 SDK 경계 capability=false→제품 어댑터→Hub 모의3개(12.702초)를 확인했다. 세 신호 검사각1·getter/구독0·null/UNSUPPORTED·필드로그1·같은PID31063·정리 완료. 착석 공통capability 로그5행을1행으로 최소수정하고 회귀1개 추가. **최종 전체746개(engine333+app413), 실패/오류/skip0·debug/test APK·release manifest/Kotlin 성공**, release fake참조0. 이전745/30분은 과거근거와 구분. 기존기기122파일/사용자DB·진단·권한·Console 원상유지, 같은브랜치/HEAD·미커밋 보존. 성능미달/의미보류/동적입력미확인 유지, 30분·TRP003·성능재시험·커밋/push/PR 없음.

2026-11-10 1번 TRP003 실제 부팅: [10종 매핑·BOOT횟수·직접명령·보존](docs/devlog/2026-11-10-trp003-reboot.md). 최종 APK에서 실제adb reboot2회, BOOT_COUNT32→33→34·boot_id3개상이 확인. 제품 MonitorBootReceiver→제품서비스공통경로의격리대상에서 UI/runner전 요청/서비스/입력/수집시작각1·운행저장timer1·checkpoint10종/전체일치·새입력전UNKNOWN/공백제외. 실제알림중지뒤둘째BOOT 요청/서비스0·중지DB동일. instrumentation종료USER_REQUESTED는보이는확인화면으로새의사를받고제품보호로직유지. **최종자동746(333+413), 실패/오류/skip0·debug/test APK·release manifest/Kotlin 성공**, 실제부팅은최종APK검증. 기존기기134파일/사용자DB·진단·권한보존, Console입력/설정복구·출처/시각메타는변경분리. arm제거·서비스없음·같은브랜치/HEAD·미커밋유지, 커밋/push/PR없음. SIG001/002미달·STR환경·동적입력/의미/팀연결대기유지.

2026-11-10 1번 선별 저장 검토: [커밋 범위·일치 대조·제외 원본](docs/devlog/2026-11-10-role1-publish.md). 검증 코드211개 해시·746개 시험 XML·실제 재부팅 APK SHA 동일을 확인해 재시험하지 않았다. 신호/미지원 처리 bb657c1(15개), 감시/부팅 격리 e9815b6(14개)와 문서/필요 근거를 선별 저장한다. 빈 동기화marker·단독trace PID는 로컬 보존/커밋 제외. 일반 develop-jdw push만 진행하고 main/PR은 조회만 한다. SIG001/002 미달·동적입력/의미/환경/다른 담당 연결 대기 유지, 1번 전체 완료 아님.

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

2026-11-07: main 합치기, 신호 변환 함수, 4번 `AgeBand`·`VulnerableTimeRange`로 교체(취약 시간대 값은 `VulnerableTimeDefaults` 한 곳), 팀 `EngineConfig`에 2번 묶음 4개 연결, 고칠 것 1(딴짓 1분 칸 정렬)·2(큰 수정 기준 하한 2/분 [제안값]), app 엔진 출력 연결기(1번 `DrivingAssessmentSource` 구현). `:engine:test` 308개(2번 125, 11/7 00:24 main 기준) + 연결기 8개 통과, 돌연변이 11개 모두 잡음. 데모 시나리오 형식 2번 제안 작성. Pleos 메일 답변(11/5)을 [회의록](docs/meetings/2026-11-05_Pleos_메일답변.md)으로 정리.

- 남은 작업: PR 리뷰·머지, 1번 앱 조립에서 연결기 띄우기·'15분 뒤 첫 확인' 입력·TTL 3초 초과·`RestRecoveryResetRequest` → `runner.onRestCertified()`(1번), 시나리오 형식 확정(1·5번), TC 시트 기록(11/10~12).
- **팀원별 주말 할 일과 먼저 읽을 곳**: [docs/fatigue-engine/README.md](docs/fatigue-engine/README.md#팀원이-먼저-볼-것-주말-117119)
- 설명 문서: [docs/fatigue-engine/](docs/fatigue-engine/README.md) — 구조·알고리즘·해석·동작 예·시험·작업 기록·팀 전달·일정·2차 확장 설계·시나리오 형식 제안.

### 3번 — 음성·실내 (`feat/voice-*`)

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| VoiceAgent (TTS) | `app/.../voice/` | 대기 |  |
| SpeechAgent (STT) | `app/.../voice/` | 대기 |  |
| CabinMonitor | `app/.../cabin/` | 대기 |  |
| CheckManager | `app/.../voice/` 또는 `app/.../cabin/` | 대기 |  |
| InterventionManager | `app/.../signal/`, `engine/.../intervention/` | 인계 대기 (기존 코드·시험·기록 보존) |  |

### 4번 — 예보·쉼터·휴식 (`feat/rest-*`)

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| ForecastEngine | `engine/.../forecast/` | 완료 | #7 |
| RestAreaRepository / NaviBridge | `app/.../navi/`, `app/.../data/` (선택 규칙은 `engine/.../rest/RestAreaSelector`) | 진행중 |  |
| ClimateController | `app/.../climate/` (규칙은 `engine/.../climate/`) | 진행중 |  |
| RestSession / NapSession | `app/.../session/` (휴식 인증·보상 규칙은 `engine/.../rest/`) | 진행중 |  |
| RewardLedger | `app/.../session/`, Room은 `app/.../data/RestRecordDatabase` | 진행중 |  |

### 5번 — 화면·시연 (`feat/ui-*`, `demo/*`)

2026-11-12 요청 목록 점검: 제품 데모의 경로 안내 중 실제 경유지 반영, 취소·파워냅·휴식 인증·회복·스탬프, 통풍 제어기 켜짐/원복, 딴짓 튐형 기록, 실제 첫 실행 권한 거절/재허용, 실제 주행 제한 잠금/해제를 확인했다. 엔진346개(기존 결과 재사용)·앱453개, 실패/오류/skip0·제품/시험 APK 빌드 성공. 음성 코드는 기존 방식을 유지하며 실제 발화·응답 시간·의도 인식 확인 1건이 남는다. [최신 결과·원본·재현](docs/devlog/2026-11-12-role5-final-checks.md).

2026-11-11 제품 실행 후속: 데모에 기존 개입 매니저·ON_DEVICE 음성·내비 요청·운전자 버튼 대체·파워냅 알람을 연결했다. 사용자 지시로 기존 음성 대상·대화 정책은 유지했다. 전체 엔진346개(기존 결과 재사용)·앱452개, 실패/오류/skip0·debug/기기시험 APK 빌드 성공. 기기에서 정상0단계·파워냅 선택 시작/알람 상태/기상, 졸음3단계·제안 취소·휴식 인증/회복·스탬프1개를 확인했다. 내비는 요청 전달 뒤 반영 확인 실패가 남아 계측 중이며 실제 발화·통풍 원복·권한/주행 잠금 전체 확인은 별도다. [최신 연결·검증·남은 항목](docs/devlog/2026-11-11-role5-connected.md).

2026-11-11: 제품 런처를 서비스 관찰 화면에 연결하고 6종 JSON의 제품 앱 재생·시작 시각·필수 신호·정차/전원 구간을 반영했다. 기기에서 재현한 데모 로드 메모리 부족, 주행 중 데모 종료 막힘, 모드 전환 후 DEMO 표시·그래프 잔상을 수정했다. 엔진346개·앱440개 단위 시험 및 debug APK 빌드 성공(엔진 기존 결과 재사용). 후속에서 종료 무효화 오경고와 진행 방향을 수정하고 단위 시험은 엔진346개·앱443개를 확인했다. 실제 음성·내비 실행·설정 입력과 영상은 남아 있으며 6종 기기 결과는 최초/후속을 나누어 기록한다. [실행·담당별 인계·한계](docs/setup/ROLE5_REHEARSAL_HANDOFF.md).

| 모듈 | 위치 | 상태 | 이슈 |
| --- | --- | --- | --- |
| Compose 화면 | `app/.../ui/` | 진행중(제품 연결·주행 제한 구현) | 실제 발화 확인·실제 모드 일부 설정 연결 남음 |
| 데모 시나리오 6종(JSON) | `data/scenarios/` (합성은 `src/tools-py/`) | 진행중(6종 기기 재생 검증) | 2번 기대 시점 문서 수신 대기 |
| 쉼터 CSV 전처리 | `data/rest_areas/` | 대기 |  |
| 시연 영상·README | `docs/design/시연영상_150초_촬영대본.md` | 대본 초안·녹화 대기 | 실제 실행 연결 후 촬영 |
| DemoPlayer | `app/.../demo/`, `engine/.../time/` | 진행중(제품 앱 재생 연결) | 요청 배속과 실제 완료 시간 구분 |

엔진 쪽 패키지 경로(`engine/.../intervention`, `engine/.../rest`)와 그 패키지를 쓰는 app 쪽 클래스의 정확한 경계는 1주차(1·2·4번)에 확정합니다.

## 이번 주 막힌 것 (블로커)

-

### 2026-11-06 — 1번 60배 Hub 성능 후속

- develop-jdw 미커밋 작업·Room 논리 데이터 보존, 브랜치 전환/커밋/push/앱 삭제/초기화 없이 작업.
- 수신/진단/RateMeter 계측·할당 감소, 짧은 묶음/잔여 즉시 양보 재생, 원본3채널 감사, 종료 trace와 지속 저장 상태 추가.
- engine148 + app202 =350개 및 assembleDebug 통과. 같은 창 있는 AVD에서 일반60배/질문취소→60배 각각2회 재검증, 원본 채널별18000·누락/중복/역행0.
- **예상외null0·완전발행 미달**: 최종 실제null발행79/156/135/147회, deadline누락5321/5199/5123/5131회. [계측·실패 실험·직접 확인 기록](docs/devlog/2026-11-06.md).

2026-11-06 후속 전달·스케줄 검증: 완전 관측 인계·로드 시 원본 준비·짧은 묶음 양보·명령 기아 방지를 추가했다. 이전350개 검증 이후 현재 **engine149개+app227개=376개·assembleDebug 통과**를 확인했다. 기본 실행 통로는 `DEFAULT_LANE`이며, 같은 AVD의 **실제 예상외null0 목표는 미달**이다. 조건별 측정값·남은 한계·직접 누를 순서는 [후속 개발 기록](docs/devlog/2026-11-06-demo-transport.md)을 따른다. 기존 미커밋 작업과 Room 데이터를 보존했으며 다른 담당 기능을 새로 통합하지 않았다.

2026-11-06 시간축 구조 검토: 기존376개 변경·장기trace·Room을 보존하고, 같은60초/600원본의1·10·60배를 비교했다. 1배는null/deadline0,10배는null0·deadline24/20,60배는실제처리완료2,280/1,415ms·달성26.32/42.40배였다. 완료watermark로Hub시계만늦추는안은최신성·시한·원천Hz를왜곡하는반례가있어기본적용하지않았다. 생산시간축/정책은유지하고요청/목표/실제완료배속표시·독립경과계측을추가했다. 기존376개포함 **385개(engine149+app236)·assembleDebug통과**, 최신성/완전발행목표는여전히미달이다. [검토·기기결과·직접확인](docs/devlog/2026-11-06-demo-timeaxis.md)·[요구의시간축구분](docs/devlog/2026-11-06-demo-timeaxis-requirements.md).

2026-11-07 SIG_001~017 SDK 수집 보완: 기존 385개·Room·미커밋 작업을 보존했다. API 지원과 의미 보류를 분리하고, 권한 상실/미지원 뒤 늦은 수신·시각 역행·통풍 SDK 범위를 보호했다. Console의 좌석 온열/냉각과 실제 통풍을 별도 진단으로 확인했다. 기존 385개 포함 **396개(engine149+app247)·assembleDebug·release manifest 확인**. 조향 물리량/착석 UNKNOWN 손실은 미해결이며 통풍1단계 입력·활성 내비 경로와 일부 지연 TC는 미확인이다. SDK Hub 73.085초 감사의 최대 간격147ms·110ms 초과20회로 SIG_001 시간 TC도 미달이다. [보완·기기 결과·직접 버튼 순서](docs/devlog/2026-11-07-signal-sdk.md). 다른 담당 통합·60배 재작업·브랜치 전환·커밋·push·초기화는 하지 않았다.
