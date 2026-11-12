# CURRENT_HANDOFF — 다음 Codex 계정용

**2026-11-12 게시 요청(현재 우선):** 사용자가 현재 로컬을 정리해develop-jdw에commit/push·main대상PR 생성을 요청했다. 이전 날짜의 ‘커밋/push 없음’은 당시 기록이며 이번 게시를 막는 지시가 아니다. [로컬 구현·데모/완성품의 남은 일](ROLE1_DEMO_PRODUCT_STATUS.md), [게시 기준·검증 재사용·제외·통합 주의점](../devlog/2026-11-12-role1-publish.md)을 먼저 읽는다. 최신main e44aae7의5번 UI는 아직 이소스에 미통합이며 검토용초안PR로 공유한다. APK 생성/서명/설치 중단은 유지한다. DB·진단·기존작업 보존, 임시marker/PID파일2개는 로컬에만 남긴다. 최종HEAD/원격일치·PR상태는GitHub와 `git status --short --branch`, `git log -1`로 확인한다. main직접push·merge·auto-merge는 요청하지 않았다.

**2026-11-12 원격 비교·디버그 정리(현재 우선):** `git fetch origin` 후 GitHub develop-jdw는f59176f, 로컬HEAD9144cfd(3커밋 앞섬), 최신main은e44aae7(PR20·5번 UI, 로컬보다6커밋 앞섬)으로 확인했다. main을 로컬에 병합하지 않았다. 5번 제품 경로와 로컬 제품 데모의 API/시간축/복원을 대조했으며 통합 검증은 여전히 필요하다. 5번 원본은45분10초로 늘어 아래30분 길이 제한은 **현재 로컬 원본의 상태**로 구분한다. 미사용 DebugButton과 debug application의 개발검증 label override만 제거했다. src/debug 도구·시험·DB·이전 미커밋은 보존, release manifest debug 구성요소0이다. 앱461개 재실행 통과·manifest 처리 성공, APK 생성/설치 없음. [원격 대비 변경·겹치는 기능·삭제/보존·다음 순서](../devlog/2026-11-12-develop-compare-debug-cleanup.md)를 우선 읽는다. 다음은5번 제품 경로에1번 예약/품질/재실행 계약 이관 여부를 확인하는 통합이며, 이전 문서만 보고 JSON을 다시 늘리거나 중복 화면을 즉시 삭제하지 않는다.

**2026-11-12 현재 진행 지점 — 제품 데모:** 사용자 요청 ‘데모가 동작하도록 필요한 일부터 진행’에 따라 간트1번 task53·task32만 보완했다. 제품 JSON 구간/전체1·10·60배 선택(기본 원본), 2번이 계산한 첫 확인 예정→제품 파이프라인→1번 매니저 예약·중복·취소·저장(v2/v1읽기호환)을 구현했다. **앱461개 실제 실행 통과**, 같은 날 이전 엔진351개 재사용=812개. 원본6종 전달·기존 판단/개입의 JVM 확인이지 실제 음성·기기·60배 성능 확인이 아니다. **APK 생성/설치 중단 유지**로 새 화면은 기기에 미반영. 출발 피로 원본30분보다 첫 확인 예정30분10.1초가 길어 원본 담당 연장이 필요하며 학습 이력 조건도 별도다. [최신 변경·실행·8개 변경/4개 신규·보존](../devlog/2026-11-12-product-demo-usable.md), [다음 직접 순서](ROLE1_DEMO_RUNBOOK.md)를 우선한다. engine·app/fatigue·voice·원본JSON은 이번 바이트 동일. develop-jdw/HEAD9144cfd·기존 미커밋 유지, 커밋/push/PR·adb/DB 조작 없음. 아래 ‘실행 코드 수정 없음/787개’는 직전 기록이다. 이번 task32 연결은 간트1번 범위이며 과거 원복한2번 컨디션/판단/음성 수정18개를 복원한 것이 아니다.

**2026-11-12 최우선 사용자 지시:** APK 생성·설치·서명·제출 준비는 사용자가 중단했다. 아래 과거 APK 다음 작업은 재개하지 않는다. 졸음 규칙 요청도 ‘1번 입력·Hub 연결만 보완’으로 범위를 확정했다. 실행 코드는 수정하지 않고 Hub→기존 엔진 계약9개를 추가했다. 이번 엔진351개·앱436개=787개 실제 실행 통과, 기존app/engine 소스395개 바이트 동일이다. SDK 조향 의미 보류null·원천Hz 미확인·기존 지연 미달은 남는다. 기기·DB 조작/커밋/push/PR 없음. [이번 범위·시험·남은 제한](../devlog/2026-11-12-role1-fatigue-input.md)을 우선 확인한다.

**2026-11-12 최신 사용자 요청 범위:** 사용자가 지정한 SIG·제품 JSON 데모·재생 표시·시동 끔 대체·README/release만 보완/검증했다. 간트 task53 DemoPlayer와 task73 README/release는 resource0(1번)이다. SDK 즉시 재조회(정기 예약 보존)와 1번 데모 화면 상태 구독만 수정했으며, 다른 담당 판단·개입·음성·다른 UI는 변경하지 않았다. 최종778개·debug/test/release/vital 성공. 전체Lint는 기존 음성 권한 오류1건 유지. 엄격 발행131ms/110ms·내비1초 초과·SDK 의미/입력 미확인·팀 서명은 미완료다. 아래 원복/773개 기록은 당시 이력이며 이번 최신 요청은 이 문단과 연결 기록을 우선한다. [변경·기기 결과·남은 항목](../devlog/2026-11-12-role1-listed-items.md).

### 이번 요청 종료 지점 — 2026-11-12

- 현재 브랜치 develop-jdw / HEAD9144cfd 유지, 미커밋 작업 보존. 이번 fetch·commit·push·PR 없음. 로컬 추적 ref 기준 origin/main과 같고 origin/develop-jdw보다3커밋 앞서던 상태를 유지한다.
- 이번 변경/정확한 소스 경로·778개·기기 결과·미해결 표는 [현재 검증 기록](../devlog/2026-11-12-role1-listed-items.md)이 우선한다. 과거 원복한 첫 확인/컨디션/판단/음성 수정18개를 다시 적용하지 않는다.
- 팀 서명 키 경로는 사용자에게 질문했으나 아직 제공되지 않았다. 비밀번호를 채팅에 요청하지 않는다. `../../README.md`의 ‘제출 APK·서명’ 절을 따라 unsigned APK를 팀 담당이 로컬 서명하고 별도 검증 기기에서 설치해야 한다.
- 현재 debug 설치 SHA a5348f373aabf2533babfc12fdc3fe538d8de7871c6bcc1c16282257a9c4a03d, unsigned release SHA 7a9fd6556711a90fd80b4eb29982f1e9063dbdafd66fcdcbfda676243ffc2437. APK는 Git 제외.
- 앱/서비스/SDK 없음·타이머 없음·AVD P/0 유지·추가 forward 없음·Gradle 데몬 종료. 기존 기기213파일 바이트 동일, 시험 소유7파일만 원본 복구. 새로운 시험 파일25개는 보존. 백업 DB 사본 무결성ok.
- 다음 시작은 읽기 전용 `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git diff --stat`로 실제 변경부터 확인한다. 이미 통과한 시험을 이유 없이 반복하지 않는다. 새 구현을 시작한다면 반드시 이번 사용자가 요청한 1번 범위인지 확인한다. 남은 성능·SDK 의미·입력 미확인을 시간/값 조작이나 테스트 성공으로 완료 처리하지 않는다.


**2026-11-12 사용자 범위 정정:** 이번 추가한 첫 확인/컨디션/정책·UI·음성·Lint 수정18개 Kotlin 파일을 모두 수정 직전으로 복구했다. 이전 미커밋 작업은 보존했다.781개·제품기기3개·Lint성공은 원복 전 코드의 과거 근거이고 현재 상태로 확대하지 않는다. 앞으로 수정은 SIG·TripRules·저장/복원/부팅/감시 서비스에 한정한다. [감사 기록과 원복 대조](../devlog/2026-11-12-role1-audit.md).

## 최신 재개 결과 — 2026-11-11 23:11 KST

**계정 전환 후 인계를 읽고 재개했다. 아래 21:15 기록은 당시의 보존 원본이며, ‘최종 빌드 필요/마지막 engine 시험 미실행/제출 파일 미복사’는 이제 해소됐다.** [재개 최종 기록](../devlog/2026-11-11-resume-final.md)과 [검증 JSON](../devlog/evidence/2026-11-11-resume-final/validation.json)이 최신이다.

- `develop-jdw` / HEAD `9144cfd7eee1b078b6a988c7692a97792e4f4407` 유지. fetch 후 origin/main과 같고 origin/develop-jdw보다 3커밋 앞섬. 기존 미커밋 코드 보존, 새 커밋·push·PR 없음.
- 최종 소스로 `:engine:test` 351개·`:app:testDebugUnitTest` 422개를 **이번에 실제 실행**해 총 773개 통과, 실패·오류·skip 0. debug/test/release APK·release Lint vital 성공(3분46초). 마지막 close 변경·24시간 만료 경계 시험 포함.
- 새 debug APK는 이전 실제 제품3건 검증 APK와 비교해 `assets/README.md`, `assets/demo.schema.json`만 다르다. 실행 코드·화면 리소스·원본6종 JSON은 동일하다. 이전 실제 음성·버튼·새PID DMO 근거는 **재사용**했고 이번 기기시험 횟수는 0이다. 같은 시연을 이유 없이 반복하지 않았다.
- 최신 debug APK 교체 설치 성공, 설치된 base APK SHA `f2c1ddc8304920ca6e6f163551a070e049fce17991e3bc6587bbdcf0b1a44b3e`. 기존 기기190파일 전부 바이트 동일·사용자 DB3개 논리행/무결성 동일·권한 동일. 앱 PID 없음, 감시 서비스 없음, forward 없음. AVD 유지. 빌드 Gradle daemon 정상 중지.
- 최종 release unsigned를 `apk/sleepissue_AwakeDrive_release-unsigned.apk`에 복사했다(Git 제외). SHA `4b1425c51ee4f1a25bc94339f27ce3b6b980859fd658cf497347a48ce0ba792d`. 팀 키/signingConfig가 없으므로 서명·release 설치/첫 화면 시한은 **미완료**다. debug 서명을 제출 서명으로 대신하지 않았다.
- README·APK 안내·현황표·데모 가이드의 최신 설명을 정리했다. 공급자/성능 문제, DMO006 평소3회 fixture, 내부 학습창 복원, REAL microphone FGS·CabinMonitor·녹음 fallback은 여전히 남는다. 이전 보고를 1번 전체 완료로 확대하지 않는다.
- 이번 개인 백업·빌드/서명 로그: `/home/a/.local/state/awakedrive-handoff/2026-11-11/resume-20261111-230500/`. 앱 원본/DB tar는 민감한 개인 보존 자료이므로 공유·커밋하지 않는다.

**다음 작업:** 이미 통과한 전체 빌드를 반복하기 전에 현재 diff/새 main 변경부터 확인한다. 팀 서명 키가 준비되면 README 명령으로 서명·검증하고 기존 사용자 앱을 삭제하지 않는 별도 기기에서 release 설치·기동을 확인한다. 병행 가능한 다음 기능 검증은 DMO006의 기준선3회·15분 판정 조건과 2번 export/import 계약 확인이다. 공개되지 않은 SDK 입력·해결되지 않은 지연은 근거 없이 값·시한을 바꿔 통과시키지 않는다. 기존 금지사항은 그대로다.

---

작성: **2026-11-11 21:15 KST**. 작업 위치: `/home/a/StudioProjects/sleepissue`.

**사용자가 계정 전환을 위해 작업 정리·인계를 요청해 중단한 상태다. 미완료 작업이 있다. 커밋·push·PR은 하지 않았다. 이 문서만 읽어도 재개할 수 있도록 현재 사실과 다음 명령을 구분했다.**

## 1. 브랜치·HEAD·원격

| 항목 | 정리 시 확인값 |
| --- | --- |
| 현재 브랜치 | `develop-jdw` |
| HEAD | `9144cfd7eee1b078b6a988c7692a97792e4f4407` |
| origin/main 대비 | `HEAD...origin/main` = `0 0` (마지막 fetch 기준 같음) |
| origin/develop-jdw 대비 | `3 0` (로컬이3커밋 앞섬) |
| 실제 origin URL | fetch/push 모두 `https://github.com/sleepissue/sleepissue.git` |
| 이번 Git 조작 | `git status`, `git fetch origin`, `git merge --ff-only origin/main` |
| 병합 전후 | `f59176f → 9144cfd` (fast-forward, 새 병합 커밋 없음) |
| stage/commit/push/PR | 없음. 모든 수정은 작업 트리에 미커밋 상태 |

앞선3커밋은 `82f8876`(PR #18 merge), `8d045ae`(4번 앱 조립), `9144cfd`(PR #19 merge)다. 문서의 SSH 원격 표기와 실제 HTTPS 설정이 다르지만 이번에는 원격 설정을 바꾸지 않았다. 새 계정 인증이 필요해도 독립적인 로컬 검증을 멈출 이유는 없다.

**처음부터 있던 미추적 파일2개를 보존했다. 삭제하거나 이번 변경으로 오해하지 말 것:**

- `docs/devlog/evidence/2026-11-09-plt013/navigation-confirmed.marker`
- `docs/devlog/evidence/2026-11-09-role1-remaining/perfetto-start.txt`

## 2. 목표·원래 사용자 요청·금지사항

원래 요청은 남은1번 기능과 제출 준비다.

1. 제품 앱에서 JSON6종 선택/재생. 원본/재생 사본의 필드 손실 확인, 회전 거리·실내온도·초기 운행시간 보존, tripPreset 계약과 APK 배포 경로 확정. DEMO도 Hub/판단 경로 사용, 점수/단계 직접 주입 금지. 실제 음성 시작부터 판정 종료까지1배속, 종료/실패/취소 뒤 원래 배속. 주행 제한 유지.
2. 실제 재생 상태에 연결된 ‘데모 재생 중’/일시정지 표시와 모든 종료 경로 정리.
3. DMO_005: 상태 저장→앱 종료→새 실행→데모 시계22분 이동→저장 상태 복원→휴식 인증1·연속0. REAL DB 분리, 실제 OFF→ON/STR 성공으로 표시하지 않음. 최근24시간 등 보존 확인.
4. 조향 물리량/부호·착석 Boolean/UNKNOWN 공식 근거. 크루즈 실제 활성·위치 이동/heading·현재온도 변경·회전/TBT의 공개 시험 경로와 실제 가능한 검증.
5. 기존 조향 약130ms/110ms, 차속 전체 하한219ms/200ms, 내비1초 초과 미달 기록 보존. TTL/timestamp/측정 기점 변경으로 통과시키지 않기. 원인 없는 반복 성능 측정으로 기능 구현을 미루지 않기.
6. README, debug/release 빌드·설치/실행. 제출 서명이 없으면 unsigned와 서명 필요를 명확히 구분.

최신 main PR #19에는 이미 제품 화면→감시 서비스→Hub→판단/개입/음성/휴식 연결이 있다. **‘연결 없음’을 전제해 중복 구현하지 말 것.** 기존 모듈의 누락·조립 결함만 보완한다.

공통 제한: 한국어 답변/주석, 영어 식별자. 변경 전 `AGENTS.md`, `CLAUDE.md`, `PROGRESS.md`, 기획서, 최신 간트·SRS를 읽는다. 기존 미커밋 작업/DB/진단 원본 보존. **reset/clean/앱 삭제/pm clear/AVD Wipe 금지. 커밋/push/PR 금지. 키·비밀번호·DB·SDK 바이너리 커밋 금지.**

기획 단일 출처: `docs/plan/깨어있는드라이브_상세기획서_v4_현재기능.md`. 간트 `docs/plan/AwakeDrive_일정_v1.gan`, SRS `docs/design/AwakeDrive_SRS_TC_v0.2.xlsx`. 이전 현황의 ‘제품 연결 대기’는 PR #19 이전 사실이다.

## 3. 수정·추가 파일과 이유

아래는 이번 미커밋 변경이다. 원본6종 JSON과 기존 재생 사본6종은 수정하지 않았다.

### 제품 데모·시간·저장

| 파일 (`src/android` 기준) | 변경 이유 |
| --- | --- |
| `app/build.gradle.kts` | `../../data/scenarios`를 assets로 직접 묶음. PC 절대경로나 수동 복사본 없이 APK `assets/<id>.json` 사용 |
| `app/src/main/AndroidManifest.xml` | 제품 `ProductDemoActivity` 등록, exported=false 유지 |
| `app/src/main/java/com/sleepissue/awakedrive/demo/DemoJsonLoader.kt` | 팀 원본 formatVersion/scenarioId/startAt/tMs를 기존 정규 형식으로 엄격 변환. tripPreset 검증/보존 |
| `.../demo/DemoScenario.kt` | 선택적 DemoTripPreset을 시나리오 계약에 보관 |
| `.../demo/ProductDemoController.kt` **신규** | Player→같은 VehicleSignalHub→TripTracker/MonitoringPipeline 조립. 실제 SDK 입력 우선 선택, 수명 정리, 쉼터 모의/P단, 일관된 디스크 checkpoint, 재실행+22분 복원/중복 방지 |
| `.../demo/DemoStorageContext.kt` **신규** | Room/SharedPreferences 이름을 `demo_<UUID>_...`로 격리. REAL 파일에 쓰지 않음 |
| `.../demo/DemoVoiceSpeedGate.kt` **신규** | 실제 음성 시작에서 동기1배속, 같은 요청의 완료/실패/취소에서 해제. 이전 요청의 늦은 종료 차단 |
| `engine/src/main/java/com/sleepissue/awakedrive/engine/demo/DemoTripPreset.kt` **신규** | 초기 카운터만 주입하지 않고 운전 구간·첫 D 관측을 가진 정밀 운행 상태 구성 |

`...`는 `app/src/main/java/com/sleepissue/awakedrive`다. Player의 기존 로직과 판단 모듈을 재작성하지 않았다. JSON 원본은 상대 시각, 제품 판단/예보는 `startEpochMs + 상대 시각`을 사용한다. 음성 반응 시간은 실제 단조 시계다.

### 기존 파이프라인·음성·제품 화면 보완

| 파일 (`app/src/main/java/com/sleepissue/awakedrive` 기준) | 변경 이유 |
| --- | --- |
| `service/MonitoringPipeline.kt` | REAL 기본 계약 유지, DEMO source/격리 restContext/실제 음성 lifecycle 인자 추가. 기존 AwakeDrivePipeline 재사용. 종료 시 check.close()로 예약 마이크 세션부터 정리 |
| `session/RestModule.kt` | demoMode에서 실제 경유지/통풍 제어를 하지 않음. 격리 context로 휴식 DB/설정 사용 |
| `session/RestStateStore.kt` | 시동 끔 직전 작은 상태를 디스크까지 flush하는 동기 경계 추가 |
| `ui/ProductDemoActivity.kt` **신규** | 제품6종 버튼/실제 배지/일시정지·중지·초기화/모의 P단·앱 종료 버튼. 표시만200ms로 제한해 프레임마다 재구성하는 불필요한 UI 작업 감소 |
| `ui/DrivingRestrictionGate.kt` **신규** | CarUxRestrictions 리스너. 미확인/오류는 제한 상태. 주행 중 설정/리포트/데모 차단 |
| `ui/AppNavigation.kt` | 기존 단계 강제 전환 테스트 버튼을 실제 JSON 데모 진입점으로 교체. 감시 결과/주행 제한 인자 연결 |
| `ui/DrivingPreviewActivity.kt` | 이미 있는 서비스 시작 연결 유지. 실제 runner/manager 상태를 제품 화면에 전달, DMO pending이면 제품 데모 복원 화면으로 진입 |
| `voice/VoiceAgent.kt` | 출력의 실제 시작 callback을 받을 수 있는 speakWithStart 계약 추가 |
| `voice/PleosTtsOutput.kt` | Pleos EventListener.onStart에서 시작 전달. ON_DEVICE 유지 |
| `voice/VoiceCheckPort.kt` | 확인 시작/종료 lifecycle, 실패/취소 복귀, close 시 active 요청/예약 확인 정리, 늦은 완료 차단 |
| `voice/PassengerCheckFlow.kt` | 종료된 요청의 늦은 TTS 완료가 마이크 확인을 시작하지 않도록 shouldContinue 확인 |

### 시험·문서

| 파일 | 이유 |
| --- | --- |
| `engine/src/test/.../engine/demo/DemoTripPresetTest.kt` **신규** | 초기 이력/첫 프레임 유지·22분 뒤 누적/ID/단계/기준선 참조 유지·잘못된 preset 거절. **마지막 추가1건(24시간 만료)은 아직 실행 전** |
| `app/src/test/.../demo/ProductScenarioContractTest.kt` **신규** | 원본6종 모든 행의 시각/조향/차속/회전/온도 및 preset 보존, 잘못된 팀 형식 거절 |
| `app/src/test/.../demo/DemoVoiceSpeedGateTest.kt` **신규** | 완료/실패/취소 복귀, 오래된 질문 종료 차단 |
| `app/src/androidTest/.../data/ProductDemoDeviceTest.kt` **신규** | 실제6종 제품 버튼과 배지/정지/초기화. 다중 창/Compose 캐시 문제를 clearCache+제품 창 선택으로 해결 |
| `app/src/androidTest/.../data/ProductDemoWiringTest.kt` **신규** | 실제 제품 조립/Hub/Room 저장·새 인스턴스 복원·최근24시간 유지·중복 방지 |
| `app/src/androidTest/.../data/ProductDemoVoiceDeviceTest.kt` **신규** | 실제 ON_DEVICE TTS 시작→1배→실제 판정 종료→10배. 점수/단계/응답 결과 주입 없음 |
| `README.md` | 빌드/설치/제품 버튼 순서/서명/한계. **최종 재빌드 후 산출물 부분 추가 검토 필요** |
| `data/scenarios/README.md`, `demo.schema.json` | 원본/정규 형식/기존 사본 차이와 tripPreset 문서화. **schema 변경은 마지막 APK 이후** |
| `docs/setup/ROLE1_DEMO_JSON.md` | 기존 계약을 유지하는 팀 원본/제품 배포 경계 설명 |
| `docs/setup/ROLE1_COMPLETION_STATUS.md`, `PROGRESS.md` | PR #19 현재 연결과 이번 결과를 과거 ‘대기’ 기록과 분리 |
| `docs/devlog/2026-11-11-product-demo-release.md` **신규** | 실제 진행·원본 차이·실패/성공·한계·담당 요청 |
| `docs/devlog/evidence/2026-11-11-product-demo/` **신규** | 입력/원본 비교, 실제3건, 음성 시간, 새PID DMO, 보존 대조·화면·초기 실패 기록 |
| `docs/setup/CURRENT_HANDOFF.md` **신규** | 이 인계 문서 |

## 4. 완료된 것 / 남은 것

### 구현·해당 조건 확인 완료

- 원본6종이 제품 APK assets에서 읽히고 기존 Hub/판단 파이프라인으로 재생된다. 기존 UI의 강제 점수/단계 시연이 아니다.
- 원본은 formatVersion/startAt/tMs, 기존 사본은 schemaVersion/startEpochMs/t다. 기존 사본에는 졸음 tripPreset 누락, 나머지5종의 회전/실내온도 누락이 있다. 제품은 원본을 읽어 손실을 피한다.
- 재생/일시정지 배지, 정지/초기화/종료/실패/화면 이탈 정리. 새 파일 로딩 중 오래된 재생 배지를 남기지 않도록 정리했다.
- 실제 TTS onStart부터 확인 종료까지1배, 종료 후 원래 배속. 취소/실패는 JVM 회귀, 실제 성공/무응답 판정 경로는 기기 확인.
- DMO 저장 시 데모 Clock을 명시적으로 정지하고 **기존 Tick을 실제 평가**한 뒤 운행/개입을 같은 시각으로 저장한다. policy timestamp를 덮어쓰지 않는다. 기존 codec의 ‘평가 시각=저장 시각’ 계약을 지킨다.
- 실제 제품 버튼으로 프로세스 종료→새PID→+22분 복원. 인증·보상1행/AWARDED·연속0·최근24시간8271초 확인. 실제 시동/STR은 아니다.
- 별도 저장소/설정 사용, REAL DB3개의 모든 논리 행 보존 확인.
- 공식 Maven Vehicle2.3.2 주석/구현 재확인: 전륜각·왼쪽 음수와 운전대각 충돌, Seat Boolean의 UNKNOWN 손실. 조향비 추정/Boolean 반전 없이 null 보류 유지.
- 공개 Console Full autonomous 입력에서 실제 SDK ACTIVATED/true→Hub true 확인. Lateral에서 ENABLED/false→Hub false. 200ms 시한/물리 차량 검증은 아님.

### 구현 중/미완료·범위 밖을 완료로 세지 말 것

1. **마지막 음성 close 변경 이후 JVM 전체와 release 재빌드 필요.** 이후 JSON schema 설명 변경과 engine의24시간 만료 경계 시험1건도 추가됐다. 이 최신 상태의 최종 전체 검증/제출 APK 복사는 아직 안 했다.
2. 제출 signingConfig/팀 keystore 없음. 최신 release 파일도 unsigned이며 마지막 음성 close 이전 산출물이다. 임의 debug 서명을 제출용으로 만들지 않았다. `apk/`에 최종 제출 파일을 복사하지 않았다.
3. README 최종 빌드 결과/해시·서명 상태 갱신 필요. 현재 내용은 준비 초안이며 이 문서가 정확한 산출물 기준이다.
4. DMO 복원은 정밀 운행·개입 checkpoint·휴식 상태·기준선 **참조** 보존이다. FatigueBridge 내부 학습 창을 직렬화하는 API는 연결하지 못했다. 복원 화면은 정지 상태이며 자연스럽게 새 감시를 재개하는 완전한 학습 창 복원으로 주장하지 않는다.
5. DMO_006의 평소 기준선3회 사전조건과15분 출발 피로 TC 전체는 미검증. 대화 JSON의 착석만으로 실제 CabinMonitor 말소리 감지 검증이라고 하지 않는다.
6. REAL 감시의 백그라운드 microphone FGS, 실제 녹음 fallback cue(현재 emptyMap), CabinMonitor 조립은 별도 보완 대상이다. 이번 제품 데모는 화면 이탈 시 마이크/재생을 정리한다.
7. 현재온도 변경·NaviHelper 연속 이동/heading·실제 교차로/TBT 감소/자연 도착 입력은 미확보/미검증이다. 고정 위치/현재온도 READ, 과거 활성 경로/TBT 확인과 구분한다.
8. 기존 조향 약130ms/110ms, 차속 전체 하한219ms/200ms, 내비 터치→Hub1초 초과 미달과60배 성능 미달은 남는다. PC/AVD·공급자 원인은 미확정. TTL/원천·수신 timestamp/측정 기점을 바꾸지 않았다.

## 5. 이번 실제 시험·빌드 / 과거 재사용 구분

전체 로그는 **내구성 개인 백업** `/home/a/.local/state/awakedrive-handoff/2026-11-11/`와 `/tmp/awakedrive-20261111/`에 있다. 새 계정에서는 전자를 우선 사용한다.

| 로그/근거 | 실제 결과 | 범위·시점 |
| --- | --- | --- |
| `build-final.log` | JVM engine350+app422, 실패/오류/skip0. debug/test/release/Lint vital 성공(2m6s) | 그 당시 소스. 최종 음성 close 이전 |
| `final-regression.log` | engine350+app422 결과0, debug/test/release 성공(2m58s) | DMO Clock/Tick/표시 샘플링/Default 작업 통로 등 포함. **그 뒤 check.close 등 소수 변경 있음** |
| `voice-final-debug.log` | debug/test APK 성공(18s) | 마지막 app Kotlin 변경(check.close 등) 포함. 기기에 설치해 다음3건 실행 |
| `product-final-device.log` | **80.937초, OK(3 tests)** | ProductDemoWiringTest + ProductDemoDeviceTest + ProductDemoVoiceDeviceTest. 단순 모의 SDK 시험이 아님 |
| `actual-voice-speed.json` | 실제 TTS onStart 뒤 실제600ms/시나리오600ms, multiplier1. 실제 확인 종료 FINISHED 뒤10배 | 실제 ON_DEVICE. 사람이 말한 정답률/자연 졸음 전체 E2E로 확대하지 않음 |
| `actual-process-restart.json`, `final-dmo-restored.png` | PID10084 종료(빈pidof)→새PID30688. +1,320,000ms, 인증/보상1행, 연속0, 최근8271초 | 별도의 실제 제품 버튼/프로세스 종료·재실행. STR/실제 전원 제어 아님 |
| `public-sdk-input-results.json` | 실제 SDK ACTIVATED/true와 Hub true, ENABLED/false와 Hub false | 공개 Console 입력. 지연200ms는 측정 안 함 |
| `preservation.json` | 기존160파일 누락0, DB 외 바이트 변경0. 기존 DB3개 논리 행/무결성 동일 | 최종 앱 종료/권한 복구 후 대조 |

**현재 시험 XML 숫자는 engine350/app422다. 마지막 추가한 engine 경계1건은 실행 전이므로351개 통과라고 쓰지 말 것. 마지막 app close 변경 이후 JVM 재실행도 아직 없다.** 기기3건은 마지막 app 코드를 검증했다. schema 변경은 APK 이후이며 원본6종은 바뀌지 않았다.

초기 실패도 보존했다: -0.0 객체 비교 신규 시험 실패→수치 비교 수정, 수정 도중 Lint UAST 내부 예외→소스 고정 후 정상 통과, 접근성 검색/캐시·다른 창 선택 실패→clearCache 보완, JSON 첫 검사가 약22초여서20초 시험 timeout→로딩 안내/기능 확인 대기 보완, DMO 시각 계약 실패→Clock 동결+실제 Tick 수정. 이를 삭제하거나 최종 성공에 합산하지 않는다.

과거 문서의748개/746개/30분 발행/실제 재부팅2회/110ms·내비 지연 표본은 **이전 실행 근거**다. 이번에 다시 실행한 결과가 아니다.

## 6. 다음에 바로 할 일·명령

### A. 먼저 읽고 보존 확인

```bash
cd /home/a/StudioProjects/sleepissue
cat docs/setup/CURRENT_HANDOFF.md
git status --short --branch
git rev-parse HEAD
git diff --stat
git diff --check
# 필요하면 fetch만 먼저 하고 변경 내용을 읽는다. dirty 상태를 reset/clean/임의 커밋하지 않는다.
git fetch origin
git log --oneline HEAD..origin/main
```

추가 파일/원본의 개인 백업은 아래9절 참조. 명시적 사용자 요청 없이는 commit/push/PR 금지를 유지한다. 최신 main에 변경이 있으면 기존 미커밋과 겹치는지 먼저 확인한다.

### B. 최신 소스 최종 검증·빌드 (다음 우선 작업)

```bash
cd /home/a/StudioProjects/sleepissue/src/android
export JAVA_HOME=/home/a/.local/opt/android-studio/jbr
export ANDROID_HOME=/home/a/Android/Sdk
./gradlew :engine:test :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease \
  > /tmp/awakedrive-resume-final-build.log 2>&1
```

engine351개(추가 경계1건 포함)가 예상되지만 **실제 XML을 집계한 뒤** 숫자를 적는다. JVM/빌드가 통과하면 불필요하게 성능 시험을 반복하지 않는다. 기기 시험 때 빌드 데몬과 함께 돌리면 메모리 경쟁/로딩이 늘어날 수 있어 빌드 뒤 `./gradlew --stop`으로 유휴 데몬을 정리할 수 있다. AVD 설정·TTL·시계를 바꿔 시한을 통과시키지는 않는다.

### C. 데이터 유지 교체 설치와 필요 시 정확한 기기 회귀

```bash
ADB=/home/a/Android/Sdk/platform-tools/adb
$ADB devices -l
$ADB shell am get-current-user   # 이번 환경10
$ADB install -r -t app/build/outputs/apk/debug/app-debug.apk
$ADB install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 실제 음성 회귀를 다시 할 때만 원래 false인 마이크를 임시 허용하고 끝나면 되돌린다.
$ADB shell pm grant --user 10 com.sleepissue.awakedrive android.permission.RECORD_AUDIO
$ADB shell am instrument --user 10 -w \
  -e class com.sleepissue.awakedrive.data.ProductDemoWiringTest,com.sleepissue.awakedrive.data.ProductDemoDeviceTest,com.sleepissue.awakedrive.data.ProductDemoVoiceDeviceTest \
  com.sleepissue.awakedrive.test/androidx.test.runner.AndroidJUnitRunner \
  > /tmp/awakedrive-resume-product-device.log 2>&1
# 결과 보존 후 정리. pm clear/uninstall/UTP 초기화는 쓰지 않는다.
$ADB shell am force-stop --user 10 com.sleepissue.awakedrive
$ADB shell pm revoke --user 10 com.sleepissue.awakedrive android.permission.RECORD_AUDIO
```

위 시험은 데모 전용 최신 checkpoint/결과를 갱신한다. 현재 수동 DMO 결과도 보존하려면 먼저 개인 백업을 만들고 실행한다. REAL DB는 쓰지 않도록 설계했다. 서명 불일치로 교체 설치가 거절되면 삭제하지 말고 멈춰 기존 인증서와 비교한다.

제품 직접 실행:

```bash
$ADB shell am start --user 10 -n com.sleepissue.awakedrive/.ui.DrivingPreviewActivity
```

정차 상태에서 **데모→시나리오 선택·재생→6종 중 하나→일시정지/재개/중지/초기화**. 최초 JSON 로딩 안내가 끝나기를 기다린다. DMO는 **졸음→쉼터 P단 준비(모의)→시동 끔(저장 후 앱 종료)→앱 재실행**. 인증1·연속0·최근누적 유지가 기대값이다. 기본 SDK 입력은 꺼져 있고, **Virtual Console 입력 우선 켜기**에서 같은 Hub의 REAL 입력을 활성화한다.

스크린샷은 실제2560×1440인데 view_image 기본 표시가2048×1152로 축소될 수 있다. 수동 좌표를 그대로 쓰지 말고 실제 PNG 크기/접근성 bounds로 확인한다. 기기 시험 중 수동 터치를 섞지 않는다.

### D. 공개 크루즈 경로 (이미 확인, 필요할 때만 재검증)

일반 사용자 경로는 `adb forward tcp:8088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:8088/`를 열고 Driving Mode 카드의 Cruise Control State를 조작한다. UI가 `/browser/session` heartbeat를 보내야 emulator_active가 true이고, 브라우저 없이 PUT만 보내면503일 수 있다.

읽기 전용 SDK 진단 자동 진입(새 파일명을 써서 기존 진단을 덮지 말 것):

```bash
$ADB shell am start --user 10 -n com.sleepissue.awakedrive/.debug.DebugHarnessActivity \
  --ez sdkProbe true --ez sdkAutoConnect true --ez sdkCapture true \
  --es sdkCaptureFile sdk-signal-resume-20261111.json
```

직접 DebugSdkProbeActivity를 shell에서 열면 exported=false로 거절된다. `sdkCaptureFile`은 **`sdk-signal-[a-zA-Z0-9-]+.json`** 형식만 허용한다. 다른 형식은 기본 `sdk-signal-probe.json`으로 떨어져 원본을 덮을 수 있으므로 주의.

이번에는 Full autonomous(공개값6)에서 실제 SDK ACTIVATED/true, Lateral(값5)에서 ENABLED/false를 받았다. UI 이름을 곧바로 Boolean으로 추정한 것이 아니다. 마지막 Ready 복귀 첫 요청은 세션 만료503이었고, 새 세션에서 Ready(값1) 복귀와 Console GET을 확인했다. 최종 Ready 후 SDK 재수신까지 확인한 것은 아니다. 시험 후 원래 ready/목표13.8889m/s, P/0, 브라우저 닫힘을 복구하고 포워딩을 제거한다.

### E. 제출 APK·README

최신 release 빌드 후 unsigned 여부를 다시 확인하고 README/개발 기록에 실제 해시·시험 숫자를 갱신한다. 팀 서명이 없으면 `apk/sleepissue_AwakeDrive_release-unsigned.apk`처럼 명확하게 구분한 파일을 만들 수 있지만 최종 제출용 서명 APK라고 하지 않는다. signingConfig가 없으므로 지금 unsigned APK 설치/기동은 검증 불가다. debug 서명을 제출 서명으로 사용하지 않는다. README의 zipalign/apksigner 예시대로 **팀 키 담당이** 서명하고, 다른 인증서의 기존 앱은 삭제하지 않고 별도 기기로 검증한다.

## 7. APK·에뮬레이터·프로세스 정리 상태

### 현재 산출물 (Git 제외)

| 경로 (`src/android` 기준) | SHA-256 | 상태 |
| --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `067d2d4ea86bac440b053fa9411e1a9400e952089ed24e8b8977c27c99521b20` | 21:00 빌드, 마지막 app 코드 포함. 설치/제품3건/새PID DMO 확인. 그 뒤 schema 문서 변경 있음 |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `dcf3a9cb58c517c9d1ce19f3d05ba2e3d9502afecf3c943e34d19040382e51d3` | 실제3건 실행한 test APK |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `9e1795ec94fd5ad2e06911ad8fd083930d9271cba594a94eb9083644d221fcaf` | 20:59 빌드. **마지막 check.close 등 이전, 최종 제출용 아님. 다시 빌드해야 함** |

release `apksigner verify`는 `DOES NOT VERIFY / Missing META-INF/MANIFEST.MF`였다. unsigned의 예상 결과이며 정상 서명이라고 하지 않는다. `apk/` 최종 제출 복사는 아직 없다. 개인 백업에는 위 APK도 별도 보관한다.

### 기기/실행 상태

- AVD: `Pleos_Connect_2_1_0`, `emulator-5554`, 운전자user10. PID10206의 qemu가 기존 데이터로 계속 실행 중이다. 시작 옵션 `-no-snapshot-load -no-snapshot-save`; Wipe나 데이터 초기화 없음.
- 앱 `com.sleepissue.awakedrive`: **force-stop 완료, pidof 빈 값**.
- 감시 서비스: 최종 `dumpsys activity services com.sleepissue.awakedrive` = **(nothing)**. 그 전 MainActivity의 flags=0 바인딩 레코드만 있었고 FGS count0/requested=false였으며, 실제 REAL 감시는 시작하지 않았다.
- 실제 SDK·데모·마이크·instrumentation·build·Gradle daemon: **실행 중인 작업 없음**. qemu와 일반 adb 서버만 유지.
- 임시 마이크 권한: 원래대로 RECORD_AUDIO=false. 기존 POST_NOTIFICATIONS=true, CAR_SPEED=true는 유지.
- adb forwarding: 시작 때 없었고, 이번 추가8088을 제거해 **최종 빈 목록**.
- Console: 시험용 브라우저 heartbeat2개 종료. ready/목표13.8889m/s·P/0 복귀. source/timestamp 등 송신 메타는 시험으로 바뀔 수 있으므로 값 복귀와 원시 시각 동일을 혼동하지 않는다.
- AVD를 켜 둔 것은 다음 계정의 재개를 위한 것이다. 종료가 필요하면 데이터 보존 정상 종료를 사용하고 Wipe/pm clear는 하지 않는다.

## 8. 보존 대상·백업·알려진 문제

### 사용자 원본과 이번 데이터

기존3개 DB:

- `awakedrive_debug_trip.db`
- `awakedrive_sdk_trip.db`
- `role1-final-20261109-awakedrive_sdk_trip.db`

최종 원본 대조는 `docs/devlog/evidence/2026-11-11-product-demo/preservation.json`에 있다. 기존160파일 누락0·DB 외 변경0, 위3개 DB 모든 논리 행 SHA/행 수/무결성 동일이다. WAL 체크포인트 등으로 DB 바이트와 논리 데이터 동일은 구분한다. 원본을 백업본으로 덮어써 복구한 것이 아니라 변경하지 않은 결과다.

이번 DEMO 저장소: `awakedrive_demo_restart.db`, `demo_<UUID>_rest_records.db`, `demo_<UUID>_rest_session.xml`, `product_demo_restart.xml` 등이다. 사용자 REAL DB와 다르다. 최종 수동 DMO namespace는 `b7b80e56-5106-4a56-be47-ab0d1ff62dfc`, ID도 `demo-`+그UUID이고, pending=false·결과8271초다. 테스트마다 별도 휴식 namespace가 생길 수 있다. 무단 삭제하지 말고 출처를 확인한다.

### 내구성 개인 백업 (커밋 금지)

`/home/a/.local/state/awakedrive-handoff/2026-11-11/` (권한700).

- `device-before.tar`, `device-after.tar`: 앱 databases/shared_prefs/cache 원본. 민감할 수 있으므로 공유·커밋 금지.
- 각 빌드/기기/실패 로그, SDK READ 원본/Console 입력 결과, PID/XML, 수동 DEMO DB tar, 검사 스크립트, 화면 캡처.
- `tracked-changes.patch`, `untracked-work.tar.gz`, `workspace-files-sha256.json`: 인계 시 작업 트리 보조 사본(생성 후 파일 목록 확인). 저장소의 파일이 우선이며 자동 덮어쓰기 복원하지 않는다.
- `app-debug-verified-2100.apk`, `app-debug-androidTest-verified-2100.apk`, `app-release-unsigned-2059-before-last-voice-cleanup.apk`: 산출물 사본. 최신/서명 한계는 위7절과 같음.

임시 작업 경로 `/tmp/awakedrive-20261111/`도 남아 있으나 재부팅/자동 정리로 없어질 수 있어 내구성 경로를 우선한다. SDK 공개 소스 jar는 Git에 넣지 않았고 `/tmp`에만 있다. 필요하면 개발 기록의 공식 Maven URL/SHA로 다시 받는다. 키·CRN·비밀번호를 출력하거나 찾는 작업은 하지 않았다.

### 미달/의미 보류·담당 요청

- 기존 SIG001/002/016 미달·60배 미달을 유지한다. 이번 UI 표시200ms 제한/Default 인계로 시한 통과를 주장하지 않는다.
- 조향 소스는 전륜각°/왼쪽 음수이고 운전대 회전각과 다르다고 명시한다. SeatImpl/PropertyCallback은 숫자를 `==1`로 축약해 UNKNOWN을 잃는다. supplier의 의미 보존 API/수정 버전 확인 전 null 보류.
- 2번: 평소3회/오늘15분 학습 창 export/import와 DMO006 fixture/API 요청. 기준선 참조만 보존한 것을 전체 학습 상태 복원이라고 하지 말 것.
- 3번: 녹음 fallback 리소스, CabinMonitor 실제 말소리, REAL microphone FGS 수명. 현재 TTS/STT ON_DEVICE·데모 이탈 정리는 연결됨.
- 4번: 해당 provider의 공개 위치 이동·모의주행/실제 회전TBT 목적지·버튼, 현재온도 변경 입력. 기존 고정sample 경로/geo fix 성공과 동적 NaviHelper 검증 구분.
- 서명 담당: 안전한 팀 keystore/alias/signingConfig. 키가 없어도 독립 빌드/문서/시험을 멈추지 않는다.

외부 메시지·GitHub 댓글·메일을 보내지 않았다. 담당 요청은 문서에만 기록했다.
