# 2026-11-10 — SIG_016 호출·예약·콜백 경계 분리

**‘목적지 있음’과 ‘안내 시작 터치’가 같은 시작점이라는 증거는 확보하지 못했다.** 기존 터치 기준 1초 초과 기록은 유지한다. 추가 3회에서도 터치→Hub는 1,140~2,059ms였고, 이미 ROUTE_GUIDING을 관찰한 뒤부터 Hub까지도 1,064~1,843ms였다. SDK 수신 뒤만을 시작점으로 바꿔 Pass 처리하지 않았다.

입력 완료 뒤 첫 `getDestinationInfo` 호출은 0~1ms에 반환했다. 그러나 첫 목적지 콜백은 그 반환보다 1,017~1,032ms 뒤에 왔고, 사이에 다음 조회가 있었다. **이 차이는 첫 요청의 RTT나 공급자 단독 처리시간으로 확정할 수 없다.** 앱의 의도된 조회 간격, 실행 복귀 지연, SDK 공개 호출, 반환 이후 콜백 공백을 아래처럼 분리한다. 기능 수정의 근거는 부족하므로 값 전달·gate·조회 순서·간격·Clock·TTL은 바꾸지 않았다.

작업 브랜치/HEAD는 `develop-jdw` / `b0cfc1eb687b5a134c5d778ca86436f11f9c40e5`로 유지했다. AGENTS.md, 최신 현황표, [앞선 SIG016 전체 시도](2026-11-10-sig016-deadline.md), SRS·TC·API Map·Data Interface 원문, 기존 Hub 계약과 실제 SDK 공개 소스를 대조했다. 앞선 미커밋·원본·임시 파일을 먼저 해시/복사해 보관했다. 커밋·push·PR·브랜치 전환·초기화는 하지 않았다.

## 1. 원문의 시작점과 이번 입력은 동치인가

SRS/TC v0.2 SHA256은 `2ea8c6d0baf3040f146fbbab0d87883f12108354411ffaad1f8e6fb1e95e69da`다. [해당 요구·인터페이스 행](evidence/2026-11-10-sig016-boundaries/requirement-interface-rows.json)을 보존했다.

| 근거 | 실제 문구/관찰 | 판단 |
| --- | --- | --- |
| SRS REQ_SIG_016, Sys Req Spec 180행 | ‘목적지 있음 → ≤1s 안에 remainTimeSec(s)·remainDistM(m) 반영, 목적지 없음 → null’ | ‘있음’이 미리보기 선택·내부 목적지 확정·안내 시작·SDK 노출 중 어느 순간인지는 정의하지 않음 |
| TC_SIG_016, Test Case 180행 | 내비 앱에서 ‘부산’ 설정→NaviState/원래 값 비교, 목적지 해제→null | ‘안내 시작 터치’를 시한 시작점으로 지정하지 않음. 공식 TC 전체의 Pass/Fail 기점을 임의 확정할 수 없음 |
| Data Interface MSG-14, 99~104행 | 생산 VehicleSignalHub, 소비 FAT/RST/FCS/RES/TRP, **주기1000ms** | 1Hz 메시지 출력이 명시됨. `latestNavi` 캐시 갱신만으로 MSG-14 출력 시한을 대체할 수 없음. 주기1000ms와 입력 후1초는 서로 다른 조건 |
| 실제 UI/SDK | 미리보기에는42dot·1분·50m가 보임. Q1은 터치 bracket 중 조회 후 SDK 목적지가0/0, 이후 ROUTE_GUIDING, 그 다음60/50 | UI의 목적지 표시와 SDK 값 노출은 같은 관측 사건이 아님. 공개 SDK만으로 provider 내부 목적지 확정 시각은 모름 |
| NaviHelper3.0.5 공식 공개 소스 | getDestinationInfo는 Unit 반환·별도 onDestinationInfo 전달. 실제 알림은 NaviProvider 구현에 따라 달라질 수 있음 | 메서드 반환은 유효 목적지 응답/안내 활성화 완료가 아님. `onRouteStarted`의 공식 주석도 경로 탐색 시작이며 ‘목적지 확정’ 시각의 정의는 아님 |

원문은 **터치 기준과 동치라고 확인되지 않았으며, 다른 기준이라고 확정한 것도 아니다.** 원문 해석이 필요한 상태다. 필요한 확인은 다음 세 가지다.

- 목적지 검색 결과/미리보기를 선택한 시점부터인지, 안내 시작이 반영된 시점부터인지, 공급자가 유효 목적지를 공개한 시점부터인지와 이를 관찰할 공식 이벤트.
- 원문 ‘부산’ 설정과 공식 sample42dot 경로의 시험 대체 범위. 이번에도 요청받은 기존60초/50m sample만 사용했다.
- MSG-14 출력 완료 경계. 현재 코드는 [기존 Hub 계약](../setup/ROLE1_SIGNAL_HUB.md#주기와-생명주기)에 따라 `latestNavi`와 실제1Hz `naviSamples`가 분리돼 있다. 소비자 수신은 emit 이후 상한이며, 캐시와 동일시하지 않는다.

이 확인을 기다리는 동안 **기존 터치 기준 초과를 삭제/통과로 바꾸지 않는다.** SDK 콜백 수신을 전체 시한의 새 시작점으로 채택하지도 않는다.

## 2. 계측만 추가한 범위

기존 `Role1NaviDeadlineProbeTest`와 실제 `Role1LiveSdkSession`을 재사용했다. 새 값 전달 구현이나 대체 SDK를 만들지 않았다. 제품 어댑터에 **기본 null인 선택적 진단 observer**를 추가해 다음 경계만 기록했다.

- `TIMER_ARMED`: 기존 delay 직전의 진단 표시. `TIMER_RESUMED.dueAtMs`는 그 표시 처리 뒤 **실제 delay 호출 직전에 읽은 elapsed + 기존1000ms**다. 코루틴 내부 등록 deadline 자체를 계측한 것은 아니며, 동일 앱 시간축의 명목 복귀 시각이다.
- `POLL_BEGIN`, `CALL_BEGIN/RETURN/THROW`: 기존 위치→경로상태→목적지→TBT 조회 순서. 공개 메서드 직전/직후이며 내부 IPC 진입·provider 처리 시작을 뜻하지 않는다. 측정 구간에는 경계 observer의 작은 오버헤드도 포함된다.
- `CALLBACK`: 제품 listener 진입의 **기존 receipt 시각**. `ADAPTER_BEGIN/END`: 기존 Main post가 실제 실행되어 `SdkNaviDelivery`를 처리하는 직전/직후.
- Hub 반영은 기존처럼 `[NaviState.tMs, latestNavi collector 수신]` 구간, MSG-14 소비자는 `naviSamples` 첫 수신을 기록했다. tMs를 실제 emit 시각이라고 부르지 않았다.

OFF에서는 진단 객체/점·추가 Clock 읽기를 만들지 않고 null 분기만 추가된다. 실제 호출을 감싼 함수는 inline이다. observer 예외가 실제 SDK 호출·배송·기존 예외를 가리지 않도록 막고 회귀2개를 추가했다. 일반 앱/서비스는 observer를 전달하지 않는다. 이 변경은 **원인 분석용 계측이며 기능/성능 수정이 아니다.**

SDK 소스는 이전에 받은 공식 Maven의 NaviHelper3.0.5 소스를 읽기 전용 사용했다(SHA256 `915feb7246215e6999f4695ba6b51cf31e4c6a5d8a8c1b36f16a3ba353569201`, 이전과 동일). `ServiceManager.sendRequest`→`BaseServiceConnector.requestService/sendApi`는 연결 시 binder를 통해 요청하고, 불준비/DeadObject면 요청을 보관할 수도 있다. 공개 getter의 반환만으로 요청 수락을 보장하지 않는다. 별도 `ResponseHandler`→listener 배송에는 공개 요청 ID가 없다. **SDK 내부 큐·service·provider를 하나의 공급자 시간으로 단정하지 않는다.** [소스 출처·입력 해시](evidence/2026-11-10-sig016-boundaries/provenance.json).

## 3. 기기 추가3회 — 시각을 그대로 나열

기존 AVD emulator-5554/user10, 실제 Playground SDK, P/0, 기본 내비 전면에서 수행했다. 기존 경로/미리보기 없음과 SDK SAFE/0을 먼저 확인했다. 고유 runId `sig016_boundaries_20261110a`로 **3회만** 시행하고 종료했다. 제품/시험 APK는 `install -r -t`로 데이터 유지 교체설치했다. 외부 Console 입력·권한·설정을 바꾸지 않았다.

모든 시각은 같은 기기의 elapsedRealtime ms다. 기존과 같은 입력 bracket과 차이의 **±2ms**를 유지한다. 아래 상대시각의0은 표시용으로 입력 bracket의 왼쪽을 택한 좌표일 뿐, 수락 판정의 시작점을 유리하게 바꾼 것이 아니다.

| 회 | requestRoute 호출 직전~반환 절대시각 | 정상 터치 직전~직후 절대시각 | 터치 수락 |
| --- | --- | --- | --- |
| Q1 | 14447663~14448800 | 14450069~14450131(폭62) | true |
| Q2 | 14454706~14454706 | 14455048~14455096(폭48) | true |
| Q3 | 14458922~14458923 | 14459279~14459292(폭13) | true |

‘입력 이후 첫 호출’은 입력 bracket이 확실히 끝난 뒤의 최초 공개 getter를 우선 열거한다. **입력 bracket과 겹친 호출도 숨기지 않고 아래 별도로 공개한다.**

| 회 | 첫 location 호출 시작→반환 | 같은 회차 destination 시작→반환 | 그 뒤 첫 목적지 콜백(값) | 어댑터 처리 시작→끝 | Hub 반영 bracket | 1Hz 소비자 수신 |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| Q1 | +1028→1028 | +1028→1028 | +2045(60/50) | +2045→2047 | +2058~2059 | +2163 |
| Q2 | +84→85 | +85→86 | +1110(60/50) | +1110→1115 | +1188~1189 | +1190 |
| Q3 | +875→875 | +876→876 | +1908(60/50) | +1909→1909 | +1955~1958 | +1958 |

Q1에는 터치 bracket 중에도 location+22→26ms, destination+26→26ms 호출이 있다. 이 호출 이후 **+68ms 목적지 콜백은0/0**이었고, 활성 gate로 유효값을 만들지 않았다. +216ms에는 실제 ROUTE_GUIDING을 받았다. 이 겹친 회차의 명목 timer due는−662ms, 복귀는+22ms로 **684ms 늦었다**. 즉 입력을 끝으로 이동해 이 지연을 없앤 것이 아니라, 입력 구간 중 조회/0응답과 실행 지연을 별도 기록한 것이다.

## 4. 앱 대기·SDK 호출·콜백 공백 분리

| 회 | 입력 bracket 대비 명목 due 구간 | 실제 timer 복귀−due | 첫 location 진입→destination 진입(선행 작업) | 첫 destination 공개 호출 시간 | 그 반환→첫 목적지 콜백 공백 | 사이의 추가 destination 시작→반환 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Q1 | +965~1027 | 1 | 0 | 0 | 1017 | +2030→2031 |
| Q2 | +35~83 | 1 | 1 | 1 | 1024 | +1089→1091 |
| Q3 | +862~875 | 0 | 1 | 0 | 1032 | +1883→1884 |

- **의도된 앱 조회 대기:** 기존 fixed-delay1000ms가 그대로다. 입력과 위상이 어긋나면 다음 조회까지 대기한다. 이 대기는 SDK 함수 안의 blocking 시간이 아니다. 조회 주기1000ms만으로 end-to-end≤1000ms가 보장되지는 않는다.
- **앱 실행 복귀 지연:** 위 첫 완료 후 조회3회는0/1/1ms였다. 전체 진단창의15회 복귀는0~6ms가14회, Q1 입력과 겹친684ms가1회였다. Main이 왜 늦게 실행됐는지(OS runnable 대기·다른 Main 작업·런타임 중단)는 이 계측만으로 분리하지 못했다. SDK 함수가684ms 막았다고 단정하지 않는다. 진단 observer ON·UI 자동화가 포함된 표본이므로 일반 실행의 고정 지연으로도 일반화하지 않는다.
- **공개 SDK 호출 구간:** 전체 진단창의 최대값은 location15ms/route16ms/destination2ms/TBT20ms다. 이 표본에서는 목적지 공개 호출이1초 동기 차단한 증거가 없다. 반환은 여전히 요청 성공/유효응답 확인이 아니다.
- **반환 후 콜백 구간:** 세 회차 모두 첫 완료 후 조회의 반환 뒤1,017~1,032ms 동안 목적지 콜백이 관찰되지 않았다. 반면 경로상태 콜백은 왔다. 다음 destination 호출도 들어간 뒤 첫 유효값이 왔다. **첫 요청의 응답이1초 걸렸다거나 마지막 요청에14~24ms만 걸렸다고 1:1 대응할 수 없다.** 공개 요청 ID·원천 생성시각·service/provider 처리 경계가 없어서 SDK 내부 배송/큐와 공급자 준비/응답 정책은 미분리다.
- **콜백 이후:** 제품 listener→어댑터 시작은0/0/1ms, 어댑터 실행은2/5/0ms, 콜백→Hub는13~14/78~79/47~50ms였다. 기존 값 전달을 재구현할 근거는 발견하지 못했다.

‘첫 완료 후 조회→콜백’은 실제 SDK 메서드 반환과 실제 callback의 **관찰 공백**이지 특정 요청의 RTT가 아니다. [전체 점298개·일반 이벤트·입력/정리 원본](evidence/2026-11-10-sig016-boundaries/device.json), [전체 호출/예약/지연 재계산](evidence/2026-11-10-sig016-boundaries/boundary-summary.json), [재계산 코드](evidence/2026-11-10-sig016-boundaries/analyze.py), [SDK 보조 로그](evidence/2026-11-10-sig016-boundaries/sdk-logcat-window.txt)를 함께 남겼다.

## 5. 기준 초과는 그대로 유지

| 회 | 원래 터치 bracket→제품 SDK 수신 | 터치→Hub | 터치→1Hz 소비자 | 이미 ROUTE_GUIDING을 관찰한 시점→Hub |
| --- | ---: | ---: | ---: | ---: |
| Q1 | 1983~2045 | 1996~2059 | 2101~2163 | 1842~1843 |
| Q2 | 1062~1110 | 1140~1189 | 1142~1190 | 1064~1065 |
| Q3 | 1895~1908 | 1942~1958 | 1945~1958 | 1676~1679 |

원래 터치 기준의 전체1초 초과는 추가3회에서도 유지됐다(±2ms 고려). 이미 활성 상태를 관찰한 뒤부터도1초가 넘는다. 다만 이 보조 관찰을 원문 시작점의 새 정의로 채택한 것은 아니다. 앞선 B5회·교정A5회는 원본/판정을 덮어쓰지 않고 [이전 기록](2026-11-10-sig016-deadline.md)으로 구분한다. 이번 시도도60초/50m와 자기 시험 경로 취소 후null3/3을 확인했으며, 이동·감소·자연도착 검증은 아니다.

## 6. 최종 판단과 다음에 필요한 근거

| 분류 | 현재 판단 | 근거와 필요한 다음 확인 |
| --- | --- | --- |
| **내 코드 수정 필요** | **행동 수정 필요성 미확정. 진단만 추가, 추측성 기능 수정 없음** | 앱의1000ms 조회 위상 대기와684ms 실행 지연은 확인. 하지만 설정 오류/실행 지연 원인과 provider 유효값 생성시각은 모른다. SDK 수신 후 어댑터/Hub 병목 증거 없음. 기준 확정 후 전체 latency budget에 조회/MSG-14 위상을 포함해 검토해야 함 |
| **공급자 구간 추가 확인** | **필요. 공급자 단독 결함으로 확정하지 않음** | 공개 getter는 즉시 반환하지만 첫 목적지 콜백이 다음 조회까지 비어 있음. SDK 내부 요청 보관·service forwarding·provider 응답/갱신·callback dispatch를 구분할 요청 ID/원천시각/공식 응답정책 근거 필요. 같은 연결에서 route callback이 오는 것만으로 destination 요청 성공을 추정하지 않음 |
| **요구 측정 기준 확인 필요** | **필요. 원문 ‘목적지 있음’과 터치의 동치 미확정** | ‘있음’을 확인할 공식 사건과 TC 목적지 설정 조작의 관계를 요구 작성자/4번과 확인. MSG-14 출력까지의 시한을 유지하며 latest 캐시/SDK 수신 이후만으로 통과시키지 않음 |

## 회귀·빌드·보존

진단 OFF/observer 오류가 SDK 호출 횟수·반환·원래 예외를 바꾸지 않는 회귀2개를 추가했다. 최종 검증은 [로그](evidence/2026-11-10-sig016-boundaries/final-validation.txt)·[집계](evidence/2026-11-10-sig016-boundaries/validation-summary.json)에 있다.

- 전체 명령의 결과 XML **748개=engine333+app415, 실패/오류/skip0**. engine333은 소스 불변으로 `UP-TO-DATE` 결과를 재사용했고, **app415는 이번에 실제 실행**했다. 이전746개에 진단 안전 회귀2개가 늘었다. 기기 진단1개/경로3회와 합산하지 않는다.
- debug APK·AndroidTest APK·release manifest·release Kotlin 성공. 최종88tasks 중6실행/82up-to-date. release manifest의 debug/fixture 참조0. 진단 자료형/호출 hook은 일반 소스에 있지만 기본 OFF이며 release에 fixture/자동 경로 생성 진입점을 추가하지 않았다.
- [설치 APK와 최종 산출물 해시](evidence/2026-11-10-sig016-boundaries/installed-apk.json)가 실제로 일치한다. debug `d996f17a4bf2557da0b40a1a47f10ec919ef98185e2da1518edde70f7bc2ff0d`, test `30e4fa1ac1f74ddd9b885f74627405261e1dc50b29986c9c9820b5f85dd8a722`. 최종 수정 코드로 기기3회를 측정했고 이후 소스 변경은 없다. 기존30분/재부팅은 반복하지 않았다.
- [보존 대조](evidence/2026-11-10-sig016-boundaries/preservation.json): 기존 기기DB/WAL/SHM·prefs·cache **150파일 모두 바이트 동일**, 새파일은 고유 진단JSON/stage2개다. 기존진단/이전 임시2파일도 그대로다. 권한/forward 동일, Console 무조작, 정상취소·SDK lease 해제/owner=null·Hub 종료·서비스 없음, SDK 미연결 화면으로 복귀했다. DB 백업은 로컬 `/tmp/awakedrive-sig016-boundaries-uae3s4e7`에 보관했다. SDK/APK 바이너리는 기존 로컬 캐시·build 경로에 유지하고 저장소에는 추가하지 않았다.

직접 경계를 재확인할 때는 P/0·감시/SDK 정상중지·사용자 경로/미리보기 없음·경로 없는 내비 지도를 먼저 확인한다. fixture가 정상 버튼을 조작하므로 수동 입력을 겹치지 않는다. `OK(1test)`는 계측/정리 정상 종료이며 전체1초 Pass가 아니다. 현재 APK가 설치되어 있으며 매번 새runId가 필요하다.

```bash
adb shell am start -W --user 10 -n ai.umos.maps.android.navigation.app/.MainActivity
adb shell am instrument --user 10 -w \
  -e role1Sig016 true -e role1Sig016Boundaries true -e runId sig016_boundaries_manual_01 \
  -e class com.sleepissue.awakedrive.data.Role1NaviDeadlineProbeTest \
  com.sleepissue.awakedrive.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.sleepissue.awakedrive --user 10 \
  cat cache/role1-sig016-sig016_boundaries_manual_01.json
# 이미 확보한 이번 원본의 재계산은 기기 입력 없이 가능하다.
python3 docs/devlog/evidence/2026-11-10-sig016-boundaries/analyze.py
```

SIG001/002·다른 담당 기능·60배 데모는 수정하지 않았다. Clock/판정 시점을 바꾸거나 과거 프레임/캐시로 공백을 채우지 않았다.
