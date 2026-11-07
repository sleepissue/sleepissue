# 실제 Playground 입력 → 운행 → Room 연결

2026-11-07 감시 서비스 작업으로 갱신. 실행 조건·화면 독립 동작은 [감시 서비스 가이드](ROLE1_MONITOR_SERVICE.md), 시험 결과는 [서비스 검증 기록](../devlog/2026-11-07-monitor-service.md)을 함께 따른다. 아래 과거 화면 소유 설명은 서비스 구현 전 기록과 구별한다. REAL은 Pleos Playground SDK·AAOS·Virtual Console 입력이며 물리 차량 검증을 의미하지 않는다. 새 [간트](../plan/AwakeDrive_일정_v1.gan)에 따라 이번 범위는 1번 신호·운행·저장이다. 기존 개입 구현은 3번, DemoPlayer·배속 구현은 5번에게 코드·시험·기록 그대로 인계한다.

## 데이터 흐름

`PlaygroundSignalAdapter → SdkSignalStore → REAL 전용 VehicleSignalHub → PlaygroundTripSession → TripTracker → TripRules → TripStateStore → TripSaveCoordinator → RoomTripRepository → awakedrive_sdk_trip.db`

`RealTripMonitorService`가 실제 SDK·Hub·운행·저장 타이머를 소유한다. 기존 debug `Playground SDK 운행·Room` 진입점은 서비스 상태 화면으로 이어진다. 순수 Kotlin 규칙은 Android를 모른다. 코루틴 scope는 ROS 노드의 수명, Flow 수집은 토픽 구독에 해당한다. 화면 중지는 구독을 종료하지 않으며 명시적 감시 중지만 종료 명령이다. 다른 담당 졸음 판단·음성·쉼터·휴식·개입 관리자는 생성하지 않는다. Vehicle capability 초기 확인의 reflection 비용은 작업 스레드에서 처리하고 상태/콜백 조립은 main으로 전달한다.

수동 `awakedrive_debug_trip.db`와 별도 DB를 사용한다. JSON DEMO·가짜 Hub·수동 시계는 공유하지 않는다. Room 스키마 1과 기존 개입 직렬화 형식은 유지하고, REAL 소유자는 `REAL_TRIP_V1` 형식으로 nullable 기어/전원을 저장한다. 파괴적 마이그레이션·앱 제거·DB 삭제 경로를 사용하지 않는다.

## 기어와 전원 정책

[공식 GEAR_SELECTION](https://developer.android.com/reference/android/car/VehiclePropertyIds#GEAR_SELECTION)은 READ·GLOBAL·Integer·ON_CHANGE다. [공식 CarPropertyValue timestamp](https://developer.android.com/reference/android/car/hardware/CarPropertyValue#getTimestamp())는 elapsedRealtimeNanos와 같은 시간축의 원천 값 발생 시각이다. [Android 14 공식 CarPropertyManager 소스](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android14-release/car-lib/src/android/car/hardware/property/CarPropertyManager.java)의 등록 계약상 ON_CHANGE에는 지정 rate가 적용되지 않는다. 주기 센서로 해석하지 않는다. 최초 `getProperty`와 변경 콜백을 받고, 기어만 별도로 현재 상태를 재조회한다.

| 항목 | 의미와 정책 |
| --- | --- |
| `observedAtMs/nativeMs` | AAOS가 제공한 원천 변경/값 발생 시각. Hub 발행이나 재조회 완료 시각으로 바꾸지 않음 |
| `receivedAtElapsedMs/receivedMs` | 실제 콜백 또는 최초/변경 값 조회 배송을 받은 시각. 같은 상태 재조회에서 유지 |
| `stateConfirmedAtElapsedMs/confirmedMs` | 조회 응답으로 현재 상태를 확인한 시각. 센서 Hz·콜백 수·관측 순번에 더하지 않음 |
| `stateLeaseUntilElapsedMs` | 기어 상태 확인의 유효 끝. [가정] 1초 재조회·확인 후 3초 lease, 경계 포함 |
| 다른 연속 신호 | 기존 검증 TTL 3초 유지. 전체 TTL을 늘리지 않음 |
| 부분 갱신 | `updatedFields`에 없는 필드는 유지. 포함된 null/UNAVAILABLE/ERROR는 명시 무효화 |
| 실패 | 권한 상실·CarService 해제·구독/조회 오류에 값과 확인 lease 무효화. 과거 D/P로 계속 계산하지 않음 |
| 재연결 | 새 어댑터·Hub 입력 세대. 중복 ready 콜백은 무시하고 이전 세대 조회/콜백은 폐기 |
| 전원 | 실제 전원 API·권한·의미 미확인으로 **미연결**. null/UNKNOWN·화면 종료·프로세스 종료를 OFF로 만들지 않음 |

SDK의 조향 전륜각·착석 Boolean 의미 보류는 기존대로다. 통풍·크루즈 등 모든 ON_CHANGE 항목의 제품 유지 정책을 확정한 것은 아니며 이번에는 운행에 필요한 기어를 연결했다.

## 운행과 시간

첫 D에서 ID·운행 시작을 만든다. 시작 전 P는 운행 시작이 아니다. 시작 후 D/R/N은 운행, P는 정차이며 속도 0을 P로 바꾸지 않는다. [가정] 기존 검증과 같은 `V03_RETROACTIVE/EXCLUDE`를 명시 선택한다. P 5분 미만은 연속에 잠정 포함, 정확히 5분부터 정차분 소급 제외, 15분부터 연속 0이다. 최근 24시간 누적은 정차를 제외하고 자정·휴식·같은 운행 재연결로 초기화하지 않는다.

세션 운행 시각은 `AnchoredTripClock`의 **시작 날짜 + 실제 단조 경과**다. 실행 중 기기 날짜를 변경해도 운행 시간이 뛰지 않는다. 저장 주기는 별도 `elapsedRealtime`의 실제 10초이며 배속하지 않는다. 기어/운행 상태 전환·입력 상실·명시적 감시 중지·정상 서비스 종료에는 즉시 저장한다. 화면만 닫으면 같은 수집·저장을 계속한다. 저장된 논리 시각은 데이터 시점이고 날짜/elapsed는 캡처 시점이다. 성공 여부는 Room transaction 결과로 표시한다.

운행 collector에서 Room I/O를 기다리지 않는다. Hub 발행 순번 누락이 발견되거나 입력이 무효해지면 **마지막 정상 처리 프레임 시각에서** 먼저 관측을 끊는다. 장애 발견 시각까지 이전 상태를 연장하지 않으며 마지막 tick 일부를 보수적으로 합산하지 않을 수 있다. 늦은 이전 세션 입력·DEMO·역행/중복 발행은 운행을 갱신하지 않는다.

## 복원

화면을 열면 전용 DB부터 읽고 원본 저장본의 ID·정밀 ms·정차 시작/정차 전 기준·운전 구간·최근 누적을 검증한다. 원본 snapshot은 진단과 복원 전 기록으로 남긴다. 활성 Tracker는 저장 시각에서 기존 입력을 끝내고 현재까지 UNKNOWN으로 전진한다. 새 SDK 입력 전에는 현재 기어·전원·운행/정차를 확정하지 않는다.

앱을 닫은 시간은 운전·정차·OFF로 더하지 않는다. 과거 정차가 새 P까지 계속됐다고 증명할 수 없으므로 활성 정차 시작은 새 P 시각부터 다시 잡는다. 원본 정차 근거와 현재 활성 상태를 화면 이벤트/진단에서 구분한다. 날짜가 저장 시각보다 뒤로 간 경우 자동 저장을 중지하고 원본 DB를 보존한다. 재부팅·절전의 전원 증명/시간 변환까지 완료했다는 뜻은 아니다.

복원 실패·손상·다른 시간축이면 DB를 덮어쓰지 않고 실패/자동 저장 중지를 표시한다. 복원만으로 과거 음성·내비 요청을 재실행하지 않는다. 새 SDK 연결 전·중지 뒤에는 자동 저장으로 원본 정차 checkpoint를 UNKNOWN으로 덮지 않는다.

## 직접 누를 순서

현재 버튼은 **권한 확인 → 감시 시작 / 감시 중지 / 즉시 저장 / 진단 저장 / 돌아가기**다. 예전 연결·재연결 버튼과 화면 종료=중지 설명은 더 이상 현재 동작이 아니다. **돌아가기**는 화면만 닫는다. 알림 또는 **감시 중지**로 끝낸다.

상세 실행·시작 연타·화면 종료/꺼짐·복원·부팅 절차와 기대값은 [서비스 가이드](ROLE1_MONITOR_SERVICE.md#직접-누를-순서와-기대값)를 따른다. 새 정상 입력 전 UNKNOWN을 확인하려면 자동 연결 debug 플래그 없이 전용 화면을 연다.

## 후속 인계

- 감시 서비스 소유권·FGS 시작/종료·SDK 중복 방지는 서비스 작업에서 구현했다. 결과와 범위는 [서비스 기록](../devlog/2026-11-07-monitor-service.md)을 따른다.
- 절전 진입/복귀·실제 전원 입력 API/권한/의미 확인. UNKNOWN과 OFF 구분 유지.
- BOOT_COMPLETED·실제 재부팅·단조 경과 초기화·관측 공백 처리는 [서비스 기록](../devlog/2026-11-07-monitor-service.md)의 최신 판정으로 구분한다.
- 3번 개입/음성, 4번 쉼터/휴식, 2번 판단 엔진 소비 연결은 각 담당 통합 대기. 5번 데모·60배 작업은 보존된 별도 경로 인계.
- 화면 소유 SDK→운행→Room 성공을 TRP_003 전체 완료나 SIG 전체 TC 합격으로 표시하지 않는다.

최종 자동414개·Room 기기10개 통과. D15초 유지·D→P→D·강제 종료/재실행·UNKNOWN 복원·재연결·차속 권한 철회/복구와 기존 수동 DB 보존을 기기에서 확인했다. 실제15분/24시간 경과·실제 CarService 장애·이동 중 제한 경로는 미검증이다. 화면의 자동 저장 정책이 켜져 있어도 연결 전/중지 후에는 캡처·DB 쓰기를 하지 않는다.

기존 화면 소유 구현 당시 자동시험·기기 결과와 미검증 항목은 [개발 기록](../devlog/2026-11-07-real-trip.md)에 기록한다.
