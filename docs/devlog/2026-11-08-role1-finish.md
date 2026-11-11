# 2026-11-08 — 1번 기능 보완·남은 검증 분리

요청 범위는 간트 10·11·12의 SIG_001~017, TRP_001~012, PLT_012·013이다. AGENTS.md, 최신 간트, 현재기능 기획서 4·7·9·10장, 관련 가이드/11월7~8일 기록과 외부 `1번_역할_진행현황_간트대조.md`를 대조했다. 요구별 판정·다른 담당 계약·직접 버튼은 [현황표](../setup/ROLE1_COMPLETION_STATUS.md)를 따른다. 공식 TC/RTM 원본이나 간트 완료율은 변경하지 않았다.

시작은 **develop-jdw / f5c6ad9e179383ddb2b5cff3aad9ac4230afabe2**, 앞선 상태 신호·거부 배송 수정과 480개 기록을 포함한 미커밋 작업이 있었다. 추적/미추적 636개 파일의 원본·SHA256·diff를 `/tmp/awakedrive-role1-finish-6gqbu4xt/`에 보관했다. 기존 480개·Room 기기10개는 과거 실행 근거이며 아래 새 실행과 구분한다.

## 이번에 고친 기능

- **실제 통풍/현재 실내온도 무변경 유지:** 최초 조회·변경 콜백만 있던 두 신호에 공식 비동기 현재값 재조회를 연결했다. 기존 `SdkStateSubscription/SdkStateDelivery`의 직렬 IO 예약·수신 시각·배송 순서·수락 시 emit·오류/권한 차단을 재사용한다. [가정] 응답 뒤1초/확인3초 lease이며 일반 TTL은 그대로다. 통풍은 capability의 운전석 min/max 안 비음수 단계, 현재온도는 유한 °C다. 범위 밖/비유한 값은 ERROR/null, 명시 null은 UNAVAILABLE이다.
- 같은 값 재조회는 확인 시각만 갱신한다. 값이 바뀐 재조회도 `confirmationFields`로 Hub 최종 값만 갱신하며 센서 관측·콜백·운전자 조작을 만들지 않는다. 조회가 끊기면 lease 끝 다음에 STALE이며, 오류·권한 상실은 즉시 무효화한다. 거부 배송 재전달 차단과 기존 네 상태/기어 계약을 유지했다.
- **내비 보고/관측 분리:** 실제 어댑터가 사용하는 `SdkNaviDelivery`를 분리했다. NaviHelper는 get 응답과 자발 알림을 같은 listener로 보내므로 모두 보수적으로 상태 보고로 취급한다. 저장소의 보고는 REQUERY로 기록하고 `NaviSignalSource.stateReportsOnly=true`로 Hub 캐시에서도 센서 관측 순번을 만들지 않는다. 다른 원천은 기본 false라 기존 동작을 유지한다. 상태 보고 수가 실제 get 호출 횟수와 반드시 같다는 뜻은 아니다.
- 경로 없는 기본0·빈 TBT·취소·만료는 null, 공개 오류는 경로 근거를 지우고 새 경로 상태 확인을 요구한다. 위치/heading은 잘못된 좌표·오류·TTL을 넘는 보고 공백에서 이력을 끊는다. 최근1분의 유효 위치 변화로 계산하며 원시 SDK heading의 단위를 추측하지 않는다.
- **발행 감사:** 조사 중 타이머 복귀 지연/잠금 대기/수신·발행 경과를 분리 계측했다. `emissionObserver`로 버스 emit 직전 실제 elapsed를 별도 기록한다. 기존 프레임 메타데이터·센서 시각·수신 시각은 덮지 않는다. debug 화면에 실제 emit 최대 간격·110ms 초과를 표시하고 진단 파일명을 분리할 수 있게 했다.

추가 점검에서 AAOS 기어 재조회도 Hub의 확인 표시에서 빠진 경로를 보완했다. `SdkCarDelivery`는 어댑터의 실제 수락/전달 판단을 공유하며 거부된 AAOS 배송을 재전달하지 않는다. 기어·여섯 상태뿐 아니라 최초 QUERY도 값/품질을 공급할 뿐 관측·콜백·raw 센서 이벤트를 만들지 않는다. store 관측 계수는 실제 CALLBACK에서만 증가한다. 조회로 조향 원천 Hz를 만들지 않으며 일반/조향/착석/범위 진단 래퍼도 receive의 수락 여부를 반영한다. 오류/권한 상태의 명시적 전달은 유지한다.

TripRules·Room 스키마/형식·감시 서비스/부팅 수명은 검토 후 변경하지 않았다. 관련 계산/저장 경계의 시험을 보강했다. 다른 담당의 판단·기준선·개입·음성·쉼터·휴식·제품UI·DemoPlayer·60배 코드는 수정/통합하지 않았다.

## 공식 API·입력 환경

[Vehicle 공식 Seat 조회](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-hvac-seat-ventilation-state), [HVAC 현재온도 조회](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-temperature-current), [공식 Maven](https://nexus-playground.pleos.ai/repository/maven-releases/)의 설치 버전 공개 소스를 다시 확인했다. Vehicle2.3.2·NaviHelper3.0.5·navi-data3.0.4의 SHA256은 [API/환경 근거](evidence/2026-11-08-role1-finish/api-environment.json)에 있다. SDK 소스/바이너리는 `/tmp`에만 보관하고 저장소에 넣지 않았다.

- 운전석 통풍은 `getHvacSeatVentilationState(SeatArea, (Int?)->Unit, (Exception)->Unit)`, CAR_SEATS normal, READ_WRITE/0..3이다. 현재온도는 `getHvacTemperatureCurrent(SeatArea, (Float?)->Unit, (Exception)->Unit)`, CAR_CLIMATE normal, °C다.
- 기기 입력 fixture는 공식 `setHvacSeatVentilation`과 [공식 내비 샘플의 공개 payload](https://github.com/Pleos-Playground/pleos-sample-navi-app/blob/76ed592487a1eab7ba102b9d4b321705426886fe/app/src/main/java/ai/pleos/playground/navidevapp/data/sample/NaviTestData.kt)를 사용했다. 명시적 `role1SdkFixtures=true`에서만 실행한다. debug manifest에만 CONTROL_CAR_SEATS/NAVI_ROUTE_SEARCH를 선언했고 **release manifest에 두 권한이 없음을 검사**했다. 제품 통풍·내비 제어는 추가하지 않았다.
- Console catalog에는 설정온도/좌석 온열·냉각이 있으나 현재 실내온도/전용 통풍 입력은 없다. V1/V3는 -1/-3의 **SEAT_TEMPERATURE**이며 통풍으로 치환하지 않았다. 이번 통풍 입력은 별도 공식 API에서 성공했다.
- 현재 AVD 지원 config에는 HVAC_TEMPERATURE_CURRENT·HVAC_SEAT_VENTILATION이 있고 IGNITION_STATE는 없다. CarService의 시험 주입 명령은 user 빌드에서 `requires non-user build`로 거절됐다. 실제 OFF는 환경 미지원/미연결이다. 주입 명령 제한이나 입력 방법 미확인을 해당 SDK 읽기 API 미지원으로 해석하지 않는다.
- 조향 전륜각/운전대각 충돌·착석 UNKNOWN 손실은 기존 의미 보류를 유지한다. 공식 근거 없이 비율·부호·Boolean 반전으로 정상화하지 않았다.

## 실제 AVD 결과

연결 기기가 없어 기존 `Pleos_Connect_2_1_0`을 **-no-snapshot-load/-no-snapshot-save**로 데이터 삭제 없이 부팅했다. API34/emulator-5554/user10, 같은 창 있는 AVD다. 감시 requested=false/실행 서비스 없음과 수동·REAL DB/기존 cache 원본을 먼저 보관했다. SDK 독립 화면과 메모리 Room만 사용했다.

| 항목 | 이번 결과 | 해석 |
| --- | --- | --- |
| 수정 전 통풍·현재온도 | 87.698초 구간의 최종 두 필드 모두 STALE | 최초 조회/콜백만으로 정상 무변경 상태가 만료되는 실제 코드 경로 재현 |
| 수정 후 두 상태 | 기본/전용 대조와 최종 각 세션에서 초기10초 뒤66표본 모두 AVAILABLE | 약1초 표본의 유지 근거. 짧은 STALE 전 프레임 완전 부재나 1초 반영 TC를 뜻하지 않음 |
| 통풍 API readback | 0→1→0, 원래0 복구 | Console 온열과 별개인 실제 통풍 입력 확인 |
| 실제 어댑터→Hub 통풍 | 최종 값0→1→0, 정상 품질 | 별도 기기 회귀1개 통과. [최종 runner](evidence/2026-11-08-role1-finish/final-sdk-device.txt), [최종 진단](evidence/2026-11-08-role1-finish/final-sdk-device.json) |
| 통풍 재조회4회 | 콜백2→2, store 관측2→2, raw 관측1→1, 수신4263650 유지, 확인4263650→4267470 | 최초/현재값 조회를 센서 관측으로 세지 않음 |
| 기어 재조회4회 | 조회2→6, 콜백1→1, store 관측1→1, raw 관측1→1, 수신4261953 유지, 확인4262962→4266971 | 기어도 동일한 조회/관측 계약 확인 |
| 현재 실내온도 | 19.1°C 수신·재확인 | 24.9→25.0 지정 변경 입력은 미확인. 설정온도 변경으로 대체하지 않음 |
| 내비 공식 요청 | 초기 경로false, 공식 샘플 요청 뒤20회 get에서도 activeObserved=false. 오류 이벤트0 | fixture는 조건 미충족(assumption). JUnit의 OK 표기를 활성 내비 TC Pass로 읽지 않음. API 미지원 단정 금지 |
| 이동 heading | emulator geo fix 2회 OK, NaviHelper 위치는 고정37.4127212/127.094681·heading null | GPS 시험 입력이 이 provider의 위치 보고로 반영되지 않음. 실제 이동 heading은 미검증, 계산 경로 자동시험과 구분 |

내비 읽기/초기화·고정 위치 응답은 동작하나 요청이 실제 활성 경로를 만들지 않은 원인(기본 provider 설정/해당 app 처리/환경 데이터)을 이번 공개 경로만으로 확정하지 못했다. 호출 성공을 경로 생성 성공으로 세지 않는다. 실제 경로/TBT 접근·통과·해제의 기기 조건은 남긴다. 입력 fixture를 위해 원래 활성 경로를 덮지 않으며 시험이 요청한 경로만 마지막에 cancel했다.

## 1배 발행 지연 조사

과거 기준은 **73.085초 / 최대147ms / 110ms 초과20회**다. 이번 수정 전 실제 SDK 창은 **87.698초 / 최대172ms / 초과15회**였다. 표의 이전 기준과 새 실행을 서로 덮지 않는다.

같은 APK·AVD·P/0·Console DDS 활성·표시500ms/자동진단1초·실제 SDK 수집 조건에서 각76초 수집 스크립트로 실행기를 비교했다. 시작 시간까지 포함한 발행 감사 창은 아래 실측 길이다. 두 구간 중 Gradle/Room runner는 실행하지 않았다. 두 실행 모두 cold 앱 시작이며 호스트 부하는 통제된 실험실 수준으로 고정하지 않았다.

| 실행 | 발행 창/프레임 | 메타데이터 최대 간격 | 110ms 초과 | 순번누락/중복/역행 |
| --- | --- | --- | --- | --- |
| 기본 Default | 77.879초/780 | 123ms | 15 | 0/0/0 |
| 전용 ScheduledExecutor 실험 | 77.097초/772 | 134ms | 21 | 0/0/0 |
| 중간 Default+실제 emit 감사 | 77.588초/777 | 127ms | 20 | 0/0/0 |
| 중간 실제 emit 직전 시각 | 77.584초/777 | 126ms | 21 | 0/0/0 |
| 계측 범위 정리 후(기어 보완 전) 메타/emit | 75.899/75.883초·760 | 136/127ms | 13/15 | 0/0/0 |
| 기어 보완 후·최초 QUERY 보완 전 메타/emit | 77.300/77.294초·774 | 137/137ms | 17/16 | 0/0/0 |
| **최종 전체 SIG 보완본 메타** | **77.080초/772** | **137ms** | **11** | **0/0/0** |
| **최종 전체 SIG 보완본 실제 emit 직전** | **77.079초/772** | **129ms** | **13** | **0/0/0** |

기본 실행기의 타이머 복귀 지연 최대24ms, 잠금 대기22.285ms, 수신 처리40.405ms가 관찰됐다. 전용 실행기는 잠금 대기가 줄었지만 타이머 지연 최대46ms·발행 경과37.126ms가 관찰됐고 최종 간격은 개선되지 않았다. [코루틴 공식 계약](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/as-coroutine-dispatcher.html)의 ScheduledExecutor 지연 예약 방식을 실제 시험했으나 **전용 실행기 안은 채택하지 않았다**. 실험 소스는 `/tmp/.../isolated-experiment/`에만 보존했다. 서비스 실행기도 변경하지 않았다. 조사용 HUB_TIMER_LATE 계측을 위해 잠시 사용한 공유 WorkProbe 변경도 원래 바이트로 복구했다. 최종 코드에는 기존 WorkProbe를 SDK 화면에서 사용하고 실제 emit 감사만 남겼다. 다른 담당의 공용 성능 계측 파일을 변경하지 않는다.

계측 경과는 CPU 실행 시간과 같지 않다. GC/OS 스케줄링/앱 계산의 기여율을 이 숫자만으로 단정하지 않는다. 초기 atrace는 버퍼 덮어쓰기와 일부 스케줄 사건 누락이 있어 정확한 프레임별 인과 증거로 쓰지 않았다. warm 전환의 발행0 세션2개도 유효한 대조에서 제외했다. 전용 실행기가 단일 해법이라는 가설은 이번 비교로 지지되지 않는다.

**SIG_001 엄격 주기는 계속 미달**이다. 평균10Hz·producer deadline누락0·소비순번누락0만으로 통과를 선언하지 않는다. 실제 emit 감사는 기존 메타데이터 시각과 별도로 기록한다. TTL 확대·시계/watermark 지연·원천/수신/발행 시각 덮어쓰기는 하지 않았다. 60배 DemoPlayer는 재실행/수정하지 않았다. [계측 요약과 원본 해시](evidence/2026-11-08-role1-finish/sdk-measurements.json).

## 운행·저장 경계 검증

TripRules와 최근창·UNKNOWN 재개의 실제 구현 및 기존 시험을 읽었고 생산 규칙 변경은 필요하지 않았다. `Role1BoundaryRoomTest`는 **주입 Clock으로 만든 epoch 관측을 메모리 Room에 저장·로드한 뒤 REAL UNKNOWN 복원**한다.

- 정차299,999/300,000ms에서 잠정 포함→소급 제외, 899,999/900,000ms에서 유지→연속0·이벤트 경계를 확인했다. UNKNOWN 복원 공백은15분 휴식 증거를 만들지 않는다.
- OFF 정확히7,200,000ms와1ms 초과를 저장·복원했다. 이미 확정한 pending-new-trip 근거만 유지하고 UNKNOWN 공백으로 OFF를 연장하지 않는다. 다음 D에서만 새 운행 이벤트가 생긴다.
- 최근24시간 창 끝에 남은1,345ms를 정밀 절단하고 초 표시는1초가 됨을 확인했다. 원래 Room snapshot·운행 ID는 그대로다.

**이번 기기3개 통과, 실행0.425초**다([runner](evidence/2026-11-08-role1-finish/boundary-room.txt)). 실제5분/15분/2시간/24시간을 기다린 시험이 아니다. 실제24시간 대기는 하지 않았다. Room 구현/스키마·서비스 수명을 변경하지 않아 기존 시작 연타·화면 독립·부팅·Room10개를 이유 없이 반복하지 않았다.

## 전체 자동시험·빌드

최종 실행:

```bash
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./src/android/gradlew -p src/android \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:-TieredCompilation' \
  --max-workers=2 --rerun-tasks :engine:test :app:testDebugUnitTest \
  assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest
```

**engine153+app337=490개**, 실패/오류/skip0. 기존480개+신규10개다. 신규는 통풍/현재온도의 실제 공유 배송→Hub2개, 내비 의미/경로/이력3개, 내비 보고→Hub 관측 무증가1개, 실제 emit과 기존 프레임/센서 시각 분리1개, 실제 AAOS 공유 전달→Hub3개다. AAOS3개는 최초 조회 무관측·같은/변경 재조회 무관측·거부 배송 무재전달을 확인한다. 기존 SDK 회귀의 최초 조회를 관측1회로 보던 기대값은 이번 “조회는 센서 관측이 아님” 계약에 맞게0으로 바로잡고 값 전달/정상 콜백/null 유지 검증을 강화했다. 81개 Gradle task를 모두 실행했고 debug APK·AndroidTest APK·release manifest가 성공했다. [전체 로그](evidence/2026-11-08-role1-finish/final-sig-tests-build.txt), [수치/APK 해시](evidence/2026-11-08-role1-finish/test-results.json)에 실제 실행을 남겼다.

검증 중 새 시험의 필수 인자와 fixture nullable 콜백 타입을 컴파일러로 확인·수정한 중간 실패가 있었다. 최종 로그와 중간 로그를 혼합하지 않았다. 중간 로그와 실행기 실험은 `/tmp/awakedrive-role1-finish-6gqbu4xt/`에 보존했다. Lint·60배 기기 성능·제품 E2E·서명release·새 부팅/STR/실제 OFF·모든 권한/CarService 장애 TC는 이번에 수행하지 않았다.

기어 단독 보완 뒤 중간 기기 runner와 최종 전체 조회 분리 뒤 runner를 추가 실행했다. 최종 통풍/기어 결합 기기1개는8.413초에 통과했다. 같은 메서드의 중간/최종 재실행을 별개 기능 시험 수로 더하지 않는다. SDK 입력 fixture 최초 runner는 통풍 readback1개 통과+내비 조건 미충족1개이며, 이어 보강한 최종 통풍 Adapter→Hub 단독 runner1개가 통과했다. 이전 입력 API 확인과 최종 Hub 확인을 서로 다른 실행으로 구분한다. 전체 자동490개에 이 기기 실행을 더해 같은 시험 수로 표시하지 않는다.

## 문서·데이터 보존과 남은 것

PROGRESS와 관련 가이드에 최신 현황표·시험 수·여섯 상태/내비 계약을 연결했다. 서비스/부팅을 여전히 미구현으로 읽게 하는 가이드 문장을 정리하고 min28/target34/compile37을 명시했다. 과거 개발 로그의 당시 기록은 수정하지 않았다. 제출 README 준비·제출용 서명APK·PT·다른 담당 통합은 제외했다. `src/android/README.md`는 개발 환경표 target34와 기존 서비스 구현의 사실관계만 정정했고 제출 안내/릴리스 문서는 작성하지 않았다.

수동/REAL 두 DB의 전후 모든 논리 행 해시가 같고 integrity는 ok다. REAL 운전구간18개와 수동1개를 포함해 기존 행을 보존했다. 기존 cache 원본 전체와 `real_trip_monitor` 실행 의사도 바이트 동일이다. 기존 sdk-signal-probe.json은 최초 바이트로 복구했고 새 진단은 sdk-signal-role1-*.json/role1-*.json의 별도 이름이다. DB를 백업본으로 덮거나 초기화하지 않았다([보존 대조](evidence/2026-11-08-role1-finish/preservation-final.json)). 최종 감시 없음/requested=false, Console의 이 시험 전용 heartbeat만 종료, 통풍0·기본 시험좌표 복귀다. 기기는 SDK 미연결 화면에 둔다.

브랜치·HEAD·기존 미커밋 작업을 보존했고 전환/main병합/커밋/push/앱 제거/DB 초기화/AVD Wipe는 하지 않았다. 교체 설치와 직접 instrumentation runner만 사용했다. APK·DB·SDK 바이너리·전체 hal dump/trace는 저장소에 추가하지 않았다. 전체 HAL dump 임시 파일은 필요한 지원 여부만 남기고 제거했다.

남은 1번 항목은 엄격 발행 시한, 실제 활성 내비/이동 heading/현재온도 변경과 일부 지연·장애 기기 조건이다. 실제 ignition은 AVD 미지원, 조향·착석은 의미 보류다. 2번 판단·기준선, 3번 개입·음성, 4번 쉼터/휴식·제어, 5번 제품UI/데모는 계약만 전달하고 연결 대기로 남긴다. 이 제한을 상위 요구 전체 완료나 공식 TC Pass로 바꾸지 않는다.

## 2026-11-09 후속 정정 — 활성 내비 관측

위 기록은 당시 판정이며 원본 증거·490개/Room3개/통풍·기어1개 실행을 그대로 보존한다. [11/9 후속](2026-11-09-role1-sig-input.md)에서 이전 `role1-navi-input.json`을 재분석하니 `activeObserved=false`와 달리 내부에 `ROUTE_GUIDING + isRouting=false` 15회·목적지60초/50m·TBT50m가 있었다. 따라서 위 ‘활성 경로를 만들지 못했다/입력 조건 미충족’ 서술은 정정한다. 이어받은 명시적 모드 gate 보완을 이번 실제 어댑터→Hub60초/50m/TBT50m·취소null로 확인했다. 이번 자동492개와 활성 내비 기기1개는 이전 실행과 별도다. 실제 이동 heading·현재온도 변경·자연 도착/교차로 통과·엄격110ms는 계속 미검증 또는 미해결이다.
