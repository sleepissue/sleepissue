# 2026-11-09 — develop-jdw에 최신 main 병합·호환성 검증

AGENTS.md를 먼저 읽고 시작했다. 범위는 기존 신호·운행·저장 계약을 보존한 로컬 커밋, 최신 main 병합, 필요한 호환 수정과 검증이다. 새 제품 조립·다른 담당 기능 재설계·push·PR 생성/merge는 하지 않는다. [병합 전 SIG 검증](2026-11-09-role1-sig-input.md)의 **자동492개·활성 내비 기기1개**는 이전 근거이며 이번 실행 결과와 구분한다.

## SHA·작업 보존

| 단계 | SHA·내용 |
| --- | --- |
| 시작 | `develop-jdw` / `f5c6ad9e179383ddb2b5cff3aad9ac4230afabe2` |
| 기존 작업 선별 커밋 | `08da2b9c94e1e63c45fe74c240aa07f76b2dee36` — 상태 신호 재확인·거부 배송 차단·조회/관측 구분·내비 gate·heading 이력·emit 감사와 코드/기기 회귀·문서·증거100개 파일 |
| fetch한 main | `5195e258470de85a9fe4337a5abb439199e3f013` — UI·엔진·음성·쉼터·데모 시험 반영 |
| 병합 커밋 | `c39bcca44060b88f50d348c7f1b6663a151c717d` — 첫 부모08da2b9, 둘째 부모5195e25, `git merge origin/main`의 ort 자동 병합. 텍스트 충돌0 |
| 후속 호환 수정·검증 커밋 | 이 문서를 포함하는 후속 커밋. 최종 SHA는 `git log -1 --format=%H` 또는 작업 결과 보고 참조 |

시작 미커밋100개 파일의 원본·해시·diff는 `/tmp/awakedrive-main-merge-tj5ysh9o/`에 별도 보관했다. 08da2b9의100개 파일이 시작 원본과 바이트 단위로 모두 같음을 확인했다. 실제 DB·APK·SDK 바이너리·local.properties·환경 파일·키·임시 산출물은 커밋하지 않았다. 문서 evidence의 공개 시험 원시 이벤트·runner 로그·스크린샷은 의도한 검증 기록으로 포함했다. 기존 raw runner3개의 EOF 공백 경고는 원본 보존을 위해 고치지 않았다. stash·reset --hard·커밋 재작성·강제 push·브랜치 전환은 사용하지 않았다.

## 충돌과 필요한 호환 수정

| 항목 | 발견·처리 |
| --- | --- |
| Git 텍스트 충돌 | 없음. PROGRESS, debug manifest/화면, PlaygroundSignalAdapter, SdkSignalStore는 자동 병합 후 양쪽 변경을 대조 |
| SDK 검증 화면 컴파일 실패 | main의 `DebugSdkProbeActivity`가 정의하지 않은 `vehicleKeys`를 참조. 첫 병합 빌드에서 `Unresolved reference vehicleKeys` 재현. 기존 차량 목록을 vehicleKeys로 명명하고 내비 목록을 별도로 합쳐 표시만 복구 |
| 새 제품 UI 기동 불가 | `DrivingPreviewActivity`는 있으나 main/debug 어느 manifest에도 등록되지 않았고 기존 DebugHarness launcher는 main에서 제거됨. main manifest에 해당 Activity와 MAIN/LAUNCHER를 등록. UI 흐름·판단·저장·SDK 조립은 바꾸지 않음 |
| 데모 시험의 로컬 파일 의존 | 팀 GeneratedScenariosTest가 없는 data/scenarios-validation을 찾으며 실패. 커밋된 data/scenarios-demo를 읽고, drowsy 필수 거리/온도와 나머지 선택 필드의 null/품질 전달을 검사하도록 수정. 시나리오 데이터·DemoPlayer는 변경하지 않음 |
| 기기 회귀 | `Role1MainMergeSmokeTest` 추가. 새 UI 기동/타 소유권 불변, 기존 소유권이 있을 때 SDK 화면 연결 거부, 해제 뒤 실제 SDK AVAILABLE 수신, 화면 종료 뒤 lease 해제. Room·운행·마이크·내비 제어 미사용 |

## 계약별 팀 코드 대조

| 계약 | 확인한 코드·유지 상태 | 아직 연결하지 않은 조건 |
| --- | --- | --- |
| Clock | REAL 서비스의 AnchoredTripClock은 epoch 앵커+단조 경과. FatigueBridge는 frame.tMs를 epoch로 읽으며 ForecastCoordinator·RestSessionMonitor는 주입 Clock 사용 | 독립 SDK 화면의 elapsed·수동/JSON의 상대 scenario 시각을 REAL epoch로 넘기면 안 됨. 현재 제품에 해당 연결은 없음. 실제 조립 때 같은 세션 Clock을 공유해야 함 |
| 조회와 실제 관측 | confirmationFields·stateReportsOnly·SdkStateSubscription/Delivery·SdkCarDelivery를 유지. QUERY/REQUERY는 최종 값/품질/확인만 갱신, 센서 관측·raw 통로·Hz를 만들지 않음 | FatigueAssessmentRunner는 hub.samples용. sourceRateInfo UNKNOWN을 측정 충분으로 해석하지 않는 엔진 변환 유지. 실제 제품 조립 전 원천 이벤트 소비와10Hz 상태 소비를 구분해야 함 |
| null·품질·출처 | Hub가 불량 값을 null로 내보내는 계약 유지. SignalAdapters는 null 유지·조향 원천 rate MEASURED만 허용, FatigueUiMapper는 무효 위험을0점으로 표시하지 않음. main의 BELT 구독/별도 SeatBeltState도 함께 보존 | 조향 전륜각·착석 Boolean 의미 보류 유지. Rest/예보는 Hub 최종 값을 받도록 설계됐으나 실제 앱 소비 연결은 대기. 혼합 source나 임의 원시 프레임을 직접 넣는 제품 E2E를 확인한 것은 아님 |
| 내비 | SdkNaviDelivery의 명시적 ROUTE_GUIDING/SIMULATION_GUIDING gate와 취소/오류/만료 null 유지 | 이번 병합 시험으로 실제 이동·자연 도착·교차로 통과를 새로 확인하지 않음 |
| 운행 ID·저장 | TripState.tripId의 String 계약, 기존 Room/TripRules/정밀 구간/UNKNOWN 복원 유지. 4번 restSessionId는 별도 String, Long 강제 변환 없음. RestRecordDatabase는 별도 DB | 새 UI에서 실제 운행 저장소를 읽는 연결은 없음. 휴식 인증·보상 DB까지 사용자 REAL 운행과 연결한 E2E 아님 |
| SDK 단일 소유권 | PlaygroundMonitorInput과 DebugSdkProbeActivity의 PlaygroundSdkOwnership lease 유지. 새 UI는 SDK/Room을 만들지 않음 | 4번 제어 wrapper의 제품 수명 조립은 아직 호출되지 않음. 실제 조립 후 제어 종료가 수집에 미치는 영향은 추가 확인 필요 |
| 실행 라우터 | main의 InterventionRouter와 InterventionRequestRouter·VoiceInterventionConnector가 공존하며 실제 제품 호출은 없음 | 서로 중복 구독시키면 결과가 중복될 수 있으므로 제품 조립 때 요청/결과 소유자를 하나로 정해야 함. 이번에 다른 담당 라우터를 재설계하지 않음 |
| 권한 | release manifest 처리를 실행해 4번 NAVI_ROUTE_SEARCH/NAVI_CUSTOM_MAP/CONTROL_CAR_SEATS와 기존 수집·서비스 권한을 함께 확인 | 이전 ‘1번 fixture 제어 권한은 debug 전용’ 기록은 당시 상태. 현재 release 제어 권한은 main에서 가져온4번 제품 코드의 권한이며, 수집기에 setter를 추가한 것이 아님 |

핵심8개 파일(SdkNaviDelivery, SdkStateSubscription/Delivery/Normalization, SdkCarDelivery, PeriodicSignalHub, SignalPorts, VehicleSignalHub)은 선별 커밋08da2b9와 바이트 동일하다. PlaygroundSignalAdapter에는 main의 BELT capability/get/listener를 더한 변경만, SdkSignalStore에는 main의 seatBelts()만 함께 들어왔다. 최근1번 보완이 빠지거나 main의 안전벨트 변경을 덮지 않았다.

## 제품 연결 상태와 검증의 범위

- `DrivingPreviewActivity → AppNavigation()`은 기본 FatigueUiState를 쓰는 UI 진입점이다. FatigueConnectedNavigation이 존재하지만 이 Activity에서 runner를 만들거나 연결하지 않는다. 새 UI의 DEMO CONTROL은 화면 단계 버튼이며 DebugDemoController/DemoPlayer 재생 조립이 아니다.
- AwakeDriveMonitoringRuntime·FatigueAssessmentRunner·VoiceInterventionConnector·RestModule·RestWiring은 부품/연결기가 있으나 실제 앱/REAL 서비스의 생성 호출은 없다. 새 UI 홈이 뜨는 것을 졸음 판단·음성·내비·휴식·보상 E2E 성공으로 해석하지 않는다.
- 기존1번 감시 서비스는 신호→운행→전용 Room 경로를 유지한다. UI 연결을 위해 서비스를 재개발하거나 사용자 저장본을 새 모델로 변환하지 않았다.

## 시험·빌드 결과

| 실행 | 결과·구분 |
| --- | --- |
| 병합 전 과거 근거 | engine153 + app339 = **492개**, 활성 내비 기기1개. 이번 실행 숫자로 재사용하지 않음 |
| 첫 병합 빌드 | vehicleKeys 미정의로 컴파일 실패. [첫 로그](evidence/2026-11-09-main-merge/merged-initial-tests-build.txt) |
| 진입점/컴파일 보완 후 | engine333 + app408 =741개 중 GeneratedScenariosTest1개 실패(없는 로컬 fixture 경로). [실패 로그](evidence/2026-11-09-main-merge/merged-fixture-failure-tests-build.txt) |
| fixture 경로·선택 필드 계약 보완 후 | **engine333 + app408 =741개**, 실패0·오류0·skip0. 이전492개 대비 팀 시험249개 증가. 모든 host 시험을 --rerun-tasks로 실행 |
| 최종 빌드 | assembleDebug·AndroidTest APK·release manifest **성공**, 81개 task 실제 실행 |
| 기기 | 새 UI·SDK 소유권/정상 수신 결합 회귀 **1개를 최종 연속2회 통과, 3.786/3.816초**. 기존 Room/내비/통풍 기기시험과 별도. 기존 소유자는 lease fixture이며 실제 운행 감시 서비스를 동시에 띄운 시험은 아님 |

[최종 로그](evidence/2026-11-09-main-merge/final-tests-build.txt)·[XML 집계/APK 해시](evidence/2026-11-09-main-merge/test-results.json)·[기기 runner](evidence/2026-11-09-main-merge/device-smoke.txt)·[기기 결과](evidence/2026-11-09-main-merge/device-smoke.json)·[제품 UI](evidence/2026-11-09-main-merge/product-ui.png)를 남겼다. 기기 회귀 첫 시행은 제품 기동 뒤 SDK 거부 안내 assertion에서 실패했고, 루트 Activity 종료 전환과 다음 기동이 겹치지 않게 시험 순서를 보강한 뒤 통과했다. 첫 시행을 통과 수에 넣지 않았다. AVD 복구 뒤에는 finish 직후 lease 해제를 단정하던 assertion도 실제 Activity 종료를 기다리도록 보강했다. 이후 같은 기기 회귀1개를 연속2회 실행해 모두 통과했으며 반복 횟수를 시험 수로 더하지 않는다. 이후 AndroidTest harness만 다시 빌드했으며 제품 소스/host 시험 동작은 바꾸지 않았다. 전체 host 시험을 이유 없이 다시 반복하지 않았다. 최종 debug/AndroidTest APK 해시는 기기 설치본과 모두 일치한다.

```bash
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./src/android/gradlew -p src/android \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:-TieredCompilation' \
  --max-workers=2 --rerun-tasks :engine:test :app:testDebugUnitTest \
  assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest
```

기존492개·내비 기기1개, 더 이전 Room 경계3개·통풍/기어1개는 과거 기록이다. 이번에 Room 장시간/실제 OFF/내비 재요청 시험을 반복하지 않는다. 첫 병합 빌드의 컴파일 실패와 최종 통과 실행은 별도 로그로 남긴다. Lint·서명 release APK·물리 차량은 이번 검증에 포함하지 않는다.

## 직접 실행 순서

| 순서 | 조작·기대값 |
| --- | --- |
| 1 | 기존 AVD의 P/0과 감시 중지 상태 확인. 아래 APK는 `adb install -r`로만 교체 설치. 삭제·데이터 초기화·Wipe 금지 |
| 2 | 앱 목록의 **AwakeDrive** 또는 아래 명령으로 제품 UI 실행 → 홈·설정·주행·휴식·리포트 화면. 아직 실제 신호/엔진이 연결된 제품 화면은 아님 |
| 3 | 기존1번 화면은 DebugHarnessActivity를 명시 실행. SDK 단독 진입은 `sdkProbe=true` → ‘차속 권한 → 연결’. 수동 Room 패널을 열 필요 없음 |
| 4 | SDK 정상 품질 확인 후 ‘중지’. 감시 서비스와 SDK 단독 화면을 함께 쓰지 않음. 기존 소유자가 있으면 연결 거부 안내가 기대값 |
| 5 | 종료 후 SDK 소유권 해제·감시 중지 확인. 기존 운행 ID·행·진단 원본을 삭제하지 않음 |

```bash
# 새 UI
adb shell am start --user 10 -n com.sleepissue.awakedrive/.ui.DrivingPreviewActivity

# 기존1번 SDK 독립 검증 화면
adb shell am start --user 10 -n com.sleepissue.awakedrive/.debug.DebugHarnessActivity \
  --ez sdkProbe true

# 병합 기동/소유권 기기 회귀. user10의 기존 SDK 소유자를 먼저 정상 중지한다.
adb shell am instrument --user 10 -w \
  -e class com.sleepissue.awakedrive.data.Role1MainMergeSmokeTest \
  com.sleepissue.awakedrive.test/androidx.test.runner.AndroidJUnitRunner
```

## 남은 과제·새 PR 범위

엄격110ms 발행 미달·실제 이동 heading·현재 실내온도 변경·STR/실제 ignition·자연 도착/교차로 통과·모든 반영 시한은 이전 미해결/미검증 상태를 유지한다. 60배 DemoPlayer 성능은5번 과제이며 병합으로 해결됐다고 표시하지 않는다.

새 PR이 필요할 때 대상은 **develop-jdw → main**이다. 범위는 main에 아직 없는1번 상태 신호/거부 배송/조회·관측/내비 gate·회귀·문서와 이번 세 호환 수정·기기 회귀·병합 검증 기록이다. main에 이미 반영된 UI·엔진·음성·쉼터 기능을 새 구현처럼 다시 설명하지 않는다. 실제 제품 전체 조립·성능 해결·미확보 입력 시험은 이 PR의 완료 주장에 포함하지 않는다. 이번 작업은 로컬에서만 완료하며 push·PR 생성/merge는 하지 않는다.


## 기기 환경과 최종 데이터 보존

첫 기기 통과 뒤 보존 대조 중 AVD 프로세스가 종료돼 adb offline이 됐다. 종료 원인은 확정하지 못했으며 제품 결함이나 STR 시험 결과로 분류하지 않는다. 실행 중인 emulator가 없는 것을 확인하고 기존 Pleos_Connect_2_1_0을 **-no-snapshot-load -no-snapshot-save -gpu host -feature -Vulkan -memory4096 -cores4**로 데이터 삭제 없이 다시 부팅했다. 이후 SDK 독립 화면의 기존 Harness redirect·새 UI 기동을 확인하고, 생명주기 종료 대기를 보강한 기기 회귀를 연속2회 통과했다. 중간 실패와 성공 runner/JSON은 서로 다른 이름으로 보존했다.

[최종 보존 대조](evidence/2026-11-09-main-merge/preservation-final.json): 수동/REAL 두 DB의 모든 논리 행 해시가 전후 같고 integrity=ok, 기존 운전구간1개/18개 보존. 기존 DB·shared_prefs·cache **81개 파일 모두 바이트 동일**이다. 시험 출력은 cache/role1-main-merge-smoke.json·sdk-signal-main-merge-smoke.json의 새 이름만 사용했다. DB나 기존 진단을 백업본으로 덮지 않았다. 감시 requested=false·실행 서비스 없음·최종 SDK owner=null이며 마지막 화면은 새 AwakeDrive 홈이다. 앱 삭제·데이터 초기화·AVD Wipe는 없었다.

[병합 출처](evidence/2026-11-09-main-merge/merge-provenance.json), [핵심8개 원본 보존](evidence/2026-11-09-main-merge/critical-source-retention.json), [manifest 결과](evidence/2026-11-09-main-merge/manifests.json), [기동 확인](evidence/2026-11-09-main-merge/entrypoints.json), [main 대비 변경](evidence/2026-11-09-main-merge/changes-vs-main.txt)을 남긴다. 최종 작업 트리는 후속 호환 수정·기록 커밋 후 clean으로 마무리한다.
