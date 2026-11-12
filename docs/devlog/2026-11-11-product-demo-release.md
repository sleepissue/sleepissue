# 제품 JSON 데모·DMO_005·제출 준비 — 2026-11-11

## 시작 상태와 범위

- `develop-jdw`에서 `git status`, `git fetch origin` 후 `git merge --ff-only origin/main`: `f59176f → 9144cfd` (PR #19 포함). 병합 커밋·일반 커밋·push·PR 없음.
- 기존 미추적 `navigation-confirmed.marker`, `perfetto-start.txt` 보존. `AGENTS.md`, `CLAUDE.md`, `PROGRESS.md`, 저장소 최신 간트, SRS v0.2의 DMO/SIG/TRP, 완료 현황과 관련 11/6~10 기록을 확인했다. 두 에이전트 안내 파일은 제목부터 다르며 이번 작업에서 수정하지 않았다.
- 최신 경로는 제품 `DrivingPreviewActivity → AppNavigation.onStartMonitoring → RealTripMonitorService → MonitoringPipeline → AwakeDrivePipeline`이다. 신호·판단·개입·음성·휴식의 조립은 PR #19에 이미 있다. 이번에는 그 파이프라인을 데모에도 재사용하며 제품 화면의 결과 구독을 보완했다. 이전 현황표의 ‘조립 없음’은 당시 사실이다.
- AVD가 꺼져 있어 기존 `Pleos_Connect_2_1_0`을 `-no-snapshot-load -no-snapshot-save`로 부팅했다. Wipe·앱 삭제·데이터 초기화·reset/clean 없음. 설치 전 user10의 DB/shared_prefs/cache를 `/tmp/awakedrive-20261111/device-before.tar`에 보관했다. 사용자 원본 DB·진단은 저장소에 추가하지 않는다.

## 원본과 재생 계약

[6종 전수 비교](evidence/2026-11-11-product-demo/scenario-comparison.json). 원본 `data/scenarios`는 `formatVersion/scenarioId/startAt/tMs`이고, 기존 `data/scenarios-demo`는 `schemaVersion/id/startEpochMs/t`다. 기존 사본은 졸음의 `tripPreset`을 누락했고, 졸음 외 5종의 회전 거리·실내온도도 누락했다. 사본과 원본은 보존한다.

제품 APK의 `assets/<id>.json`은 Gradle assets 경로로 **원본**을 직접 묶는다. 수동 복사본이나 PC 절대경로가 필요 없다. `DemoJsonLoader`의 엄격 파서 안에서 팀 형식을 정규 형식으로 변환하고 같은 시간/필드 검증을 통과시킨다. 모르는 필드·소수 ms·잘못된 preset은 거절한다. 신호·배속·회전 거리·실내온도·시작 날짜·preset을 보존한다. 음성 결과·점수·단계를 JSON에 추가하지 않는다.

`tripPreset`은 DEMO에만 사용한다. 카운터만 `TripStateStore`에 넣지 않고 과거 운전 구간과 관측을 갖춘 `TripRuleState`로 만든다. 연속 ≤ 최근24시간 ≤ 24시간을 검사한다. 시나리오의 날짜+상대시각을 Hub/판단의 epoch 시간으로 옮겨 취약 시간대도 유지한다. 실제 음성 응답 시간은 단조 실제 시계다.

제품 데모 화면은 `ScenarioDemoPlayer → VehicleSignalHub → TripTracker + MonitoringPipeline/AwakeDrivePipeline`을 사용한다. 점수·단계를 화면에 직접 주입하던 기존 테스트 버튼을 JSON 선택 진입점으로 교체했다. 배지는 Player의 PLAYING/PAUSED에만 표시한다. 중지·종료·실패·초기화·화면 이탈에서 정리한다. 음성 배속은 실제 Pleos TTS `EventListener.onStart`에서 동기적으로 1배속이 되고, 해당 확인의 완료/실패/취소에서 원래 구간으로 돌아간다. 이전 질문의 늦은 종료는 새 질문 잠금을 풀지 않는다.

기본 데모는 SDK 입력을 끈 격리 재생이다. **Virtual Console 입력 우선 켜기**를 선택하면 같은 Hub의 REAL 원천을 활성화해 유효한 SDK 입력을 우선한다. 이때 실제 차량 조향/착석 의미 보류는 그대로 적용되어 null이 될 수 있다. DEMO의 JSON 값과 실제 SDK 검증을 합치지 않는다. 데모는 실제 경유지·통풍 제어를 실행하지 않는다.

새 화면에도 `CarUxRestrictionsManager` 리스너를 적용했다. 서비스 확인 전/오류는 제한 상태이고, 제한 중 설정·리포트·데모 진입을 막는다. 실행 중 제한이 걸리면 데모를 중지한다. 화면을 떠나면 마이크를 포함한 데모 수명을 끝낸다.

## DMO_005 재실행

**쉼터 P단 준비(모의)**는 내장 쉼터 좌표와 P단 관측을 DEMO Hub로 넣는다. 실제 도착으로 표시하지 않는다. **시동 끔(저장 후 앱 종료)**은 정밀 운행·구간·운행 ID·단계·기준선 참조·위치·기어·개입 checkpoint를 `awakedrive_demo_restart.db`에 저장하고, 격리 휴식 상태를 디스크까지 반영한 뒤 앱 프로세스를 종료한다. REAL DB는 열어 쓰지 않는다.

앱 아이콘으로 다시 실행하면 pending 표식으로 제품 데모 화면을 열고 저장 상태를 읽는다. 데모 시계만 +22분 이동해 기존 `TripRules`와 `RestSessionMonitor/RestCertifier/RewardLedger`가 연속시간 초기화와 인증을 계산한다. 휴식 DB·설정 이름은 `demo_<세션UUID>_...`로 격리한다. 같은 휴식 sessionId는 중복 기록하지 않는다. 실제 OFF→ON·STR·물리 차량 검증이 아니다.

현재 엔진 내부 학습 창을 직렬화하는 공개 API는 없다. 이번 복원은 정밀 운행/개입 checkpoint/휴식 상태와 기준선 참조까지 보존하며, 내부 학습 창을 복원했다고 주장하지 않는다. 복원 화면은 인증 결과를 보여 주는 정지 상태다. 새 시나리오를 누르면 새 데모 세션으로 시작한다.

## 신호 의미·공개 입력

이번에 [공식 Maven Vehicle 2.3.2 소스](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/Vehicle/2.3.2/Vehicle-2.3.2-sources.jar)를 다시 내려 읽었다. SHA-256 `62b5dd2c4c0523fd13c2e64040cd775bc89a14ce54f35a37560d0eb4eb3fab40`는 이전 근거와 같다. 소스/바이너리는 `/tmp`에만 두었다.

- `SteeringWheel.kt` 95~109행은 전륜각 °·왼쪽 음수로 설명한다. [API 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/get-current-steering-wheel-angle)의 이름이나 Console의 운전대각 설명만으로 운전대 회전각과 같다고 할 수 없다. 조향비 추정 없이 canonical null 보류를 유지했다.
- `SeatImpl.kt` 435행과 `PropertyCallback.kt` 352행은 숫자를 `== 1`로 Boolean에 축약한다. [착석 API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/get-current-seat-occupancy)의 Boolean만으로 UNKNOWN과 비착석/착석을 복원할 수 없다. 반전하지 않고 null 보류를 유지했다.
- 공개 Console `/vehicle/catalog`의 크루즈 상태는 `/vehicle/driving-mode`의 `ad_mode_state`이며 not_ready/ready/longitudinal/lateral/full_autonomous만 제공한다. 이 이름을 SDK ACTIVATED 수신으로 대체하지 않는다.
- 이번 공개 catalog에는 현재 실내온도 변경과 NaviHelper 연속 위치·heading 입력을 찾지 못했다. 설정온도와 현재온도는 다르다. 기존 geo fix의 Android GPS 배송 성공과 NaviHelper 위치 고정을 구분한다. 실제 이동/TBT 감소·자연 도착은 여전히 별도 확인 대상이다. JSON의 위치/온도/회전 거리로 대체 시연할 수 있지만 SDK 검증은 아니다.

## 지연 근거와 제출 한계

기존 조향 최대 약130ms/110ms 기준, 차속 전체 지연 하한219ms/200ms 기준, 내비 터치→Hub 1초 초과 기록은 그대로 보존한다. 이번에는 앱의 불필요한 조회 대기를 줄일 새로운 원인 근거를 확보하지 못해 공급자 조회·TTL·timestamp·측정 기점을 변경하지 않았다. PC/AVD와 공급자 내부 원인도 미확정이다. 구현을 미루는 성능 반복 측정은 하지 않았다.

실행 결과·버튼 순서·최종 APK 해시는 아래 최종 검증 절에 기록한다. 이전 748개 등 과거 시험 수와 이번 실행 수는 구분한다.

## 초기 검증에서 발견한 문제

- 첫 전체 실행은 0.0과 -0.0을 객체 동일성으로 비교한 신규 시험1건이 실패했다. 신호 숫자 비교를 오차0의 수치 비교로 고쳤다. 원본 신호를 변경한 것은 아니다.
- 같은 초기 빌드의 Lint는 Kotlin UAST/`AsyncExecutionService` 내부 예외로 종료했다. 소스 변경을 멈춘 상태에서 다시 실행한 release 빌드/Lint vital은 통과했다. Lint를 끄거나 예외를 무시하지 않았다.
- 제품 버튼 기기시험1차는 Compose 접근성의 텍스트 검색 API에서 실패해 재귀 노드 탐색으로 보완했다. 2차와 직접 조립20초 시험은 원본 JSON 최초 검증 대기 중 시간 제한에 걸렸다. 이 실패를 성공 실행으로 바꾸지 않는다. 로딩 상태를 표시하고 충분한 초기 검증 대기를 둔 뒤 실제 재생 여부를 다시 확인한다. 이것은 SIG 지연 문턱이나 측정 시작점을 바꾸는 작업이 아니다.
- 빌드 후 유휴 Gradle 데몬은 종료해 기기시험과 빌드의 메모리 경쟁을 줄였다. 동일한 호스트 저부하 성능 통과로 해석하지 않는다.

## 담당별 구체적 요청 (외부 메시지 전송 없음)

| 담당 | 필요한 계약·자료 |
| --- | --- |
| 2번 | FatigueBridge/기준선 저장소의 버전 있는 export/import 계약. 현재 기준선 참조뿐 아니라 평소3회와 오늘15분 창까지 복원하는 입력·검사 API. DMO_006의 평소 기준선3회 fixture와 정확한15분 판정 기대값 |
| 3번 | ON_DEVICE TTS/STT 실패 시 실제 녹음 cue 리소스 제공(현재 emptyMap), 실제 EPD/말소리 경로와 CabinMonitor 조립, 화면 없는 감시 서비스의 microphone FGS 수명. demo 음성 시작은 TTS onStart로 연결했으므로 같은 lifecycle 계약을 유지할 것 |
| 4번 | 해당 내비 provider 버전의 공개 위치 이동/모의주행 절차와 실제 회전 TBT를 만드는 목적지·버튼 순서. 현재온도 변경 입력은 설정온도와 구분. 자동 경유지의 실제 성공 readback은 별도 REAL 시험에서 확인 |
| 팀장·SDK 문의 담당 | 조향 전륜각/운전대각·부호와 Console 충돌, Seat Boolean의 UNKNOWN 손실을 공식 확인. 공개 SDK에서 의미를 보존하는 원시 상태 API 또는 수정 버전 요청 |
| 서명 담당 | 팀 소유 keystore·alias를 안전한 로컬 경로에 준비하고 unsigned APK에 서명. 인증서가 다른 기존 데이터 보존 기기는 삭제하지 말고 별도 기기에서 설치·기동 확인 |
| 5번·영상 담당 | 6종은 실제 파일의10배/1배를 사용. 대화는 실제 말소리 입력이 있어야 하며 착석만으로 대화 검증이라고 부르지 않음. 시동 끔은 모의/P단→프로세스 종료→새 PID 복원임을 영상에 표시 |

기기 후속: 직접 제품 조립은 **22.428초, 1건 통과**했다. 제품 화면에서도 정상/출발 피로의 실제 위험도, 졸음 preset 7,800초 시작과 온도/회전 거리 전달을 관찰했다. UI 자동 시험의 접근성 캐시가 화면보다 오래된 텍스트를 돌려주어 제품 창 선택뿐 아니라 매번 `UiAutomation.clearCache()`로 새 노드를 읽게 보완했다. 중간 수동 탭1회는 축소된 스크린샷 좌표를 사용해 계기판 포커스에 영향을 줬으므로 해당 자동 실행은 최종 통과 근거에서 제외한다. 이후 수동 조작은 실제 PNG2560×1440 좌표로 환산했다.

첫 DMO 저장은 `InterventionCheckpoint`의 **평가 시각 = 저장 시각** 계약에 맞지 않아 Room 거부로 끝났다. 앱 종료 표식을 쓰지 않고 프로세스를 유지한 실패 실행이다. 운행 저장 순간에 데모 시계를 명시적으로 동결한 뒤 **기존 Tick을 실제 평가**하고, 그 checkpoint와 운행을 같은 시각으로 캡처하도록 고쳤다. 평가 timestamp를 덮어쓰거나 SIG 지연 측정에 쓰는 시계를 바꾼 것이 아니다. 이 경계를 제품 연결 기기 회귀에 넣었다.

10배 재생에서는 UI가 프레임/판단 스냅샷마다 다시 그려지는 불필요한 작업도 확인했다. 화면 표시만200ms로 제한하고 Player 원본 전달·TripTracker를 Default 실행 통로로 옮겼다. 신호/판단의 주기·TTL·수신 시각·누락 처리·원본 샘플은 바꾸지 않는다. 이 변경으로 기존110/200/1000ms 시한을 통과했다고 주장하지 않는다.

음성 종료 정리에서는 `VoiceCheckPort.close()`가 먼저 활성 요청·예약 콜백을 취소하게 했다. TTS 종료의 늦은 완료 콜백이 종료 뒤 마이크 확인을 새로 시작하지 않도록 한다. 실제 TTS `onStart`→1배속→실제 확인 종료→원래10배의 별도 기기 회귀도 준비했다. 이 회귀는 기존 CheckPort 실행을 직접 요청할 뿐, 위험 점수·단계·응답 결과를 주입하지 않는다.

## 최종 제품 기기 회귀

[직접 실행한3건 결과](evidence/2026-11-11-product-demo/device-three-success.txt): **80.937초, OK(3 tests)**. 이전 접근성/시간 제한/저장 실패 실행과 구분한다.

1. `ProductDemoWiringTest`: 제품 조립→원본 로드→Hub 현재온도23.5℃ 발행→쉼터 P단→일관된 Room 저장→새 controller 인스턴스 복원. 연속0·최근24시간/ID 유지, pending 재적용 없음, 결과 화면 재진입 상태 동일. **같은 PID 안의 저장/새 인스턴스 회귀**이며 실제 프로세스 재실행은 아래 별도 수동 기록으로 확인한다.
2. `ProductDemoDeviceTest`: [6개 실제 제품 버튼의 화면 값](evidence/2026-11-11-product-demo/product-six-success.txt). 각 버튼의 실제 재생·Hub 출처 DEMO/온도·졸음 preset 확인, 일시정지/재개/중지/초기화와 배지 제거 통과. 접근성 캐시를 비우고 제품 창만 읽었다. 점수·단계 주입 없음.
3. `ProductDemoVoiceDeviceTest`: [실제 음성 배속](evidence/2026-11-11-product-demo/actual-voice-speed.json). 실제 ON_DEVICE TTS `onStart` 이후 **실제600ms/시나리오600ms**, multiplier1. 실제 CheckPort 판정 종료에서 FINISHED·원래10배 복귀를 확인했다. 사람이 말한 응답 정확도나 자연 졸음 시나리오 전체의 확인 성공을 의미하지 않는다. 실패/취소·이전 질문 종료 경계는 JVM 회귀로 별도 확인했다.

## 실제 제품 버튼·프로세스 종료·재실행

[PID·DB 읽기 요약](evidence/2026-11-11-product-demo/actual-process-restart.json), [P단 준비](evidence/2026-11-11-product-demo/final-dmo-parked.png), [복원 화면](evidence/2026-11-11-product-demo/final-dmo-restored.png).

제품 홈→데모→시나리오 선택·재생→졸음→쉼터 P단 준비(모의)→시동 끔(저장 후 앱 종료)을 직접 눌렀다. PID10084가 없어졌고, 앱 실행 후 새 PID30688에서 pending 저장본을 읽었다. DB의 원본 checkpoint는 Source.DEMO/MANUAL_SCENARIO/P/OFF이고 저장 운행ID가 화면 복원ID와 같다. 시계는 저장 시각에서 **1,320,000ms(+22분)** 이동했다. 별도 휴식 DB의 기록은 **1행·AWARDED**, 화면은 **연속0초·최근24시간8271초·저장 단계 CAUTION**이다. pending은 false로 정리됐다. 실제 기어·시동 전원이나 STR을 바꾸지 않았다.

원본 checkpoint의 연속8286초에는 P단 초기 잠정 정차가 포함되며 최근24시간8271초와 다른 이유는 기존 정차 계산 정책이다. 복원 후 연속0과 최근24시간8271을 확인했다. DB 원본 파일은 `/tmp`에만 두고, 저장소에는 위 요약·화면만 남겼다.

## 공개 SDK 입력 후속과 작업 중단 인계

공개 Console UI는 `/browser/session` heartbeat 동안 가상 입력을 활성화한다. 브라우저가 닫힌 상태에서는 공개 PUT도503 `emulator_inactive`로 거절됐다. UI와 동일한 공개 세션을 열어 Driving Mode의 **Full autonomous** 입력을 보낸 뒤 실제 SDK **ACTIVATED/true → Hub cruiseActive=true**를 확인했다. **Lateral**에서는 SDK ENABLED/false → Hub false를 확인했다. [원시값·어댑터·Hub 요약](evidence/2026-11-11-product-demo/public-sdk-input-results.json). 이는 Console 라벨을 자의적으로 Boolean에 대응시킨 결과가 아니라 실제 SDK 수신이며, 물리 차량이나200ms 시한 시험은 아니다. Longitudinal 직후 첫 캡처는 아직 이전 ENABLED여서 그 표본으로 활성 실패를 결론 내리지 않는다.

첫 Ready 복귀 요청은 시험용 브라우저 세션이 만료돼503이었다. 새 공개 세션에서 Ready로 복귀해 Console GET을 확인하고 두 시험 세션을 닫았다. 마지막 Ready 복귀 후 SDK를 다시 수신 확인한 것은 아니며, 최종 제어값은 ready/설정13.8889m/s이다. 현재온도19.1℃와 고정 NaviHelper 위치 READ도 수신했지만 변경·이동 입력은 확보하지 못했다. [제어값 복귀](evidence/2026-11-11-product-demo/console-restoration.json).

사용자의 계정 전환 요청으로 **새 빌드·새 기능 작업은 여기서 중단**한다. 최종 debug/test APK와 실제3건 및 새PID DMO는 확인했지만, **마지막 음성 close 보완 이후 전체 JVM/release 재빌드**, 이후 추가한 JSON schema 설명·24시간 만료 경계 시험의 실행, 제출 APK 복사/최종 README 검토는 남았다. 기존 release 성공 산출물을 최신 소스의 최종 제출 APK로 표시하지 않는다.

기기 정리 후 기존160개 파일 누락0·DB 외 바이트 변경0, 기존 DB3개 논리 행/무결성 모두동일이다([보존 대조](evidence/2026-11-11-product-demo/preservation.json)). 앱/감시/SDK/시험은 종료, 마이크 권한은 원래 false, 알림·차속 권한은 기존 true, 8088 forward는 제거했다. AVD는 데이터 그대로 실행 중이다. 임시 원본은 `/tmp/awakedrive-20261111`, 내구성 사본은 `/home/a/.local/state/awakedrive-handoff/2026-11-11`에 있다. 이 개인 원본·DB·APK·SDK 바이너리는 커밋하지 않는다. 재개는 `docs/setup/CURRENT_HANDOFF.md`를 따른다.
