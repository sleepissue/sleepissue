# 2026-11-07 REAL 감시 서비스·저장·복원

`develop-jdw`의 기존 미커밋 작업을 보존하고 1번 담당의 실제 Playground 수집·운행·저장을 화면에서 서비스로 옮겼다. 브랜치 전환·커밋·push·앱 삭제·사용자 데이터 초기화는 하지 않았다. 기존 개입·음성·쉼터·판단 엔진·DemoPlayer·60배 성능 구현은 통합하거나 수정하지 않았다. REAL은 Playground/AAOS/Virtual Console 입력이며 물리 차량 검증이 아니다.

직접 누를 버튼·공식 실행 근거·제한은 [서비스 가이드](../setup/ROLE1_MONITOR_SERVICE.md), 이전 화면 소유 구현은 [REAL 운행 기록](2026-11-07-real-trip.md)을 따른다.

## 변경과 소유권

- `RealTripMonitorService`가 기존 `PlaygroundTripSession`·SDK 어댑터·Hub·Room을 소유한다. `RealTripMonitorRuntime`은 시작·중지 명령을 직렬화하고 collector·저장 주기를 각각 하나만 만든다.
- 기존 debug SDK 운행 Activity의 ViewModel 조립을 제거하고 서비스 상태 화면으로 연결했다. 화면은 바인딩과 표시만 종료한다. **돌아가기**, 화면 꺼짐·재진입·회전은 감시 중지가 아니다.
- `PlaygroundSdkOwnership`의 객체 식별 lease로 서비스와 SDK 단독 검증 화면의 동시 수집을 거절한다. 기존 소유자를 강제로 빼앗지 않는다. 이전 lease의 늦은 해제는 새 소유자를 지우지 않는다.
- 명시적 중지는 실행 의사를 먼저 false로 동기 저장하고 collector·대기 저장·Hub 주기를 취소한 뒤 SDK를 해제한다. 원자적인 마지막 상태 캡처/입력 종료와 최종 저장을 수행하고 알림을 제거한다. 정상 service destroy는 같은 정리를 시도하며, 강제 종료·SIGKILL에서는 마지막 callback이나 저장을 보장하지 않는다.
- 이전 인스턴스의 최종 저장/DB close가 끝난 다음 새 인스턴스가 복원하도록 프로세스 안의 Mutex 장벽을 둔다. 입력 Flow 종료/오류도 현재 입력 객체를 확인한 뒤 종료하므로 오래된 collector의 종료가 새 세션을 끄지 않는다. SDK close 자체의 예외에도 finally에서 별도 SDK scope를 취소하고 소유권을 해제한다.

## 공식 실행 조건과 실제 환경

현재 targetSdk **34**, compileSdk **37**, Pleos Connect AVD **API 34**, 사용자 **10**이다. [필요 속성만 추린 실제 환경](evidence/2026-11-07-monitor-service/platform.json).

다른 FGS 유형에 포함되지 않는 사용자 시작 차량 내부 SDK 감시를 [공식 specialUse 범주](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)에 적용했다. `FOREGROUND_SERVICE`·`FOREGROUND_SERVICE_SPECIAL_USE`·subtype 설명을 선언하고 실제 기기의 유형 `0x40000000`을 확인했다. 외부 장치 전제가 없는 connectedDevice나 시스템 특권 유형을 임의 사용하지 않았다. subtype은 이번 사용례에 대한 개발자 판단이며 배포 심사 승인과 구별한다.

보이는 Activity의 **감시 시작**에서 시작하며 먼저 LOW 알림을 게시한 뒤 SDK를 초기화한다. 알림의 **감시 중지** PendingIntent와 화면 중지를 제공한다. 이 앱은 사용자가 알림/중지 동작을 볼 수 있도록 알림 허용을 요구한다. Android 자체는 POST_NOTIFICATIONS 거절을 FGS 시작의 절대 금지 조건으로 삼지 않는다는 점과 구별한다. [공식 시작](https://developer.android.com/develop/background-work/services/fgs/launch), [알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [실제 알림](evidence/2026-11-07-monitor-service/10-notification.json).

시스템의 `START_STICKY` 재생성은 [공식 계약](https://developer.android.com/reference/android/app/Service#START_STICKY)에 따른다. 부팅은 사용자 unlocked 뒤 `BOOT_COMPLETED`의 [백그라운드 시작 예외](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)를 사용하며, 남아 있는 실행 의사와 사용자 종료 이력을 확인한다. 마이크·카메라·위치 FGS를 부팅에서 시작하지 않는다. 실제 BOOT 로그에 `code:BOOT_COMPLETED`·`Allowed`가 있고 시스템은 location/camera/microphone 접근이 없는 background FGS라고 표시했다. [실제 시스템 로그](evidence/2026-11-07-monitor-service/25-boot-system-log.txt).

명시적 중지 뒤에는 sticky/부팅 재개를 하지 않는다. OS Active apps/강제 종료는 callback 없이 끝날 수 있으므로 다음 실행에서 `ApplicationExitInfo.REASON_USER_REQUESTED`가 감시 요청 이후에 있으면 실행 의사도 철회한다. 모든 OEM의 종료 기록 보존이나 강제 종료 후 자동 실행을 보장하지 않는다. 부팅 시작이 거부되면 반복 예약으로 우회하지 않고 `bootPending`과 사용자의 **감시 시작** 경로를 남긴다. 시작 거부 fallback 자체를 실제 정책 거부로 유도하는 기기 시험은 미수행이다.

## 시간·저장·전원 계약

기존 REAL 날짜 축 `AnchoredTripClock`과 전용 `awakedrive_sdk_trip.db`를 유지했다. 실행 중 단조 경과를 운행에 사용하고 실제 경과 **10초** 주기를 기존 저장 조정기로 판정한다. 수동 DB/시계·DEMO를 연결하지 않는다. 새 정상 입력 전 UNKNOWN이며 종료·재부팅을 OFF로 생성하지 않는다. 저장 원본과 활성 UNKNOWN을 구별하고 복원 실패/날짜 역행이면 원본 DB를 덮어쓰지 않는다.

같은 프로세스의 절전도 Hub 순번이 반드시 누락되는 것은 아니다. 이전 기어 확인 lease 끝보다 새 확인이 늦으면 마지막 정상 관측에서 구간을 끊고 공백을 제외한다. 정상 재확인으로 과거 공백 전체를 증명하지 않는다. 기어가 같아도 이 단절은 즉시 저장 대상이다. 새 `finishInputAndCapture`는 마지막 유효 상태와 입력 중단을 한 잠금 안에서 처리한다.

재부팅 전후 elapsed를 직접 빼지 않는다. 첫 재부팅 직전 캡처 행은 elapsed **1,153,873ms**, 부팅 때 읽은 최종 원본은 **1,161,746ms**이며 부팅 후 저장은 **49,687ms**다. 종료 직전 checkpoint 메타데이터가 추가 저장됐고 정밀 누적은 687,851ms로 같았다. 이 elapsed들은 서로 다른 부팅 값이다. 날짜 기반 snapshot을 검증하고 새 앵커/새 저장 타이머로 재개했다. [부팅 전 행](evidence/2026-11-07-monitor-service/24-before-reboot-row.json), [화면을 열기 전 자동 재개](evidence/2026-11-07-monitor-service/25-after-reboot-auto-service.txt), [복원 원본과 이벤트](evidence/2026-11-07-monitor-service/26-after-reboot-restored.json).

공식 `IGNITION_STATE`·`VehicleIgnitionState`의 서명·권한·의미는 확인했지만 실제 지원 config 312개에 해당 속성이 없다. `GEAR_SELECTION`과 차속은 있다. API34 `CarPowerManager` 시동/절전 리스너는 public SDK가 아니고 `CAR_POWER`도 특권 권한이다. 반사·특권 부여로 연결하지 않았다. 따라서 실제 전원 입력은 미구현/UNKNOWN이며 기어 기반 서비스 작업은 진행했다. 화면 꺼짐·AP 부팅을 차량 시동 OFF 증명으로 사용하지 않는다. [공식 속성](https://developer.android.com/reference/android/car/VehiclePropertyIds#IGNITION_STATE), [공식 전원 소스](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android14-release/car-lib/src/android/car/hardware/power/CarPowerManager.java).

FGS는 CPU deep sleep 중 정확한 실행을 보장하지 않는다. wake lock·배터리 최적화 제외·주행 제한 해제는 추가하지 않았다. 이번 화면 꺼짐은 `mWakefulness=Dozing` 관찰이며 실제 자동차 Suspend-to-RAM 합격으로 확대하지 않는다.

## 자동시험·빌드

최종 `:engine:test` **153개** + `:app:testDebugUnitTest` **280개** = **433개 통과**. 실패·오류·건너뜀 0개다. 기존414개에 저장/시간6개·SDK소유권3개·감시 수명10개를 추가했다. 중복 시작/초기화, 단일 collector/타이머, 마지막 저장·중지 후 늦은 입력, 재시작 세대, Flow 종료/오류, 시작·해제 예외, 권한 상실, 절전 D/P lease 단절/경계, 재부팅 elapsed 초기화/새10초 주기를 포함한다. 기존 운행 경계·개입·DEMO 회귀도 같은 전체 실행에 포함했다.

`assembleDebug`·`assembleDebugAndroidTest`·`processReleaseMainManifest` 성공. 추가 `:app:lintDebug`는 변경하지 않은 3번 담당 `voice/PleosSpeechRecognizer.kt:129`의 `MissingPermission` 오류 1건으로 실패했다(경고180개). 해당 파일의 현재 Git blob이 HEAD와 같음을 대조했고 음성 코드 수정·전역 lint 억제를 하지 않았다. [Lint 결과와 동일 blob 근거](evidence/2026-11-07-monitor-service/lint.json), [원문](evidence/2026-11-07-monitor-service/lint.txt). 이 실패를 빌드/시험 성공과 구분한다. [최종 빌드 원문](evidence/2026-11-07-monitor-service/build-final.txt), [시험 합계·APK SHA256](evidence/2026-11-07-monitor-service/build.json). AGP/Gradle/SDK 의존성·targetSdk는 올리지 않았다.

첫 실행은 JBR25 C1 JIT의 `LinearScan::append_scope_value` SIGSEGV로 코드 검사 전 실패했다. [초기 JVM 실패](evidence/2026-11-07-monitor-service/build-initial-jbr-failure.txt). JDK21 재시도는 저장소의 daemon JVM25 기준 때문에 진행되지 않아 중단했다. 저장소 빌드 설정을 바꾸지 않고 기존 JBR25에 CLI `-XX:-TieredCompilation`·max-workers2를 적용해 최종 성공했다. 중간 Kotlin의 View 이름 충돌과 시험 import 누락도 수정한 뒤 전체를 다시 실행했다. 실패 실행을 합격에 포함하지 않았다.

```bash
cd src/android
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:-TieredCompilation' \
  --max-workers=2 :engine:test :app:testDebugUnitTest \
  assembleDebug assembleDebugAndroidTest processReleaseMainManifest
```

실제 Room 기기 runner **10개 통과**(기존 Room8 + REAL2). 메모리 DB와 시험별 고유 임시 DB만 사용했으며 기존 수동/REAL DB를 삭제하지 않았다. 실제 경과10초 저장·원자 교체 rollback·정밀값·nullable 전원·UNKNOWN 복원을 포함한다. [기기 runner 원문](evidence/2026-11-07-monitor-service/room-instrumentation.txt). 실제 수집 시나리오와 Room 단위 기기 시험의 숫자를 합산하지 않는다.

## 실제 AVD 시험

데이터 삭제 없이 cold boot한 기존 AVD에 `adb install -r --user 10`으로 교체 설치했다. Console이 사용하는 로컬 HTTP 입력/heartbeat를 사용했고 앱의 실제 SDK·Hub·Room 값으로 대조했다. HTTP200만으로 성공으로 세지 않았다. P/0에서 화면을 조작하고 D단에서 OS가 화면을 가리면 그대로 유지했다. 권한 철회 시험은 CAR_SPEED의 기존 허용을 확인한 후 수행하고 원래 허용으로 돌렸다. 재부팅 뒤 차속 권한이 거부된 상태도 확인했으므로 종료 전 화면의 **권한 확인 → 허용**으로 다시 허용하고 실제 새 SDK 입력까지 확인한다. 부팅 자체가 위험 권한을 보장한다는 뜻은 아니다. 회전 정책은 원래 `free`로 복구했다.

| 시험 | 실제 관찰과 근거 |
| --- | --- |
| 시작 연타 | 3회 시작 후 starts1/timers1, 같은 서비스 인스턴스. [03](evidence/2026-11-07-monitor-service/03-d-start-burst.json) |
| D단 OS 화면 제한 | 제한 화면을 우회하지 않은 동안 정상 기어 D 계산·Room 저장이 이어짐. [제한 화면](evidence/2026-11-07-monitor-service/04-os-blocked-screen.png), [저장 행](evidence/2026-11-07-monitor-service/04-os-blocked-drive-row.json) |
| Activity 끝내기 | 돌아가기 뒤 D 정상 입력을 같은 서비스가 계속 계산·저장. [06 행](evidence/2026-11-07-monitor-service/06-activity-finished-drive-row.json) |
| 화면만 꺼짐 | Dozing 상태에서도 D 정상 입력 구간의 저장 정밀값 484,660→494,857ms 증가. [꺼짐 상태](evidence/2026-11-07-monitor-service/07-screen-off-power.txt), [07 행](evidence/2026-11-07-monitor-service/07-screen-off-drive-row.json) |
| 재진입·회전 | 같은 서비스 인스턴스/starts1/timers1 유지. 실제 화면을 회전하고 원래 free/가로로 복구. [08](evidence/2026-11-07-monitor-service/08-reentered-single-owner.json), [09](evidence/2026-11-07-monitor-service/09-rotation-single-owner.json), [회전 화면](evidence/2026-11-07-monitor-service/09-rotation-screen.png) |
| SDK 단독 검증 충돌 | 서비스 소유 중 Probe 연결을 거절하고 현재 소유자 표시. [화면](evidence/2026-11-07-monitor-service/10-probe-conflict.png) |
| 명시적 중지 | UNKNOWN·requested=false·foreground=false·타이머0, 마지막 유효 P checkpoint 저장 성공. 대기 후 DB 논리 hash·성공 저장 수 동일. [11](evidence/2026-11-07-monitor-service/11-explicit-stopped.json), [대기 후12](evidence/2026-11-07-monitor-service/12-stopped-after-wait.json), [중지 행](evidence/2026-11-07-monitor-service/11-explicit-stop-row.json), [대기 행](evidence/2026-11-07-monitor-service/12-stopped-after-wait-row.json) |
| 알림 중지 직접 터치 | 실제 Pleos 알림 패널의 **감시 중지**를 눌러 requested/foreground/running=false, 타이머0, 마지막 저장 성공 확인. [버튼 화면](evidence/2026-11-07-monitor-service/30-notification-stop-button.png), [결과](evidence/2026-11-07-monitor-service/30-notification-stopped.json) |
| 프로세스 SIGKILL | 최종 별도 시험에서 화면을 열기 전 새 PID23823, `STICKY flags=0 id=2`, foreground/running=true·starts1/timers1, 같은 ID. [실제 client dump](evidence/2026-11-07-monitor-service/20-sticky-client-dump.txt), [이후 복원 원본/이벤트](evidence/2026-11-07-monitor-service/20-sticky-restored.json) |
| 사용자 강제 종료 | 프로세스·서비스 종료, 자동 수집 없이 재실행 UNKNOWN/같은 ID/581,258ms 복원. DB 논리 행 동일. [16 UNKNOWN](evidence/2026-11-07-monitor-service/16-force-stop-restored-unknown.json), [전후 행](evidence/2026-11-07-monitor-service/16-force-stop-preserved-row.json) |
| 차속 권한 상실 | 철회 시 OS가 프로세스를 종료. 재생성/화면 재개는 UNKNOWN으로 저장값 유지. 명시적 시작에서 차속 PERMISSION_DENIED/null, 기어 P는 독립적으로 AVAILABLE. 이후 권한 원복. [철회 복원](evidence/2026-11-07-monitor-service/22-permission-denied.json), [거부 입력](evidence/2026-11-07-monitor-service/23-explicit-start-speed-denied.json) |
| 감시 중 실제 재부팅 | 화면을 열기 전 BOOT 명령으로 자동 FGS·collector1/timer1, 같은 운행 ID, 원본 UNKNOWN 복원 뒤 새 정상 관측만 합산. [25 자동 서비스](evidence/2026-11-07-monitor-service/25-after-reboot-auto-service.txt), [26 복원](evidence/2026-11-07-monitor-service/26-after-reboot-restored.json) |
| 중지 뒤 실제 재부팅 | BOOT receiver 전달 후에도 서비스 없음·requested=false, 전후 checkpoint 논리 hash 동일. [receiver 전달](evidence/2026-11-07-monitor-service/28-stop-boot-receiver-delivery.txt), [서비스](evidence/2026-11-07-monitor-service/28-stopped-after-reboot-service.txt), [전후 동일 행](evidence/2026-11-07-monitor-service/28-stopped-after-reboot-row.json) |

초기 SIGKILL 뒤14/18번 표본에서는 새 서비스가 만들어졌지만 onStartCommand 미도착·수집 미재개 상태를 관찰했다. 초기 표본을 합격으로 쓰지 않는다([14](evidence/2026-11-07-monitor-service/14-initial-sticky-not-running.json), [18](evidence/2026-11-07-monitor-service/18-second-sticky-not-running.json)). 재바인딩이 대기 중 시작을 취소하지 않도록 수정하고 action 없는 복귀 Intent도 실행 의사가 있는 경우만 받도록 보완했다. 최종19/20은 Activity 종료를 확인하고 화면을 다시 열기 전에 서비스 자체 dump로 읽은 별도 시험이다. 초기 표본의 실제 원인은 확정하지 않았으며 정확한 자동 재생성 지연이나 모든 종료 경로의 자동 수집 재개를 보장하지 않는다. 권한 철회 뒤에도 일시적으로 UNKNOWN 복원/수동 재개가 필요한 경우를 관찰했다. 이때 사용자의 **감시 시작**이 재개 경로이며 과거 값으로 시간을 연장하지 않는다.

## 완료·남은 범위

| 구분 | 판정 |
| --- | --- |
| 완료 | 실제 SDK/Hub/운행/Room의 서비스 단일 소유, 알림·명시적 시작/중지, 화면 종료/꺼짐/재진입/회전 독립성, 저장/UNKNOWN/관측 공백, 시스템 sticky 재생성의 최종 관찰, 허용된 BOOT 자동 재개와 중지 후 비재개, 전용 저장공간·회귀/기기 기록 |
| 미구현 | AVD 미지원 실제 ignition 입력·물리 차량 시동/OFF 증명·특권 CarPowerManager 리스너. 이 이유로 가능한 서비스 작업을 중단하지 않았음 |
| 미검증 | 진짜 차량 STR/Doze로 CPU 실행 자체가 멎는 시험, 실제 CarService 강제 해제·기어 권한 철회/구독 강제오류, 실제15분/24시간 장기 대기, OS 시작 거부/알림 차단 시 bootPending 경로, OS Active apps 중지의 제조사별 기록 보존. 해당 규칙 자동시험과 구별 |
| 다른 담당 연결 대기 | 2번 판단, 3번 개입·음성, 4번 쉼터·휴식·전원 기반 인증, 5번 제품 화면·데모. 기존 코드와 기록은 인계 자료로 보존 |

기본형 시연의 서비스 경로를 구현한 것이며 실제 전원이 없는 상태에서 시동 OFF 휴식 인증까지 완성됐다고 표시하지 않는다. TRP_003 전체·물리 차량 검증 완료를 주장하지 않는다.


## 앞선 구현 검증의 최종 보존과 종료 상태

[검증 요약](evidence/2026-11-07-monitor-service/validation.json)의 조건별 관찰을 대조했다. 수동 `awakedrive_debug_trip.db`의 모든 논리 행 SHA256은 전후 **c9961514d35261a0aec539d34c3bb0123d8756c24f9f01625bef0a80dfd23d9f**로 같고 SQLite integrity도 ok다. 기존 `trip_checkpoint`·`drive_segment`·수동 개입 저장본을 보존했다. REAL DB는 기존 운행 ID와 구간을 유지하며 이번 실제 입력 시험의 정상 관측만 갱신했다. 시작 전 원본 DB/WAL과 논리 dump는 검증용 `/tmp/awakedrive-monitor-preserve/`에 별도 보존했고 공유 증거에는 필요 행만 기록했다. SDK close 예외 시 scope 취소 보완 뒤 전체433개와 APK 빌드를 다시 통과했고, 최종 교체 APK의 실제 SDK 시작/중지를 재확인했다([정상 입력](evidence/2026-11-07-monitor-service/32-final-apk-running.json), [중지](evidence/2026-11-07-monitor-service/33-final-apk-stopped.json)). 차속0/AVAILABLE·기어P/AVAILABLE·타이머1→0과 권한 원복을 확인했다. 설치 APK SHA256은 최종 빌드와 같다.

최종 화면은 **UNKNOWN·감시 중지·타이머0·마지막 관측 저장 성공**이다. [최종 화면](evidence/2026-11-07-monitor-service/33-final-stopped-screen.png), [서비스 상태](evidence/2026-11-07-monitor-service/33-final-service.txt), [최종 REAL 행](evidence/2026-11-07-monitor-service/33-final-real-row.json). Console은 P/0, CAR_SPEED는 원래 허용 상태, 회전 설정은 원래 free로 두었다. 시험용 Console heartbeat만 닫고 AVD·사용자 앱·DB를 제거하지 않았다. 다음 시작은 **권한 확인 → 감시 시작**이다.


## 사용자 직접 확인과 중간 커밋 (2026-11-07)

사용자가 오늘 직접 조작하여 **중복 시작 방지**, **화면 종료 중 감시·저장 지속**, **명시적 중지와 재진입 후 같은 운행 ID·누적 복원**을 확인했다고 보고했다. 보관본 `/tmp/awakedrive-user-monitor-20261107.json`을 읽어 로그와 실행 상태를 대조했다. 이 절은 앞선 구현 검증 종료 상태와 별도의 후속 관찰이며, 이번 커밋 준비 중 AVD를 다시 조작하거나 시험을 새로 실행한 결과는 아니다.

원본 SHA256은 **ef11dfecfa20225ae8f98d0ded6e052e1b713e0f5741e18dd4ab5c55351d932d**다. 원본은 `/tmp`에 보존하고 커밋에는 [검토 요약과 필요한 전환 로그](evidence/2026-11-07-monitor-service/user-check-20261107.json)만 남긴다. 원본 표본 시점은 감시 중이며 같은 운행 ID `4e5db99c-bae6-4990-bdfd-39c53d5f9145`, 서비스 수명 내 시작2회·활성 저장 타이머1개·입력 세대3·성공 저장39회를 보인다. 시작2회는 서비스 인스턴스 수명 누계이므로 중복 수집기2개로 해석하지 않는다. 화면 종료와 중지/재진입의 직접 관찰은 사용자 보고이며, 단일 진단 파일만으로 모든 버튼 동작의 전후 상태를 증명한다고 확대하지 않는다.

보관된 이벤트에서 **기어 STALE → UNKNOWN 1회**를 확인했다.

- `1794050565043`: 기어 null/STALE, 현재 UNKNOWN, 연속 **1,017,040ms**.
- `1794050565136`: 기어 D/AVAILABLE, 현재 DRIVING, 연속 **1,017,040ms**.
- 두 로그 시각의 차이는 **93ms**다. UNKNOWN 공백을 누적하지 않은 상태로 복귀했다. 이 값은 로그 간격이며 실제 센서 단절 시간·재조회 지연·원인을 증명하지 않는다. **STALE 발생 원인은 미해결**이다. 이번 중간 저장에서 TTL을 늘리거나 현상을 해결됐다고 표시하지 않았다.

기존 **자동시험433개(engine153+app280)·Room 기기 시험10개 통과**는 앞선 실행 기록으로 유지한다. 이번 기록 추가를 새 자동시험 통과로 세지 않는다. **실제 AVD 60배 재생의 예상외 null·발행 누락/최초 지연 목표 미달**, **변경하지 않은 음성 코드 `PleosSpeechRecognizer.kt:129`의 Lint 권한 오류1건**, 실제 STR·CarService 강제 장애·장기 대기·시작 제한 fallback 등 미검증 범위도 그대로 남는다. 이 중간 커밋은 구현·시험·문서와 개입/데모 인계 자료를 보존하며, 성능 문제나 모든 제품 통합의 완료 선언이 아니다.
