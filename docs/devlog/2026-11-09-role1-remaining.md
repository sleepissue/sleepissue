# 2026-11-09 — 1번 남은 기기 검증

시작 develop-jdw/68696cf158f5cc94ea3b257b87cc2b01bba4a122. 기존 미커밋 주기 계측·회귀·문서를 먼저 보존했다. AGENTS·현황표·주기 조사·main 병합 기록과 SRS/TC 원문을 대조했다. 아래는 시험 전에 만든 중복 제외 목록이다. 기존 자동743개와 과거 Room/내비/서비스 근거를 이번 실행으로 세지 않는다.

| 대상 | 기존 근거 | 이번 미검증 목록/진행 범위 |
| --- | --- | --- |
| SIG_001 | 평균10Hz·누락0, 엄격110ms 미달, timer 분리 실패 | Perfetto sched/dalvik와 기존 계측을 BOOTTIME으로 대조, 소비자 수신 별도 |
| SIG_002/003/004/011/012/013 | raw/Hub 값과 변환·유지 과거 확인 | 조작→SDK→Hub 전체200ms, 입력 오차와 consumer 별도 |
| SIG_005 | Boolean UNKNOWN 의미 손실 | raw 수신만 관찰 가능, 추정 반전/비율 금지 |
| SIG_006/015/016/017 | 고정 위치/현재19.1°C 유지/활성60초50m·취소null | 공식 동적 입력 경로 확인, 위치·온도·회전·도착 변화는 가능한 경우만 |
| SIG_007/008/009/010 | 권한·출처·단위·Hz 회귀 | 실제 권한 상실/복구와 의미 보류. 다른 담당 데모 재개발 제외 |
| TRP_001/005/006/007/010/011/012 | Clock 주입/Room 경계 | 실제5분·15분 경과, 저장 주기와 재시작 복원. 쉼터/보상 실행은4번 연결 대기 |
| TRP_009 | 과거 프로세스 복원 | 격리 시험 DB에서 새 실행으로 force-stop 복원·원본 불변 |
| TRP_002/003/008 | IGNITION_STATE 미지원·부팅 제약 | 실제 시동·STR·2시간/24시간 대기는 환경/미수행으로 명시 |
| PLT_013/서비스 | 과거 화면독립 수집/단일소유권 | 격리 DB로 알림 거부·SDK 소유 충돌·복구·화면 이탈,30분 TC와 짧은 확인 구분 |

시험 진행 결과와 최종 ID별 상태는 아래에 추가한다. 저장소 원본 Excel/TC 상태는 수정하지 않는다.

## SRS 원문·시한과 측정 해석

원본 `/home/a/Downloads/AwakeDrive_SRS_TC_v0.2.xlsx` SHA256은 이전과 같은 `2ea8c6d0baf3040f146fbbab0d87883f12108354411ffaad1f8e6fb1e95e69da`다. [SRS/TC 셀 추출](evidence/2026-11-09-role1-remaining/requirements.json)을 보존한다. SIG002/003/004/011/012/013은200ms, SIG005/006/014/015/016/017은1초, SIG001은60초 간격≤110ms·누락0이다. TRP005/006/007/010/011/012 TC는 원래 JVM/Clock 주입을 허용한다. 이번 실제 대기는 그 원래 자동시험과 별도 근거다. PLT013은 내비 화면 뒤30분 누락0이므로 짧은 기동이나20분 정차 시험을 완전 통과로 세지 않는다.

이번 공개 Console HTTP 요청 전후 `adb shell cat /proc/uptime`으로 BOOTTIME 구간을 잡았다. uptime 출력10ms 양자화 때문에 뒤쪽에10ms를 더한 보수 구간이며, 입력 bracket은30~70ms다. UI 클릭/손가락 접촉 시각을 측정한 것은 아니다. 프레임 준비 tMs·메타데이터, 실제 emit, 소비자 수신은 다르다. 지연 시험에서는 실제 emit의 순간 대신 **준비 시각 하한~소비자 수신 상한**으로 배송을 감싼다(Clock ms 양자화 여유1ms). 해당 값은 SDK 수신 뒤 지연만 뜻하지 않는다. 첫 일치 프레임의 필드 수신 시각이 입력 하한 이후인지도 확인해 과거 값/재조회 유지를 새 반영으로 세지 않았다.

## SIG_001 시스템 추적

기기의 Perfetto v34·atrace·simpleperf 실행 파일과 linux.ftrace sched/dalvik 수집 가능을 확인했다. 실제 사용은 Perfetto sched_switch/sched_waking·Dalvik이며 simpleperf 샘플링이나 특권 API는 사용하지 않았다. 기존 계측에 debug Linux TID와 CONSUMER_RECEIVED만 추가했다. Java Thread ID와 OS TID를 섞지 않는다. [설정](evidence/2026-11-09-role1-remaining/sched.pbtxt), [시각/요약](evidence/2026-11-09-role1-remaining/system-trace-summary.json), [앱 점 기록](evidence/2026-11-09-role1-remaining/perfetto.json.gz), [스케줄링 대조](evidence/2026-11-09-role1-remaining/late-gap-scheduling.json)를 남긴다.

Perfetto clock_snapshot에서 MONOTONIC-BOOTTIME은109/56ns였다. 앱의 nanoTime-경과ms 짝 차이는0.000240~1.177991ms로, ms 읽기 후 nanoTime 읽기까지의 양자화/선점 폭도 기록했다. 순수 nanos를 임의 epoch로 해석하지 않았다. 동일 기동의 boot 시간축에서 대조했으며 수집 중 재부팅·STR은 없었다.

| 초과 프레임 | 예약 이후 관찰된 실행 대기 | 증거로 말할 수 있는 범위 |
| --- | --- | --- |
| seq305, emit114ms | 발행 worker Runnable10.739ms | CPU 배정 대기 관찰 |
| seq326, emit121ms | DefaultExecutor가 deadline+0.33ms에 깨어나 잠깐 실행 후 Runnable19.420ms, 이어 worker Runnable1.602ms | 타이머를 깨우는 스레드 자체의 선점과 발행 스레드 실행 대기를 구분 |
| seq389, emit111ms | worker Runnable10.915ms | CPU 배정 대기 관찰 |
| seq535, emit120ms | worker Runnable21.914ms | 같은 구간 RHIThread/GameThread·시스템/Binder 실행 확인. 동시 실행만으로 특정 앱의 책임/모든 원인으로 일반화하지 않음 |
| seq617, emit113ms | worker sleep8.073ms + Runnable6.072ms | task가 큐에 들어간 정확한 시각은 미계측이므로 sleep 전체를 실행 큐 대기로 부르지 않음 |

[타이머 waker](evidence/2026-11-09-role1-remaining/timer-waker.csv), [경쟁 CPU slice](evidence/2026-11-09-role1-remaining/gc-competition.csv), [런타임 구간](evidence/2026-11-09-role1-remaining/pauses.csv)으로 근거를 분리했다. 해당5건은 앱의 Background GC 구간과 겹치지 않았다. 동시 GC 전체 duration을 stop-the-world 정지로 해석하지 않는다. 같은21.914ms×4CPU 창에는 앱 main도14.118ms, traced_probes도8.997ms 실행됐으므로 추적 도구 부하까지 포함한 조건임을 명시한다. 이 결과를 무계측 환경의 모든 지연 원인으로 일반화하지 않는다. 다른 시점의 GC/FullSuspendCheck는 실제 관찰됐지만 이번 초과의 주원인으로 증명되지 않았다.

이번 앱75초 감사는 frame.tMs 최대129ms·초과8, emit 직전130ms·초과10, 평균 약10Hz·순번/deadline 누락0이다. emit 전→소비자 수신은750개에서0~12ms다. [배송 감사](evidence/2026-11-09-role1-remaining/delivery-summary.json)의 소비자 간격은최대130ms·초과11회, emit 후 marker는145ms·14회다. 후 marker는 tryEmit 뒤 선점까지 포함하므로145ms를 정확한 내부 emit 순간 간격으로 부르지 않는다. `현재 emit전−직전 emit후` 하한만으로도10회가110ms를 초과했다. 소비자 수신 간격은 이 배송 지연까지 포함하며 발행 주기나 원천 센서 Hz와 구분한다. 64MB ring이 초반을 덮어 시스템 trace의 공통 창은 약50초이며5건만 상세 대조했다. 손실 통계0을 초반 보존의 증거로 쓰지 않는다. raw67MB trace는 임시 경로와 SHA로 보존하고 저장소에는 관련 추출을 남겼다.

**엄격110ms 미해결.** OS Runnable 대기가 일부 초과의 원인임은 확인했으나 모든 지연·기기 상한은 확정하지 못했다. 앱의 특정 알고리즘 결함이나 최소 성능 수정 효과가 증명되지 않아 스케줄러/우선순위/주기를 변경하지 않았다. 제거한 전용 타이머 실험을 반복하지 않았다. [Perfetto 공식 설명](https://perfetto.dev/docs/data-sources/cpu-scheduling)은 waking과 running 구분의 근거다.

## 동적 입력·의미 보류

현재 공식 [NaviHelper3.0.5 소개](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/intro)와 [공식 샘플](https://github.com/Pleos-Playground/pleos-sample-navi-app), 공개 SDK 소스를 재대조했다. 제공자 응답에 의존하며 helper의 위치 조회·경로 요청·재탐색과 **위치/이동 생성 입력**은 다르다. 조사한 공개 helper에 위치 setter/모의 주행 시작 API는 확인되지 않았다. 현재 내비 UI도 읽기 전용으로 열어 확인했고 시험 경로나 기존 경로를 새로 요청/취소하지 않았다. 기본 내비의 지도는 비어 있고 위치/모의 주행 입력은 찾지 못했다. UI 클릭이 동작하지 않은 것을 SDK 미지원으로 해석하지 않는다.

이번100초 SDK 수집의 위치는37.4127212/127.094681 고정이고 heading은null이었다. 현재온도는19.1°C READ 유지였다. [공식 현재온도 API](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/h-v-a-c/get-hvac-temperature-current)는 get/register이고 설정온도 setter와 다르다. 현재 Console catalog에도 HVAC_TEMPERATURE_SET/좌석 온열만 있고 현재온도 입력은 확인하지 못했다. 이전 geo fix 수신 성공·활성60초/50m/TBT50m와 취소null은 과거 근거다. 실제 이동 heading·거리 감소·회전 통과·자연 도착·현재온도 변경은 **입력 경로 미확인/미검증**, SDK 미지원이 아니다. 조향/착석 Boolean 의미 보류·null도 유지했다.

## 신호별 입력→SDK→Hub 지연

[공개 요청·시각 bracket](evidence/2026-11-09-role1-remaining/latency-inputs.json), [첫 새 값 분석](evidence/2026-11-09-role1-remaining/latency-analysis.json), [100초 수신 원본](evidence/2026-11-09-role1-remaining/latency-capture.json.gz)을 분리한다. 다음 상한은 소비자 수신까지 포함하며 ms 양자화 여유1ms를 별도로 더해도 결론이 같다. HTTP 성공만으로 SDK 값 일치를 판단하지 않았다.

| 대상 | 실제 입력/변환 | 입력→준비 범위·입력→소비자 상한 | SDK 수신→준비 | 이번 판정 |
| --- | --- | --- | --- | --- |
| SIG002 | 0↔80km/h 각3회, raw22.222223m/s→80.0km/h | 준비 최대259ms, 소비자 최대260ms. 최장80입력의 준비 하한219ms | 22~73ms | 변환 일치, 전체200ms는 **미달**. SDK 이후만 보면 통과처럼 보임 |
| SIG011 | P→R→N→D→P와 추가D/P | 준비 상한19~119ms, 소비자 최대120ms | 15~96ms | 이번6회 값/200ms 확인 |
| SIG003 | LEFT→OFF→RIGHT→OFF | 준비 상한43~100ms, 소비자 최대102ms | 22~64ms | 이번4회 값/200ms 확인 |
| SIG012 | ON→OFF | 준비 상한55/53ms, 소비자57/55ms | 41ms | 이번2회 값/200ms 확인 |
| SIG013 | 25→27.7777778m/s 요청, SDK25.0→27.777779m/s, Hub90→100km/h | 준비 상한61/88ms, 소비자62/89ms | 46/75ms | 이번2회 오차≤0.1km/h·200ms 확인 |
| SIG004 | ready→longitudinal→not_ready→ready 공개 입력 | Console 응답은 수락했지만 SDK ACTIVATED/Hub true를 확인하지 못함. 마지막ENABLED→false만 확인 | 마지막32ms | 전체모드·200ms **미검증**. ready를 활성/미지 모드를off로 추정하지 않음 |
| SIG005 | 조수석occupied true/false | raw Boolean은 확인 대상이나 정상 착석/null/UNKNOWN을 복원할 수 없음 | 정상값 지연 판정 제외 | 의미 보류 유지 |

차속 최장 입력 하한15,115,690ms에 대해 AAOS 원천15,115,900ms, SDK 수신15,115,903ms, 준비15,115,949ms, 소비자15,115,950ms였다. 따라서 이번 초과는 앱 수신 이전에도 발생했다. 공개 Console→DDS→VHAL 중 어느 단계인지까지는 분리하지 못했으며 앱의 변환/Hub만 고쳐 전체200ms를 보장한다고 주장하지 않는다. 조작 기준이 UI 클릭 시각인 전체 TC와도 측정 시작점을 명시해 구분한다.

## 저장 격리와 서비스 시험 방식

제품 서비스의 저장 Context 기본값은 기존this 그대로다. debug 전용 하위 서비스만 `role1-final-20261109-` DB·prefs·진단 경로를 쓴다. SDK·Clock·서비스 수명·감시/저장 코드는 같은 경로를 사용한다. release manifest에는 이 fixture 서비스가 없다. 다른 담당 기능이나 제품 UI를 조립하지 않았다.

SDK 기존 소유권을 잡은 실제 서비스 시작은 수집/타이머0으로 거부했고, 해제 후 재시작은foreground=true·starts1·타이머1이었다. 중복 START 뒤에도starts1, 종료 뒤SDK owner=null이다. [runner](evidence/2026-11-09-role1-remaining/service-ownership-runner.txt)와 [결과](evidence/2026-11-09-role1-remaining/service-ownership.json).

초기 알림 AppOps 방식은 기대한 거부 조건을 관찰하지 못해15초 timeout으로 실패했다. [초기 실패 원본](evidence/2026-11-09-role1-remaining/service-notification-runner.txt)을 남기고 통과에 세지 않는다. 이 결과를 제품 결함/SDK 미지원으로 단정하지 않았으며 실제 runtime 권한 철회가 확인되는 절차로 후속 확인한다.

## 실제 5분·15분·Room 결과

[runner](evidence/2026-11-09-role1-remaining/long-runner.txt)는 **1,232.686초, 1개 통과**다. 실제 SystemClock/AnchoredTripClock, 공개 Console D/P, Playground SDK→Hub→기존 TripRules→격리 Room 경로다. 가짜 신호나 배속 Clock을 넣지 않았다. 내비 화면 뒤에서도 foreground=true·starts1·timer1이었다. 호스트 전체 빌드는 이 대기 중 실행됐으며 이 실행으로 110ms 성능 개선을 주장하지 않는다.

| 실제 관찰 | P 경과 | 연속 운전 | reset flag | 최근24h |
| --- | ---: | ---: | --- | ---: |
| 첫 P 시작 | 0 | 4,200ms | false | 4초 |
| 5분 직전 | 299,000ms | 303,200ms | false | 4초 |
| 5분 이후 | 300,203ms | 4,200ms | false | 4초 |
| 재출발 후 두 번째 P | 0 | 8,500ms | false | 8초 |
| 15분 직전 | 899,099ms | 8,500ms | false | 8초 |
| 15분 이후 | 900,300ms | 0 | true | 8초 |

[원본](evidence/2026-11-09-role1-remaining/long-result.json)과 [집계](evidence/2026-11-09-role1-remaining/long-analysis.json). 운행 ID는 유지됐다. 이는 연속0/이력 유지 코어 확인이다. 쉼터149m·보상·회복·음성·기준선 실행을 호출하지 않았으며 휴식 인증 전체 성공으로 세지 않는다. 299초에서 재출발, 실제20분 쉼터 밖, P/OFF 합산, 실제24시간 창 통과도 이번 근거에 포함하지 않는다.

저장 성공 이벤트는 **126회(주기120+즉시6)**, 완료 뒤 찍힌 wall 이벤트 간격 최대 **10,857ms**다. 연속된 주기 이벤트는10,011~10,163ms였다. 상태 전환 즉시 저장이 다음 주기 기준을 재설정하므로 주기 이벤트만 뺀 뒤 두 상태 전환 사이를 잇는24초를 미저장 간격으로 부르지 않는다. 전체 이벤트와 capture 시각을 보존했다. savedAtElapsedMs는 snapshot 캡처 시각, 성공 로그는 DB transaction 반환 뒤 시각이며 정확한 fsync 순간은 아니다.

## 비정상 종료·권한·통풍

- **장시간 실행 뒤 복원:** [첫 복원](evidence/2026-11-09-role1-remaining/restore-after-long.json)에서 연결 전 UNKNOWN·같은 ID/저장 연속값을 확인했다. 원본 snapshot과 활성 상태를 구분한다.
- **실제 force-stop:** 격리 DB에 단계CHECK·baseline 참조·위치를 시험 metadata로 채웠다. 다른 담당 판단기를 실행한 결과가 아니다. 실제 D 입력 뒤13초 운행해 자동 저장을 만들고 외부 am force-stop으로 종료했다. [종료 직전](evidence/2026-11-09-role1-remaining/kill-progress.json), [예상된 runner 종료](evidence/2026-11-09-role1-remaining/kill-intentional-termination-runner.txt), [새 PID 복원](evidence/2026-11-09-role1-remaining/restore-after-kill.json), [대조](evidence/2026-11-09-role1-remaining/force-stop-analysis.json). PID5656→8445, 종료 전13,194ms·저장/복원10,894ms로 **차2,300ms≤10초**다. 저장 snapshot의10종 필드를 대조했고 활성 상태는 UNKNOWN이다. 종료 공백을 P/OFF로 이어 계산하지 않았다.
- **실제 알림 권한 철회:** 최초 granted 상태에서 POST_NOTIFICATIONS를 철회하고 새 runner에서 DENIED를 확인했다. 서비스는 시작 불가 IllegalStateException·수집/타이머0·requested=false로 거부했다. 재허용 뒤 정상 foreground/SDK 수집으로 복구됐다. [최종 runner](evidence/2026-11-09-role1-remaining/notification-final-runner.txt), [결과](evidence/2026-11-09-role1-remaining/notification-final.json). 앱의 알림 가시성 조건 거부이며 OS background FGS 제한을 만든 시험은 아니다. 원래 granted/AppOps default로 복구했다.
- **실제 차속 권한 철회:** 별도 SDK 연결에서 raw callback/관측0, Hub null/PERMISSION_DENIED·프로세스 유지를 확인했다. 재허용·새 연결에서0.0km/h/AVAILABLE·raw callback29를 확인했다. [요약](evidence/2026-11-09-role1-remaining/speed-permission-summary.json). OS 권한 철회는 프로세스를 종료하므로 동일 프로세스 안의 늦은 콜백 장애 시험과 다르다. 최초 조회/재조회를 새 센서 관측으로 세지 않는 계약은 유지한다.
- **공식 통풍 입력:** 현재0을 읽고0→1→0, 요청 직전→일치 프레임 관찰 상한 **128/105ms**, SDK 수신→프레임80/75ms, 원래0 복구를 확인했다. [결과](evidence/2026-11-09-role1-remaining/ventilation-latency.json). 50ms 관찰 polling·ms 양자화가 포함된 상한이다. 좌석 온열/설정온도와 구분한다.

## 최종 시험·보존과 남은 조건

이번 전체 자동시험 **engine333+app410=743개, 실패/오류/skip0**,81개 task 재실행으로 assembleDebug·AndroidTest APK·release manifest가 성공했다. 앞선743개와 이번743개는 별도 실행이며 합산하지 않는다. 명시적 기기 runner는 **측정·회귀6종 최종 정상 종료, 복원2회로 성공 실행7회**다. 초기 AppOps 조건 실패1회는 별도로 남겼다. 의도한 force-stop과 실제 SDK 권한 거부/복구2세션은 JUnit 통과 개수에 넣지 않는다. [집계](evidence/2026-11-09-role1-remaining/verification-summary.json). 호스트 집계기의 nested reading 키 접근 오류는 원시 기기 자료를 보존해 재분석했으며 앱 결함으로 세지 않았다.

제품 측 변경은 저장 Context 기본값을 유지한 debug 격리 seam이다. 기존 계측에는 Linux TID/소비자 시각만 추가했다. TTL·Clock·100ms·정규화·출처/최신성·관측 시각·TripRules·Room 스키마·서비스 수명 알고리즘은 바꾸지 않았다. 다른 담당 판단/개입/음성/휴식/제품 UI/60배 데모도 수정하지 않았다. 성능 수정 효과가 입증되지 않아 발행 알고리즘을 바꾸지 않았다.

[최종 보존](evidence/2026-11-09-role1-remaining/preservation-final.json): 시작의 DB/prefs/cache **94개 파일 모두 바이트 동일**, 수동 DB 운전구간1개·REAL18개 및 모든 논리 행 해시 동일/integrity=ok다. DB/SDK 바이너리/APK/키는 저장소에 추가하지 않았다. 격리 DB/시험 설정/진단은 새 경로다. debug fixture는 release manifest에 없다. 최종 설치 APK는 빌드 SHA `879939e3f61c6ac0360d92c2c5e8f62459c5075f9faba93bcb8abaf828e72709`와 같다. 장시간/지연 측정 APK는 `81bf0671ef553b0992787c82c722301a39b55f5e58a6fbbfebe591a30e0d4694`이며 최종 재설치/회귀와 구분한다. 최초 Perfetto 측정 APK의 개별 해시는 보관하지 못해 추정하지 않는다.

앱 삭제/초기화/Wipe/재부팅/커밋/push/PR/브랜치 전환은 없었다. HEAD68696cf·develop-jdw·stage0 유지. 기존 미커밋45개 중41개 파일은 바이트 동일이며 나머지는 문서와 기존 계측의 추가 보완이다. 기존 PeriodicSignalHub/SdkPublicationAudit·회귀·주기 원본은 보존했다. 최종 UI에서 **중지·콜백/조회/연결 해제·Hub 발행 중지·최종값 null**을 확인했고 서비스도 없다. 알림/차속 권한은 원래 허용, Console 제어/모드 복구·시험 heartbeat 종료·이번 adb8088 forward 제거 상태다.

**남은 조건:** 엄격110ms와 차속 전체200ms 미달, 전체 지연 원인/OS 시작 거부 조건 미확정, 실제 이동 heading/동적 내비/현재온도 변경 입력 미확인, 조향/착석 의미 보류. 실제 ignition/STR·재부팅 자동시작·P/OFF 합산·2시간/24시간·30분 누락0에는 새 기기 근거가 없다. 기존 Clock/Room 경계 시험은 별도 근거다. [ID별6상태표](../setup/ROLE1_COMPLETION_STATUS.md)를 따른다.

4번에게 확인할 질문은 다음과 같다.

1. 현재 provider가 받는 공개 이동/모의 주행 입력과 거리 감소·회전·자연 도착의 재현 순서는 무엇인가?
2. 현재 실내온도24.9→25.0을 바꾸는 공식 입력 경로가 있는가?
3. Console longitudinal 수락과 SDK ACTIVATED 수신을 연결하는 공식 활성 조건은 무엇인가?

main5195e25는 이미 병합됐으며 제품 조립은 별도 연결 대기다. 제어/설정값을 관측값으로 대체하지 않는다.

## 직접 누를 버튼과 기대값

1. 기존 AVD를 P/0에 두고 감시가 켜져 있으면 정상 **감시 중지**. 아래 명령으로 SDK 독립 화면을 열어 **차속 권한→연결**을 누른다.
2. Console **AVD Enabled/DDS ONLINE→기어D→속도80→0→기어P**, **방향지시등 좌→OFF→우→OFF**, **브레이크 ON→OFF**, **목표속도90→100**을 확인한다. Hub80/0·D/P·LEFT/RIGHT/OFF·true/false·90/100이 기대값이다. Ready를 활성true로 해석하지 않는다. 정량200ms는 화면 눈대중 대신 runner/원시 시각으로 판정한다.
3. **60초 이상→진단 저장 완료→중지**. 평균약10Hz와 별도로 최대≤110ms·누락0을 본다. 이번 환경은 엄격 기준 미달이다. 시험 제어는0/P/Off로 복구한다.
4. 실제 정차/Room은 아래 **격리 runner**를 실행한다. SDK 독립 화면은 중지 상태여야 한다. Console에서 **D약5초→P5분1초→D약5초→P15분1초**. 진행 phase에 맞춰 바꾼다. P299초에는 정차 전 값+299초,300초 뒤에는 정차 전 값, 재출발 뒤 이어 계산, 별도P900초 뒤0/같은ID/24h누적 유지가 기대값이다. 약20분 대기한다. 격리 DB는 시험 데이터로 재사용하며 이번 진단 원본은 docs/evidence에 보존했다.
5. 종료 뒤 **기어P·속도/페달/조향0**, 서비스 없음을 확인한다. 권한 거부는 앱 설정에서 권한 변경 후 SDK 새 연결로 확인한다. 거부 시 null/PERMISSION_DENIED, 재허용/재연결 시 AVAILABLE가 기대값이다. 사용자 DB와 연결된 일반 감시 화면과 격리 runner를 구분한다.

```bash
adb forward tcp:8088 tcp:8088
adb shell am start --user 10 -n com.sleepissue.awakedrive/.debug.DebugHarnessActivity \
  --ez sdkProbe true --ez sdkCapture true --es sdkCaptureFile sdk-signal-user-remaining-NEW.json
# NEW는 새 파일 이름으로 바꾼다. 상세 시각이 필요하면 --ez sdkTiming true 추가.

adb shell am instrument --user 10 -w -e role1Remaining true -e timeout_msec 1800000 \
  -e class com.sleepissue.awakedrive.data.Role1RemainingDeviceTest#actualFiveAndFifteenMinuteParkWithIsolatedRoom \
  com.sleepissue.awakedrive.test/androidx.test.runner.AndroidJUnitRunner
# 별도 터미널에서 phase 확인
adb exec-out run-as com.sleepissue.awakedrive --user 10 \
  cat cache/role1-final-20261109-diagnostics/long-progress.json
```
