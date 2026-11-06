# Playground SDK 입력 연결·실시간 검증

`PlaygroundSignalAdapter`는 실제 Vehicle 2.3.2·NaviHelper 3.0.5와 AAOS를 읽어 별도 `VehicleSignalHub`에 `Source.REAL`로 전달한다. `REAL`은 Playground 가상 차량에서 받은 입력이라는 뜻이다. 물리 차량 검증이나 가짜 REAL 패널의 성공을 뜻하지 않는다.

운행·개입·Room 화면과 가짜 Hub 화면을 보존했다. SDK 화면에는 Tracker·TripStateStore·Room·가짜 입력·음성/경유지 실행을 연결하지 않는다. 앱을 닫아도 저장 운행에 OFF를 만들지 않으며, SDK 경과 시간을 수동 운행에 더하지 않는다.

## 확인한 근거

- 원본 `/home/a/Downloads/AwakeDrive_SRS_TC_v0.2.xlsx`: SIG_001~017, PLT_012, MSG-01/14. `Sys Req Spec`·`Test Case`의 PLT_012는 **192행**, targetSdk 34다. 원본의 Draft/미승인 상태는 바꾸지 않았다. SHA-256 `2ea8c6d0baf3040f146fbbab0d87883f12108354411ffaad1f8e6fb1e95e69da`.
- [Vehicle 공식 API](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/entry), [공식 샘플](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/tree/959981d960ec71144accb27af5ae5d341e72a644)의 VehicleClient/VehicleSession, 샘플 매니페스트. 초기화·capability 뒤 구독·해제/release 구조를 대조했다. 샘플의 리플렉션 탐색기는 복사하지 않았다.
- [공식 Maven](https://nexus-playground.pleos.ai/repository/maven-releases/)에서 설치 버전과 같은 공개 `-sources.jar`를 읽었다. `ai/pleos/playground/{artifact}/{version}/{artifact}-{version}-sources.jar`: Vehicle 2.3.2, NaviHelper 3.0.5, vehicle-data 1.1.11, navi-data 3.0.4. 아래 표의 시그니처·주석·단위는 이 소스를 기준으로 한다. SDK 소스/바이너리를 저장소에 추가하지 않았다.
- [AAOS 속성](https://developer.android.com/reference/android/car/VehiclePropertyIds), [CarPropertyManager](https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager), [CarPropertyValue 원천 시각](https://developer.android.com/reference/android/car/hardware/CarPropertyValue#getTimestamp()), 설치된 API 34 `android.car.jar` 공개 서명을 확인했다.
- [공식 Console 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator), 실행 중인 Console이 제공하는 HTML·`/vehicle/catalog`의 제어 항목과 단위를 대조했다.

공개 소스 SHA-256:

| artifact | SHA-256 |
| --- | --- |
| Vehicle | `62b5dd2c4c0523fd13c2e64040cd775bc89a14ce54f35a37560d0eb4eb3fab40` |
| NaviHelper | `915feb7246215e6999f4695ba6b51cf31e4c6a5d8a8c1b36f16a3ba353569201` |
| vehicle-data | `54094b98cfb7b1d2fa7fab743b0e48074da1d9f4bd2a2cbd3ad4a9914de18f65` |
| navi-data | `4da974b6b0050662c4b346d3fca96073c8c1f241ce10c7d667cfef64cbceedaf` |

## 신호별 API·단위·제약

권한 약어 `P.*`는 `pleos.car.permission.*`, `A.*`는 `android.car.permission.*`다. Vehicle 권한은 해당 공개 소스에서 normal로 명시된다. AAOS 차속은 기기에서 런타임 동의를 요청한다. 내비 조회에는 `P.NAVI_ROUTE`를 사용하며 경로 제어 권한 `NAVI_ROUTE_SEARCH`를 추가하지 않는다.

| 신호 | capability → 최초 조회 → 구독 / 해제 | 권한·원단위 → 내부 값 |
| --- | --- | --- |
| 조향 | `checkSteeringWheelAngleCapabilities` → `getCurrentSteeringWheelAngle` → `register/unregisterSteeringWheelAngle` | P.CAR_DRIVE. **전륜 조향각 °**, 왼쪽 음수. 운전대 회전각이 아니라는 소스 설명이 있으므로 원시값만 표시하고 `steeringDeg=null/UNSUPPORTED` |
| 차속 | AAOS `getCarPropertyConfig(PERF_VEHICLE_SPEED)` → `getProperty` → `register/unregisterCallback` | A.CAR_SPEED. 부호 있는 m/s → 속력 `abs × 3.6` km/h. 전후진 구분은 기어에 둠 |
| 기어 | 같은 AAOS 절차, `GEAR_SELECTION` | A.CAR_POWERTRAIN. `VehicleGear` 상수 P/R/N/D만 매핑. 알 수 없는 값은 null |
| 방향지시등 | `checkTurnSignalStateCapabilities` → `getCurrentTurnSignalState` → `register/unregisterTurnSignalState` | P.CAR_LIGHTS. OFF/LEFT/RIGHT. SDK enum에 없는 HAZARD를 만들어 내지 않음 |
| 브레이크 | `checkBrakePedalStatusCapabilities` → `getCurrentBrakePedalStatus` → `register/unregisterBrakePedalStatus` | P.CAR_DRIVE. Boolean?를 그대로 사용 |
| 크루즈 상태 | `checkCruiseControlStateCapabilities` → `getCruiseControlState` → `register/unregisterCruiseControlState` | P.CAR_DRIVE. ACTIVATED=true, ENABLED/SUSPENDED=false. OTHER/USER_OVERRIDE/FORCED_DEACTIVATION_WARNING/null은 미확정 null. 이 이진 투영은 앱의 보수적 연결 정책 |
| 크루즈 목표 속도 | `checkCruiseControlTargetSpeedCapabilities` → `getCruiseControlTargetSpeed` → `register/unregisterCruiseControlTargetSpeed` | P.CAR_DRIVE. 소스 주석의 **m/s × 3.6 → km/h** |
| 좌석 착석 | 새 2인자 `checkSeatOccupancyCapabilities(config, areas)` → `getCurrentSeatOccupancy(area)` → `register/unregisterSeatOccupancy(area, listener)` | P.CAR_SEATS. 개별 ROW_1_LEFT/RIGHT, ROW_2_LEFT/CENTER/RIGHT만 구독. SDK Boolean 축약의 의미/UNKNOWN 손실 때문에 원시 값만 표시, 정규화 null/UNSUPPORTED |
| 운전석 통풍 | 새 `checkSeatVentilationCapabilities(config, areaBounds)` → `getHvacSeatVentilationState` → `register/unregisterHvacSeatVentilation` | P.CAR_SEATS. ROW_1_LEFT의 Int 단계. 제어 호출 없음 |
| 실내 온도 | `checkHvacTemperatureCurrentCapabilities` → `getHvacTemperatureCurrent` → `register/unregisterHvacTemperatureCurrent(HvacTemperatureListener)` | P.CAR_CLIMATE. ROW_1_LEFT **현재 실내 °C**. 설정 온도를 실내 온도로 대체하지 않음 |
| 위치 | NaviHelper 연결 callback → `getCurrentLocationInfo` / `onCurrentLocationInfo` | P.NAVI_ROUTE. latitude/longitude °, 유효한 같은 응답의 쌍만 전달 |
| 남은 시간·거리 | `getRouteStateInfo`와 `getDestinationInfo` / 각각 이벤트 | P.NAVI_ROUTE. `isRouting=true`의 최신 근거가 있을 때만 remainTime 초 / remainDistance m. 활성 경로가 없으면 SDK 기본 0도 null |
| 다음 안내 거리 | `getTBTInfo` / `onTBTInfo` | P.NAVI_ROUTE. TBTInfo.distance m 중 가장 가까운 유효 비음수 거리. 빈 목록·활성 경로 미확인은 null. 안내 유형별 실제 경로 시험은 후속 |
| 진행 방향 | 현재 위치 응답들의 최근 `EngineConfig.headingWindowMs=60_000` 창 | SDK heading 원단위는 공개 소스에서 확인하지 못해 그대로 사용하지 않음. 같은 위치/첫 위치는 null, 위치 변화가 있으면 방위각을 계산. 실제 이동 경로 시험은 미검증 |

화면의 **SDK 원시 → 정규화 → 최종**은 서로 다른 값이다. 예를 들어 기어 raw 4는 P로 정규화돼도, 원천 시각이 오래되면 Hub 최종은 null/STALE이다. 수신 기록은 그 응답을 받았을 때의 정규화 결과이며 현재 최신성을 보장하는 기록이 아니다.

Vehicle은 `Vehicle.initialize()` 뒤 도메인 객체를 얻고 capability `AccessType.READ/READ_WRITE`를 확인한 경우만 읽기·구독한다. NONE/WRITE나 지원 영역 없음은 UNSUPPORTED다. 최초 조회와 콜백은 별도 계수다. 늦은 최초 조회가 이미 받은 콜백을 덮지 않는다.

NaviHelper는 해당 버전에 별도 capability API가 없다. `initialize(onConnected,onFailed)`·권한·실제 이벤트/오류를 근거로 상태를 표시하고 `addListener/removeListener/release`를 사용한다. `EngineConfig.naviStateIntervalMs=1_000`마다 위치·경로 상태·목적지·TBT를 조회한다. 요청을 보냈다는 이유만으로 수신 시각을 갱신하지 않는다.

**조향 의미 충돌은 해결되지 않았다.** Console은 steering-wheel angle이라는 이름과 큰 범위를 표시하지만 SDK 2.3.2 공개 주석은 front-wheel이라고 명시한다. 값이 수신된 사실은 의미 일치의 증거가 아니다. 조향비 추정·엔진 문턱 변경·원시값의 운전대각 대입은 하지 않았다. 공급자 확인 뒤 정상화 계약을 확정해야 한다.

**좌석 의미도 보류했다.** 공개 `SeatImpl.getCurrentSeatOccupancy`와 `PropertyCallback`은 문자열 상태를 `== 1`로 바꾼다. null/UNKNOWN과 다른 숫자 상태가 false로 합쳐져 복원할 수 없다. [AOSP 좌석 상태](https://android.googlesource.com/platform/packages/services/Car/+/master/car-lib/src/android/car/VehicleSeatOccupancyState.java)는 UNKNOWN=0, VACANT=1, OCCUPIED=2이며 실제 Console Empty와 SDK Boolean도 일치하지 않았다. 역으로 뒤집는 처리도 UNKNOWN을 복원하지 못하므로 넣지 않았다. capability READ와 SDK Boolean 수신은 표시하고 Hub SEATS는 null/UNSUPPORTED로 둔다. 원시 숫자 상태를 보존하는 공식 경로 또는 공급자 수정 확인이 필요하다.

## 시간·품질·수집 종료

- Vehicle/Navi 콜백은 원천 시각을 제공하지 않는다. `observedAtMs=null/UNKNOWN`, 원천 Hz=알 수 없음으로 유지한다. 앱 콜백 입구의 elapsedRealtime 수신 시각을 main 전달 전에 캡처한다.
- AAOS만 `CarPropertyValue.timestamp`의 elapsedRealtime **ns÷1,000,000 → ms**를 보존한다. 원천 Hz는 같은/역행 시각을 각각 중복/측정 불가로 구별한다. 최초 조회를 센서 콜백으로 세지 않는다.
- 수신 Hz는 최근 10초의 **콜백 도착 횟수**다. 동일 값이라도 실제 새 콜백은 센다. 온전한 창 전·미지원·권한/연결 오류는 알 수 없음이다. 활성 수집의 무콜백 창만 0Hz다. 원천 Hz나 SDK 설정 요청 Hz와 같은 뜻이 아니다.
- Hub Hz는 발행 순번/시각으로 별도 측정한다. Android 스케줄링 지연 때 과거 프레임을 만들지 않고 deadline 누락을 표시한다. 화면의 ‘표시 고정’은 표시만 멈추며 SDK/Hub 수집은 계속한다. ‘수신 기록’은 최근 실제 값/품질 변경 최대 100건의 캡처를 보여 준다. 반복된 같은 값의 콜백은 계수에 반영하고 변경 기록만 생략한다. 중지 뒤 기록은 이전 세션이라고 표시하고 새 연결 때 비운다. Room에는 저장하지 않는다.
- **[가정] SDK 검증 화면 TTL은 관측/수신 각각 3초**, 경계 포함, 원천 시각 없으면 수신 경과 허용, DEMO 입력 없음. 기존 가짜 패널 TTL 1초와 독립이다. 이벤트형 기어/좌석은 값이 그대로면 콜백이 적어 3초 뒤 STALE/null일 수 있다. 제품의 신호별 TTL·이벤트 유지·재조회 정책은 미확정이다.
- 차량은 명시적 `updatedFields` 부분 갱신이다. 내비는 모든 필드의 값·기존 수신 시각을 가진 완전 스냅샷이다. 공유 정규화기는 어댑터가 준 수신 시각을 보존하므로 목적지 응답이나 Hub 재발행이 위치 최신성을 연장하지 않는다. 완전 관측 null의 기존 ‘모름/지움’ 의미는 유지한다.
- 개별 좌석 품질을 별도로 표시한다. Hub의 SEATS 묶음은 가장 오래된 구성 좌석 시각을 사용해 다른 좌석의 오래된 착석 근거를 연장하지 않는다. 보수적으로 묶음 전체가 STALE이 될 수 있다.
- SDK 콜백 진단 계수는 Hub 소비 속도와 독립이다. 차량 전달 버퍼는 128개·DROP_OLDEST, 내비는 최신 스냅샷 StateFlow다. 화면/Hub가 느리면 중간 업데이트를 잃을 수 있어 원본 기록 통로로 사용하면 안 된다. Hub 출력의 기존 대기 1/DROP_OLDEST 정책도 유지한다.
- 중지·화면 종료/onStop: 조회 Job 취소, 등록 listener 해제, Vehicle/NaviHelper release, Car disconnect, Hub 소유 scope 취소, 화면 캐시 제거. 늦은 callback은 폐기한다. 재연결은 새 어댑터·진단 창·Hub를 만들며 이전 세션 값을 가져오지 않는다. FGS·부팅 수신·백그라운드 수집은 추가하지 않았다.

실제 D/차속 입력 시 OS가 ‘주행 중에는 사용할 수 없습니다’를 표시한다. 이 Pleos 이미지는 user 빌드이며, [Android 공식 설명](https://developer.android.com/training/cars/apps/automotive-os)에 따르면 일반 sideload 앱의 실행 허용 메타데이터만으로 주행 제한을 해제할 수 없다. 실험한 메타데이터/AAOS 기능 선언은 최종 변경에서 제거했다. P/0에서 연결·조작하며, 주행 입력을 바꾼 뒤 P/0으로 돌아와 **수신 기록**에서 실제 받은 변경을 읽는다. 화면이 onStop으로 종료되면 SDK를 정리하고 그때까지의 기록만 메모리에 남긴다. 백그라운드 수집이 계속된다고 보장하지 않는다. OS가 전달하지 않은 값은 기록에 만들어 넣지 않는다.

## 빌드·설치와 데이터 보존

`app/build.gradle.kts`의 **targetSdk만 37→34**로 변경했다. compileSdk 37·AGP 9.4.0·Gradle 9.6.0·Kotlin·minSdk 28·기존 SDK 버전은 유지했다. 플랫폼 `useLibrary("android.car")`와 필요한 읽기 권한만 추가했다. API 34 기기에서 동작하는 `registerCallback/status`를 사용하며 compileSdk 최신 deprecation만 보고 API 36 전용 호출로 바꾸지 않는다.

```bash
cd /home/a/StudioProjects/sleepissue/src/android
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ANDROID_HOME=/home/a/Android/Sdk \
  ./gradlew :engine:test :app:testDebugUnitTest assembleDebug :app:processReleaseMainManifest --offline --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -W --user current \
  -n com.sleepissue.awakedrive/com.sleepissue.awakedrive.debug.DebugHarnessActivity
```

교체 설치는 메모리를 종료할 수 있다. 중요한 수동 상태는 기존 화면의 ‘즉시 저장’으로 먼저 저장한다. **앱 제거·pm clear·Wipe·Gradle connectedDebugAndroidTest는 수동 저장본이 있는 기기에서 실행하지 않는다.** 이번 시험은 DB를 백업한 뒤 `install -r`로만 교체하며 같은 DB의 논리 행을 비교한다. Room 스키마·DB명·저장/복원 코드는 변경하지 않았다. SDK 화면을 나가 수동 가상 시각·ID·누적을 확인한다.

## 직접 누를 순서

1. Pleos Connect v2.1.0을 Cold boot한다. 기존 AVD/앱 데이터를 지우지 않는다. PC에서 `adb -s emulator-5554 forward tcp:8088 tcp:8088` 뒤 **브라우저로 `http://127.0.0.1:8088/`를 열어 둔다.** Console의 AVD Enabled·DDS ONLINE을 확인한다. 페이지 세션 heartbeat가 끊기면 입력은 HTTP 503/emulator_inactive로 거절될 수 있다. 이 오류를 앱 수신 성공으로 세면 안 된다.
2. 앱의 기존 Room 요약을 확인하고 **Playground SDK 실시간 검증**을 누른다. 수동 화면의 D/P·시간 버튼을 SDK 입력으로 사용하지 않는다.
3. P/0km/h에서 **차속 권한**을 누르고 Android 동의창에서 허용한다(이 이미지의 동의창은 ‘위치 정보’라는 그룹 이름으로 표시됐다. 요청한 권한은 CAR_SPEED다). 거부하면 SPEED_KPH는 null/PERMISSION_DENIED여야 한다. 권한창을 닫은 뒤 **연결**을 누른다. 이미 연결돼 있었다면 **재연결**한다. 신호별 READ/지원 상태와 조회·콜백 계수가 실제로 늘어난 것을 확인한 뒤 입력을 바꾼다. 전역 ‘연결 요청 완료’는 모든 신호 수신 성공을 뜻하지 않는다.
4. Console에서 방향지시등을 켜면 차량 기본 카메라 팝업이 SDK 상단을 덮을 수 있다. 팝업의 X로 닫은 뒤 SDK 버튼을 누른다. 먼저 속도 0에서 Console Motion의 Gear Selection을 D로 바꾼다. GEAR 원시 VehicleGear 값과 정규화 D, REAL/AVAILABLE을 확인한다. 가만히 두면 이 화면의 임시 TTL 때문에 STALE이 될 수 있다. P로 다시 바꾸면 새 콜백과 P를 확인한다.
5. Console에서 차속을 80km/h 부근으로 올린다(상단 ACCEL/가속 페달). OS가 화면을 가리면 정차해 P/0으로 돌아온 다음 **수신 기록**에서 SPEED_KPH 원시 약 22.22m/s → 약 80km/h가 실제로 들어왔는지 확인한다. 움직이는 페달/브라우저 물리 계산으로 값이 변하므로 딱 80을 유지한다고 가정하지 않는다. AAOS 원천 ms와 수신 경과가 따로 표시돼야 한다.
6. Motion → Steering Angle을 10°로 바꾼다. SDK에서 실제 받은 부호(이번 장치에서는 -10°)와 콜백 증가, **정규화 null/UNSUPPORTED·전륜각 경고**를 확인한다. 이는 의도한 의미 보호다. Convenience → Turn Signal State를 Blink left/right로 바꾸면 LEFT/RIGHT를 확인한다. Brake Pedal 0→50%면 BRAKE_ON false→true를 확인한다. 이 입력들도 P/0에서 바꾸거나 정차 후 수신 기록에서 읽을 수 있다.
7. ADAS → Cruise Target Speed를 **20m/s**로 바꾸면 원시20→72km/h다. Cruise Control State는 Console/SDK의 서로 다른 enum 명칭을 그대로 추측하지 않고 화면의 실제 SDK raw enum을 함께 확인한다. ACTIVATED만 true, ENABLED/SUSPENDED는 false, 모호한 상태는 null이다.
8. Convenience → Seat Occupancy에서 Passenger를 Empty/Occupied로 바꿔 SDK Boolean과 수신 계수를 본다. **현재 버전은 착석 정규화를 보류해 null/UNSUPPORTED가 정상**이다. SDK 원시 Boolean을 빈 좌석/동승자 존재의 증거로 쓰지 않는다. Climate에서 실제 지원하는 운전석 Seat Ventilation을 변경하면 단계가 표시된다. Temperature Set은 현재 실내온도가 아니므로 CABIN_TEMP_C가 같은 값으로 즉시 바뀐다고 기대하면 안 된다. SDK 현재 온도 조회/콜백의 값을 확인한다.
9. 내비 위치는 기본 좌표 쌍을 표시한다. 목적지가 없으면 남은 시간/거리/회전은 null이다. 내비 앱에서 경로 안내가 활성화되고 실제 이벤트가 와야 남은 초/m/TBT를 확인할 수 있다. 이 경로 안내 수동 시험은 아래 검증 기록의 미검증 여부를 확인한다.
10. 10초 이상 지나면 수신 Hz와 Hub Hz를 비교한다. 센서 시각 없는 Vehicle/Navi의 **원천 Hz는 알 수 없음**이어야 한다. 원시 조향 콜백 증가를 Hub 10Hz 발행 횟수로 대체해서 세면 안 된다. **표시 고정/재개**로 같은 시점의 값과 계수를 천천히 읽는다(수집은 계속됨).
11. **중지**하면 값/캐시가 비고 발행/연결이 종료된다. **재연결**하면 새 세션으로 계수를 시작한다. **운행·Room으로 돌아가기** 후 기존 저장 가상 시각·운행 ID·정차 근거·누적이 같아야 한다. 다음 명령으로 프로세스를 종료/재실행해도 마지막 저장본부터 복원돼야 한다.

```bash
adb -s emulator-5554 shell am force-stop --user current com.sleepissue.awakedrive
adb -s emulator-5554 shell am start -W --user current \
  -n com.sleepissue.awakedrive/com.sleepissue.awakedrive.debug.DebugHarnessActivity
```

## 완료 범위와 후속

구현·실제 수신·검증 증거는 [11월 3일 개발 기록](../devlog/2026-11-03.md)의 SDK 절에서 구분한다. 기존 가짜 REAL/DEMO 시험은 [Hub 가이드](ROLE1_SIGNAL_HUB.md)에 별도로 남아 있다. 실제 SDK·JSON 동시 중재 TC 전체를 완료로 바꾸지 않는다.

남은 순서: 조향 의미/신호별 최신성 계약 확인 → DemoClock·JSON → 제품 감시 서비스·전원/부팅 복원 → 2·3·4번 판단/실행 담당과 최종 UI 통합. TRP_003 전체, SIG_009의 역할2 가중치 재정규화, 실제 음성·경유지·통풍 제어는 완료 범위가 아니다.


이번 호스트의 소프트웨어 그래픽 실행에서 차량 기본 카메라 영상/그래픽 버퍼 오류가 재현됐다. 현재 검증 AVD는 호스트 렌더러로 실행 중이다. 재실행이 필요하고 **기존 AVD가 종료된 경우에만** 다음을 사용한다. 기존 AVD 설정 파일과 실행 스크립트는 바꾸지 않는다.

```bash
/home/a/Android/Sdk/emulator/emulator -avd Pleos_Connect_2_1_0 -no-snapshot \
  -gpu host -feature -Vulkan -memory 4096 -cores 4
```
