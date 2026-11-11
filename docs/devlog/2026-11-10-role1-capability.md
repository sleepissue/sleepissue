# 2026-11-10 — 최신 알림 짧은 기기 회귀·TC_SIG_007

AGENTS.md, [최종 검토](2026-11-09-role1-final-review.md), [현황표](../setup/ROLE1_COMPLETION_STATUS.md)를 먼저 읽었다. 시작/종료는 develop-jdw / `68696cf158f5cc94ea3b257b87cc2b01bba4a122`다. 시작 미커밋154개 파일과 diff·관련 소스 사본을 `/tmp/awakedrive-sig007-de3qo61o/`에 보존했다. 기존 변경을 삭제/stash/stage하지 않았다. 이번 작업은 알림1개·capability 모의3개에 한정하며 30분 재시험·TRP003 재부팅·SIG001/002 성능 재작업은 하지 않았다.

## 원문과 시험 경계

원본 `/home/a/Downloads/AwakeDrive_SRS_TC_v0.2.xlsx`의 SRS/TC21행, SHA256 `2ea8c6d0baf3040f146fbbab0d87883f12108354411ffaad1f8e6fb1e95e69da`를 확인했다. TC_SIG_007은 가짜 SDK 래퍼가 있는 에뮬레이터에서 조향·착석·통풍 각각 capability=false를 넣고 해당 프레임 필드null·비활성화로그1행·프로세스유지/크래시0을 확인한다. [원문 발췌와 출처 해시](evidence/2026-11-10-role1-capability/requirements.json). 공식 Excel의 Not Run/승인 상태는 변경하지 않았다.

실행 기기는 기존 Pleos Connect AVD emulator-5554, API34, 사용자10이다. Console은 P/속도0이며 emulator_active/input_active=false·DDS bridge 미연결 상태를 유지했다. Console 세션 활성화·값 입력·권한 변경은 하지 않았다. 알림 서비스는 실제 Playground 어댑터/AAOS의 현재 P 조회로 시작했고, capability 시험의 선택 신호는 의도적인 모의다. 이번을 실제 센서 동적 입력/SDK 실제 미지원 시험으로 확대하지 않는다.

## 코드 변경

- `VehicleReadFeatures.kt`는 기존 공식 Vehicle API의 조향·착석·통풍 capability/현재값 조회/리스너 해제를 좁은 인터페이스로 감싼다. PlaygroundSignalAdapter는 기본적으로 실제 PleosVehicleReadFeatures를 사용한다. 좌석 접근의 지연 초기화로 기존 방향지시등/브레이크보다 앞서 좌석 API 실패가 전파되지 않게 했다. 나머지 수집·정규화·권한/Clock/TTL·Hub 정책은 유지한다. 새 경계 객체는 연결 시 생성하며100ms 발행 루프에 넣지 않았다.
- 생성자 factory를 명시적으로 넘기는 AndroidTest만 `src/debug/.../DebugCapabilityReadFeatures`를 사용한다. 선택 신호 하나의 check가 false를 반환하고 getter/구독 호출을 계수한다. 다른 신호는 실제 SDK 래퍼를 유지한다. main에는 fake 객체·Intent 토글·전역 모의 스위치가 없다.
- 가짜 객체는 SignalFrame/VehicleSignalInput을 생성하지 않는다. **가짜 SDK check → 제품 어댑터 checkedRead → SdkSignalStore capability/quality → 어댑터 vehicleSource → 실제 자동 타이머 VehicleSignalHub**를 통과한다. Hub의 실제 발행 상태를 읽으며 완료된null프레임을 직접 주입하지 않는다.
- 각 false 실행 뒤 새 세션의 true 대조군에서 같은 getter/구독 경계를 통과한다. 조향0f는 SEMANTIC_UNCONFIRMED, 착석false는 LOSSY_SDK_BOOLEAN으로 여전히 의미 보류다. 통풍1은 NORMALIZED/AVAILABLE이다. false 실행은 apiReadable=false·UNASSESSED·미수신과 구분한다. 조향비 추정/Boolean 반전은 없다.

## 새로 확인한 로그 결함과 수정

첫 모의 실행은 getter/구독 차단·Hub null/UNSUPPORTED·PID 유지에 성공했지만, 공통 착석 capability=false 한 번에 좌석별 STATE 로그5행을 남겼다. 처음 runner는 좌석별1행만 검사해 JUnit3개가 통과했으나, 원문의 **필드별1행** 기준은 충족하지 못했다. [수정 전 착석 원본](evidence/2026-11-10-role1-capability/before-log-fix-SEATS.json.gz)과 [당시 runner](evidence/2026-11-10-role1-capability/before-log-fix-capability-runner.txt.gz)를 보존한다. 이를 최종 TC 성공으로 세지 않는다.

공통 SDK capability가 착석 전체를 끄는 경우에만 로그 식별자를 SEATS로 공유했다. 좌석5개 각각의 apiReadable=false/UNSUPPORTED·원시 진단은 유지하고, 개별 영역 미지원/다른 오류의 로그는 기존 식별자를 유지한다. 반복된 같은 판정은1행이며, READ 가능 상태를 거친 뒤 다시 미지원이면 새 전이1행을 남긴다. SdkSignalFailureTest에 이 동작의 회귀1개를 추가했고, 기기 runner도 필드 전체 로그 수를 검사하도록 강화했다.

중간 빌드에서 소스를 수정해 이전 클래스와 새 회귀가 한 차례 섞였다(앱413개 중1개 실패, 기대전이2/기록1). 이 실행은 최종 결과와 분리했다. 이후 소스를 고정하고 전체를 강제 재실행했다. [중간 실행 설명](evidence/2026-11-10-role1-capability/intermediate-build-note.json), [최종 소스 해시](evidence/2026-11-10-role1-capability/final-code-inputs.json). 최종 빌드 이후 Kotlin 코드를 바꾸지 않았으며, 그 APK를 교체설치해 아래 네 조건을 다시 확인했다.

## 최종 기기 결과

| 신호/가짜 check가 대신한 API | false 검사 | getter / 구독 | 실제 Hub | 비활성화 로그 | PID·정리 |
| --- | --- | --- | --- | --- | --- |
| 조향 / checkSteeringWheelAngleCapabilities | 1회 | 0 / 0 | steeringDeg=null, UNSUPPORTED, REAL | STEERING_DEG1행 | 31063→31063, 정상 정리 |
| 착석 / checkSeatOccupancyCapabilities | 공통1회 | 5개 좌석 모두0 / 0 | seats=null, UNSUPPORTED, REAL | **SEATS 전체1행** | 31063→31063, 정상 정리 |
| 통풍 / checkSeatVentilationCapabilities | 1회 | 0 / 0 | ventilationLevel=null, UNSUPPORTED, REAL | VENTILATION_LEVEL1행 | 31063→31063, 정상 정리 |

false의 모든 대상 SDK reading은 apiReadable=false·normalization=UNASSESSED·수신시각null·query/callback계수0이다. 따라서 기존 의미 보류null을 모의 미지원 성공으로 세지 않았다. true 대조군은 apiReadable=true·getter/리스너 호출·실수신시각을 확인했다. 두 조향/착석 대조군의 의미 보류는 그대로 남고 실제 SDK 미지원이라는 결론은 내리지 않는다.

최종 capability runner는 **12.702초, OK(3 tests)**다. 각 false/true 종료에서 어댑터 close·scope cancel/join·Hub stop·SDK lease 반환을 수행했다. owner=null·Hub running=false·종료 뒤250ms 동안 발행계수 증가0, true 대조군의 모든 구독에 대응한 해제를 확인했다. 동일 PID/정상 JUnit 종료와 해당 PID의 TestRunner/AndroidRuntime 로그에 FATAL0을 대조했다. [신호별 집계·원시 로그](evidence/2026-11-10-role1-capability/capability-summary.json), [runner](evidence/2026-11-10-role1-capability/final-capability-runner.txt). 이250ms는 종료 확인이며 성능/장기 내구성 시험이 아니다.

| 최신 알림 회귀 항목 | 최종 확인 |
| --- | --- |
| 실제 격리 알림 | ID4102, channel role1_isolated_trip_monitor. 사용자 ID4101 알림은 새로 만들지 않음 |
| 사용자 채널 | real_trip_monitor의 before/after 설정 문자열 동일. 새 시험 채널은 종료 후 공식API로 삭제 |
| 알림 화면 | DebugSdkProbeActivity 진입. 사용자 DB 열린 FD 목록0. 서비스 시작 전/후·화면 진입 FD 표본과 Room 없는 소스 경로를 대조 |
| 중지 | 알림 action이 자신의 격리 서비스 중지. SDK owner=null·timer0, PID29831→29831 |
| runner | **6.065초, OK(1 test)**. DB 보존 위해 사용자 서비스를 동시에 시작하지 않음 |

[알림 원본](evidence/2026-11-10-role1-capability/final-notification-result.json), [최종 runner](evidence/2026-11-10-role1-capability/final-notification-runner.txt). 초기 알림 회귀5.863초와 최종6.065초를 따로 보존했다. 기존30분을 반복하지 않았다. FD 검사는 명시한 시점의 표본이며 모든 파일 열기 syscall을 후킹했다는 주장은 아니다.

## 최신 자동시험·빌드·release 분리

```bash
cd src/android
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:-TieredCompilation' \
  --max-workers=2 --rerun-tasks \
  :engine:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:processReleaseMainManifest :app:compileReleaseKotlin
```

| 검증 | 이번 최종 결과 | 이전 근거와 구분 |
| --- | --- | --- |
| engine JVM | 333, 실패0/오류0/skip0 | 최종 소스 고정 뒤 실제 재실행 |
| app JVM | 413, 실패0/오류0/skip0 | 이전412+공통 착석 로그 회귀1 |
| 전체 자동 | **746, 실패0/오류0/skip0** | 이전745와 구분. 기기4개를 여기에 합산하지 않음 |
| debug/test APK | 모두성공·사용자10에 -r -t 교체설치 | -g/삭제/초기화 사용 안 함. 설치본 두 SHA가 최종 산출물과 일치 |
| release manifest/Kotlin | 모두성공 | manifest debug구성0, release 자체 Kotlin661개 클래스에서 fake객체/명시flag 참조0 |
| 최종 기기 | 알림1+모의3 정상종료, skip0 | 첫 모의3의 느슨한 로그 판정과 이전 기기시험은 별도 |

마지막 전체 BUILD SUCCESSFUL은1분27초·88개task 실제실행이다. [전체 로그](evidence/2026-11-10-role1-capability/frozen-code-full-tests-build.txt), [집계·APK 해시](evidence/2026-11-10-role1-capability/final-build-results.json), [release 분리 검사](evidence/2026-11-10-role1-capability/release-isolation.json). fake 구현은 debug, runner는 androidTest에만 존재한다. main의 인터페이스/실제 SDK 구현은 release에 포함되며, release APK 서명/제출은 이번 시험이 아니다.

## 보존과 종료 상태

기기 databases/shared_prefs/cache의 기존 **122개 파일을 최종 바이트 동일**로 확인했다. 사용자 수동/REAL DB·WAL/SHM·기존 진단도 동일하다. 시험이 쓴 기존 파일은 prefix가 붙은 격리 DB·설정2개뿐이며, 정상 중지 뒤 이2개만 시작 사본으로 복구했다. 새로운 시험 진단은 고유 runId 폴더에 남겼다. DB tar와 APK는 /tmp에만 보존하고 저장소에는 넣지 않았다.

권한 선언/승인, Console 전체 상태, adb forward 목록도 전후 동일하다. 실행 중 감시 서비스는 없고, 원래의 Room 없는 SDK 미연결 화면으로 돌아왔다. 내부 Activity 직접 시작은 unexported로 거부돼, 기존 exported DebugHarnessActivity의 sdkProbe 전달 경로를 사용했다. 권한/화면 제한 우회나 manifest export 변경은 하지 않았다. [보존 결과](evidence/2026-11-10-role1-capability/preservation-final.json), [저장소 보존 검사](evidence/2026-11-10-role1-capability/repository-checks.json).

## 직접 실행하는 정확한 명령

P/0·사용자10·기존SDK/감시 정상중지 상태에서 실행한다. 사용자 내비 경로는 건드리지 않는다. 최신 APK 두 개는 이미 설치돼 있다. 이후 새 빌드를 교체할 때에도 아래 데이터 유지 방식만 사용한다.

```bash
adb install -r -t --user 10 src/android/app/build/outputs/apk/debug/app-debug.apk
adb install -r -t --user 10 src/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

trial_id="role1-$(date +%Y%m%d-%H%M%S)"
adb shell am instrument --user 10 -w \
  -e class 'com.sleepissue.awakedrive.data.Role1Plt013DeviceTest#isolatedNotificationOpensSafeScreenAndStopsItsOwnService' \
  -e role1Plt013Notification true -e plt013RunId "notify-$trial_id" \
  com.sleepissue.awakedrive.test/androidx.test.runner.AndroidJUnitRunner

adb shell am instrument --user 10 -w \
  -e class com.sleepissue.awakedrive.data.Role1CapabilityDeviceTest \
  -e role1CapabilityFalse true -e role1CapabilityRunId "$trial_id" \
  com.sleepissue.awakedrive.test/androidx.test.runner.AndroidJUnitRunner
```

기대값은 각각 OK(1 test), OK(3 tests)다. capability 명시flag가 없으면 모의는 실행하지 않는다. 진단은 `cache/role1-sig007-$trial_id/{STEERING_DEG,SEATS,VENTILATION_LEVEL}.json`에 남으며 기존 이름이면 덮지 않는다. 알림 진단은 격리 cache의 `plt013-notify-$trial_id/notification-result.json`이다. 실제 실행 argv는 [알림 명령](evidence/2026-11-10-role1-capability/final-notification-command.json)·[모의 명령](evidence/2026-11-10-role1-capability/final-capability-command.json)에 있다.

알림 runner는 기존 격리 DB·설정에 저장하므로 **기존 격리 자료까지 원상복구하려면 실행 전에 해당 prefix DB·설정의 사본을 별도로 보존하고, 정상 종료/DB close 뒤 두 파일만 원상복구**한다. 사용자 DB 복구/덮어쓰기는 하지 않는다. 이번 수행에서는 전체122개 시작사본과 바이트 대조로 이를 완료했다. capability runner는 Room을 열지 않는다.

화면으로 확인할 때는 **P/0 → 켜진 감시의 감시 중지 → SDK 화면 중지 → 위 명시 runner 실행** 순서다. 알림 runner가 실제 화면 진입·중지 action을 보내며 capability=false는 Console/UI 버튼이 아니다. 세 JSON에서 check:false1회·getter/구독0·필드null/UNSUPPORTED·로그1행·정리owner=null을 확인한다.

## 남은 요구

| 항목 | 현재 상태 |
| --- | --- |
| 이번 TC_SIG007 세 미지원 모의 | 해당 조건 검증 완료. 실제 SDK 지원 여부·모든 실패 조합의 전 환경 보장으로 확대하지 않음 |
| 최신 알림 ID/채널/화면/자기중지 | 짧은 기기 회귀 완료. 전체 제품 판단·음성 연결은 별도 |
| SIG001/002 | 엄격110ms·전체200ms 미달 유지. 이번 재측정/성능 수정 없음 |
| 조향/착석 의미, 이동 heading·동적 내비·현재온도 | 기존 의미 보류/공개 입력 경로 미확인 유지 |
| TRP003 실제 재부팅·10종 대조 | 실행가능 미검증 유지. 이번 시작하지 않음 |
| OFF→ON/STR·다른 담당 제품 연결 | 기존 환경 제한/연결 대기 유지 |

1번 전체31개 ID는 [현황표](../setup/ROLE1_COMPLETION_STATUS.md#요구별-최종-상태)에 유지했다. 다른 담당 기능·60배·TripRules·Room schema·감시 수명 구현은 바꾸지 않았다. 커밋/push/PR·앱 삭제/초기화/Wipe는 없다.
