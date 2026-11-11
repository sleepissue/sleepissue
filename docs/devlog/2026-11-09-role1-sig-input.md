# 2026-11-09 — 1번 SIG 실제 내비 입력 후속 검증

시작·종료 브랜치는 **develop-jdw**, HEAD는 **f5c6ad9e179383ddb2b5cff3aad9ac4230afabe2**다. AGENTS.md, [직전 현황표](../setup/ROLE1_COMPLETION_STATUS.md), [11/8 완료 기록](2026-11-08-role1-finish.md), 현재기능 기획서 4-2·9장을 먼저 읽었다. 기존 미커밋 84개 파일의 원본·SHA256·diff를 `/tmp/awakedrive-role1-input-_zsddeus/`에 보관하고 이어 작업했다. 처음부터 있던 11/9 작성분인 모드 기반 `SdkNaviDelivery` gate·전체 모드 회귀·`Role1NaviInputProbeTest`도 보존하고 검증했다. 이번에 새로 추가한 코드는 위치 이력 단절의 **Hub 결합 자동 회귀 1개**와 해당 시험 클래스의 opt-in이다. 수집 제품 코드의 추가 변경은 필요하지 않았다.

이 문서의 실제 기기 결과는 **Pleos Connect Emulator AVD**에서 받은 Playground REAL 입력이다. 물리 차량 시험이나 공식 TC 전체 통과를 뜻하지 않는다. 기존 **자동 490개(engine153+app337)·Room 경계 기기3개·통풍/기어 SDK 결합 기기1개**는 11/8의 이전 근거로 구분한다. Room·TripRules·감시 서비스·다른 담당 제품 기능·60배 DemoPlayer는 변경하거나 재개발하지 않았다.

## ① 이번에 실제 확인한 입력과 출력

| 구간 | 실제 입력·원시 응답 | 어댑터 → Hub 결과와 판정 |
| --- | --- | --- |
| 초기 경로 | `SAFE_GUIDING`, `isRouting=false`, 목적지 없음 | 기존 사용자 안내 경로가 없는 것을 확인한 뒤에만 시험 요청. 경로 기본 0을 유효값으로 쓰지 않음 |
| 요청 | 공식 샘플 `NaviTestData.routeInfo`로 `requestRoute` 반환, elapsed 88,475ms | 반환형 Unit은 요청 호출의 반환일 뿐 안내 시작 성공 응답이 아님 |
| 실제 안내 시작 | 기본 내비 UI에 `42dot` 경로 미리보기·‘안내 시작’ 카운트다운. 첫 `ROUTE_GUIDING`은 94,086ms, 요청 반환 약5.611초 뒤. `isRouting`은 계속 false | 명시적 안내 모드로 gate가 열림. UI 탭도 수행했으나 탭과 자동 카운트다운의 인과는 분리하며, 성공 근거는 원시 안내 모드·목적지·TBT와 Hub 대조임 |
| 남은 시간 | `DestinationInfo.remainTime=60` | `remainTimeSec=60L` (초) |
| 남은 거리 | `DestinationInfo.remainDistance=50` | `remainDistM=50f` (m) |
| 다음 회전 거리 | TBT `[TURN_ARRIVE, distance=50.0]` | `distToNextTurnM=50f` (m). 이번 TBT는 도착 항목이며 실제 좌·우회전 접근/교차로 통과 시험은 아님 |
| 시험 경로 취소 | 공식 `cancelRoute`, `onRouteCancelled` 1회, `SAFE_GUIDING` 복귀 | 남은 초·남은 m·다음 회전 m **모두 null**. 위경도는 별도 위치 입력으로 유지 |
| GPS 이동 입력 | 공개 `adb emu geo fix` 3회 OK. Android GPS 최신값 `37.413720/127.095680`, 내비 앱으로 GPS/passive 배송 확인 | NaviHelper 위치 86회는 모두 **37.4127212/127.094681**, Hub도 고정·heading null. GPS 입력 성공과 NaviHelper 이동 수신 성공은 다름 |
| 현재 실내온도 | 공식 `getHvacTemperatureCurrent(ROW_1_LEFT)` READ·조회/콜백, 원시 19.1°C | 약49.059초의 1초 간격50표본에서 **19.1°C**. 전후 조회1→50, 콜백1→1, 관측1→1, 수신93,973ms 유지, 확인94,987→144,545ms. 변경 입력·반영 시한 시험은 아님 |

[이번 runner](evidence/2026-11-09-role1-sig-input/navi-device-runner.txt)는 **58.441초, 1개 통과**다. 이 1개는 활성 내비 세 값·취소·온도 유지의 결합 시험이며 이동 heading/현재온도 변경에 별도 성공 assertion을 두지 않았다. [원시 이벤트와 Hub](evidence/2026-11-09-role1-sig-input/navi-device.json), [요약](evidence/2026-11-09-role1-sig-input/input-summary.json), [요청 UI](evidence/2026-11-09-role1-sig-input/navi-request-preview.png), [안내 UI](evidence/2026-11-09-role1-sig-input/navi-guidance.png)를 함께 본다. 이벤트350개 중 오류0, routeStarted1·cancelled1이며 onRoutingCompleted 수신은 없었다. 해당 콜백 부재로 실제 안내 상태를 부정하지 않는다.

## 이전 activeObserved=false 원인 정정

**11/8의 ‘입력 조건 미충족’ 서술은 원시 진단과 대조하면 부정확하다.** 보존된 [이전 원본](evidence/2026-11-08-role1-finish/role1-navi-input.json)은 최상위 `activeObserved=false`이지만 내부에는 `ROUTE_GUIDING + isRouting=false`가 **15회**, 목적지60초·50m와 TBT50m가 들어 있다. 오류 이벤트는0이다. 당시 Boolean 의존 판정으로 활성 안내를 놓친 문제이며, 이를 SDK 미지원이나 provider 미설정으로 해석하지 않는다. 원본 JSON·당시 runner·당시490개 기록은 고치지 않고 이 정정을 추가한다.

이어받은 미커밋 gate는 `ROUTE_GUIDING` 또는 `SIMULATION_GUIDING`만 활성으로 인정한다. `SAFE_GUIDING`·`NOT_WORKING`은 `isRouting=true`여도 열지 않는다. 요청 반환·탐색 중 상태로 안내 성공을 추정하지 않는다. 공식 공개 `NaviMode` 의미와 현재 provider의 서로 다른 두 보고를 구분한 보완이며, provider Boolean 자체의 내부 원인은 확정하지 못했다. 모든 모드×Boolean의 자동 회귀와 이번 실제 Hub 수신으로 해당 보완을 확인했다.

## 기본 내비·공식 근거·읽기 전용 비교

- 실제 APP_MAPS/launcher 해석과 요청 후 표시된 앱은 **`ai.umos.maps.android.navigation.app/.MainActivity`**, 버전 **3.3.2-rc5-hotfix.3**이다. NaviService는 `ai.pleos.playground.service.navi/.NaviService` **3.0.4**, user10에서 동작하고 내비 앱이 바인딩돼 있다. 임의 이름의 `default_navigation_app` 설정 키는 null이었으므로 그 키를 기본 앱 부재의 증거로 쓰지 않았다.
- `NaviHelper.initialize` 연결 성공, `NAVI_ROUTE`·debug 전용 `NAVI_ROUTE_SEARCH` 모두 GRANTED(0)를 확인했다. `NAVI_CUSTOM_MAP`은 선언하지 않았으며 `requestNavigationScreen`은 호출하지 않았다. 설치된 내비의 정상 launcher UI를 열었다. release 수집기의 제어 권한을 추가하지 않았다.
- [공식 Navi 샘플 README](https://github.com/Pleos-Playground/pleos-sample-navi-app) 및 [공식 payload 76ed592](https://github.com/Pleos-Playground/pleos-sample-navi-app/blob/76ed592487a1eab7ba102b9d4b321705426886fe/app/src/main/java/ai/pleos/playground/navidevapp/data/sample/NaviTestData.kt)를 확인했다. 샘플도 기본 내비 앱이 응답을 만드는 구조이며 샘플 자체가 응답을 합성하지 않는다고 설명한다.
- [공식 Maven 공개 소스](https://nexus-playground.pleos.ai/repository/maven-releases/)의 NaviHelper3.0.5·navi-data3.0.4·Vehicle2.3.2를 `/tmp`에서만 읽었다. 해시는 이전 근거와 같고 [요약 JSON](evidence/2026-11-09-role1-sig-input/input-summary.json)에 있다. `getCurrentLocationInfo` 응답은 NaviProvider 구현에 달린다는 주석, `NaviMode` enum, `requestRoute` Unit/권한, 현재온도 °C getter·구독과 설정온도 setter의 구분을 확인했다. SDK 바이너리·소스는 저장소에 추가하지 않았다.
- GitHub main **f4b01fcad0856041bd2798a928700402b06fb418**의 [4번 PleosNaviBridge](https://github.com/sleepissue/sleepissue/blob/f4b01fcad0856041bd2798a928700402b06fb418/src/android/app/src/main/java/com/sleepissue/awakedrive/pleos/PleosNaviBridge.kt)를 GitHub 연결 도구로 읽기 전용 확인했다(blob `90d6a63dbd46351afebba5e8d9db8e93b5f613d3`). 로컬 origin/main의 동일 파일과도 일치한다. 4번은 addWaypoint→경유지 목록 확인→필요 시 재탐색→Foreground 화면 요청이며, 초기 목적지 안내 활성화나 위치/현재온도 입력 생성 기능은 아니다. 브랜치 전환·병합·제품 통합·커밋·push는 하지 않았다.

## ② 결함 보완과 시험 결과

| 구분 | 내용 | 검증 |
| --- | --- | --- |
| 이어받은 수집 gate 보완 | `isRouting=false` 때문에 실제 안내를 누락하는 경로를 명시적 안내 모드로 판별. 기존 미커밋 파일을 그대로 유지 | 전체 모드×Boolean 회귀1개, 이번 실제 세 값과 취소의 기기1개 |
| 이번 신규 회귀 | `SdkNaviHubConfirmationTest.headingAtHubRestartsAfterLocationGapInvalidCoordinatesAndProviderError` | 이동쌍→동90°, TTL 공백→null, 새 첫 위치→null, 새 쌍→북0°, 잘못된 좌표→null, 오류→ERROR/null, 복구 첫 위치→null, 다음 쌍으로만 재계산. 임의 SDK heading9999를 방위각으로 채택하지 않음 |
| 관련 자동시험 | `:app:testDebugUnitTest --tests 'com.sleepissue.awakedrive.pleos.SdkNavi*Test'` | 6개 통과. 이때 추가한 Hub 회귀 포함 |
| 최종 전체 자동시험 | **engine153 + app339 = 492개**, 실패·오류·skip0 | 이전490 + 이어받은 gate 회귀1 + 이번 Hub heading 회귀1. 실제 기기 결과와 합산하지 않음 |
| 빌드 | `assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:processReleaseMainManifest` | **81개 task 실제 실행, BUILD SUCCESSFUL**. Room 경계3개·통풍/기어 결합1개는 이전 근거이며 재실행하지 않음 |

최종 명령은 다음과 같다. [전체 로그](evidence/2026-11-09-role1-sig-input/final-tests-build.txt), [집계·APK 해시](evidence/2026-11-09-role1-sig-input/test-results.json), [회귀 XML](evidence/2026-11-09-role1-sig-input/TEST-com.sleepissue.awakedrive.pleos.SdkNaviHubConfirmationTest.xml)에 실행 결과가 있다. Lint·Room/서비스 기기 재시험·물리 차량·실제 전원·60배 성능 시험은 수행하지 않았다.

```bash
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./src/android/gradlew -p src/android \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:-TieredCompilation' \
  --max-workers=2 --rerun-tasks :engine:test :app:testDebugUnitTest \
  assembleDebug :app:assembleDebugAndroidTest :app:processReleaseMainManifest
```

## ③ 여전히 확인하지 못한 조건

| 조건 | 정확히 확인한 경계와 이유 | 분류 |
| --- | --- | --- |
| 실제 이동 heading | geo fix는 Android GPS에 들어가 내비 앱까지 배송됨. NaviHelper 응답은 고정. [Android 배송 발췌](evidence/2026-11-09-role1-sig-input/android-location-delivery.txt). provider 내부에서 왜 위치가 고정되는지는 공개 응답만으로 확정할 수 없음 | **이동 입력 조건 미확인**, SDK 미지원 아님 |
| provider에 반영되는 공개 위치 입력 | 조사한 NaviHelper3.0.5 공개 표면은 위치 조회이고 위치 setter가 없음. Console catalog와 내비 설정 UI에도 위치 주입·모의 이동 항목을 찾지 못함. 숨은 API·특권 주입·다른 provider 설치로 우회하지 않음 | 공개 입력 방법 미확인 |
| 실제 위치 오류/공백 후 재개 | 실제 움직이는 provider 입력과 공개 오류 주입 경로 미확보. 이력 단절은 저장소+Hub 자동 회귀로만 확인 | 실제 기기 장애 TC 미검증 |
| 현재 실내온도 변경 | READ19.1°C와 유지 확인. 공개 Vehicle은 현재온도 getter/구독, setter는 별도 설정온도. [현재온도 공식 API](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-temperature-current). Console catalog에는 HVAC_TEMPERATURE_SET과 HVAC_SEAT_TEMPERATURE만 있으며 현재온도 입력이 없음 | **READ 확인 / 변경 입력 미확인**. SDK 미지원 아님 |
| 요청 좌표와 실제 목적지 | 요청37.48544722/127.03636666, 응답37.41289678/127.09383464. 동일 공식 poiId/이름이나 좌표가 다름. POI 해석 우선순위나 샘플 provider 응답 정책은 미확정 | 임의 장거리·실제 교차로 TBT 검증 대기 |
| 취소 이외의 경로 종료 | 공식 cancel과 SAFE_GUIDING/null 확인. 자연 도착·실제 교차로 통과·움직이면서 값 감소·모든1초 시한은 미시험 | 취소 확인 / 자연 종료·시한 미검증 |
| 1배 Hub 엄격110ms 발행 | 이번 내비 검증은 성능 대조가 아님. 이전 실제 emit 최대129ms·초과13회 등 미달 근거 유지 | **별도 성능 과제, 미해결** |

[Console 입력 표면 발췌](evidence/2026-11-09-role1-sig-input/console-input-surface.json)는 현재온도·위치 제어가 없는 조사 범위를 기록한 것이며, 공급자의 모든 비공개·향후 입력 가능성을 부정하는 자료가 아니다. 설정온도·좌석 온열을 현재 실내온도로, SDK heading0을 이동 방위각으로 치환하지 않았다. 조향 물리량·착석 UNKNOWN의 의미 보류도 유지한다.

## ④ 4번에게 전달할 구체적인 질문

| 질문 | 필요한 답·근거 |
| --- | --- |
| 같은 기본 내비 버전에서 `ROUTE_GUIDING/SIMULATION_GUIDING`인데 `isRouting=false`가 재현되는가? | 4번 측 RouteStateInfo 원시 응답과 provider 버전. 초기 안내 성립 조건과 소비자 gate 계약을 모드 기준으로 맞출 수 있는지 |
| 공식 sample poiId가 요청 좌표와 다른 성남 목적지50m로 해석되는 이유는 무엇인가? | POI·좌표 우선순위의 공식 근거, 실제 중간 회전 TBT를 만드는 시험 전용 목적지·버튼 순서 |
| geo fix는 내비 앱에 배송되는데 NaviHelper 위치가 고정되는 환경에 공개 이동/모의주행 입력 방법이 있는가? | UI 메뉴 경로 또는 공식 문서/API와 해당 provider에서 위치가 변한 원시 이벤트. GPS OK만으로 답하지 않기 |
| 현재 실내온도 `HVAC_TEMPERATURE_CURRENT`를 바꾸는 공식 입력은 있는가? | Console/에뮬레이터 메뉴·공개 API. 설정온도/온열과 구분한 현재온도 readback→Hub 근거 |
| 경유지 처리 전에 실제 안내가 없는 경우의 사용자 안내·실패 계약은 무엇인가? | 4번의 목록 확인 성공과 실제 안내 활성화 성공을 구분한 결과. 기존 사용자 목적지를 보존하는 시험 절차 |

질문은 인계 문서에 정리했으며 GitHub 댓글·메신저 등으로 전송하지 않았다.

## ⑤ 직접 따라 할 버튼 순서와 기대값

| 순서 | 버튼·조작 | 기대값 |
| --- | --- | --- |
| 1 | 기존 AVD를 켜고 P/0km/h 확인. 기존 사용자 내비 안내 중이면 이 시험을 실행하지 않음. 감시가 켜져 있으면 정상 ‘감시 중지’ | 사용자 경로를 덮지 않고 SDK 소유권이 풀림. 데이터 삭제·Wipe 불필요 |
| 2 | 기본 **내비게이션** 앱을 연 뒤 아래 명시적 fixture runner 실행 | 연결·권한 확인 후 시험용42dot 경로 미리보기. 기존 경로가 있으면 assumption으로 시험 요청을 생략하므로 통과로 읽지 않음 |
| 3 | 미리보기의 **안내 시작**을 누르거나 화면의 시작 카운트다운 완료를 관찰 | 원시 `naviMode=ROUTE_GUIDING`; 이 AVD에서는 `isRouting=false`도 함께 올 수 있음. 요청 반환만으로 성공 판정하지 않음 |
| 4 | fixture가 활성 Hub 값을 대조하고 약50초 기록하는 동안 기다림 | 이 공식 sample에서는 남은60초·50m·TBT50m, 현재온도19.1°C. 새 버전/다른 목적지라면 해당 원시값과 Hub의 일치를 비교 |
| 5 | fixture의 시험 경로 자동 취소까지 기다린 뒤 내비 화면 확인 | **안내 종료 상태**, Hub 세 경로 값 null, runner `OK (1 test)`와 `completed=true`. 수동 종료를 확인할 때는 자신의 시험 경로에서만 ‘안내 종료’ |
| 6 | **AwakeDrive 개발 검증 → Playground SDK 실시간 검증 → 차속 권한 → 연결**, 온도·경로 확인 후 **진단 저장 → 중지** | CABIN_TEMP_C는 현재값·AVAILABLE 유지, 재조회는 확인 시각만 갱신. 경로 없음은 null. 중지 뒤 수집 해제. 이 화면은 Room과 분리됨 |

```bash
# 이미 설치된 debug/test APK에서 실행. UTP·앱 삭제·사용자 DB를 사용하지 않는다.
adb shell am instrument --user 10 -w \
  -e role1SdkFixtures true \
  -e class com.sleepissue.awakedrive.data.Role1NaviInputProbeTest \
  com.sleepissue.awakedrive.test/androidx.test.runner.AndroidJUnitRunner

# 원시 결과 조회. 반복 시 같은 시험 cache 이름을 덮으므로 보존할 기존 진단은 먼저 복사한다.
adb exec-out run-as com.sleepissue.awakedrive --user 10 \
  cat cache/role1-sig-input-live.json
```

수동 관찰 화면만 사용할 때는 실제 시험 경로에서 원시 내비 상태를 확인하고 SDK 화면으로 돌아온다. Console **Temperature Set**을 바꾸어도 현재온도 변경 검증이 되지 않는다. `adb emu geo fix <경도> <위도>` OK만으로 heading 성공이라고 판단하지 않는다. 이번 자동 fixture는 취소까지 직접 확인하며 Room/운행 상태를 만들지 않는다.

## 보존 결과

기존 AVD가 꺼져 있어 `-no-snapshot-load -no-snapshot-save`로 데이터 삭제 없이 부팅했다. 수동/REAL DB·shared_prefs·cache81개 파일을 시험 전에 별도 보관했다. DB별 모든 논리 행 해시는 시험 전후 같고 integrity는 모두ok, 수동 운전구간1개·REAL18개를 보존했다. 시험으로 덮인 기존 `cache/role1-sig-input-live.json` 1개만 원래 바이트로 복구한 뒤 **기존81개 파일 모두 동일**함을 확인했다. 새 원시 결과는 이번 날짜의 저장소 evidence에 별도로 남겼다. DB를 백업본으로 덮거나 초기화하지 않았다. [보존 대조](evidence/2026-11-09-role1-sig-input/preservation-final.json).

원래 활성 경로 없음 확인→시험 경로 요청→시험 경로만 cancel했고 최종 SAFE_GUIDING이다. GPS는 시험 기준좌표37.4127212/127.094681로 복귀했다. 감시 requested=false·실행 서비스 없음, 화면은 SDK 미연결 상태다. 조사에 추가한8088 forwarding도 제거했다. 기존 미커밋 제품 소스·이전 진단 원본·다른 담당 파일은 보존했다. 브랜치 전환·병합·커밋·push·앱 제거·데이터 초기화·AVD Wipe는 하지 않았다.
