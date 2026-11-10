# 2026-11-09 — 실제1배속 SDK→Hub 발행 간격 조사

**결과: SIG_001 엄격110ms 기준은 미해결이다.** 원인을 프레임별 타이머 복귀·잠금·준비/진단·emit 구간으로 나눴고, 입력 수집을 그대로 둔 발행 타이머 전용 실행기도 반복 측정했지만 기준을 만족하지 못해 제외했다. 최종에는 선택적 계측·감사 보완만 남겼다. Clock·TTL·100ms 예약·늦은 tick 처리·센서 관측 계약은 바꾸지 않았다.

시작·종료 기준은 **develop-jdw / 68696cf158f5cc94ea3b257b87cc2b01bba4a122**, 시작 작업 트리는 clean이다. AGENTS.md, ROLE1_COMPLETION_STATUS, 11/8 종합·11/9 SIG 입력·main 병합 기록을 먼저 읽었다. 이전 **741개 자동시험·main 병합 기기1개**, 더 이전492개/490개·Room/통풍 기기시험은 과거 근거로 구분한다. 실제 기기는 Pleos Connect Emulator의 Playground REAL 입력이며 물리 차량 검증이 아니다. 커밋·push·PR·브랜치 전환은 하지 않았다.

## SRS·TC 원문 기준

원본 `/home/a/Downloads/AwakeDrive_SRS_TC_v0.2.xlsx`, SHA256 **2ea8c6d0baf3040f146fbbab0d87883f12108354411ffaad1f8e6fb1e95e69da**를 읽기 전용으로 확인했다. workbook·TC/RTM 상태·승인 여부는 고치지 않았다. [추출한 셀/행](evidence/2026-11-09-sig-timing/srs-tc.json).

- `Sys Req Spec!E15/F15`, REQ_SIG_001: 조향을 구독해10Hz SignalFrame 발행, **프레임 간격 ≤110ms, 60초 누락0건**, 시간은 주입 Clock(데모는 시나리오 시계).
- `Test Case!E15:G15`, TC_SIG_001: AVD2.1.0/API34·Virtual Console 연결, 운전대±10° 반복 조작(또는 데모), Hub 출력 **frame.tMs를60초 기록**해 최대 간격/누락을 계산.
- 하한 간격은 명시하지 않았다. 이번 **90ms 미만**은 [가정] 짧은 간격/몰아 발행 조사 지표이며 TC 하한·합격 기준을 추가한 것이 아니다. 평균10Hz만으로 통과를 선언하지 않는다.

독립 SDK 화면 Clock의 scenarioTimeMs와 elapsedRealtimeMs는 모두 실제 SystemClock.elapsedRealtime이다. 제품 REAL의 epoch 앵커·DEMO 배속과 섞지 않았다. 최종 `frameTimeAudit`는 이 Clock의 frame.tMs, `publicationAudit`는 기존 발행 메타데이터, `emissionAudit`는 버스 emit 직전 Clock 시각을 각각 집계한다. 원천/수신 시각이나 frame.tMs를 나중 시각으로 덮지 않는다.

## 수정 전 환경·측정 방법

[환경/원본 APK 해시](evidence/2026-11-09-sig-timing/environment-before.json): 현재 설치 APK와 빌드 APK 모두 **0bdafbaa37878fdd99f8dc5e4fa3d7d540996c6c7f76b32199682fbc9e0d9653**. AVD Pleos_Connect_2_1_0/emulator-5554/API34/user 빌드/user10, P/0, 독립 SDK 화면·UI500ms·자동 진단1000ms·기본 Default 실행기다. TTL3000ms·상태 재조회1000ms·확인 lease3000ms·내비 조회1000ms를 고정했다. 측정 중 Gradle·Room runner는 실행하지 않았고 호스트 부하는 실험실처럼 고정하지 못했다.

수정 전 현재 Console은 browser heartbeat가 없어 DDS offline, AVD 선택 모드는 subscribe_hvac_base였다. 이 조건의 SDK 정적 입력을 수정 전/최종의 동일 조건 대조로 삼고, TC의 활성 Console 조향 입력은 별도 실행으로 구분했다. SDK read·조향 raw 구독 자체는 동작한다.

매 실행은 데이터 유지 force-stop 후 SDK 전용 진입으로 새 세션을 만들고 같은 표시·자동 진단 설정을 썼다. 요청한 관찰은76초이며 앱 기동·최종 캡처 시점 때문에 **실제 첫/마지막 emit span**을 표에 기록했다. 모든 표 구간은60초 이상이다. 초기 UI dump가 지속 갱신 때문에 idle을 얻지 못했고 AVD의 뒤로가기 입력으로도 수집이 끝나지 않아, 이후 화면에서 확인한 **중지 버튼**을 명시적으로 누르게 측정기를 보완했다. 앞선 자동 스냅샷의 실제 유효 창과 연장된 상세 계측 창을 꾸미거나 잘라 숨기지 않았다.

## 전후 실제 기기 측정

최대·초과는 이전129ms/13회와 같은 **emit 직전** 지점의 주입 Clock ms다. 평균은 `(frames-1)/실제 span`이며 원천 센서 Hz가 아니다. 전체 수치/메타데이터·tMs 감사는 [측정 집계](evidence/2026-11-09-sig-timing/measurements.json).

| 실행 | 실제 span(초) | frames | 평균 Hz | 최대 ms | >110ms | 최소 ms | <90ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 과거11/8 마지막 근거 | 77.079 | 772 | 약10 | 129 | 13 | 미계측 | 미계측 |
| 수정 전1 · 기본/상세계측 끔 | 76.594 | 767 | 10.0008 | 122 | 12 | 미계측 | 미계측 |
| 수정 전2 · 기본/상세계측 끔 | 76.885 | 770 | 10.0020 | 124 | 21 | 미계측 | 미계측 |
| 실험 APK · 기본/상세계측 켬 | 75.175 | 753 | 10.0033 | 128 | 12 | 72 | 14 |
| 실험 APK · 타이머만 분리1 | 75.099 | 752 | 10.0001 | 143 | 12 | 60 | 11 |
| 실험 APK · 타이머만 분리2 | 75.197 | 753 | 10.0004 | 151 | 10 | 52 | 8 |
| 최종1 · 기본/상세계측 끔 | 74.997 | 751 | 10.0004 | 130 | 25 | 74 | 26 |
| 최종2 · 기본/상세계측 끔 | 74.892 | 750 | 10.0011 | 128 | 7 | 73 | 8 |
| 최종 · Console ±10°/상세계측 켬 | 75.182 | 753 | 10.0024 | 131 | 13 | 70 | 14 |

표의 **모든 이번 실행**에서 순번 누락·중복·역행·producer skippedDeadlines는 각각0이다. 새 감사가 있는 실행은 동일 ms 연속 발행0건이다. 지연 뒤 짧은 간격은 원래100ms 예약 격자를 따라잡는 양상이며, 과거 프레임을 반복 생성하는 방식으로 고치지 않았다. 평균은 모두 약10Hz이나 엄격 기준은 모두 미달이다.

최종1/2의 정확한 frame.tMs 최대/110ms 초과는 **128ms/17회, 127ms/6회**였고, 실제 emit 직전은 **130ms/25회, 128ms/7회**다. Console 실행은 **tMs129ms/11회, emit131ms/13회**다. 메타데이터만으로 실제 배송 지연을 대신하지 않는다. 서로 다른 APK/계측 유무/Console 조건을 섞어 개선율이나 인과를 주장하지 않는다.

## 프레임별 지연 구간과 실험 결과

`HubTimingPoint`는 예약 deadline, timer schedule/resume, 발행 진입, 입력/발행 잠금 요청·획득·해제, 프레임 준비, 진단 완료, emit 전/후를 기록한다. 경과 Clock ms와 분석용 nanoTime·스레드 이름을 분리했다. nanoTime은 처리 경과 분석용이며 CPU 실행 시간이나 운행 시각으로 쓰지 않는다. debug에서 명시한 sdkTiming=true에만60,000점 상한 메모리를 만들고 입력/발행 스레드에서 파일을 쓰지 않는다. 자동 진단은 점 수만 저장하고 수동/종료 진단에 상세 점을 저장한다. 유효 상세 실행 모두 pointsDropped=0이다.

| 상세 실행 | 타이머 복귀 최대 | 발행 잠금 대기 최대 | 프레임 준비 최대 | 진단 구간 최대 | 결과 |
| --- | ---: | ---: | ---: | ---: | --- |
| 기본 실행기 상세146.290초 | 22ms | 1ms | 5ms | 8ms | >110ms16건 중15건은 지연 증가의 주된 구간이 타이머 복귀. 최초1건은 복귀와 진단이 함께 기여 |
| 동일 실험 APK 기본75.175초 | 28ms | 1ms | 12ms | 2ms | 최대128ms·초과12회 |
| 발행 타이머만 분리1 | 28ms | 12ms | 19ms | 40ms | 최대143ms·초과12회, **제외** |
| 발행 타이머만 분리2 | 33ms | 2ms | 20ms | 35ms | 최대151ms·초과10회, **제외** |

상세146.290초는 종료 조작 문제로 연장된 원인 분석 창이며 수정 전76초 대조와 같은 창으로 취급하지 않는다. [원인 분석](evidence/2026-11-09-sig-timing/trace-before-2-analysis.json), [기본](evidence/2026-11-09-sig-timing/shared-1-analysis.json), [분리1](evidence/2026-11-09-sig-timing/isolated-1-analysis.json), [분리2](evidence/2026-11-09-sig-timing/isolated-2-analysis.json)에 각 초과 프레임의 시각·단계별 차이를 남겼다. 대응 `*-frames.csv`와 `*-raw.json.gz`는 실제 상세 자료다.

예를 들어 기본 상세의 seq475는 due4,777,215ms, resume4,777,232ms, emit전4,777,233ms로 예약 복귀17ms+준비1ms가 관찰됐고 직전 프레임과117ms였다. 분리1의 seq4는 복귀2ms·잠금1ms·준비3ms·진단40ms가 합쳐 emit전143ms 간격으로 나타났다. 이40ms를 계산 자체의 CPU 소모나 GC로 단정하지 않는다.

실험은 **입력 Default 수집 유지 + 발행 timer coroutine만 단일 ScheduledExecutor로 분리**했다. 스레드 이름으로 실제 분리를 확인했고 자원은 세션 종료 시 닫았다. 같은 실험 APK **c43b3ef1295abec18a992191e8992165207d6b95f35b901f807203283518c12b**에서 기본/분리 실행을 비교했다. 이전 전체 수집 실행기 교체와 다른 실험이지만 이번에도 효과가 입증되지 않아 publisherDispatcher·전용 thread·설정 플래그를 최종 코드에서 모두 제거했다. 실험 소스/diff는 `/tmp/awakedrive-sig-timing-p1t15j0q/publisher-experiment-source/`에만 보관했다.

프로젝트와 같은 [kotlinx.coroutines1.10.2 delay 공개 소스](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.2/kotlinx-coroutines-core/common/src/Delay.kt)는 지정 시간 **이후** 재개하며 시간 처리는 dispatcher 구현에 달려 있다고 설명한다. [동일 버전 Executors 공개 소스](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.2/kotlinx-coroutines-core/jvm/src/Executors.kt)의 ScheduledExecutor 예약 경로로 실제 분리를 시험했다. 어느 쪽도110ms 상한을 증명하는 계약은 아니다. 이는 관찰한 지연과 맞는 구현 근거이며, 특정 OS/VM/GC 원인을 확정하는 근거는 아니다.

emit 직전·직후 시각은 SharedFlow.tryEmit 호출을 감싼다. 내부의 한 순간을 정확히 안다고 주장하지 않고, 분석에 `현재 전 - 직전 후` 하한과 `현재 후 - 직전 전` 상한도 남겼다. 기본 실험에서는 하한만으로도 **12건**이110ms를 초과했다. 따라서 계측 지점의 폭을 감안해도 미달 결론은 유지된다. 일부 emit 호출 구간은10ms 이상 경과했으며 이 역시 CPU 시간과 구분한다.

## TC Console 입력 확인

공식 Console HTML의 공개 경로 `/browser/session`, `/vehicle/controls`를 사용했다. 기존 subscribe_hvac_base 모드를 유지하고 이번 시험의 고유 browser heartbeat만 열었다. DDS ONLINE/AVD active·P/0을 확인한 뒤0→+10→0→-10을0.5초마다 반복했다. [입력 전제](evidence/2026-11-09-sig-timing/console-active-state.json), [입력/복구 기록](evidence/2026-11-09-sig-timing/console-stimulus.json).

실제 SDK STEERING_DEG raw 콜백 **147회**와 -10/0/+10 변화를 확인했다. 전륜각/운전대각의 의미 충돌은 보류해 정규화는 null/SEMANTIC_UNCONFIRMED 그대로이며 부호·비율을 추정하지 않았다. 이 조건에서도 emit131ms·초과13회다. [Console 상세](evidence/2026-11-09-sig-timing/console-final-analysis.json). API 입력 성공을 조향 의미 해결이나 SIG_001 전체 Pass로 바꾸지 않는다. 시험 뒤 제어값 전부0·원래 AVD 모드 복귀/유지, 시험 heartbeat 종료·DDS inactive를 확인했다.

## 최종 수정과 자동시험

- 선택적 타이밍 observer와 bounded debug recorder, SDK 진입의 sdkTiming 인자 전달을 추가했다. 일반 수집에서는 상세 observer가 null이고 기존 실행기·예약100ms·시간축·deadline 누락 처리·TTL·최신성·출처 중재를 유지한다. 실제 SDK setter나 운행/Room/서비스·다른 담당 기능은 수정하지 않았다.
- SdkPublicationAudit에 최소 간격·90ms 미만·동일 ms 연속 발행 감사를 추가했다. 기존에는 같은 시각의 다른 순번을 간격 집계에서 생략했으므로0ms burst가 드러나지 않았다. 중복 순번·역행·세션 reset·누락 회복·기존110ms 카운터는 유지한다.
- 신규 회귀2개는0ms/짧은 간격과 누락/중복의 분리·세션 reset, deadline100/실제 실행125와 frame.tMs100·원천 관측0의 분리를 확인한다. 기존 상태 재조회/거부 배송/내비/TTL 회귀도 포함한 전체 실행이다.

**이번 최종 자동시험 engine333 + app410 =743개, 실패·오류·skip0.** 이전741개와 구분한다. `--rerun-tasks`로81개 task를 실행해 assembleDebug·AndroidTest APK·release manifest 모두 성공했다. [전체 로그](evidence/2026-11-09-sig-timing/final-tests-build.txt), [집계/APK 해시](evidence/2026-11-09-sig-timing/test-results.json). Lint·Room/운행/부팅 기기 재시험·60배 DemoPlayer·서명release·물리 차량은 수행하지 않았다.

## 남은 원인과 판단

**미해결 유지.** 현재 AVD에서 예약 복귀가22~33ms 늦고, 일부 잠금·프레임 준비·진단·emit 호출 구간에도 긴 경과가 생긴다. 호스트/게스트 OS 스케줄링, Default 큐 대기, 런타임 중단/GC의 기여율은 이번 점 계측만으로 분리하지 못했다. 이것을 SDK 미지원·환경에서 원천적으로 불가능하다는 결론으로 쓰지 않는다. 다음 원인 분리에는 같은 시각축의 OS runnable/CPU 실행·런타임 중단 trace가 필요하다. 버퍼 누락이나 과거 trace로 인과를 꾸미지 않았다.

TTL 확대·Clock 지연·조기 발행/주기 단축·과거 프레임 채우기·재조회를 센서 관측으로 세기·소스 Hz 추정은 하지 않았다. 이동 heading·현재온도 변경·STR·조향 의미·착석 UNKNOWN·60배 성능 등 기존 미해결은 그대로다.

## 직접 누를 버튼과 기대값

1. 기존 AVD P/0에서 감시 서비스가 켜져 있으면 정상 **감시 중지**. 아래 명시적 SDK 진입을 사용한다. Room 화면을 열 필요 없다.
2. **차속 권한 → 연결**. 신호 READ/품질과 Hub 발행을 기다린다. 표시500ms는 화면 갱신이고 실제 발행100ms와 다르다.
3. Console을 열어 **AVD Enabled·DDS ONLINE** 확인 후 운전대를±10°로 반복 조작한다. raw SDK 변화가 보여도 조향 의미 보류의 null을 임의로 정상값으로 바꾸지 않는다.
4. 최소60초 관찰 후 **진단 저장** 완료를 확인하고 **중지**를 누른다. 최대·110ms 초과·최소·90ms 미만·deadline 누락을 함께 확인한다. 이번 환경의 기대 판정은 **평균 약10Hz지만 엄격 기준 미달**이다. 향후 최대≤110ms와 누락0을 실제로 만족한 창에서만 해당 조건의 통과를 검토한다.
5. 중지 뒤 값null·SDK 소유권 해제 확인. 일반 뒤로가기만으로 중지됐다고 단정하지 않는다. 자신의 시험 Console 입력은0으로 복구하고 창/시험 heartbeat를 닫는다.

```bash
# 이번 과거 진단 원본을 덮지 않는 별도 이름. 현재 설치된 최종 debug APK에서 실행한다.
adb shell am start --user 10 -n com.sleepissue.awakedrive/.debug.DebugHarnessActivity \
  --ez sdkProbe true --ez sdkCapture true \
  --es sdkCaptureFile sdk-signal-timing-user-check.json

# 상세 원인 분석이 필요하면 진입 인자에 --ez sdkTiming true 추가.
# 일반 측정과 상세 측정은 부하가 다르므로 결과 조건을 구분한다.
adb exec-out run-as com.sleepissue.awakedrive --user 10 \
  cat cache/sdk-signal-timing-user-check.json
```

## 원본 보존·최종 상태 대조

[최종 대조](evidence/2026-11-09-sig-timing/preservation-final.json): 시작 시 앱의 databases/shared_prefs/cache **기존83개 파일 모두 바이트 동일**이다. 수동 DB drive_segment1개·REAL DB18개 및 checkpoint/메타데이터 논리 해시도 전후 동일하다. DB/기존 진단 원본은 저장소에 추가하지 않았고 비공개 임시 폴더에만 대조 사본을 뒀다. 앱은 데이터 유지 교체 설치만 했으며 삭제·초기화·Wipe·재부팅하지 않았다.

최종 설치 APK는 빌드 APK SHA256 **f4cb72bfe0c6385b6eb616a8f5f36cfdf03a5787ab8d911ad7b774e5275eb37c**와 같다. SRS 원본 SHA도 동일하다. SDK 수집은 중지했고 서비스 ServiceRecord 없음·requested=false를 확인했다. 시험 전0이던 Console 입력은0으로 복구했고 이번 heartbeat는 종료했다. 이번에 추가한 adb8088 forward도 제거했다. 기존 내비 경로와 실내온도/통풍은 조작하지 않았다.

브랜치/HEAD는 시작과 같은 develop-jdw/68696cf다. stage·커밋·push·PR·브랜치 전환은 없고 이번 변경은 작업 트리에 남겼다. Room·TripRules·WorkProbe·감시 서비스·DemoPlayer·다른 담당 제품 코드는 변경하지 않았다.
