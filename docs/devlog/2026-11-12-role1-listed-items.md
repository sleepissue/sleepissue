# 2026-11-12 — 사용자 지정 항목, 간트 1번 범위 보완·검증

이번 요청은 신호 SDK/Hub, 제품 JSON 데모, 재생 표시, 시동 끔 대체 시연, README·release 준비다. 간트 `AwakeDrive_일정_v1.gan`의 task53(DemoPlayer)·task73(README/release)은 resource0(1번) 배정임을 확인했다. 판단·단계 전환·음성 구현·다른 제품 화면은 이번에 수정하지 않았다. 이전에 범위를 넓혔다가 원복한 18개 Kotlin 파일의 변경을 다시 적용하지 않았다.

**전체 완료 판정은 아니다.** SDK 의미/입력 미확인, 엄격 발행·입력 지연 기준, 팀 서명은 남는다. 아래 실제 기능 결과와 요구사항 전체 통과를 구분한다.

## 이번 코드 변경

| 파일 | 이유 |
|---|---|
| `pleos/SdkNaviRefreshLoop.kt` 신규 | 새 활성 경로에서 다음 1초 조회까지 기다리지 않고 재조회. 여러 알림은 합치고 기존 정기 예약을 미루지 않음. Clock 주입, 취소 시 종료 |
| `pleos/PlaygroundSignalAdapter.kt` | 기존 공개 NaviHelper 조회를 위 경로에 연결. 원래 1초 주기·권한·route gate·TTL·품질·원천 관측 구분 유지 |
| `pleos/SdkNaviDelivery.kt` | 새 활성/만료 후 재확인과 반복 상태 보고 구분. 오래된 목적지나 기본0을 새 경로 값으로 재사용하지 않음 |
| `ui/ProductDemoActivity.kt` | 1번 데모 화면의 StateFlow 초기값을 remember 경계에서 읽어 Compose 정적 검사 오류 제거 |
| `pleos/SdkNaviRefreshLoopTest.kt` 신규 / `SdkNaviDeliveryTest.kt` | 즉시 조회·연타 합치기·중지·정기 예약 보존·늦은 조회 따라잡기 금지·경로 값 보호 회귀 5개 |

위 경로의 기준은 `src/android/app/src/main/java/com/sleepissue/awakedrive`, 시험은 대응 `src/test/java`다. 기존 ProductDemoController/데모 시동 끔/배지/실제 음성 연결은 이미 있던 구현이며 다시 만들지 않았다. `EngineConfig`, TripRules, 개입·판단, 음성, RestModule, 다른 UI는 이번 변경 전 해시와 대조한다.

초기 연타 시험에서 2회 대신 3회 조회되는 결함을 확인해 수정했다. 중간 구현은 이벤트 뒤 정기 예약을 재설정했으나, 기존 예정 조회를 미룰 수 있어 최종에서 예약을 보존하도록 보완하고 회귀를 추가했다. 중간 계측을 최종 APK 결과로 섞지 않았다.

## 항목별 현재 결과

| 사용자 항목 | 이번 확인 / 남은 문제 |
|---|---|
| 제품 JSON 재생·표시 | 기존 제품 6종 버튼으로 원본 JSON→Hub→기존 파이프라인 재생. 배지·일시정지/재개·중지/초기화 확인 |
| 실제 음성 배속 | 실제 ON_DEVICE TTS 시작 시 1배. 최종 관찰 600ms 동안 시나리오도 600ms. 실제 확인 종료(FAILED) 뒤 10배 복귀. 마이크 거부 상태의 실패 복귀 시험이며 사람 응답 성공으로 표시하지 않음 |
| 데모 시동 끔 | 제품 버튼으로 PID9329 종료→새 PID5241. 저장본+22분, 인증1건, 연속0초·최근24시간1006초, 같은 운행ID·단계 CHECK 복원. 실제 OFF→ON/STR은 아님 |
| 크루즈 켜짐 | 최종 실제 Console Full autonomous→SDK ACTIVATED→Hub true(61프레임), Lateral→ENABLED→false 확인 |
| 차속 | 최종 22.222223m/s→80km/h 일치. 이번 공개 입력→Hub 관찰 86~128ms, 입력 시계 양자화 추가±10ms. 과거219ms 미달을 없었던 것으로 하지 않으며 모든 입력200ms 통과로 확대하지 않음 |
| 조향 값 의미 | Console+10/−10→SDK−10/+10 수신. 전륜각/운전대각 문서 충돌 미해소, 엔진 null 유지. 조향비 추정 없음 |
| 착석 | 실제 Passenger Occupied→SDK false, Empty→true. UNKNOWN을 Boolean에서 복원할 수 없어 정규화 null 유지. 단순 반전 없음 |
| 위치 이동·현재온도 변경·실제 회전 | 현재 위치 READ와 현재온도19.1°C는 수신. 공개 catalog에는 목표온도/좌석온도가 있으며 현재온도·이동/TBT 입력을 대신하지 않는다. 연속 이동·실제 교차로 회전·현재온도 변경은 미확인 유지 |
| README·release | 최신 소스 unsigned APK 준비, 원본6종 assets 일치·제품 데모 등록·debug 구성요소 제외 확인. 팀 keystore가 없어 제출 서명과 서명 APK 별도 기기 설치는 미완료 |

제품 화면의 재생 버튼은 ‘제품 홈 → 데모 → 시나리오 선택·재생’에 있다. P/속도0에서 사용한다. ‘쉼터 P단 준비(모의) → 시동 끔(저장 후 앱 종료)’ 뒤 **앱 PID가 없어진 것을 확인한 후** 앱 아이콘으로 다시 연다. 마이크 권한을 거절해도 복원 결과를 확인할 수 있다. 이 시험에서 화면 요소가 동적으로 이동해 첫 좌표 터치가 다른 버튼을 눌렀던 시도는 종료 성공으로 세지 않았고, 마지막에는 실제 접근성 노드의 버튼 bounds를 읽어 실행했다.

## 성능 — 미달 유지

최종 APK의 실제 SDK→Hub 100.095초 수신은 1002프레임, 평균10.0005Hz, 순번 누락/중복/역행0이었다. **frame.tMs 최대131ms, 110ms초과48회로 SIG_001 미달**이다. 원천 관측·재조회 계수·TTL·Clock·frame 시각을 바꿔 통과시키지 않았다.

내비는 기존 공식 시험 경로의 정상 ‘안내 시작’ 터치 bracket을 그대로 사용했다. 단위 ms, 시계 양자화 오차±2ms 별도다. 각 시도에서 60초/50m 값 및 취소 후 null을 확인했다.

| 최종 시도 | 터치→SDK | SDK→Hub | 터치→Hub |
|---|---:|---:|---:|
| 1 | 1790~1863 | 216~218 | 2006~2081 |
| 2 | 1068~1128 | 62~66 | 1130~1194 |
| 3 | 1182~1260 | 19~21 | 1201~1281 |

**3회 모두 터치 기준1초 초과다.** 수정 전3회도 초과했다. 입력 위상·기기 부하를 엄밀히 통제한 개선율/회귀율 비교는 아니며, 즉시 조회의 추가가 외부 지연을 해결했다고 판정하지 않는다. 단위시험은 앱 내 불필요한 추가 대기를 제거한 조건을 검증한다. SDK 수신을 새 측정 시작점으로 바꾸지 않았으며 ‘목적지 있음’과 UI 터치의 공식 동일성도 여전히 미확정이다. 공급자 구간·앱 처리·AVD 영향을 분리해서 보아야 하며 컴퓨터 사양만을 원인으로 단정하지 않는다.

## 시험·산출물

- 최종 전체 자동시험: engine351+app427=**778개**, 실패/오류/skip0.
- debug APK·AndroidTest APK·release APK·release Lint vital 성공. 전체 Lint는 변경하지 않은 `voice/PleosSpeechRecognizer.kt:136`의 MissingPermission 오류1개로 실패. 데모 화면의 기존 오류는 사라짐. 음성 코드는 수정하지 않음.
- 최종 설치 APK에서 제품 기기3개(6종 UI, 저장/복원 조립, 실제 TTS 배속)가 81.744초에 통과했다. 내비 최종3회 runner1개·SDK100초 runner1개도 통과(계측 실행 통과이며 시간 기준 통과가 아님). 제품 실제 프로세스 종료·복원은 별도 수동 경로로 확인했다. 수동 경로 당시 SDK 중간 버전과 최종 버전의 제품 데모 화면/Controller 코드는 같지만 APK 전체가 같다는 주장은 하지 않는다.
- 최종 unsigned release: `apk/sleepissue_AwakeDrive_release-unsigned.apk`, SHA-256 `7a9fd6556711a90fd80b4eb29982f1e9063dbdafd66fcdcbfda676243ffc2437`. Git 제외. 팀 서명을 대신한 임의 키 생성은 하지 않았다.
- 원본/집계: [이번 근거](evidence/2026-11-12-role1-listed-items/). 제품 기기 최종 실행·보존·설치 APK 일치 대조는 해당 디렉터리의 최종 JSON을 기준으로 한다.

## 근거·한계·다음 작업

공식 [NaviHelper3.0.5 소스](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/NaviHelper/3.0.5/NaviHelper-3.0.5-sources.jar)의 조회 함수/콜백을 대조했다. 공개 [Console 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)는 스티어링휠 각도·Cruise 상태·Seat Occupancy·Target/Seat Temperature를 설명한다. 고정 Vehicle2.3.2 의미/Boolean 손실 근거는 [기존 공식 API 대조](2026-11-06-signal-sdk-api-evidence.md)를 따른다. 새 공식 근거 없이 의미를 추정하지 않았다.

다음 필수 입력은 **팀 서명 키 경로와 서명 담당의 로컬 서명**이다. 비밀번호는 채팅·저장소에 넣지 않는다. 지연110/200ms/1초, 조향 물리량·착석 UNKNOWN, 실제 이동/현재온도/회전 입력은 미해결 목록을 유지한다. 실제 시동/STR 대체 데모의 성공을 하드웨어 전원 시험으로 확대하지 않는다. 다른 담당 코드·첫 확인/컨디션·개입 정책·60배 알고리즘을 이 작업에서 다시 변경하지 않는다.

브랜치 develop-jdw / HEAD9144cfd 유지. 기존 미커밋 작업 보존. 커밋·push·PR·reset/clean·앱 삭제·DB 초기화·AVD Wipe 없음. 개인 원본 백업은 `/home/a/.local/state/awakedrive-handoff/2026-11-12/role1-listed-items/`이며 앱 원본/DB tar는 공유·커밋하지 않는다.

## 종료·보존 대조

이번 시작 시 기존 기기 파일213개가 최종 모두 바이트 동일하다. 시험이 덮어쓴 데모 재실행 DB·설정과 캐시7개는 새 증거를 별도 보관한 뒤 **해당7개만** 원본으로 복구했다. 사용자 REAL DB나 다른 변경을 되돌린 것이 아니다. 기기에 직접 SQLite로 연결하지 않고 백업 사본10개 DB의 integrity_check가 모두ok였다. 새 시험 소유 파일25개는 별도로 남겼다. 기존 데모 저장본도 복구했으므로 다음 실행 화면의 과거 결과와 이번1006초 시연 증거를 혼동하지 않는다.

최종 설치 debug SHA는 `a5348f373aabf2533babfc12fdc3fe538d8de7871c6bcc1c16282257a9c4a03d`이며 빌드 APK와 일치한다. AVD는 켜진 채 P/0, 앱 PID 없음·감시 서비스 없음·SDK 수집 종료 상태다. 추가한8088 forward·Console 시험 heartbeat와 UI dump 임시파일을 정리했다. Console 값은 시작 당시0/정차/Ready/빈 동승석으로 복원했고, 수신 시각/출처 같은 메타데이터는 입력으로 갱신됐다. 마이크는 원래 거부 상태와 user-set/user-fixed 없는 플래그로 복구했으며 차속·알림 허용을 유지했다. Gradle 빌드 데몬은 정상 종료했다.

소스 대조는 기존 Kotlin4개 변경+신규2개뿐이며 그 외 기존 Kotlin은 모두 시작 해시와 같다. 정확한 경로는 [scope.json](evidence/2026-11-12-role1-listed-items/scope.json), 기기 보존은 [preservation.json](evidence/2026-11-12-role1-listed-items/preservation.json), 최종 APK는 [apk-final.json](evidence/2026-11-12-role1-listed-items/apk-final.json)을 따른다.
