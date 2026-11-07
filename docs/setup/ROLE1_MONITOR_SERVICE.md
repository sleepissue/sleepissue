# REAL 운행 감시 서비스 사용·인계 가이드

2026-11-07 기준. 기존 `develop-jdw`의 실제 Playground SDK → Hub → 운행 계산 → Room 연결을 재사용한다. REAL은 Pleos Playground·AAOS·Virtual Console 입력이며 물리 차량 검증을 뜻하지 않는다. 이번 범위는 1번의 수집·운행·저장·복원이다. 다른 담당의 판단·개입·음성·쉼터·휴식·DemoPlayer·60배 재생을 이 서비스에 통합하지 않는다.

이 문서는 구현 계약과 사용 절차를 설명한다. 자동시험 수·기기 시험의 수행 여부·관찰 결과는 [감시 서비스 검증 기록](../devlog/2026-11-07-monitor-service.md)을 따른다. 아래 구현 설명만으로 기기 시험 합격을 의미하지 않는다. 이전 화면 소유 연결의 기록은 [REAL 운행 연결](ROLE1_REAL_TRIP.md), [기존 검증 기록](../devlog/2026-11-07-real-trip.md)에서 별도로 확인한다.

## 화면에서 서비스로 옮긴 것

`RealTripMonitorService → RealTripMonitorRuntime → PlaygroundMonitorInput → PlaygroundSignalAdapter → SdkSignalStore → REAL 전용 VehicleSignalHub → PlaygroundTripSession → 기존 운행 규칙·저장 조정기 → RoomTripRepository → awakedrive_sdk_trip.db`

서비스가 SDK 어댑터·Hub·운행 collector·저장 주기·Room 연결의 수명을 소유한다. `RealTripMonitorRuntime`은 Android 화면과 무관한 시작·중지 조정기다. 코루틴 scope는 ROS 노드 수명, Flow collector는 토픽 구독에 해당한다. 화면은 서비스에 바인딩하여 상태를 읽고 시작·중지·저장 명령을 전달한다. 바인딩은 서비스와 화면 사이의 연결이며, 화면을 닫는 동작 자체가 감시 중지는 아니다.

기존 **Playground SDK 운행·Room** 진입점은 유지하고 화면 본체를 `RealTripMonitorActivity`로 바꿨다. 화면을 열면 SDK를 연결하기 전에 Room을 읽어 UNKNOWN 상태를 표시한다. **감시 시작**이 있어야 foreground 감시를 시작한다. 화면 종료·재진입·회전은 SDK를 새로 만들지 않는다. 시작 명령은 직렬화하고 이미 실행 중이면 같은 수집을 유지한다. 화면의 `수집 시작 N회`는 해당 서비스 인스턴스 안에서 성공한 시작 횟수이며 누적 앱 실행 횟수가 아니다. `활성 저장 타이머 1개`는 서비스 소유 주기가 하나라는 의미다.

## Android 실행 근거와 제한

현재 `app/build.gradle.kts`는 targetSdk 34, compileSdk 37이며 검증 대상 Pleos Connect AVD는 API 34다. Android 14부터 targetSdk 34 이상 앱의 FGS에는 실제 작업에 맞는 유형과 유형별 권한이 필요하다. [공식 변경 사항](https://developer.android.com/develop/background-work/services/fgs/changes#android-14)

이번 서비스는 **`specialUse`**를 선언한다. 공식 문서는 다른 유형에 포함되지 않는 유효한 FGS 사용례를 이 유형으로 설명하고 `FOREGROUND_SERVICE_SPECIAL_USE`와 서비스 내부 subtype property를 요구한다. 이 서비스는 사용자가 시작한 차량 내부 SDK의 지속적 관측·로컬 운행 저장을 subtype에 명시한다. 이는 공식 범주에 이번 기능을 적용한 판단이며, Play 또는 Pleos 배포 심사를 통과했다는 뜻은 아니다. `connectedDevice`의 외부 BT/NFC/IR/USB/network 전제나 `systemExempted`의 시스템 자격을 감시 서비스에 임의로 적용하지 않는다. [공식 유형 계약](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)

선언 권한은 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`다. 기존 차량 조회 권한은 그대로 사용한다. 마이크·위치 FGS 유형을 추가하거나 3번의 마이크 서비스를 시작하지 않는다. 현재 입력의 전원·조향 의미 보류 등 기존 신호 정책도 이 선언으로 해결되는 것은 아니다.

**감시 시작**은 보이는 Activity에서 사용자가 누른다. 서비스는 먼저 알림을 게시하고 foreground로 승격한 뒤 SDK 초기화를 진행한다. 시작 제한·권한 오류를 잡아 상태로 남기며 반복 예약·오버레이·배터리 최적화 해제 등으로 제한을 우회하지 않는다. 알림은 LOW 채널의 **AwakeDrive 운행 감시 중**, 동작은 **감시 중지**다. [공식 시작 절차](https://developer.android.com/develop/background-work/services/fgs/launch)

Android 자체는 알림 권한 거절만으로 FGS 시작을 금지하지 않는다. 다만 알림 서랍에서 알림과 중지 동작을 확인할 수 있도록 이 구현은 알림 허용 후 시작하도록 한다. 권한 또는 앱 알림이 꺼져 있으면 화면에서 허용하고 다시 **감시 시작**을 누른다. 이 정책은 OS 필수 조건과 구분한다. [공식 알림 권한 설명](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

서비스는 `START_STICKY`를 반환한다. Android 공식 계약은 시스템이 sticky FGS를 재생성하는 경우를 백그라운드 신규 시작 제한과 구분한다. 재생성 시 저장된 실행 의사가 있어야 다시 수집한다. 이는 재생성 시각이나 무중단 실행 보장이 아니다. [공식 START_STICKY 계약](https://developer.android.com/reference/android/app/Service#START_STICKY)

## SDK 단일 소유권

같은 앱 프로세스의 `PlaygroundSdkOwnership`이 실제 SDK 소유자를 하나로 제한한다. 서비스 소유자 이름은 **REAL 운행 감시 서비스**, 기존 원시 검증 화면은 **SDK 단독 검증 화면**이다. 하나가 실행 중이면 다른 쪽은 현재 소유자를 표시하고 연결을 거절한다. 이전 사용처에서 **감시 중지** 또는 **중지**를 누르고 정리가 끝난 뒤 다음 사용처에서 시작한다. 이전 lease의 늦은 해제는 새 소유권을 해제하지 않는다.

여기서 SDK 소유권 lease는 앱 안의 수집기 중복 방지용이다. 기어 상태 확인 lease는 최근 조회가 현재 기어를 언제까지 증명하는지 나타내는 별도 신호 계약이다. 둘을 혼동하지 않는다. DEMO·수동 검증은 실제 SDK 소유권을 획득하지 않으며 이번 서비스의 입력으로 연결하지 않는다.

## 시간·저장·UNKNOWN 계약

- 운행 논리 시각은 기존 `AnchoredTripClock`의 **시작 날짜 + 현재 실행의 단조 경과**다. 저장 주기는 별도의 실제 `elapsedRealtime` 10초다. 배속·수동 시계를 쓰지 않는다.
- 단일 저장 주기가 저장 조정기를 확인하고, 상태 전환·입력 상실에는 필요한 즉시 저장을 요청한다. **즉시 저장**은 현재 관측이 있을 때만 저장한다. 연결 전·중지 후에는 원본 checkpoint를 UNKNOWN으로 덮어쓰지 않는다.
- 명시적 중지는 collector·Hub 주기·저장 주기를 취소하고 SDK 구독·조회·연결을 해제한 뒤 마지막 확정 상태를 저장한다. 활성 상태는 UNKNOWN으로 바꾼다. 서비스 종료를 OFF로 저장하지 않는다. 새 정상 입력이 없거나 저장이 실패하면 마지막 저장 성공을 주장하지 않는다.
- 권한 상실·연결 해제·조회/구독 오류는 기존 상태형 신호의 값과 유효 확인을 무효화한다. 과거 D/P로 장애 발견 시각까지 계산하지 않고 마지막 정상 처리 프레임에서 끊는다.
- 절전 중에는 Hub 순번이 연속이어도 관측이 비어 있을 수 있다. 새 기어 확인이 이전 확인 lease 끝을 지나서 도착하면 마지막 정상 관측에서 구간을 끊는다. 복귀 후 조회 성공만으로 그 사이 운전·정차·휴식을 증명하지 않는다.
- Room 복원은 원본 snapshot의 ID·정밀 ms·구간·최근 누적을 검증하고, 활성 상태는 UNKNOWN으로 재개한다. 저장된 P가 복원 뒤 새 P까지 이어졌다고 가정하지 않는다. 날짜 역행·손상·다른 시간축은 원본 DB 보존과 저장 중지로 처리한다.

`elapsedRealtime`은 deep sleep을 포함하지만 부팅 이후의 값이다. 재부팅 전후 저장 elapsed를 직접 차감하지 않는다. 날짜 기반 checkpoint를 검증한 뒤 새로운 단조 시계를 앵커하고 미관측 공백을 제외한다. FGS가 CPU의 deep sleep을 막거나 잠든 동안 정확히 10초마다 실행된다고 설명하지 않는다. wake lock·정확한 알람으로 시스템 절전을 우회하지 않는다. [SystemClock 계약](https://developer.android.com/reference/android/os/SystemClock), [Doze 설명](https://developer.android.com/training/monitoring-device-state/doze-standby)

## 종료·절전·재부팅별 동작

| 상황 | 구현상 동작과 복원 의미 |
| --- | --- |
| 화면만 꺼짐 | 명시적 중지가 아니므로 서비스는 실행 의사를 유지한다. CPU가 실행되고 정상 입력을 받는 구간은 계속 계산·저장한다. 실제 suspend로 실행이 멈춘 구간은 확인 lease 단절로 제외한다. 화면 꺼짐을 시동 OFF로 만들지 않는다. |
| Activity 종료·뒤로 가기·회전 | 화면의 상태 구독·바인딩만 끝난다. 이미 foreground 감시 중이면 수집을 유지한다. 감시를 시작하지 않은 채 열었던 서비스는 화면 이탈 후 정리한다. |
| 앱 프로세스가 시스템에 의해 종료·재생성 | 마지막 성공 checkpoint가 근거다. `onDestroy`나 마지막 저장은 보장되지 않는다. 실행 의사가 유지되고 OS가 sticky 서비스를 재생성하면 UNKNOWN 복원 후 새 SDK 관측으로 재개한다. 시간 공백은 합산하지 않는다. |
| 화면/알림의 **감시 중지** | 실행 의사를 먼저 false로 디스크에 기록한다. 수집·주기 정리와 마지막 유효 상태 저장을 수행하고 foreground 알림을 제거한다. 재진입·sticky·부팅으로 자동 시작하지 않는다. |
| OS Active apps 중지·설정의 강제 종료 | 앱 전체가 콜백 없이 종료될 수 있다. 마지막 저장 시도를 보장하지 않는다. 다음 서비스 생성/부팅 처리에서 `ApplicationExitInfo.REASON_USER_REQUESTED` 기록이 감시 요청 이후에 있으면 실행 의사를 철회한다. 강제 종료 후 자동 재시작을 보장하지 않는다. |
| 실제 기기 재부팅 | 사용자 unlocked 이후 `BOOT_COMPLETED`에서 실행 의사·사용자 종료 이력을 확인한다. 실행 의사가 남고 OS가 허용하면 비마이크 specialUse 감시를 다시 시작한다. 기존 ID·누적을 UNKNOWN으로 복원하고 새 관측 전까지 현재 기어·전원을 확정하지 않는다. |
| 부팅 시작이 제한됨·알림 비허용 | 반복 자동 시작하지 않는다. `bootPending`을 남기고 자동 실행 의사를 철회한다. 저장본은 유지하며 화면에 **재부팅 뒤 수동 재개 대기 · 저장본 보존**을 표시한다. 사용자가 화면에서 조건을 확인한 후 **감시 시작**으로 재개한다. |

`BOOT_COMPLETED`는 공식 백그라운드 FGS 시작 예외이며 specialUse는 현재 제한 유형에 포함되지 않는다. 마이크는 부팅 경로로 시작하지 않는다. 이 앱의 receiver는 잠긴 사용자 저장소를 여는 `LOCKED_BOOT_COMPLETED` 경로를 사용하지 않는다. OEM 정책·사용자 제한에 따른 실패는 수동 재개로 처리한다. [백그라운드 시작 예외](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [유형별 부팅 제한](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed), [BOOT_COMPLETED 저장소 계약](https://developer.android.com/reference/android/content/Intent#ACTION_BOOT_COMPLETED)

OS Active apps 중지에는 콜백이 없다는 점과 강제 종료·정상 중지의 차이를 유지한다. 종료 이력은 실행 의사를 보수적으로 철회하는 추가 근거이며 모든 제조사·OS의 사용자 종료 추적을 보장하는 API가 아니다. [공식 사용자 중지 설명](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)

## 실제 전원은 UNKNOWN

공개 AAOS `VehiclePropertyIds.IGNITION_STATE`는 `289408009`(`0x11400409`), READ/GLOBAL/ON_CHANGE/Integer이고 `CAR_POWERTRAIN` 일반 권한으로 읽는다. `VehicleIgnitionState`는 OFF·ON·ACC·START·LOCK·UNDEFINED를 구분한다. 따라서 앱 종료나 AP 절전 자체를 이 값으로 대체할 수 없다. [공식 속성](https://developer.android.com/reference/android/car/VehiclePropertyIds#IGNITION_STATE), [공식 상태 의미](https://developer.android.com/reference/android/car/VehicleIgnitionState)

이번 API 34 AVD의 읽기 조사에서 `dumpsys car_service --hal PropertyHalService`의 지원 config 312개에 `IGNITION_STATE`는 없고 기존 `GEAR_SELECTION`과 `PERF_VEHICLE_SPEED`는 있다. `CAR_POWERTRAIN`은 normal, `CAR_POWER`는 signature/privileged/vendorPrivileged로 확인했다. `PowerHalService`의 절전 지원 표시는 시스템의 AP 전원 관리 지원이며 일반 앱이 시동 상태를 받았다는 증거가 아니다. 원시 전체 dump에는 비밀값이 포함될 수 있으므로 공유용 근거는 필요한 ID 지원 여부만 남긴다.

API 34의 `CarPowerManager.setListener/getPowerState`는 AOSP에서 `@SystemApi`·`CAR_POWER`가 필요하며 공개 `android.car.jar`에 없다. 반사나 특권 부여로 연결하지 않는다. 공개 power-policy도 차량 시동 ON/OFF의 증명이 아니다. 이 환경에서는 기존 전원을 null/UNKNOWN으로 유지하며 기어 기반 운행·서비스·저장 작업은 계속한다. [Android 14 공식 CarPowerManager 소스](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android14-release/car-lib/src/android/car/hardware/power/CarPowerManager.java), [AAOS 전원 구조](https://source.android.com/docs/automotive/power/power)

## 직접 누를 순서와 기대값

1. AVD·Console의 **AVD Enabled**, **P**, **속도 0**을 확인한다. 앱의 **Playground SDK 운행·Room**을 연다. 복원 `EMPTY` 또는 `RESTORED`를 기다린다. 저장 이력이 있으면 같은 운행 ID·누적을 보여도 현재 기어·운행·전원은 UNKNOWN이다.
2. **권한 확인**에서 차속과 알림을 허용한다. **감시 시작**을 누른다. `foreground true`, `활성 저장 타이머 1개`, SDK 연결·기어 `AVAILABLE`을 확인한다. 시작을 여러 번 눌러도 같은 실행 중 `수집 시작 1회`와 타이머 1개를 유지해야 한다. 알림 **AwakeDrive 운행 감시 중**도 확인한다.
3. Console을 **D**로 바꾸고 10초 이상 정상 입력을 유지한다. 처음 D에서 운행이 생기고 연속·최근 누적이 증가한다. 저장 성공 횟수와 저장 시각이 갱신되는지 확인한다. D단에서 OS가 화면을 가리면 접근을 우회하지 말고 수집을 그대로 유지한 뒤 P/0으로 돌아와 기록을 확인한다. 이번 실제 관찰 결과와 실제 차량 이동 검증 여부는 검증 기록에서 구분한다.
4. **돌아가기**로 화면을 닫았다 다시 같은 화면을 연다. 감시를 중지하지 않았다면 수집 시작 횟수·단일 타이머를 유지하고 정상 관측 구간의 시간이 이어져야 한다. 화면 꺼짐 시험도 정상 입력이 계속 전달된 구간과 실제 절전 공백을 구분해서 기록한다.
5. Console을 **P**로 바꾼 뒤 **즉시 저장 → 진단 저장**을 누른다. 저장 성공·운행 ID·정밀 누적·현재 기어를 확인한다. 진단은 `cache/sdk-trip-probe.json`이며 `serviceInstance`, PID, `starts`, `activeTimers`, `sdkOwner`, `requested`, `bootPending`을 함께 기록한다. 오래된 진단 파일을 현재 상태로 읽지 않는다.
6. **감시 중지** 또는 알림의 **감시 중지**를 누른다. 현재 UNKNOWN, 활성 저장 타이머 0개, 마지막 저장 결과, SDK 소유권 해제·알림 제거를 확인한다. 그대로 기다리거나 화면을 다시 열어도 새 수집·자동 저장을 시작하지 않아야 한다. 다음 재개는 **감시 시작**이다.
7. SDK 단독 검증이 필요하면 감시를 중지한 뒤 **Playground SDK 실시간 검증 → 연결**을 누른다. 반대쪽이 소유 중이면 현재 소유자를 안내해야 한다. 단독 검증 화면에서 **중지**한 뒤 감시를 다시 시작한다.
8. 강제 종료·프로세스 종료·실제 재부팅 시험은 먼저 저장 성공을 확인하고 각각 따로 기록한다. 복원 시 같은 저장 ID·정밀값과 현재 UNKNOWN을 대조한다. 명시적 중지 후 재부팅은 자동 시작하지 않아야 한다. 실행 중 재부팅은 허용된 자동 재개 또는 명시된 수동 재개 상태를 확인한다. 재부팅 공백을 운전·정차·휴식에 더하지 않는다.

Debug 호환 플래그 `sdkTripAutoConnect`는 보이는 검증 화면에서 시작 명령을 요청하는 시험용 기능이며 권한·알림 조건을 건너뛰지 않는다. `sdkTripCapture`는 화면이 떠 있는 동안 진단을 주기적으로 저장하는 기능이다. 화면이 닫힌 동안의 수집·Room 저장과 진단 파일 갱신은 서로 다르다.

## 저장공간과 인계 상태

REAL checkpoint는 기존 **`awakedrive_sdk_trip.db`**를 재사용한다. 수동 **`awakedrive_debug_trip.db`**, DEMO 입력·시계·상태와 섞지 않는다. 실행 의사와 부팅 재개 대기는 별도 SharedPreferences **`real_trip_monitor`**에 둔다. 진단 cache는 DB가 아니다. Room 스키마·기존 수동 개입 직렬화·사용자 데이터를 삭제하거나 초기화하는 경로를 추가하지 않는다. 기기 시험별 임시 저장공간은 검증 기록에서 구분한다.

| 구분 | 범위 |
| --- | --- |
| 구현 | 서비스의 SDK·Hub·운행·저장 단일 소유, 화면의 상태/명령 역할, foreground 알림·명시적 중지, 실행 의사 보존, sticky/부팅 재개와 제한 시 수동 경로, UNKNOWN 복원·관측 공백 분리. 실제 시험 결과는 검증 기록 참조. |
| 미구현 | 이 AVD가 제공하지 않는 실제 ignition 입력. 특권 AP 전원 리스너·물리 차량 시동/절전 인증. 전원 미연결을 다른 신호로 대신하지 않음. |
| 미검증 | 실제 기기에서 아직 수행하지 않은 항목은 검증 기록에 명시한다. 자동시험 통과만으로 화면 꺼짐·Activity 종료·프로세스 재생성·강제 종료·실제 재부팅·이동 중 OS 제한 전체를 합격 처리하지 않는다. |
| 다른 담당 연결 대기 | 2번 판단 엔진, 3번 개입·음성, 4번 쉼터·휴식, 5번 제품 화면·데모 소비 연결. 기존 코드·시험·기록은 보존한다. |
