# Vehicle 2.3.2 조향·착석·좌석 통풍 공식 근거

조사 시작 2026-11-06, 보완 확인 2026-11-07. `AGENTS.md`, 기존 [SDK 연결 기록](../setup/ROLE1_PLAYGROUND_SDK.md), 설치된 SDK와 같은 버전의 공식 Maven 소스, 공식 API 문서·샘플, Android 14 고정 소스를 대조했다. 이 문서의 SDK 검증 대상은 Playground 가상 차량이며 물리 차량 검증을 뜻하지 않는다. 이번 조사에서는 생산 코드·권한 선언·기기 상태를 변경하지 않았다.

## 판정

| 항목 | 공식 자료에서 명시된 사실 | 아직 확정할 수 없는 부분 / 적용 기준 |
| --- | --- | --- |
| 조향 | Vehicle 2.3.2 소스 주석은 전륜 조향각, 도(°), 왼쪽 음수, 운전대 회전각과 다름을 명시한다. API 반환은 `Float?`다. | Console 공식 가이드는 운전대 각도라고 설명한다. 실행 중 서비스의 실제 물리 의미가 어느 쪽인지 공개 자료만으로 해소되지 않는다. 운전대/전륜 비율을 추정하거나 엔진 조향각에 그대로 대입할 근거가 없다. |
| 착석 | SDK 조회·이벤트는 `Boolean?`지만 구현은 정수 문자열을 `== 1`로 축약한다. 좌석은 capability가 돌려준 `SeatArea`를 사용한다. | Boolean의 true를 실제 착석으로 간주할 공식 의미 설명이 없고, 숫자 0·2·null이 모두 false로 합쳐진다. Boolean 반전으로 UNKNOWN을 복구할 수 없다. |
| 좌석 통풍 | `Vehicle.getSeat()`의 Seat API다. 조회·구독은 `pleos.car.permission.CAR_SEATS`, 설정은 `pleos.car.permission.CONTROL_CAR_SEATS`. 별도 ventilation 속성의 `Int?`를 받는다. | 범위는 해당 좌석의 capability min/max로 확인한다. Console V1/V3는 별도 seat-temperature 속성의 -1/-3이므로 SDK ventilation 값으로 치환할 수 없다. |
| 착석 원시 정수 대안 | Android 14의 `SEAT_OCCUPANCY`는 Integer 읽기 전용이고 AAOS `CONTROL_CAR_SEATS`가 필요하다. 이 권한은 signature\|privileged다. | 현재 일반 debug 앱에서 런타임 권한 요청으로 얻는 대안이 아니다. 최신 문서의 `READ_CAR_SEATS`를 API 34에 소급하지 않는다. 권한 우회·매니페스트 추가로 해결하지 않는다. |

각 판정의 고정 버전·행·URL은 아래에 기록했다. API 지원 READ와 앱의 정규화 가능 여부는 서로 다른 사실이다. 원시 API를 읽을 수 있어도 의미가 확정되지 않은 신호의 정규화가 지원된다고 표시하면 안 된다.

## 버전과 재현 자료

공식 공개 소스는 다음 URL에서 받았고 기존 11월 3일 기록의 SHA-256과 일치했다. SDK 소스·바이너리는 저장소에 넣지 않았다.

| 자료 | 버전 / SHA-256 |
| --- | --- |
| [Vehicle 공식 sources.jar](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/Vehicle/2.3.2/Vehicle-2.3.2-sources.jar) | 2.3.2 / `62b5dd2c4c0523fd13c2e64040cd775bc89a14ce54f35a37560d0eb4eb3fab40` |
| [vehicle-data 공식 sources.jar](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/vehicle-data/1.1.11/vehicle-data-1.1.11-sources.jar) | 1.1.11 / `54094b98cfb7b1d2fa7fab743b0e48074da1d9f4bd2a2cbd3ad4a9914de18f65` |
| 로컬 Gradle cache의 Vehicle AAR | 2.3.2 / `e11fdfe498cad3ecc0d5a2a6992b0c4a4d135195f1455018127bc069fd5ef937` |
| 로컬 API 34 `optional/android.car.jar` | `892c7dbe92a7f7245b81bfd6904f8ea980d55a2753fba8c279293cf1a589352a` |
| [공식 샘플 고정 리비전](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/tree/959981d960ec71144accb27af5ae5d341e72a644) | `959981d960ec71144accb27af5ae5d341e72a644` |

임시 조사 자료는 `/tmp/awakedrive-sdk-api-evidence-20261106/`에 있다. 아래 SDK 행 번호는 `sources.jar` 안의 원본 Kotlin 파일 기준이며, 클릭 가능한 로컬 소스는 그 파일을 변형 없이 `source/`에 푼 것이다. Vehicle AAR의 `classes.jar`에 `javap`를 적용하여 아래 공개 메서드·콜백의 바이너리 서명도 대조했다. 물리 의미 주석은 소스 근거이고 바이너리 서명 검사가 이를 별도로 입증하지는 않는다.

공식 `document.pleos.ai` API 페이지는 이번 조회에서 메서드 제목·목록만 있고 상세 본문이 비어 있었다. 따라서 상세 단위·구현은 버전이 고정된 공식 Maven 소스로 확인했다. 서로 다른 클라우드 Vehicle Data API의 단위를 차량 내 Vehicle SDK 근거로 섞지 않았다.

## SteeringWheel: 이름과 물리량을 구분

- `api/SteeringWheel.kt` 45–60행: `checkSteeringWheelAngleCapabilities`는 `(CapabilityConfig, AreaBounds<Float>)`를 반환하고 각도 bounds의 단위가 도임을 명시한다. 권한은 `pleos.car.permission.CAR_DRIVE`이며 소스 설명은 normal이다.
- 같은 파일 93–109행: `getCurrentSteeringWheelAngle`의 물리량은 **전륜 조향각**, 단위 °, 왼쪽 음수다. 운전대 회전각과 다르다는 설명이 별도로 있다. 112–126행의 구독도 같은 설명이다. [공식 API 페이지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/get-current-steering-wheel-angle), [로컬 고정 소스](</tmp/awakedrive-sdk-api-evidence-20261106/source/ai/pleos/playground/vehicle/api/SteeringWheel.kt:93>).
- `api/implement/SteeringWheelImpl.kt` 91–104행은 `PERF_STEERING_ANGLE` 문자열을 `toFloat()`로 전달한다. 이 SDK 래퍼에는 조향비·부호 변환이 없다. 이것은 래퍼의 동작을 확인한 것이며 하위 서비스가 공급하는 실제 물리량을 입증하지 않는다. [로컬 구현](</tmp/awakedrive-sdk-api-evidence-20261106/source/ai/pleos/playground/vehicle/api/implement/SteeringWheelImpl.kt:91>).

[공식 Virtual Console 가이드의 Motion 표](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide#motion)는 Steering Angle을 운전대 각도, -540~540°, 1° 간격으로 설명한다. 이 설명과 SDK 2.3.2의 전륜 설명은 충돌한다. 큰 범위나 두 화면의 숫자가 같다는 관찰만으로 조향비·의미를 결정할 수 없다. **확정된 것은 SDK의 선언 의미이며, 실제 서비스·Console 경로가 그 선언을 지키는지는 미확정이다.** 현재 원시 표시를 보존하고 정규화 조향은 미지원으로 두는 근거다.

## Seat: Boolean 축약과 좌석 영역

SDK의 새 `checkSeatOccupancyCapabilities`는 `(CapabilityConfig, List<SeatArea>)`를 반환한다. 기존 config 없는 overload는 deprecated다. 최초 조회 `getCurrentSeatOccupancy(area, onComplete, onFailed)`와 `register/unregisterSeatOccupancy(area, listener)`는 모두 `CAR_SEATS`를 요구한다. `Seat.kt` 45–61, 730–746, 1296–1322행과 [공식 조회 페이지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-occupancy), [로컬 선언](</tmp/awakedrive-sdk-api-evidence-20261106/source/ai/pleos/playground/vehicle/api/Seat.kt:730>)을 대조했다.

공개 주석은 착석 상태를 반환한다고만 설명하고 true/false/unknown 대응을 명시하지 않는다. 구현은 더 구체적이다.

| 입력 문자열 | 최초 조회 결과 | 이벤트 결과 |
| --- | --- | --- |
| `1` | true | true |
| `0`, `2`, 그 밖의 유효 정수 | false | false |
| null | false | false |
| 정수로 변환할 수 없는 문자열 | false | `toInt()` 변환 실패 가능 |

근거는 `SeatImpl.kt` 423–438행의 `toIntOrNull() == 1`, `PropertyCallback.kt` 351–353행의 `toInt() == 1`이다. 반환 타입이 nullable이어도 이 두 비교 자체는 null을 false로 합친다. [조회 구현](</tmp/awakedrive-sdk-api-evidence-20261106/source/ai/pleos/playground/vehicle/api/implement/SeatImpl.kt:423>), [이벤트 구현](</tmp/awakedrive-sdk-api-evidence-20261106/source/ai/pleos/playground/vehicle/callback/PropertyCallback.kt:351>).

Android 14 고정 소스의 [VehicleSeatOccupancyState](https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-14.0.0_r1/car-lib/src/android/car/VehicleSeatOccupancyState.java#28)는 UNKNOWN=0, VACANT=1, OCCUPIED=2다. **[조건부 비교]** Pleos 서비스가 이 AOSP 숫자를 그대로 SDK에 전달한다면 SDK true는 VACANT이고 OCCUPIED와 UNKNOWN은 모두 false가 된다. SDK 구현만으로 하위 서비스의 원시 상태 매핑까지 확정할 수는 없다. 따라서 false를 뒤집어 착석으로 쓰거나 unknown을 빈 좌석으로 쓰지 않는다. Console의 Empty/Occupied 카드도 API Boolean의 의미를 정의하는 근거는 아니다. [공식 Console 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide#convenience).

좌석은 `SeatValue`가 1·2·3열의 LEFT/CENTER/RIGHT 9개를 정의하고 `SeatArea(vararg values)`로 하나 이상을 지정한다. 실제 지원 영역은 capability 응답으로 제한한다. `SeatValue.kt` 8–11행, `SeatArea.kt` 5–10행, `ImplementUtil.kt` 22–38행에 따르면 여러 영역의 ID는 bitwise OR로 합쳐진다. 복합 영역을 하나의 독립 좌석으로 잘못 분리하지 않는다. `ROW_1_LEFT`라는 위치명만으로 모든 차량의 운전석을 뜻한다고 가정할 수 없다. [로컬 영역 변환](</tmp/awakedrive-sdk-api-evidence-20261106/source/ai/pleos/playground/vehicle/utils/ImplementUtil.kt:22>).

vehicle-data 1.1.11 `VehicleAreaValue.kt` 13–23행의 영역 ID는 다음과 같다. 이는 enum 정의이며 실제 지원 좌석 목록이 아니다.

| 열 | LEFT | CENTER | RIGHT |
| --- | --- | --- | --- |
| ROW_1 | `0x0001` | `0x0002` | `0x0004` |
| ROW_2 | `0x0010` | `0x0020` | `0x0040` |
| ROW_3 | `0x0100` | `0x0200` | `0x0400` |

## Seat 통풍: API·범위·권한과 별도 temperature 채널

| 순서 | Vehicle 2.3.2 API / 근거 행 | 확인할 값 |
| --- | --- | --- |
| capability | `Seat.checkSeatVentilationCapabilities`, `Seat.kt` 495–511행 | `CapabilityConfig`와 `Map<SeatArea, AreaBounds<Int>>`; 읽기 READ/READ_WRITE, 설정 WRITE/READ_WRITE 및 해당 영역 존재 확인 |
| 최초 조회 | `getHvacSeatVentilationState`, 1255–1272행 | 지정 영역의 `Int?`, `pleos.car.permission.CAR_SEATS` |
| 구독·해제 | `register/unregisterHvacSeatVentilation`, 1724–1750행 | 같은 영역·listener, `CAR_SEATS` |
| 설정 | `setHvacSeatVentilation`, 1274–1293행 | 지정 영역의 Int, `pleos.car.permission.CONTROL_CAR_SEATS` |

[공식 capability 페이지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/check-seat-ventilation-capabilities), [공식 조회 페이지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-hvac-seat-ventilation-state), [공식 설정 페이지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/set-hvac-seat-ventilation), [로컬 선언](</tmp/awakedrive-sdk-api-evidence-20261106/source/ai/pleos/playground/vehicle/api/Seat.kt:1255>).

SDK는 Int 통풍값과 영역별 bounds를 정의하며 RPM·풍량·퍼센트 같은 물리 단위나 모든 차량에 적용되는 고정 0..3 범위를 명시하지 않는다. 통풍 입력은 bounds로 확인하고 값을 임의로 부호 변환하지 않는다. `SeatImpl.kt` 315–320행과 944–959행은 **`HVAC_SEAT_VENTILATION`**을 사용하고 정수로 직접 전달한다. 설정 완료 콜백은 같은 파일 906–925행에서 요청했던 `(area, value)`를 반환한다. 완료 콜백 자체를 재조회한 실제 반영값으로 해석할 수 없다. 제어 구현 시 기획대로 별도 조회로 확인해야 한다. [로컬 설정·조회 구현](</tmp/awakedrive-sdk-api-evidence-20261106/source/ai/pleos/playground/vehicle/api/implement/SeatImpl.kt:906>).

공식 Console 가이드는 Seat Temperature 카드에 통풍 3단계/Off/열선 3단계를 설명한다. 보존된 Console catalog의 `features[34]`는 이 카드의 V1=-1, V2=-2, V3=-3, H1=1, H2=2, H3=3과 속성 **`HVAC_SEAT_TEMPERATURE=0x1540050b`**를 명시한다. SDK는 temperature도 `checkSeatTemperatureCapabilities`·`getHvacSeatTemperature`·`register/unregisterHvacSeatTemperature`라는 별도 API로 읽는다(`Seat.kt` 476, 1249, 1708, 1722행; `SeatImpl.kt` 928–941행). 이것을 ventilation 값으로 정규화하지 않는다. [공식 Console Climate 표](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide#climate), [로컬 temperature 구현](</tmp/awakedrive-sdk-api-evidence-20261106/source/ai/pleos/playground/vehicle/api/implement/SeatImpl.kt:928>).

Android 14의 [VehiclePropertyIds](https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-14.0.0_r1/car-lib/src/android/car/VehiclePropertyIds.java#1551)도 temperature=`356517131`/`0x1540050b`, ventilation=`356517139`/`0x15400513`을 별도 Integer 좌석 속성으로 정의한다(1571, 1748행). 이름·UI에서 둘 다 통풍이라는 표현이 나와도 같은 속성이 아니다. Console temperature 변화와 SDK ventilation 0이 함께 보이는 현상은 서로 다른 채널일 수 있으므로 채널 이름과 원시값을 각각 기록해야 한다.

보존 catalog: `/tmp/awakedrive-sig-sdk-preserve-20261106/console-catalog.json`, SHA-256 `f5468e672f284b98f479d44588587ddce42166ca5b38cc89d7226acf308aa6c2`. 이는 실행 이미지의 관찰 자료이며 모든 Pleos 버전의 보장으로 일반화하지 않는다.

## API 34 원시 Integer 대안의 권한 검토

SDK Boolean 대신 `CarPropertyManager`로 AAOS `SEAT_OCCUPANCY`를 직접 읽으면 원시 정수 타입은 보존할 수 있다. 하지만 **현재 일반 앱에 허용된 대안은 아니다.** Android 최신 웹 문서가 아닌 `android-14.0.0_r1` 고정 소스에서 권한을 확인했다.

| AAOS 속성 | Android 14 읽기 / 쓰기 권한 | 고정 소스 근거 |
| --- | --- | --- |
| `SEAT_OCCUPANCY` (`0x15400bb0`) | `android.car.permission.CONTROL_CAR_SEATS` / 쓰기 불가 | `VehiclePropertyIds.java` 3782–3802행, `PropertyHalServiceIds.java` 476–478행 |
| `HVAC_SEAT_TEMPERATURE` (`0x1540050b`) | `android.car.permission.CONTROL_CAR_CLIMATE` / 동일 | `VehiclePropertyIds.java` 1551–1571행, `PropertyHalServiceIds.java` 559–561행 |
| `HVAC_SEAT_VENTILATION` (`0x15400513`) | `android.car.permission.CONTROL_CAR_CLIMATE` / 동일 | `VehiclePropertyIds.java` 1728–1748행, `PropertyHalServiceIds.java` 583–585행 |

[Android 14 속성 선언](https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-14.0.0_r1/car-lib/src/android/car/VehiclePropertyIds.java#3782), [Android 14 서비스 권한 매핑](https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-14.0.0_r1/service/src/com/android/car/hal/PropertyHalServiceIds.java#476). 이는 AOSP 기준이고 OEM 서비스 구현 변경 가능성은 별도로 남지만, 아래 실행 이미지의 권한 정의도 같은 보호 수준이다.

Android 14 [CarService manifest](https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-14.0.0_r1/service/AndroidManifest.xml#141)는 `CONTROL_CAR_SEATS`를 signature\|privileged로 선언한다. 고정 `Car.java`와 manifest에는 `READ_CAR_SEATS`가 없다. 로컬 API 34 public `android.car.jar`에도 `READ_CAR_SEATS`가 없고, `VehicleSeatOccupancyState` 및 숨겨진 `Car.PERMISSION_CONTROL_CAR_SEATS` 상수도 없다. 반면 세 속성 ID는 공개 stub에 존재한다. ID의 컴파일 가능 여부는 권한 부여를 의미하지 않는다. [Car.java의 @hide/SystemApi 선언](https://android.googlesource.com/platform/packages/services/Car/+/refs/tags/android-14.0.0_r1/car-lib/src/android/car/Car.java#898).

root가 확보한 [실행 이미지 권한 선별 기록](</tmp/awakedrive-sdk-api-evidence-20261106/device-api34-selected-permissions.txt>)은 `android.car.permission.CONTROL_CAR_SEATS`와 `CONTROL_CAR_CLIMATE`를 signature\|privileged, `pleos.car.permission.CAR_SEATS`를 normal로 표시하며 `READ_CAR_SEATS`는 없다고 기록한다. 따라서 Pleos의 normal 읽기 권한과 같은 이름 끝부분을 가진 AAOS privileged 권한을 섞으면 안 된다. manifest에 문자열을 추가하거나 일반 런타임 동의를 요청해 해결할 문제가 아니므로, 이번 작업에서는 raw AAOS 좌석 경로를 추가하지 않는다. 이 선별 기록 SHA-256은 `9c6633b26c1748773744febdb42eaf17c7fa9fc53d8d9322be0bad8b36bf3a10`이다.

## 공식 샘플이 입증하는 범위와 남은 확인

고정 리비전 [공식 README](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/blob/959981d960ec71144accb27af5ae5d341e72a644/README.md)는 Vehicle 2.3.2의 typed 참조 구현을 Door Position이라고 명시하고, Seat/SteeringWheel은 범용 API 탐색기에서 다룬다. [VehicleApiExplorer](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/blob/959981d960ec71144accb27af5ae5d341e72a644/app/src/main/java/ai/pleos/playground/sample/vehicle/explorer/VehicleApiExplorer.kt#L471)는 선택한 SeatValue로 SeatArea를 만들지만, 이 코드가 조향의 물리 의미나 occupancy true의 의미를 추가로 보장하지는 않는다. [샘플 manifest](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/blob/959981d960ec71144accb27af5ae5d341e72a644/app/src/main/AndroidManifest.xml#L4)의 Pleos normal 읽기/제어 권한도 AAOS signature\|privileged 권한의 대체 근거가 아니다.

공급자 확인이 필요한 항목은 세 가지다.

1. Vehicle 2.3.2 `PERF_STEERING_ANGLE`이 현재 Playground 서비스·Console에서 실제로 전륜각인지 운전대각인지, 두 공식 설명의 정정 대상과 지원 차량별 단위 계약.
2. 서비스 `SEAT_OCCUPANCY`의 실제 숫자 상태·UNKNOWN 계약과 SDK `==1` 축약의 의도, 원시 상태를 보존하는 일반 앱용 공식 경로 또는 수정 버전.
3. 현재 이미지에서 SDK `HVAC_SEAT_VENTILATION`을 바꾸는 공식 Console 입력 경로와 영역별 bounds, temperature의 음수 통풍 표시와의 관계. 두 채널 간 변환은 별도 보장이 나오기 전까지 만들지 않는다.

실제 API34 권한 선별 출력과 Console 입력 계약은 [지속 보존 증거](evidence/2026-11-07-sdk/validation.json)에 연결했다. 보완 구현과 기기 결과는 [11월7일 최종 기록](2026-11-07-signal-sdk.md)을 따른다.
