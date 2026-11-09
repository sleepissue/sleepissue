# 2026-11-07 — SIG_001~017 실제 Playground 수집 보완

## 범위와 판정

[구현 전 17개 현황](2026-11-06-signal-sdk-review.md)을 먼저 작성하고 [공식 SDK·API34 근거](2026-11-06-signal-sdk-api-evidence.md)를 대조했다. SRS 원본은 Draft·빈 TC 판정을 그대로 보존했다. 기존 11/03 기기 증거와 385개 기준 소스·APK·Room·미커밋 작업을 백업한 뒤 **1번 SDK 수집·진단·시험만** 보완했다. 다른 담당 엔진/음성/휴식 실행 통합과 60배 성능 재작업은 하지 않았다.

**SIG 17개 전체 완료로 판정하지 않는다.** 조향/착석은 API 수신과 의미 정규화를 구분했고, 통풍 1단계와 활성 내비 경로·일부 지연 TC는 미확인이다. 실제 SDK Hub의 110ms 간격 TC는 아래 측정에서 미달했다. 기존 검증을 새 합격으로 바꾸거나 새 미재현 결과로 지우지 않았다.

## 공식 계약과 우선 확인

- **조향:** Vehicle2.3.2 공식 소스의 선언은 전륜각 °·왼쪽 음수이며 운전대각과 다르다. Console의 운전대각 설명/범위와 충돌한다. 현재 AVD에서 +10→SDK -10, -10→+10을 다시 수신했다. 이 관찰로 조향비나 실제 물리 의미를 확정하지 않았다. `apiReadable=true/READ`와 `normalization=SEMANTIC_UNCONFIRMED`, canonical `steeringDeg=null/UNSUPPORTED`를 따로 표시한다.
- **착석:** 공식 SDK의 조회/이벤트가 정수 상태를 `==1` Boolean으로 축약한다. 네 Console 좌석에서 Occupied→SDK false, Empty→true를 확인했다. UNKNOWN과 다른 숫자 상태가 false로 합쳐지므로 Boolean 반전이나 동승자 존재 추정은 하지 않는다. `LOSSY_SDK_BOOLEAN`으로 격리한다. SDK는 5개 좌석 영역 READ를 제공하지만 Console은 Rear center 입력을 제공하지 않아 해당 변경은 미확인이다. 이 AVD에서 Driver→ROW_1_LEFT, Passenger→ROW_1_RIGHT, Rear left/right→ROW_2_LEFT/RIGHT의 개별 raw 변경과 다른 좌석의 불변을 대조했다. ROW_1_LEFT를 모든 차량의 운전석으로 일반화하지 않는다.
- **API34 대안 권한:** 현재 이미지의 AAOS 원시 좌석 조회는 signature/privileged `CONTROL_CAR_SEATS`가 필요하다. 최신 문서의 dangerous `READ_CAR_SEATS`는 이 API34 이미지에 없다. 권한 문자열 추가·루트 우회·SDK 내부 API로 해결하지 않았다.
- **통풍 입력 불일치:** Console V1/V3는 `HVAC_SEAT_TEMPERATURE=0x1540050b`의 -1/-3이다. SDK 통풍은 `HVAC_SEAT_VENTILATION=0x15400513`의 별도 비음수 단계다. 새 진단 `SEAT_TEMPERATURE:ROW_1_LEFT`에 -1/-3/0을 수신했고, 통풍의 실제 조회값은0·callback0·원래 수신시각 그대로였다. 이를 통풍1/3으로 치환하지 않았다. 통풍 API는 READ_WRITE/영역0..3으로 지원되지만 Console catalog에는 전용 ventilation 입력이 없다. **입력 환경 미제공/TC 미확인이며 API 미지원이나 수집 미구현이 아니다.**

고정 SDK 소스 해시·메서드·콜백·영역·권한·공식 URL은 위 근거 문서에 있다. 공개 SDK 소스/바이너리는 저장소에 넣지 않았다. 온열/냉각 정수 단계는 현재 실내온도 °C와도 다른 값이다.

## 바꾼 코드와 이유

1. `SdkSignalStore`는 수집 중 `PERMISSION_DENIED` 뒤 늦은 callback/query/capability가 값을 AVAILABLE로 복구하지 못하게 한다. 권한 재허용 뒤에는 새 어댑터로 재연결한다. capability NONE 뒤 늦은 수신과 수신 경과시각 역행도 차단한다. 읽기 지원 재확인은 이미 받은 정상 값/시각을 지우지 않는다.
2. 미지원·권한 거부·오류·미수신 상태를 센서 수신과 다른 `STATE` 기록으로 남긴다. 같은 상태 반복은 중복 기록하지 않고, 실패가 원천/수신 시각이나 callback/query 개수를 만들지 않게 했다. 보호 폐기는 `rejectedDeliveries`에 별도로 표시한다. 수용 callback의 도착 Hz를 원천 센서 Hz로 해석하지 않는다.
3. 통풍은 capability가 알려 준 min/max 안의 값만 수용한다. 범위 밖 값은 raw를 남기고 ERROR/null이다. 좌석 온열/냉각은 별도 진단 전용 값으로만 읽는다. 통풍 제어 API나 다른 담당 ClimateController는 연결하지 않았다.
4. 좌석 capability의 동기 예외를 5개 좌석 모두에 반영하고, 위치 조회 실패는 LAT/LON/HEADING, 목적지 조회 실패는 남은 시간/거리 함께 무효화한다. 경로 상태가 미수신/만료되면 기존 경로 값을 지우되 수신 시각을 연장하지 않는다.
5. SDK 화면에 원시 API 지원과 정규화 상태, 보호 폐기 계수를 나눠 표시한다. **진단 저장**은 원시/정규화/Hub 최종 값·권한·시각·Hz·수신 기록·실제 발행 순번 감사를 원자 교체로 저장하고 진행/완료/실패를 화면에 남긴다. SDK 자동 진입은 Room ViewModel을 만들지 않는다. 검사 플래그의 자동 저장 주기는 실제1초이며 전 원본 로그나200ms 지연 측정기가 아니다.

Vehicle/Navi의 원천 시각 미제공은 계속 null/UNKNOWN이다. AAOS의 원천 ns→ms와 필드별 실제 수신시각은 유지한다. SDK 검증 TTL3초는 여전히 **[가정]**이다. 통풍/기어 같은 on-change 입력이 가만히 있으면 최종 STALE/null이 될 수 있으며, 새 좌석 온열 응답이나 Hub 발행이 이들의 시각을 연장하지 않는다. 제품의 이벤트형 값 유지/재조회 정책은 별도 확정이 필요하다.

## 실제 기기 결과

같은 창 있는 Pleos_Connect_2_1_0/API34/emulator-5554/user10을 사용했다. 연결 가능한 브라우저 제어가 없어 Console이 실제 사용하는 catalog·HTML과 동일한 로컬 HTTP API로 입력하고 **앱의 실제 SDK/AAOS 응답을 별도 대조**했다. HTTP200만으로 수신 성공을 세지 않았다. DDS 입력 세션을 유지했으며 주행 제한을 해제하거나 SDK 값을 가짜 입력으로 대체하지 않았다.

| 항목 | 이번 관찰 | 판정/한계 |
| --- | --- | --- |
| 조향 | +10/-10/0 → SDK -10/+10/-0, canonical null | SDK API READ 수신 확인. 운전대각 의미는 미확정 |
| 좌석 | Driver/Passenger/Rear left/Rear right의 Occupied→false, Empty→true | raw 수신 확인. 정규화 전부 보류, Rear center 변경 미확인 |
| 좌석 온열/냉각 | V1→-1, V3→-3, Off→0, 범위-3..3 | 별도 진단 수신 확인. 통풍으로 변환하지 않음 |
| 실제 통풍 | 최초 조회0·범위0..3·변경 callback0 | 온열 입력과 독립. 3초 뒤 Hub는STALE/null. 통풍0→1→0 미확인 |
| 방향지시등 | LEFT→RIGHT→OFF | 전체 enum 수신 확인. 조작→Hub 최초반영≤200ms는 미측정 |
| 브레이크 | true→false | 실제 수신 확인. ≤200ms 미측정 |
| 기어 | R/N/D/P, raw2/1/8/4 | 모두 실제 수신 확인. ≤200ms 미측정 |
| 크루즈 목표 | 25m/s→90km/h, 27.777779m/s→100km/h | 변환 수신 확인. ≤200ms 미측정 |
| 크루즈 상태 | Console Longitudinal/Ready에 SDK ENABLED→false, Not ready에 raw null→미수신 null | Console 라벨을ACTIVATED로 추정하지 않음. 활성 true 전이 TC 미확인 |
| 차속 | 현재0 수신, 권한 거부 null, 재허용 후 AAOS READ/0 복구 | 새 HTTP80 요청은SDK0으로 관찰되어 성공으로 세지 않음. 입력/브라우저 물리계산/에뮬레이터 적용 경로 원인 미분리. 기존11/03의22.222223→80.0 증거와 변환 시험은 보존 |
| 내비·현재 온도 | 고정 위치 쌍·현재 온도 조회, 활성 경로 없는 남은 시간/거리/TBT null | 실제 이동/활성 경로/현재24.9→25.0 지정 입력은 미확인 |
| 권한 철회 | CAR_SPEED 철회로 PID7584 종료. 거부 재진입에서 null/PERMISSION_DENIED·로그1회, 원래 grant 복구·새수신 확인 | 같은 살아 있는 세션의 늦은 callback 차단은 단위시험 근거와 분리 |
| 종료 | 같은 PID8838에서 새 미연결 SDK 화면으로 가린 뒤 onStop 최종 저장, 이후2초 파일 불변 | SDK 세션/조회/Hub 종료. Room 미진입 |

`case` 단위 원시 기대값이 없는 크루즈 관찰들은 raw 관찰로 표시했으며 Console→SDK enum 대응 합격으로 세지 않았다. 수집 파일1초 주기로 최초 반영 지연200ms/1s를 판정하지 않는다. 변경 값 수신과 지연 TC를 분리했다.

### SDK 10Hz 발행 감사

최초 연결의 **73,085ms** 연속 구간에서732개 발행을 관찰했다. producer deadline누락0·소비 순번누락0·중복0·역행0이지만 **최대 실제 발행 간격147ms, 110ms 초과20회**다. 평균10Hz나누락0만으로 모든 간격≤110ms를 충족했다고 하지 않는다. 이는 SIG_001의 주기 부분 미달이며 조향의 물리 의미 보류와도 별개다.

권한 복구 뒤의 별도 세션은 종료까지840,698ms/8,408개, 최대150ms·110ms초과174회였고 순번누락/중복/역행은0이었다. 두 감사 창을 합산하거나 더 좋은 값만 선택하지 않았다. SDK/GC/호스트 스케줄링 기여율은 이번 수집 보완에서 분리 측정하지 않았으므로 환경 탓만으로 결론 내리지 않는다. 60배 성능 수치는 재측정하지 않았다.

## SIG_001~017 보완 후 상태

| ID | 구현·이번 보완 | 기기 확인 / 남은 판정 |
| --- | --- | --- |
| SIG_001 | SDK 조향 수집·100ms Hub·발행 감사 구현 | raw 수신, 110ms 시간 TC 미달. 운전대각 의미 미확정 |
| SIG_002 | 속도 변환·권한 보호 구현 | 0/거부/복구 확인. 새80 재현 미완료, 이전80 증거 보존.200ms 미확인 |
| SIG_003 | OFF/LEFT/RIGHT 수집 구현 | 세 상태 확인.200ms 미확인 |
| SIG_004 | SDK enum 기준 활성 여부 구현 | ENABLED/미수신 확인. ACTIVATED true 전이와200ms 미확인 |
| SIG_005 | 개별 좌석 구독·손실 의미 표시 구현 | 4개 Console 좌석 raw 변경 확인. canonical 의미 보류, Rear center 변경·1s 미확인 |
| SIG_006 | 위치/계산 heading·조회 실패 그룹 무효화 구현 | 고정 위치 확인. 이동 heading/내비 앱 부재/1s 미확인 |
| SIG_007 | 권한/미지원 뒤 늦은 수신·역행 차단·상태 로그 보완 | 권한 거부·복구/종료 확인. 실제 capability NONE·구독 강제오류는 미확인, 해당 경계는 단위시험 |
| SIG_008 | 기존 Hub 중재 계약 보존 | SDK+JSON 동시 실행은 미조립/미확인. 이번 작업에서 추가하지 않음 |
| SIG_009 | 기존 RateMeter/UNKNOWN 계약 보존 | Vehicle 원천 시각 미제공으로 센서Hz 미확정. 역할2 지표/가중치 소비는 미통합·범위 밖 |
| SIG_010 | 기존 외부 단위 변환 보존 | 목표속도90/100 실제 변환·AAOS 원천시각 보존. 전체 TC 완료는 주장하지 않음 |
| SIG_011 | 기어 수집 보존 | P/R/N/D 확인.200ms 미확인 |
| SIG_012 | 브레이크 수집 보존 | true/false 확인.200ms 미확인 |
| SIG_013 | 목표속도 수집 보존 |90/100 확인.200ms 미확인 |
| SIG_014 | 통풍 SDK bounds 검증·별도 좌석 온도 진단 | API READ_WRITE/0 확인. Console 통풍 전용 입력 미제공,0→1→0/1s 미확인 |
| SIG_015 | 현재 실내온도 수집 보존 | 현재값 수신. 설정온도와 구분,24.9→25.0/1s 미확인 |
| SIG_016 | 경로 gate·목적지 실패 그룹 무효화 보완 | no-route null 확인. 활성 경로 남은 시간/거리·오류 회복·1s 미확인 |
| SIG_017 | TBT 수집·만료 gate 보완 | no-route null 확인. 실제 다음 회전의 의미/접근/해제·1s 미확인 |

**미지원/의미 미확정/미구현은 다르다.** 현재 조향·착석의 canonical `UNSUPPORTED`는 SDK API capability NONE을 뜻하지 않는다. 통풍의 Console 입력 부재도 SDK 수집 미구현을 뜻하지 않는다. SDK+JSON 동시 조립과 다른 담당 소비는 실제로 없는 경로이며, 활성 내비 TC는 구현된 읽기 경로의 미검증 조건이다.

## 시험·빌드·보존

권한 상실 후 늦은 수신과 상태 로그의 새2시험은 수정 전 실제red를 확인했다. 보완 후 **기존385개 포함 engine149+app247=396개**, 실패/오류/skip0·`assembleDebug`·`:app:processReleaseMainManifest`가 통과했다. 새11개는 상태 보호/기록2·범위/의미/늦은 수신5·발행 감사4다. API/의존성·targetSdk34/compileSdk37·Room 스키마·엔진·60배 생산 경로는 바꾸지 않았다. release manifest에 개발 Activity가 없음을 확인했다.

최종 APK SHA256은 `1decf7ccb2ff79377e354d69cfb9acac3e5cb9a6771d914dc8f63b0493ce507a`다. 실제 입력 비교는 capture-only baseline APK(`f750257b94eec9bf560bbb394147e7cdc06bce9ab6b1453e41303fb712c6cb2d`)와 최종APK를 구분한다. 자동 상태 저장 전의 오래된 캐시와 초기 연결 중 UNAVAILABLE을 새 수신 완료로 세지 않고, 실제 API READ·수신 확인을 기다렸다. 첫 권한 복구 확인은 초기 UNAVAILABLE 상태를 너무 일찍 읽어 재확인했고 정상 수신을 확인했다. 첫 종료 시도는 기존 task에 Intent만 전달돼 onStop이 성립하지 않았으므로 무효 증거로 보존했다.

원본·중간red·최종 로그와 모든 JSON은 `src/android/app/build/reports/signal-sdk-20261107/`, 선택된 증거와 해시는 [증거 모음](evidence/2026-11-07-sdk/validation.json)에 있다. 기존 미커밋 작업/385개·장기 측정 사본·DB는 `/tmp/awakedrive-sig-sdk-preserve-20261106/`에 보존했다. 브랜치 전환·커밋·push·앱 제거·데이터 초기화는 하지 않았다.

## 직접 누를 순서와 기대값

1. 기존 창 있는 AVD를 유지한다. `adb forward tcp:8088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:8088/`를 열어 AVD Enabled/DDS ONLINE을 확인한다. 입력은 P/0부터 시작한다.
2. 앱 **Playground SDK 실시간 검증 → 차속 권한 허용 → 연결**. 권한을 바꿨으면 **재연결**한다. 전역 연결 문구만 보지 말고 신호별 API READ·조회/콜백 증가를 기다린다.
3. Console **Motion → Steering Angle +10/-10**: 이 버전의 SDK raw는 -10/+10, canonical null과`SEMANTIC_UNCONFIRMED`가 기대값이다. 비율을 곱하거나 부호를 뒤집어 운전대각으로 넣지 않는다.
4. **Convenience → Seat Occupancy → Passenger Empty/Occupied**: 이 버전 SDK raw true/false, canonical null과`LOSSY_SDK_BOOLEAN`이 기대값이다. Driver/Rear left/Rear right도 같은 raw 비교를 할 수 있다. Rear center는 현재Console 버튼이 없다.
5. **Climate → Driver Seat Temperature V1/V3/Off**: `SEAT_TEMPERATURE:ROW_1_LEFT`의 -1/-3/0을 확인한다. `VENTILATION_LEVEL`은 별도 raw0이며, 새 통풍 수신이 없으면 3초 뒤 최종null/STALE다. 온도 setpoint를 바꿔 현재 실내온도가 즉시 같은 값이 된다고 기대하지 않는다.
6. **Convenience → Turn Signal State Left/Right/Off**, **Brake Pedal0→50→0**: LEFT/RIGHT/OFF와false/true/false를 확인한다. 기본 카메라 팝업은 닫되 OS 주행 제한을 우회하지 않는다.
7. 속도0에서 **Gear Selection P/R/N/D/P**, **ADAS → Cruise Target Speed25/27.7778m/s**: 기어 그대로, 목표90/100km/h를 확인한다. Cruise Mode 라벨만으로활성true를 기대하지 말고 SDK rawACTIVATED인지 확인한다.
8. 차속은 D에서 Console의 **ACCEL**로 실제속도를 올린다. 정차·P로 돌아와 **수신 기록**에서 raw m/s→km/h를 본다. 이번 직접 HTTP80 요청은 재현되지 않았으므로 현재브라우저 입력 경로의 실제수신을 확인해야 한다.
9. **진단 저장** 완료를 확인한다. **중지**는 SDK/Hub를 닫고 최종값을 비운다. **재연결**은 새 계수/기록을 시작한다. 저장 파일과 과거 수신 기록은 현재 최신값이라는 뜻이 아니다. Room은 이 SDK 세션에서 사용하지 않는다.

최종 Room 논리 덤프 SHA256은 전후 `c9961514d35261a0aec539d34c3bb0123d8756c24f9f01625bef0a80dfd23d9f`로 같다. `trip_checkpoint`·`drive_segment` 각1개를 포함한 모든 행수와integrity_check=ok를 대조했으며 원본DB를 복원본으로 덮지 않았다. CAR_SPEED grant는 원래 허용 상태로 복구했다. 측정 입력은 P/0·조향0·브레이크0·좌석Empty·좌석온열Off·방향지시등Off·크루즈Ready/원래목표로 복귀했고, 자체 Console session만 종료했다. SDK는 미연결 화면에 두었으며 사용자 브라우저·AVD는 종료하지 않았다.

기존385개 소스 사본과 기존 SDK 증거는 유지했고, 회귀시험이 고정 이름에 생성한 JVM 산출물9개는 새 보고서 `regression-generated/`에 보존한 뒤 원래385개 기준 내용으로 복구했다. 기존 소스 변경은 1번 SDK/진단 진입4파일과PROGRESS/SDK가이드2파일뿐임을 해시 대조했고, 엔진·데모시간축·다른 담당 모듈은 변경하지 않았다.
