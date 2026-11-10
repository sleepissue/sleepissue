# 2026-11-08 — 방향지시등·브레이크·크루즈 상태 유지와 최신성

시작 `develop-jdw`·HEAD `f5c6ad9e179383ddb2b5cff3aad9ac4230afabe2`, 작업 트리 clean을 확인했다([시작 기록](evidence/2026-11-08-state-signals/start.json)). AGENTS.md·PROGRESS·SDK/Hub 가이드·기어 조사 기록과 기획서4-2/4-3·보조 주행 절을 읽었다. 기존 **자동450개(engine153+app297)·Room 기기10개**는 기준 기록이다. 이번 실행 결과는 아래에 따로 둔다. 브랜치 전환·커밋·push·앱 삭제·데이터 초기화는 하지 않았다.

## 공식 계약과 수정 전 문제

[TurnSignal 공식 API](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/turn-signal/entry), [Brake](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/entry), [DrivingMode](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/entry), [공식 샘플](https://github.com/Pleos-Playground/pleos-sample-vehicle-app)과 설치 버전의 공개 소스를 대조했다. 문서 페이지는 서명 본문 일부를 텍스트로 제공하지 않아, 구체적인 nullable 형식·권한·단위는 [공식 Maven Vehicle 2.3.2 sources](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/Vehicle/2.3.2/Vehicle-2.3.2-sources.jar)를 근거로 삼았다. SDK 소스/바이너리는 `/tmp/awakedrive-state-signals-20261108/`에만 두었다.

- Vehicle sources SHA256 `62b5dd2c4c0523fd13c2e64040cd775bc89a14ce54f35a37560d0eb4eb3fab40`.
- vehicle-data1.1.11 sources SHA256 `54094b98cfb7b1d2fa7fab743b0e48074da1d9f4bd2a2cbd3ad4a9914de18f65`.
- 조회는 `onComplete/onFailed` 비동기, 등록은 상태 갱신 리스너다. 공개 capability는 READ/READ_WRITE 확인에 사용하며 **무변경 상태의 최소 콜백 주기는 공개 API에서 보장하지 않는다.** ON_CHANGE라는 내부 모드를 추측해 지정하거나 요청 Hz를 임의 설정하지 않았다.
- Vehicle 콜백/조회에는 원천 센서 시각이 없다. source time은 null/UNKNOWN이며 앱에서 만든 시각으로 채우지 않는다.

권한 `P.*`는 `pleos.car.permission.*`이며 아래 두 권한은 SDK 주석에서 normal이다. 각 신호의 capability가 READ/READ_WRITE이고 실제 권한이 있을 때만 조회/구독한다.

| 신호 | 공식 capability → 조회 → 등록/해제 | 원단위·정규화·의미 |
| --- | --- | --- |
| TURN_SIGNAL | `checkTurnSignalStateCapabilities` → `getCurrentTurnSignalState` → `register/unregisterTurnSignalState` | P.CAR_LIGHTS, `TurnSignalValue?` OFF/LEFT/RIGHT. OFF는 유효, null은 미수신/미확정. 없는 HAZARD를 만들지 않음 |
| BRAKE_ON | `checkBrakePedalStatusCapabilities` → `getCurrentBrakePedalStatus` → `register/unregisterBrakePedalStatus` | P.CAR_DRIVE, `Boolean?` 그대로. false는 유효한 해제 상태, null과 다름 |
| CRUISE_ACTIVE | `checkCruiseControlStateCapabilities` → `getCruiseControlState` → `register/unregisterCruiseControlState` | P.CAR_DRIVE, `CruiseControl?`. ACTIVATED=true, ENABLED/SUSPENDED=false. OTHER/USER_OVERRIDE/FORCED_DEACTIVATION_WARNING/null은 미확정 null |
| CRUISE_TARGET_KPH | `checkCruiseControlTargetSpeedCapabilities` → `getCruiseControlTargetSpeed` → `register/unregisterCruiseControlTargetSpeed` | P.CAR_DRIVE, `Float?` **m/s×3.6→km/h**, 비음수 유한값만 사용. 0도 유효, null과 구분 |

수정 전에는 이 네 신호에 최초 조회/콜백만 연결하고 일반3초 수신 TTL을 적용했다. 실제 수정 전 APK에서 원시 OFF·false·ENABLED·13.8889m/s가 남아 있는데 Hub 최종 값은 모두 null/STALE인 표본을 확인했다. 콜백/조회는 각각1/1, 마지막 수신부터 캡처까지 약119,983~120,808ms였다([실제 수정 전 요약](evidence/2026-11-08-state-signals/baseline-summary.json)). 기어만 성공 조회로 확인 lease를 갱신하던 구조이므로, 상태가 안 바뀌는 정상 무콜백을 입력 단절과 구분하지 못했다. 이 관찰을 SDK의 주기 보장이나 물리 차량 검증으로 확대하지 않는다.

## 구현 정책과 경계

`SdkStateSubscription`을 네 신호에 각각 연결했다. 최초 조회와 이후 재조회를 같은 직렬 루프에서 실행한다. **[가정] 성공/응답 뒤1초 간격, 확인 lease3초, 경계 포함**을 기존 `SdkProbePolicy`에서 사용한다. 전체 TTL을 늘리지 않았다. 예약/SDK 호출은 IO이며 Main의 화면 갱신·Store 반영을 기다려야 다음 조회를 예약하는 의존은 없다. 중복 start는 무시하고 신호당 미완료 조회는 하나다.

- SDK 응답/실제 콜백 입구에서 실제 elapsed 수신 시각을 채집하고 Main으로 전달한다. 같은 원시/정규화 상태의 재조회는 **확인 시각만** 갱신한다. 원천 시각·기존 실배송 시각·센서 관측 순번·콜백 계수는 유지한다. 원천의 실제 변경 시각은 SDK가 제공하지 않아 미확정이다.
- 재조회에서 값이 달라지면 실제 응답 값을 전달하지만 새 센서 관측으로 세지 않는다. `VehicleSignalInput.confirmationFields`가 이를 표시하며 Hub는 캐시/품질·명시 null을 반영하되 관측 순번·`vehicleCallbacks`·`rawVehicleObservations`를 증가시키지 않는다. 기존 입력의 기본은 빈 집합이라 기존 DEMO·일반 입력 경로는 유지한다. 기어의 기존 재조회/계측 경로는 보존했다.
- 부분 갱신에 없는 필드는 유지하고 해당 필드의 명시 null은 UNAVAILABLE로 지운다. OFF/false/0은 AVAILABLE이다. 음수·NaN·무한 목표 속도는 ERROR/null, 의미가 모호한 크루즈 enum은 raw를 보존한 SEMANTIC_UNCONFIRMED/null이다.
- 조회 중 또는 Main 반영 대기 중 새 콜백이 오면 그보다 먼저 시작한 조회는 폐기한다. 배송 순번이 같은 ms의 역순 전달도 차단하고, Store는 과거 응답의 확인 시각 역행을 차단한다. 실제 수신/원천/확인 시각을 clamp하거나 현재 시각으로 덮지 않는다.
- 조회/구독 실패·권한 상실은 해당 신호를 무효화하고 세션을 닫는다. SDK 요청 취소 API가 없어 응답 무기한 대기 대신3초 기한을 두며, 기한이 끝난 요청 위에 새 요청을 겹쳐 보내지 않는다. 늦은 성공·중복 응답·이전 어댑터 콜백은 폐기한다. 재개는 새 어댑터를 만드는 명시적 재연결이다. CPU/스레드가 실행 가능한 조건에서 기한을 평가하며 정지된 OS의 정확한3초 실행은 보장하지 않는다.
- `Vehicle` 공개 API에는 연결 해제/재연결 세대 이벤트 리스너가 없다. 내부 Binder 세대를 추측하지 않는다. 공개 오류/무응답 기한과 lease 만료로 과거 값을 차단한다. **성공 조회는 SDK 현재값 확인이며, DDS/물리 센서의 무중단 생존 증명은 아니다.** 공개 오류가 나오기 전 모든 내부 연결 해제를 즉시 알아낸다고 보장하지 않는다.

SDK 단독 화면은 계속 화면 종료 시 정리되고, 감시 서비스에서는 기존 서비스 소유 scope를 그대로 사용한다. 서비스 수명·운행 규칙·Room 스키마·전원 UNKNOWN을 변경하지 않았다. 통풍·조향·착석·내비, 다른 담당 엔진·음성·개입·DemoPlayer·60배 성능은 수정하지 않았다.

## 자동시험·빌드

이번 결과는 **engine153+app320=473개**, 실패/오류/skip0이다([결과·XML 실행 시각·APK SHA](evidence/2026-11-08-state-signals/test-build-result.json), [최종 빌드 로그](evidence/2026-11-08-state-signals/verified-build-test.txt)). 기준450개를 포함하며 신규23개는 상태 저장/정규화14·비동기 구독8·Hub 통합1이다.

- 각 상태20초 유지, 정확한3,000ms/3,001ms 경계, 실제/확인 수신 시간 분리, 같은/역순 응답, 동일 ms 배송 순서, 조회 중 새 콜백, null·부분 유지, 조회 실패/권한/해제 뒤 과거 응답 차단을 검증했다.
- 직렬 미완료 조회1개·중복 start·Main 정체와 독립인 예약·기한 초과/중복 응답·이전 인스턴스 폐기를 검증했다. 크루즈 전체 SDK enum과 목표0/20m/s·음수/비유한 입력도 검증했다.
- Hub의 최종 값/품질·REAL 출처·UNKNOWN 원천 시각·실배송/확인/유효 끝 전달을 검증했다. **값이 바뀐 재조회도** 센서 관측 통로·콜백 수·관측 순번을 늘리지 않으며 부분 null이 다른 신호를 지우지 않음을 확인했다.
- 처음 선택 실행에서 신규 시험의 예외 객체 동일성 단정1건이 실패했다. 코루틴 stacktrace 복구가 예외를 복제할 수 있어 타입/메시지·실제 차단 동작을 검증하도록 고쳤다. 다음 빌드에서는 신규 Hub 시험의 필수 `rateTimeBasis` 인자 누락을 수정했다. 제품 결함을 숨기기 위해 기대값을 완화한 변경은 아니다. 최종473개는 이 수정 후 결과다.
- engine153개는 이번 작업의 `--rerun-tasks` 실행에서 실제 재실행했다. 최종 명령에서는 engine은 UP-TO-DATE, app320개는 최종 소스로 실제 재실행했다. 실패했던 빌드 전체를 통과로 표시하지 않는다.
- `assembleDebug assembleDebugAndroidTest processReleaseMainManifest` 성공. 기존 JBR25/Gradle 기준을 유지하고 `-XX:-TieredCompilation`, workers2를 사용했다. 설치 APK SHA256은 `024c7e369dcd0b314d784263be8231940e2fde4d845e2b4a00a6b582d6c85f91`로 최종 빌드와 같다.
- **Room 기기10개는 기준 통과 기록이며 이번에는 재실행하지 않았다.** Lint도 재실행하지 않았고 기존 음성 MissingPermission 오류·60배 기기 성능 미해결을 보존했다.

## 실제 AVD — 각 상태15초 이상 유지

기존 Pleos Connect2.1.0/API34/user10 AVD를 데이터 삭제 없이 cold boot하고 교체 설치했다. 시험 전 SDK 화면/감시 서비스 소유 충돌이 없음을 확인하고, SDK 단독 화면을 사용해 Room 저장과 분리했다. Console의 공개 catalog에 있는 항목만 사용했다. P/속도0에서 진행했으며 OS 주행 제한을 우회하지 않았다. Console 요청 성공만으로 수신 성공을 세지 않고 실제 SDK raw·최종 프레임 품질을 대조했다.

각 조건은1회, 약1초 간격 진단21개씩이다. 표의 유지 시간은 마지막 값 조합이 동일한 연속 캡처 구간이다. Full autonomous 조건은 첫 표본에 직전 값이 남아 다음 표본부터 계산했다. **완전한10Hz 전 프레임 trace가 아니므로 짧은 STALE이 전혀 없었다고 입증하지 않는다.** 아래 조건의 표본에서는 STALE/ERROR0이며 Not ready의 크루즈 null/UNAVAILABLE은 정상 미확정 처리다. [필드별 첫/마지막 시각·품질·누계·표본 요약](evidence/2026-11-08-state-signals/avd-summary.json).

| Console 조합 | 동일 최종 상태 유지 구간 | 실제 TURN / BRAKE | 실제 크루즈 raw→최종 | 목표 raw m/s→km/h |
| --- | --- | --- | --- | --- |
| Off / Brake0 / Ready /20m/s | 20,877ms | OFF / false | ENABLED→false | 20→72 |
| Blink left / Brake50 / Longitudinal /25m/s | 21,511ms | LEFT / true | ENABLED→false | 25→90 |
| Blink right / Brake0 / Lateral /13.8889m/s | 21,257ms | RIGHT / false | ENABLED→false | 13.8889→50.00004 |
| Off / Brake50 / Full autonomous /5.556m/s | **19,384ms** (전체 캡처21,401ms 중 첫 전환 표본 제외) | OFF / true | **ACTIVATED→true** | 5.556→20.0016 |
| Off / Brake0 / Not ready /20m/s | 20,724ms | OFF / false | **null→UNAVAILABLE/null** | 20→72 |

정상 값은 REAL/AVAILABLE·REAL_FRESH와 원천 시각null/UNKNOWN을 유지했다. 무변경 구간에서 조회 누계는21~22회 증가하고 실제 콜백/관측은 증가하지 않았다. Full autonomous 조건의 전환 표본은 별도로 분리했다. `ENABLED`는 실제 SDK enum이므로 Ready·Longitudinal·Lateral 표시만으로 true를 만들지 않았다. 반대로 Full autonomous의 true는 실제 ACTIVATED 수신으로 확인했다. Not ready에서 받지 못한 SDK enum을 OTHER라고 만들어 기록하지 않았다.

## 신호별 결과·사용 조건

| 신호 | 변경 전 문제 | 적용 정책·근거 | 구현·자동·실제 결과 | 미지원/보류·미검증 및 소비 조건 |
| --- | --- | --- | --- | --- |
| TURN_SIGNAL | 최초 OFF 콜백 뒤 무변경이면3초 후 STALE | 공식 현재값 조회로1초 재확인/3초 lease | 구현·OFF/LEFT/RIGHT20초 자동시험·실제 세 상태15초 이상 확인 | SDK에 없는 HAZARD는 생성하지 않음. 실제 권한/연결 장애는 미주입. OFF는 유효, null은 게이트 불통과. 재확인은 방향지시등 조작 횟수가 아님 |
| BRAKE_ON | false 유지가 미수신처럼 STALE | 공식 Boolean 현재값 조회·같은 확인 정책 | 구현·false/true20초 자동시험·실제0/50% 입력의 false/true 유지·전환 확인 | 실제 권한/강제 조회 오류는 미검증. false와 null을 구분하고 재조회로 새 제동 조작을 만들지 않음 |
| CRUISE_ACTIVE | ENABLED/false 무변경이면 STALE | 공식 enum 현재값 조회·같은 확인 정책·기존 이진 투영 유지 | 구현·전체 enum 자동시험·실제 ENABLED/ACTIVATED/null 및 false/true/UNAVAILABLE 유지 확인 | SUSPENDED/OTHER/USER_OVERRIDE/FORCED_DEACTIVATION_WARNING의 실제 수신은 미검증. 모호한 enum은 null. 목표 속도나 Console 이름으로 활성 추정 금지 |
| CRUISE_TARGET_KPH | 고정 목표가3초 뒤 STALE | 공식 m/s 조회·같은 확인 정책·한 번만3.6 변환 | 구현·0/72 유지 및 단위/무효 입력 자동시험·실제4종 목표 유지/전환 확인 | Console slider 최소5.556m/s라0의 실제 수신은 미검증.0은 자동시험에서 유효. 목표 존재가 크루즈 활성 증명은 아님 |

모든 소비자는 `SdkReading.value`의 과거 진단값 대신 Hub 최종 값과 해당 필드 품질을 사용한다. AVAILABLE·REAL·확인 lease가 유효한지 보고, null/STALE/ERROR/PERMISSION_DENIED/UNSUPPORTED는 “현재 모름”으로 처리한다. 재조회 성공은 새 센서 관측·조작 공백 초기화 근거가 아니다. SDK 제공 없는 원천 시간/Hz를 만들지 않는다.

**미검증:** 실제 권한 철회(normal 권한을 강제 우회해 철회하지 않음), VehicleService/CarService 강제 해제·조회 오류/지연 주입, 실제 STR/장기 실행·물리 차량, 이번 변경본의 서비스 재생성/부팅 E2E, 위에 표시한 enum/0 실제 수신. 이 항목들의 자동 회귀와 실제 성공을 혼합하지 않는다. 원본 기어 STALE 직접 원인 미확정·음성 Lint·60배 성능·다른 담당 연결 대기는 그대로다.

## 데이터 보존과 종료

시험 전 DB/WAL 보존본은 `/tmp/awakedrive-state-signals-20261108/before/`에 두고 SDK 화면만 사용했다. [전후 대조](evidence/2026-11-08-state-signals/preservation-final.json)에서 **수동/REAL 두 DB의 모든 논리 행 동일·integrity ok**, 기존 사용자 진단 원본 해시 동일, 설치 APK/빌드 SHA 일치를 확인했다. 실제 DB·APK·SDK 소스/바이너리·원본 진단 전체를 저장소에 넣지 않는다.

종료 시 SDK 화면의 **중지**를 직접 눌러 네 필드 최종null/캐시폐기·Hub 발행 중지를 확인했다([중지 UI 문구](evidence/2026-11-08-state-signals/stopped.json)). 감시 서비스는 실행하지 않았고 마지막에도 [서비스 없음](evidence/2026-11-08-state-signals/final-service.txt), 실행 의사requested=false·boot_pending=false다. Console은 P/속도0·Turn Off·Brake0·Ready·목표13.8889m/s로 두고 이번 시험 heartbeat만 닫았다. AVD/사용자 앱은 제거하지 않았다.

## 직접 누를 Console 순서와 기대값

1. Console **AVD Enabled**, Gear **P**, 속도0 확인 → 앱 **Playground SDK 실시간 검증 → 연결**. 감시 서비스가 SDK를 소유하면 먼저 그 화면에서 **감시 중지**한다. 권한/READ 여부와 실제 값 수신을 확인한다.
2. Convenience/Turn Signal → **Off → Blink left → Blink right → Off**, 각15초 이상 유지한다. Hub OFF/LEFT/RIGHT·REAL/AVAILABLE, 콜백이 멈춰도 조회/상태 확인 시각이 진행되는지 본다. 좌우 카메라 팝업이 버튼을 덮으면 팝업만 닫는다.
3. Motion/Brake → **Brake Pedal0 →50→0%**, 각15초 이상 유지한다. false→true→false이며 false도 AVAILABLE이다.
4. ADAS/Driving Mode → **Cruise Target Speed20→25m/s**, 각15초 이상 유지한다. raw20→72km/h, raw25→90km/h다. **Cruise Control State Ready→Full autonomous→Not ready→Ready**를 각각 유지한다. 이번 이미지에서는 SDK raw ENABLED/false→ACTIVATED/true→null/UNAVAILABLE→ENABLED/false를 받았다. 이미지가 다르면 UI 이름보다 실제 raw enum을 우선한다.
5. **진단 저장** 후 새 `cache/sdk-signal-probe.json`의 세션·`capturedAtElapsedMs`를 확인한다. 쿼리 수/콜백 수, 실배송·원천·상태 확인·유효 끝, 최종 품질을 비교한다. **중지**하면 최종null·Hub 중지이며 재개는 **재연결**이다. 감시 서비스에서 확인할 경우 종료할 때 **감시 중지**를 눌러 타이머0·UNKNOWN을 확인한다.

후속 [거부된 배송의 Hub 재전달 수정](2026-11-08-rejected-delivery.md)에서 저장소가 거부했는데도 직전 CALLBACK 상태를 emit하던 결함을 실제 배송 판단→Hub 자동시험으로 재현하고 차단했다. 기존473개 기록은 당시 결과이며, 후속 전체479개/신규6개 및 기기 미재시험은 해당 기록을 따른다.
