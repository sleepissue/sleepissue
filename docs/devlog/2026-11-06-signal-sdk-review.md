# SIG_001~017 Playground SDK 수집 현황 검토

보완 후 결과는 [11월7일 최종 기록](2026-11-07-signal-sdk.md)을 따른다. 이 문서는 구현 전 상태를 보존한다.

검토일: **2026-11-07**. 파일명 날짜는 이어서 진행한 11월 6일 작업을 따른다. 이 문서는 새 기기 시험 전의 현황 초안이며, **2026-11-03의 보존된 실제 SDK 증거와 아래에 고정한 소스 읽기 결과**만 판정 근거로 사용한다. 동시 진행 중인 SDK 보완·새 시험의 결과는 포함하지 않는다.

검토자는 기존 문서·Excel·소스·증거·AVD·Room을 수정하지 않았고 Gradle이나 기기 명령을 실행하지 않았다. 60배 데모 성능 재작업, 다른 담당의 판단·음성·내비 실행 통합은 범위에서 제외했다.

## 요구와 판정 기준

기획의 기준은 [현재 기능 기획서](../plan/깨어있는드라이브_상세기획서_v4_현재기능.md) 4-2·9·10장과 [AGENTS.md](../../AGENTS.md)다. 상세 요구·TC는 [AwakeDrive_SRS_TC_v0.2.xlsx](../design/AwakeDrive_SRS_TC_v0.2.xlsx)의 `Sys Req Spec`·`Test Case`를 직접 대조했다. SIG_001~009는 두 시트의 **15~23행**, SIG_010~017은 **174~181행**이다. 원본 SHA-256은 `2ea8c6d0baf3040f146fbbab0d87883f12108354411ffaad1f8e6fb1e95e69da`이며, 17개 요구 모두 **Draft**다. TC의 측정값·판정·증거 칸은 비어 있다. 이 문서가 원본 승인이나 합격 판정을 대신하지 않는다.

- **구현 있음**: 소스에 수집·변환·전달 경로가 있다. 전체 TC 합격과 다르다.
- **기기 부분 확인**: 실제 Playground SDK/AAOS 응답에서 해당 값 또는 상태를 확인했다. 모든 상태·지연·장시간 누락 조건까지 측정했다는 뜻은 아니다.
- **의미 보류**: API와 capability가 있고 원시값도 받았지만 요구 필드의 의미로 안전하게 정규화할 수 없어 의도적으로 null이다. capability `NONE`과 구분한다.
- **미확인**: 경로는 있으나 해당 조건의 실제 기기 증거가 없다. 미지원이나 미구현으로 단정하지 않는다.
- **미구현/미조립**: 요구한 일부 동작 또는 실제 SDK를 함께 연결하는 실행 경로가 아직 없다. 기기 시험만 추가해 해결되는 항목과 구분한다.

`Source.REAL`은 Pleos Playground의 가상 차량 SDK/Virtual Console 입력이다. 물리 차량 검증을 뜻하지 않는다. Console HTTP 성공, 가짜 REAL 입력, JVM 모의 입력은 실제 SDK 수신의 대체 증거로 세지 않는다. 지연은 **조작→Hub 반영**의 같은 시간축을 기록해야 하며, callback의 수신 시각 한 개나 화면 캡처만으로 ≤200ms·≤1s를 판정하지 않는다.

## 기존 근거

| 근거 | 확인 범위 |
| --- | --- |
| [SDK 수집 가이드](../setup/ROLE1_PLAYGROUND_SDK.md) | 같은 버전의 공개 SDK 소스·공식 API/샘플·AAOS 서명 대조, API·단위·권한·의미 제한, 수명주기와 버튼 순서 |
| [11월 3일 SDK 개발 기록](2026-11-03.md) | 구현 이유와 실제 Console/SDK 수신, 보존 범위, 미검증 항목 |
| [validation.json](evidence/2026-11-03-sdk/validation.json) | Pleos Connect 2.1.0/API34/emulator-5554/user10, APK별 증거 해시, 당시 222개 시험 통과, Room 논리 행 보존, 명시적 `not_claimed` |
| [01 실제 입력 XML](evidence/2026-11-03-sdk/01_real_inputs.xml) / [PNG](evidence/2026-11-03-sdk/01_real_inputs.png) | raw/정규화/최종·STALE·권한·capability·Hz를 분리한 한 시점의 화면 |
| [02 SDK 변경 기록 XML](evidence/2026-11-03-sdk/02_sdk_history.xml) / [PNG](evidence/2026-11-03-sdk/02_sdk_history.png) | 실제 QUERY/CALLBACK·수신 시각·원시값·정규화·원천 시각 미제공 기록 |
| [03 차속/기어 기록 XML](evidence/2026-11-03-sdk/03_speed_gear_history.xml) / [PNG](evidence/2026-11-03-sdk/03_speed_gear_history.png) | 실제 AAOS P/D·0/80km/h와 원천/수신 시각, 브레이크 false 복귀 |
| [04 중지](evidence/2026-11-03-sdk/04_stopped.xml) / [05 재연결](evidence/2026-11-03-sdk/05_reconnected.xml) | 세션 중지·캐시 정리·새 세션 계수 시작 |
| [06 Room 복귀](evidence/2026-11-03-sdk/06_room_return.xml) / [07 프로세스 복원](evidence/2026-11-03-sdk/07_room_process_restore.xml) | 기존 수동 저장본 복귀·복원. 실제 SDK의 운행/Room 통합 근거는 아님 |

당시 최종 APK는 `a3ee6c89481502fff242b3c21b60a7fc564190e88c83fdb8c4ac40e5015459c6`이고, 00/02~05 캡처 APK는 `a4958e4269d09e38f965ccd6a933849ec9c4d6034ce92be7755eabd8251b4671`이다. `validation.json`이 이 구분을 보존한다. 당시 시험 수는 engine 83 + app 139 = 222, 실패·오류·skip 0이다. 이 수치를 이번 검토에서 다시 실행한 시험 수로 쓰지 않는다.

## 요구별 현황

아래 합격 기준은 원본 SRS/TC를 요약했다. **기존 근거만으로 17개 TC 전체를 완료 처리할 수 없다.** 일부 수신·단위 변환을 확인한 요구도 미측정 지연이나 상태 범위가 남아 있다.

| ID · SRS/TC 행 | 요구·합격 기준 | 구현 상태 | 기존 실제 기기 근거 | 미지원·미구현·미확인 구분 / 남은 확인 |
| --- | --- | --- | --- | --- |
| SIG_001 · 15 | 조향 구독→10Hz SignalFrame. 주입 Clock 기준 60초, 모든 간격 ≤110ms·누락 0 | Hub 주기 발행·Clock 주입·조향 SDK 구독은 있음. **운전대각 정규화는 의미 보류** | 02: Console +10°에 SDK -10° callback, `null/UNSUPPORTED`. 01: Hub 150회·10.00Hz·deadline 0이라는 한 시점 | SDK 공개 설명은 **전륜각**, 요구는 운전대 회전각이므로 변환비 추정 없이 보류. API capability 미지원이 아니다. 실제 60초 전체 간격/누락 감사는 **미확인**. JVM의 600프레임 시험은 기기 타이밍 대체 불가 |
| SIG_002 · 16 | AAOS 속도 m/s→km/h. Console 80→22.22→80.0±0.1, ≤200ms | `PERF_VEHICLE_SPEED` 구독·`abs ×3.6` 변환 있음 | 03: raw `22.222223`→`80.0`, 0→0 callback; AAOS 원천·수신 시각 보존 | 값·변환은 **기기 부분 확인**. 조작→Hub 반영 ≤200ms는 **미확인**. 이미 km/h인 JSON 시험으로 대체하면 안 됨 |
| SIG_003 · 17 | OFF→LEFT→RIGHT→OFF, 변경 후 ≤200ms | SDK capability→조회/구독, enum OFF/LEFT/RIGHT 매핑 있음 | 02/03: OFF·LEFT·OFF 실제 수신 | RIGHT까지 포함한 전체 순서와 각 ≤200ms는 **미확인**. 미지원 근거 없음 |
| SIG_004 · 18 | 크루즈 ready→active→off ≤200ms, 미수신 null | SDK 상태 구독 있음. `ACTIVATED=true`, `ENABLED/SUSPENDED=false`, 모호한 enum은 null | 01/02: 실제 `ENABLED→false`; 원천 시각 없음과 3초 뒤 STALE가 분리됨 | 실제 ACTIVATED/해제 전체 상태전이·Console/SDK enum 대응·≤200ms는 **미확인**. 임의 enum 추정 금지 |
| SIG_005 · 19 | 운전석·조수석·뒷좌석 변경 ≤1s, 개별 미지원 null | 5개 좌석별 capability·조회/구독·품질 경로 있음. **착석 정규화는 의미 보류** | 01/02: 개별 `READ`·SDK Boolean 수신, 최종 `null/UNSUPPORTED` | SDK가 숫자 상태를 `==1` Boolean으로 축약해 UNKNOWN/착석/빈 좌석을 복원할 수 없음. API 자체 미지원으로 분류하면 안 됨. 원시 상태를 보존하는 공식 경로/SDK 수정 확인이 필요. 5좌석 전환·≤1s·미지원 모의는 **미확인** |
| SIG_006 · 20 | Navi 위치≤1s, 최근 1분 위치 변화의 heading, 내비 앱 없으면 null | 위치 쌍 검증·최근 60초 위치 기반 방위 계산·NaviState 전달 있음. SDK raw heading의 단위는 미확인이라 사용하지 않음 | 02: `37.4127212,127.094681` 실제 응답, 같은/첫 위치 heading null | 실제 이동 경로·방위 변화·≤1s·내비 앱 부재/종료 null은 **미확인**. 고정 좌표 수신은 이동 검증이 아님 |
| SIG_007 · 21 | capability 미지원·구독 실패·권한 거부→null/기능 비활성, 로그 1행·크래시0 | 상태별 null·capability 후 구독·오류 격리 있음. 읽은 소스에서 **수집 중 권한 거부의 후속 callback 차단과 비활성화 전이 1행 기록은 보완 필요** | 01/02의 조향·좌석 null은 의미 보호 사례임. 모든 capability NONE/강제 구독 실패/실시간 권한 철회 시험은 기존 `not_claimed` | 기존 JVM 상태 구분 시험과 실제 SDK 오류 유발을 분리. 3종 실패의 기기 TC는 **미확인**. 위 기록/권한 고정 부분은 **구현 보완**, 다른 담당 소비 지표 비활성 통합은 범위 밖. 동시 추가 중인 회귀시험 파일의 존재만으로 통과 판정하지 않음 |
| SIG_008 · 22 | 데모 중 REAL 우선≤1프레임110ms. 시나리오 좌표 있으면 DEMO, 없으면 실제 Navi | Hub 중재·좌표 예외 규칙과 모의 시험 있음. **실제 SDK+JSON 동시 실행 경로는 별도 SDK 화면에 미조립** | 11/03 SDK 화면은 REAL 전용 Hub이며 JSON 입력 없음. 기존 `not_claimed`에 실제 동시 중재 명시 | SDK+JSON 동시 TC는 **미조립/미확인**. 현재 조향 의미 보류 상태에서 raw를 엔진 운전대각으로 강제 대입해 TC를 통과시킬 수 없음. 다른 지원 필드 중재 시험과 조향 계약 문제도 구분 필요 |
| SIG_009 · 23 | 10초 원천 Hz 오차≤10%, 2/5/10Hz, <5Hz 미세 수정 비활성·나머지 가중치 재정규화 | `RateMeter`의 10초 창·중복/역행·`belowMinimum` 노출 구현. **FatigueEngine 지표 비활성/재정규화 소비 연결은 역할2 범위로 미통합** | 01: 차속 AAOS 원천/수신 6.20Hz, Hub10.00Hz는 서로 별도. Vehicle 조향은 원천 시각 미제공이므로 원천 Hz 알 수 없음 | 조향 callback 도착 Hz를 센서 원천 Hz로 바꾸지 않음. 실제 조향 원천 Hz는 **측정 불가/공급자 계약 미확인**. JVM 2/5/10Hz 품질 계약은 있으나 재정규화까지 완료로 세지 않음 |
| SIG_010 · 174 | 외부 단위는 app 경계에서 한 번 변환. 속도km/h·거리m·각도°·온도°C·시각ms. 변환 시험, engine 외부 변환0 | `UnitConverter`와 입력 정규화 경계 구현. SDK target m/s·AAOS 속도 변환, 원천 ns→ms, Navi 초/m 보존 | 02/03의 target20→72·21→75.6, speed22.222223→80.0. 온도19.1°C 수신 | 변환 사례는 **부분 확인**. `engine`의 내부 기간 ms→초 계산까지 외부 단위 변환으로 세어 일괄 삭제하면 안 됨. 원본의 ‘×3.6·÷1000 0건’ 문구는 외부 SDK 경계와 내부 기간 계산을 구분해 코드 검사 근거를 남겨야 함. 전체 TC 판정은 **미완료** |
| SIG_011 · 175 | AAOS 기어 P→R→N→D 모두 일치·≤200ms | `GEAR_SELECTION` 구독, VehicleGear P/R/N/D만 매핑, 기타 null | 03: raw4→P, raw8→D 실제 callback | R/N·전체 순서·≤200ms는 **미확인**. 오래된 원천 시각인 P가 최종 STALE/null인 것은 정규화 실패와 별개 |
| SIG_012 · 176 | 브레이크 ON→OFF, 각 ≤200ms | Boolean 조회/구독·nullable 전달 있음 | 02: false QUERY·true CALLBACK, 03: false CALLBACK 복귀 | 상태 수신은 **기기 부분 확인**. 조작 시각을 함께 기록한 ≤200ms는 **미확인** |
| SIG_013 · 177 | 설정 속도90→100km/h, 오차≤0.1·≤200ms | 같은 버전 SDK 공개 소스의 m/s를 km/h로 변환·구독함 | 02:20m/s→72.0,21m/s→75.6 실제 callback | 단위·변환은 **기기 부분 확인**.90→100km/h 지정값과≤200ms는 **미확인** |
| SIG_014 · 178 | 운전석 통풍 OFF→1→OFF,≤1s | 운전석 영역 capability·현재 상태 조회/구독·0이상 단계 전달 있음. 제어 호출 없음 | 01/02: ROW_1_LEFT `READ_WRITE`, 범위0~3, 실제 통풍0 | 1단계 변경·복귀·≤1s는 **미확인**. Console에서 조작 가능한 **온열**을 **통풍** 입력으로 오인하면 안 됨. capability 지원과 실제 변경 가능성은 별도 확인 |
| SIG_015 · 179 | 현재 실내24.9→25.0°C,≤1s | `get/registerHvacTemperatureCurrent` 사용, 현재°C와 setpoint 구분 있음 | 02: 실제 현재19.1→19.1°C | 지정값 변화·≤1s는 **미확인**. Console의 Temperature Set을 현재 온도와 동일시하지 않음. API 미지원 근거 없음 |
| SIG_016 · 180 | 실제 목적지 있으면 남은 초/m≤1s, 없음 null | 목적지 조회/이벤트·`isRouting` 최신 근거 gate 있음. 미확인/해제 경로의 SDK 기본0을 null로 유지 | 02: 활성 경로 없는 raw0초/0m→null | 목적지 부산 설정·실제 남은 초/m 변화·해제·≤1s는 **미확인**. no-route null 확인만으로 활성 경로 수신 성공을 주장하지 않음 |
| SIG_017 · 181 | 실제 다음 회전 거리m≤1s, 목적지 없음 null | TBT 조회/이벤트·활성 경로 gate·유효 비음수 거리 중 최솟값 전달 있음 | 02: 활성 경로 없는 TBT null | 실제 안내 중 회전 접근·해제·≤1s는 **미확인**. 목록의 최솟값이 요구한 ‘다음 회전’에 해당하는지 안내 종류별 의미 검증도 필요. 공급 API 부재/미지원으로 단정하지 않음 |

## 상태를 잘못 합치기 쉬운 부분

**조향과 좌석은 ‘값을 받지 못함’이 아니다.** 기존 실제 응답과 capability는 있으나 요구 의미가 맞지 않아 정규화를 보류했다. SDK 2.3.2의 전륜각을 운전대각으로 바꾸는 공식 비율은 확인되지 않았고, 좌석 Boolean에서 손실된 UNKNOWN은 역논리로 복원할 수 없다. 두 항목의 `UNSUPPORTED` 표시는 이 의미 제한을 설명한다. 전체 신호 수집 API가 없다는 판정이 아니다.

**API 34 좌석 권한과 Pleos 좌석 권한도 별도다.** 이번 보완 작업에서 공유된 API34 서명 확인상 AAOS `READ_CAR_SEATS`는 없으며 `CONTROL_CAR_SEATS`는 signature/privileged 권한이다. 이는 기존 `pleos.car.permission.CAR_SEATS`로 SDK Boolean을 받은 사실을 부정하거나, 일반 앱이 AAOS 원시 좌석 상태를 읽을 수 있음을 입증하지 않는다. 새 기기/서명 증거는 별도로 남겨야 한다. Console V1/V3의 `HVAC_SEAT_TEMPERATURE`는 온열이고 SDK가 읽는 `HVAC_SEAT_VENTILATION`과 다른 속성이므로, 온열을 바꾼 결과를 SIG_014 통풍 변경 합격으로 사용하지 않는다. 이 문서에서 해당 새 확인을 기존 11/03 기기 PASS에 합치지 않았다.

**시간·Hz·최신성은 별도 계약이다.** Vehicle/Navi는 원천 시각을 제공하지 않아 `observedAtMs=null/UNKNOWN`이다. AAOS만 원천 timestamp를 보존한다. 수신 Hz는 callback 도착 빈도이고 Hub10Hz 발행이나 SDK의 요청 Hz와 다르다. SDK 검증 화면 TTL3초는 **[가정]**으로, 이벤트형 값이 변하지 않으면 STALE일 수 있다. 이를 숨기려고 관측 시각을 덮거나 TTL을 늘리는 방식으로 요구 달성을 선언하지 않는다. 제품의 필드별 TTL·이벤트 유지/재조회 정책은 별도 확정 항목이다.

**SDK 화면은 제품 감시 서비스가 아니다.** 현재 SDK 화면은 REAL 전용이며 Tracker·TripStateStore·Room·가짜 REAL·JSON·음성/경유지 실행을 연결하지 않는다. onStop/중지는 조회·구독·연결·Hub 작업을 정리한다. OS의 주행 중 화면 제한으로 onStop된 구간을 지속 수집 완료로 세지 않는다. TRP_003, 역할2 FatigueEngine 소비, 역할3 음성·확인, 역할4 경유지/통풍 제어, 최종 제품 UI 통합을 이 SIG 수집 검토에서 완료 처리하지 않는다.

## 읽은 구현·시험 소스

수집 경계는 [PlaygroundSignalAdapter.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/pleos/PlaygroundSignalAdapter.kt), 원시/정규화/품질/Hz 저장은 [SdkSignalStore.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/pleos/SdkSignalStore.kt), 활성 경로 보호는 [SdkRouteGate.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/pleos/SdkRouteGate.kt), 외부 변환은 [UnitConverter.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/signal/UnitConverter.kt)다. 실제 SDK 화면의 조립은 [DebugSdkProbeActivity.kt](../../src/android/app/src/debug/java/com/sleepissue/awakedrive/debug/DebugSdkProbeActivity.kt), 중재/발행은 [PeriodicSignalHub.kt](../../src/android/app/src/main/java/com/sleepissue/awakedrive/signal/PeriodicSignalHub.kt), 원천 품질 계약은 [RateMeter.kt](../../src/android/engine/src/main/java/com/sleepissue/awakedrive/engine/signal/RateMeter.kt)에 있다.

[SdkSignalStoreTest.kt](../../src/android/app/src/test/java/com/sleepissue/awakedrive/pleos/SdkSignalStoreTest.kt)는 원천 시각 없음·중복/역행·늦은 최초 조회·원천/수신 보존·조향/좌석 의미 보류·이동 방위·경로 gate·세션 정리를 다룬다. [PeriodicSignalHubTest.kt](../../src/android/app/src/test/java/com/sleepissue/awakedrive/signal/PeriodicSignalHubTest.kt)의 가상 Clock60초/600프레임 시험은 Hub 발행과2Hz 원천 관측을 분리한다. [RateMeterTest.kt](../../src/android/engine/src/test/java/com/sleepissue/awakedrive/engine/signal/RateMeterTest.kt)는2/5/10Hz·10초 창·임계 경계·중복/역행·시나리오 원천 시각을 다룬다. 시험 소스 확인과 이번 실행은 다르며, 이 검토에서는 실행하지 않았다.

동시 보완 작업과 혼동하지 않도록 읽은 주요 소스 SHA-256을 고정한다. 이후 소스가 달라지면 이 표의 SIG_007 등 구현 판정을 최신 코드에 자동 적용하지 않는다.

| 파일 | 읽은 SHA-256 |
| --- | --- |
| `PlaygroundSignalAdapter.kt` | `283d91a6a1e400c672924ccc4015ad17620c34719ea2ee34b48274c2b0eb1973` |
| `SdkSignalStore.kt` | `92ab87d13aa7d75fd3ffa87976103310c61a13e17627d97bf447ff185fb7be60` |
| `DebugSdkProbeActivity.kt` | `3d88cee1c85ac40405b7f57a260400692b2aba62c77d59b0739d7989c7a4829d` |
| `RateMeter.kt` | `f3fd9d6a0ef40b1de972bd4cbbd1e6837e195e4eed964d631de4d39a4f00d223` |
| 기존 `2026-11-03-sdk/validation.json` | `48eb9a7e4477bfa166b769e79e974ab366512fc0e5808563971ce2aac50e66de` |

이번 후속 검증은 위의 미확인 상태전이와 조작→Hub 지연을 각각 새 증거에 연결하고, 지원 경로가 없는 경우에는 실제 capability/권한/SDK 의미 제한을 기록해야 한다. 기존 11/03 증거와 미지원·미구현 항목을 그대로 보존한 채 새 결과를 별도 구분하는 것이 이 초안의 목적이다.
