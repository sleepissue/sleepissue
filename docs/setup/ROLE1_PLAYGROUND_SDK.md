# Playground SDK 입력 연결·실시간 검증

2026-11-07 후속: 기어 ON_CHANGE는 1초 재조회/3초 확인 lease로 현재 상태를 유지하며 변경·수신·확인 시각은 분리한다. 다른 연속 신호 TTL은 유지한다. 전용 [SDK 운행·Room 화면](ROLE1_REAL_TRIP.md)을 추가했고 아래 기존 SDK 진단 화면은 계속 Room과 분리한다.

2026-11-08 후속: **TURN_SIGNAL·BRAKE_ON·CRUISE_ACTIVE·CRUISE_TARGET_KPH**에도 공식 현재값 조회를 사용한 확인 lease를 연결했다. 아래 11/7의 기어 전용 설명은 당시 범위이며, 현재 네 상태형 신호의 계약·시험은 [상태형 신호 기록](../devlog/2026-11-08-state-signals.md)을 따른다. 이 문장은 앞선 네 신호 작업 범위다. 현재 후속에서는 실제 통풍·현재 실내온도까지 동일한 확인 계약을 확장했다. 조향/착석 의미 보류는 유지하며 [이번 기록](../devlog/2026-11-08-role1-finish.md)과 [요구별 판정](ROLE1_COMPLETION_STATUS.md)을 따른다.

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
| 남은 시간·거리 | `getRouteStateInfo`와 `getDestinationInfo` / 각각 이벤트 | P.NAVI_ROUTE. 최신 `ROUTE_GUIDING` 또는 `SIMULATION_GUIDING` 보고에서만 remainTime 초 / remainDistance m. 현재 provider는 안내 중에도 `isRouting=false`를 보고하므로 Boolean을 필수 조건으로 쓰지 않음. SAFE_GUIDING·경로 없음의 기본0은 null |
| 다음 안내 거리 | `getTBTInfo` / `onTBTInfo` | P.NAVI_ROUTE. TBTInfo.distance m 중 가장 가까운 유효 비음수 거리. 빈 목록·활성 경로 미확인은 null. 안내 유형별 실제 경로 시험은 후속 |
| 진행 방향 | 현재 위치 응답들의 최근 `EngineConfig.headingWindowMs=60_000` 창 | SDK heading 원단위는 공개 소스에서 확인하지 못해 그대로 사용하지 않음. 같은 위치/첫 위치는 null, 위치 변화가 있으면 방위각을 계산. 실제 이동 경로 시험은 미검증 |

화면의 **SDK 원시 → 정규화 → 최종**은 서로 다른 값이다. 예를 들어 기어 raw 4는 P로 정규화된다. 기어의 원천 변경 시각이 오래되어도 현재 상태 재조회가 성공해 확인 lease 안이면 AVAILABLE을 유지하고, 확인이 만료되거나 오류가 나면 null/STALE 또는 오류가 된다. 수신 기록은 그 응답을 받았을 때의 정규화 결과이며 현재 최신성을 보장하는 기록이 아니다.

Vehicle은 `Vehicle.initialize()` 뒤 도메인 객체를 얻고 capability `AccessType.READ/READ_WRITE`를 확인한 경우만 읽기·구독한다. NONE/WRITE나 지원 영역 없음은 UNSUPPORTED다. 최초 조회와 콜백은 별도 계수다. 늦은 최초 조회가 이미 받은 콜백을 덮지 않는다.

NaviHelper는 해당 버전에 별도 capability API가 없다. `initialize(onConnected,onFailed)`·권한·실제 이벤트/오류를 근거로 상태를 표시하고 `addListener/removeListener/release`를 사용한다. `EngineConfig.naviStateIntervalMs=1_000`마다 위치·경로 상태·목적지·TBT를 조회한다. 요청을 보냈다는 이유만으로 수신 시각을 갱신하지 않는다.

2026-11-09 실제 내비 후속: 이전 `activeObserved=false` 원본에도 ROUTE_GUIDING/false와60초/50m/TBT50m가 있었다. 모드 gate 보완을 실제 SDK→어댑터→Hub에서 확인하고 시험 경로 취소 후 세 값null을 확인했다. 현재온도19.1°C 유지와 이동 입력 미확인·GPS 배송 경계를 분리했다. 최신 시험492개/기기1개, 4번 질문과 직접 버튼은 [후속 기록](../devlog/2026-11-09-role1-sig-input.md)을 따른다. 이전490개·Room 경계3개·통풍/기어 기기1개는 과거 실행 근거다.

**조향 의미 충돌은 해결되지 않았다.** Console은 steering-wheel angle이라는 이름과 큰 범위를 표시하지만 SDK 2.3.2 공개 주석은 front-wheel이라고 명시한다. 값이 수신된 사실은 의미 일치의 증거가 아니다. 조향비 추정·엔진 문턱 변경·원시값의 운전대각 대입은 하지 않았다. 공급자 확인 뒤 정상화 계약을 확정해야 한다.

**좌석 의미도 보류했다.** 공개 `SeatImpl.getCurrentSeatOccupancy`와 `PropertyCallback`은 문자열 상태를 `== 1`로 바꾼다. null/UNKNOWN과 다른 숫자 상태가 false로 합쳐져 복원할 수 없다. [AOSP 좌석 상태](https://android.googlesource.com/platform/packages/services/Car/+/master/car-lib/src/android/car/VehicleSeatOccupancyState.java)는 UNKNOWN=0, VACANT=1, OCCUPIED=2이며 실제 Console Empty와 SDK Boolean도 일치하지 않았다. 역으로 뒤집는 처리도 UNKNOWN을 복원하지 못하므로 넣지 않았다. capability READ와 SDK Boolean 수신은 표시하고 Hub SEATS는 null/UNSUPPORTED로 둔다. 원시 숫자 상태를 보존하는 공식 경로 또는 공급자 수정 확인이 필요하다.

## 시간·품질·수집 종료

### 방향지시등·브레이크·크루즈·통풍·현재 실내온도 확인 (2026-11-08)

위 네 신호와 `VENTILATION_LEVEL/CABIN_TEMP_C`의 공개 조회는 `onComplete/onFailed` 비동기 형식이며 등록은 상태 갱신 리스너다. SDK 2.3.2 공개 API에는 무변경 상태의 최소 콜백 주기가 보장돼 있지 않다. 일반 TTL을 늘리지 않고 이 여섯 SDK 상태만 **[가정] 응답 뒤1초 재조회·성공 응답 수신 기준3초 lease(경계 포함)**를 사용한다. 첫 조회도 동일한 직렬 루프에 넣어 중복 capability/start 요청이 조회 타이머를 늘리지 않는다. IO에서 예약/호출하고 응답을 받은 실제 경과 시각을 Main 반영 전에 채집한다. 화면 갱신과 다음 조회 예약은 독립이다.

- 같은 원시/정규화 상태 재조회는 `confirmedMs`만 갱신한다. 이전 실제 배송 `receivedMs`, 원천 `nativeMs=null/UNKNOWN`, 관측 순번·콜백 수는 유지하고 조회 수만 증가한다. 값이 달라진 재조회는 응답 값을 전달하지만 새 콜백/센서 관측으로 세지 않는다. **OFF·false·0은 유효**, 명시 null은 UNAVAILABLE이며 빠진 부분 갱신 필드만 유지한다. 음수/비유한 목표 속도는 ERROR/null이다.
- 상태 전달의 `confirmationFields`는 재조회로 확인한 필드를 표시한다. Hub는 최종 값/품질·lease를 갱신하되 센서 관측 통로·콜백 계수·관측 순번을 늘리지 않는다. 기존 입력은 기본 빈 집합이라 기존 경로를 유지한다. 원천 수신 Hz나 조작 횟수는 재조회 수로 계산하지 않는다.
- 조회 중/반영 대기 중 새 콜백이 오면 과거 조회를 폐기한다. 배송 순번은 같은 ms의 역순 전달도 차단하며 실제 수신/확인 시각을 보정하지 않는다. 새 어댑터로 재연결한 뒤 이전 어댑터의 응답·콜백은 사용할 수 없다.
- 오류·권한 상실·구독 실패는 null/ERROR 또는 PERMISSION_DENIED로 무효화하고 해당 구독/조회 세션을 종료한다. 조회 무응답은3초 기한 후 오류 처리하며 SDK 요청 취소 API가 없으므로 같은 요청을 겹쳐 재시도하지 않는다. 늦은 성공도 폐기한다. **중지→재연결**, 감시 서비스는 **감시 중지→감시 시작**이 재개 경로다. 기한은 OS/스레드 실행이 가능한 조건에서 평가하며 실제 절전 공백을 새 입력으로 채우지 않는다.
- 공개 `Vehicle`에는 연결 해제/재연결 세대 이벤트를 구독하는 API가 없다. 내부 바인더 세대를 추측하거나 숨은 API를 호출하지 않는다. 공개 오류/조회 무응답·lease 만료로 과거 값을 차단하며, 공개 오류 전 모든 연결 해제를 즉시 감지한다고 보장하지 않는다. 실제 장애 강제 주입 여부는 개발 기록과 구분한다.

크루즈 raw enum은 유지한다. **ACTIVATED만 true**, ENABLED/SUSPENDED는 false, OTHER/USER_OVERRIDE/FORCED_DEACTIVATION_WARNING/null은 미확정 null이다. Console Ready/Longitudinal 등 UI 이름으로 true를 만들지 않는다. 상태와 목표 속도는 독립 품질이며 목표가72km/h라는 사실만으로 크루즈 작동을 확정하지 않는다. 목표 속도 원단위는 m/s이고 한 번만3.6을 곱한다. Console이 허용하지 않는0 또는 특정 enum은 자동시험 통과와 실제 수신 확인을 구분한다.

- Vehicle/Navi 콜백은 원천 시각을 제공하지 않는다. `observedAtMs=null/UNKNOWN`, 원천 Hz=알 수 없음으로 유지한다. 앱 콜백 입구의 elapsedRealtime 수신 시각을 main 전달 전에 캡처한다.
- AAOS만 `CarPropertyValue.timestamp`의 elapsedRealtime **ns÷1,000,000 → ms**를 보존한다. 원천 Hz는 같은/역행 시각을 각각 중복/측정 불가로 구별한다. 최초 조회를 센서 콜백으로 세지 않는다.
- 수신 Hz는 최근 10초의 **콜백 도착 횟수**다. 동일 값이라도 실제 새 콜백은 센다. 온전한 창 전·미지원·권한/연결 오류는 알 수 없음이다. 활성 수집의 무콜백 창만 0Hz다. 원천 Hz나 SDK 설정 요청 Hz와 같은 뜻이 아니다.
- Hub Hz는 발행 순번/시각으로 별도 측정한다. Android 스케줄링 지연 때 과거 프레임을 만들지 않고 deadline 누락을 표시한다. 화면의 ‘표시 고정’은 표시만 멈추며 SDK/Hub 수집은 계속한다. ‘수신 기록’은 최근 실제 값/품질 변경 최대 100건의 캡처를 보여 준다. 반복된 같은 값의 콜백은 계수에 반영하고 변경 기록만 생략한다. 중지 뒤 기록은 이전 세션이라고 표시하고 새 연결 때 비운다. Room에는 저장하지 않는다.
- **[가정] 일반 신호 TTL은 관측/수신 각각 3초**, 경계 포함, 원천 시각 없으면 수신 경과 허용, DEMO 입력 없음. 기어와 위 여섯 SDK 상태형 신호는 별도1초 재조회/3초 확인 lease를 사용하며 같은 상태 응답은 센서 관측/수신 시각을 바꾸지 않는다. 기존 가짜 패널 TTL1초와 독립이다. 그 외 신호의 제품 정책은 별도 확정 대상이다.
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

교체 설치는 메모리를 종료할 수 있다. 중요한 수동 상태는 기존 화면의 ‘즉시 저장’으로 먼저 저장한다. **앱 제거·pm clear·Wipe·Gradle connectedDebugAndroidTest는 수동 저장본이 있는 기기에서 실행하지 않는다.** 이번 시험은 DB를 백업한 뒤 `install -r`로만 교체하며 같은 DB의 논리 행을 비교한다. 기존 수동 DB명·스키마는 보존한다. 후속 실제 운행 연결의 별도 DB와 owner 형식은 위 전용 가이드에 설명했다. SDK 화면을 나가 수동 가상 시각·ID·누적을 확인한다.

## 직접 누를 순서

1. Pleos Connect v2.1.0을 Cold boot한다. 기존 AVD/앱 데이터를 지우지 않는다. PC에서 `adb -s emulator-5554 forward tcp:8088 tcp:8088` 뒤 **브라우저로 `http://127.0.0.1:8088/`를 열어 둔다.** Console의 AVD Enabled·DDS ONLINE을 확인한다. 페이지 세션 heartbeat가 끊기면 입력은 HTTP 503/emulator_inactive로 거절될 수 있다. 이 오류를 앱 수신 성공으로 세면 안 된다.
2. 앱의 기존 Room 요약을 확인하고 **Playground SDK 실시간 검증**을 누른다. 수동 화면의 D/P·시간 버튼을 SDK 입력으로 사용하지 않는다.
3. P/0km/h에서 **차속 권한**을 누르고 Android 동의창에서 허용한다(이 이미지의 동의창은 ‘위치 정보’라는 그룹 이름으로 표시됐다. 요청한 권한은 CAR_SPEED다). 거부하면 SPEED_KPH는 null/PERMISSION_DENIED여야 한다. 권한창을 닫은 뒤 **연결**을 누른다. 이미 연결돼 있었다면 **재연결**한다. 신호별 READ/지원 상태와 조회·콜백 계수가 실제로 늘어난 것을 확인한 뒤 입력을 바꾼다. 전역 ‘연결 요청 완료’는 모든 신호 수신 성공을 뜻하지 않는다.
4. Console에서 방향지시등을 켜면 차량 기본 카메라 팝업이 SDK 상단을 덮을 수 있다. 팝업의 X로 닫은 뒤 SDK 버튼을 누른다. 먼저 속도 0에서 Console Motion의 Gear Selection을 D로 바꾼다. GEAR 원시 VehicleGear 값과 정규화 D, REAL/AVAILABLE을 확인한다. 기어를 유지해도 재조회 확인이 성공하면 3초 뒤 AVAILABLE을 유지한다. 원천 변경·실제 수신과 재조회 확인 시각을 따로 확인한다. P로 다시 바꾸면 새 콜백과 P를 확인한다.
5. Console에서 차속을 80km/h 부근으로 올린다(상단 ACCEL/가속 페달). OS가 화면을 가리면 정차해 P/0으로 돌아온 다음 **수신 기록**에서 SPEED_KPH 원시 약 22.22m/s → 약 80km/h가 실제로 들어왔는지 확인한다. 움직이는 페달/브라우저 물리 계산으로 값이 변하므로 딱 80을 유지한다고 가정하지 않는다. AAOS 원천 ms와 수신 경과가 따로 표시돼야 한다.
6. Motion → Steering Angle을 10°로 바꾼다. SDK에서 실제 받은 부호(이번 장치에서는 -10°)와 콜백 증가, **정규화 null/UNSUPPORTED·전륜각 경고**를 확인한다. 이는 의도한 의미 보호다. Convenience → Turn Signal State를 Blink left/right로 바꾸면 LEFT/RIGHT를 확인한다. Brake Pedal 0→50%면 BRAKE_ON false→true를 확인한다. 이 입력들도 P/0에서 바꾸거나 정차 후 수신 기록에서 읽을 수 있다.
7. ADAS → Cruise Target Speed를 **20m/s**로 바꾸면 원시20→72km/h다. Cruise Control State는 Console/SDK의 서로 다른 enum 명칭을 그대로 추측하지 않고 화면의 실제 SDK raw enum을 함께 확인한다. ACTIVATED만 true, ENABLED/SUSPENDED는 false, 모호한 상태는 null이다.
8. Convenience → Seat Occupancy에서 Passenger를 Empty/Occupied로 바꿔 SDK Boolean과 수신 계수를 본다. **현재 버전은 착석 정규화를 보류해 null/UNSUPPORTED가 정상**이다. SDK 원시 Boolean을 빈 좌석/동승자 존재의 증거로 쓰지 않는다. Climate의 Seat Temperature V1/V3는 `SEAT_TEMPERATURE:ROW_1_LEFT` 진단에 -1/-3으로 들어오며 별도 `VENTILATION_LEVEL`을 바꾸는 입력이 아니다. 현재 Console catalog에는 `HVAC_SEAT_VENTILATION` 전용 입력이 없어 Console 버튼으로는 통풍 OFF→1→OFF를 만들 수 없다. 이번 시험 전용 공식 setter/readback에서는 0→1→0을 확인했다. 수집기·제품에는 제어를 연결하지 않는다. Temperature Set은 현재 실내온도가 아니므로 CABIN_TEMP_C가 같은 값으로 즉시 바뀐다고 기대하면 안 된다. SDK 현재 온도 조회/콜백의 값을 확인한다.
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

현재 SDK 수집·REAL 운행·Room·감시 서비스·부팅 재개는 구현돼 있다. 1번의 남은 것은 엄격 주기·실제 활성 내비/이동 heading·현재 온도 변경 입력과 의미/전원 제약이다. DemoClock·JSON은 5번 인계 대상이며 제품 통합은 이번 범위에서 진행하지 않는다. TRP_003 전체, SIG_009의 역할2 가중치 재정규화, 실제 음성·경유지·통풍 제어는 완료 범위가 아니다.


이번 호스트의 소프트웨어 그래픽 실행에서 차량 기본 카메라 영상/그래픽 버퍼 오류가 재현됐다. 현재 검증 AVD는 호스트 렌더러로 실행 중이다. 재실행이 필요하고 **기존 AVD가 종료된 경우에만** 다음을 사용한다. 기존 AVD 설정 파일과 실행 스크립트는 바꾸지 않는다.

```bash
/home/a/Android/Sdk/emulator/emulator -avd Pleos_Connect_2_1_0 -no-snapshot \
  -gpu host -feature -Vulkan -memory 4096 -cores 4
```


## 2026-11-07 SIG 수집 보완

SDK API 지원(`apiReadable`)과 정규화 상태를 따로 표시한다. 조향은 `SEMANTIC_UNCONFIRMED`, 착석은 `LOSSY_SDK_BOOLEAN`으로 기존 null 격리를 유지한다. API READ 수신 자체를 미지원이라고 판정하지 않는다. Console 온열/냉각은 `DIAGNOSTIC_ONLY`로 받고 통풍에 치환하지 않으며 통풍은 SDK가 알려 준 영역별 범위 안에서만 수용한다.

수집 중 권한 거부·capability 미지원 뒤 늦은 수신과 수신시각 역행을 차단하고, 비활성 상태 전이는 중복 없이 기록한다. 보호 폐기는 별도 계수다. `callbacks`와 수신 Hz는 수용한 callback 기준이며 원천 센서 Hz를 뜻하지 않는다. **진단 저장**은 raw/정규화/최종 품질/수신 시각/발행 감사를 `cache/sdk-signal-probe.json`에 저장하고 진행·완료·실패를 화면에 남긴다. SDK 화면은 Room을 쓰지 않는다.

396개 시험·assembleDebug·release manifest 확인, 실제 Console API→SDK 대조와 남은 TC는 [11월7일 보완 기록](../devlog/2026-11-07-signal-sdk.md)에 정리했다. [구현 전17개 현황](../devlog/2026-11-06-signal-sdk-review.md)과 [공식 SDK/API34 근거](../devlog/2026-11-06-signal-sdk-api-evidence.md)를 함께 본다. 새 증거로 기존11월3일 기록을 덮어쓰지 않았다.

Room ViewModel을 만들지 않는 자동 검증 진입은 다음과 같다. 일반 사용은 기존 화면의 **Playground SDK 실시간 검증 → 연결**이다. 자동 진입은 별도 task이므로 돌아가기 버튼은 이전 화면을 닫는 동작이며 Room 화면을 새로 만들지 않는다.

```bash
adb shell am start --user 10 -n com.sleepissue.awakedrive/.debug.DebugHarnessActivity \
  --ez sdkProbe true --ez sdkAutoConnect true --ez sdkCapture true
```

이미 앱 task가 실행 중이면 Intent가 현재 화면에만 전달될 수 있다. 새 세션 검증은 데이터 유지 `am force-stop` 뒤 진입하고, 실제 onStop만 검증할 때는 새 task의 자동 연결 없는 SDK 화면으로 가린다. 주행 제한을 우회하지 않으며 백그라운드 지속 수집을 보장하지 않는다.

## 최초 조회·기어·거부 배송의 통일된 계약 (이번 후속)

최초 QUERY와 REQUERY는 값/품질/확인을 전달하지만 센서 관측·콜백·운전자 조작을 만들지 않는다. `confirmationFields`는 최초 조회와 기어 조회에도 적용한다. 저장소의 `observations`도 CALLBACK에서만 증가하며 Hub 원천 Hz에 조회를 섞지 않는다. AAOS 및 일반/조향/착석/범위 래퍼는 저장소가 false를 반환한 배송을 Hub에 재전달하지 않는다. 명시적 null/오류/권한 상실 상태 전달과 조향·착석의 의미 보류는 유지한다.
