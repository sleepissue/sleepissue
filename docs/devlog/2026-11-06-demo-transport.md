# 2026-11-06 후속 — 완전 관측 인계·발행 스케줄 비교

## 범위와 판정

[이전350개 시험과 성능 기록](2026-11-06.md)을 출발점으로 **1번의 DemoPlayer·VehicleSignalHub·검증 도구만** 수정했다. 다른 담당의 엔진·음성·예보·휴식 기능을 새로 통합하지 않았다. 현재 상세 계측을 켠 기기 결과는 **예상외null0 목표 미달**이며, 기능 완료라고 판정하지 않는다.

브랜치는 `develop-jdw`를 유지했다. 브랜치 전환·커밋·push·앱 제거·`pm clear`·AVD Wipe·Room 초기화는 하지 않았다. 설치는 `adb install -r`이고, '최초' 비교에 사용하는 force-stop은 프로세스만 종료한다. 기존 작업과 원본·중간 실험 결과를 보존했다.

## 전달 구조와 계측

기존 SDK의 `VehicleSignalSource.samples`, `NaviSignalSource.states`, `PowerSignalSource.events` 인터페이스를 유지했다. `HubSourceSet.complete`가 없는 SDK 입력은 기존 세 collector를 그대로 사용한다. 새 내부 `CompleteSignalSource`는 **JSON 원본 하나의 차량·내비·전원을 한 번에 인계**하고, Hub 잠금 한 번 안에서 기존 세 정규화·캐시 함수를 실행한다. 원본 여러 개를 합치거나 중간 표본을 삭제하는 방식이 아니다.

| 비교 구간 | 계측 의미 |
| --- | --- |
| PUMP_VEHICLE/NAVI/POWER_EMIT | 각 emit 호출부터 반환까지의 경과. 처리·인계·재개가 포함될 수 있음 |
| EMIT_TO_RECEIVE_* | emit 직전부터 source의 map 진입까지. SharedFlow 내부 처리와 스케줄 대기 포함 |
| HUB_RECEIVE_VEHICLE/NAVI/POWER | 잠금을 얻은 뒤 실제 채널 정규화·캐시 처리 |
| PUMP_COMPLETE_EMIT / EMIT_TO_RECEIVE_COMPLETE | 같은 원본을 한 통로로 넘기는 비교 구간 |
| HUB_RECEIVE_COMPLETE | 세 채널 캐시 처리를 감싼 구간. 채널별 구간과 중첩됨 |
| CONTROLLER_COMMAND_WAIT/BODY | 명령 잠금 대기와 명령 처리 |
| UI_CANCEL_DISPATCH + QUESTION_CANCEL_WAIT/BODY | Main의 act 본문 진입부터 worker 전달·잠금 대기·취소 적용까지. Android 입력 큐에서 act 시작 이전 대기는 제외 |
| SCHEDULER_POLL | 다음 원본/Hub 시한 조회 경과. yield dispatch 자체의 CPU는 포함하지 않음 |

구간 경과는 주입한 nanoTime으로 측정한다. **중첩 구간을 더해 전체 CPU로 해석하지 않는다.** Android의 `processCpuMs`는 시작 버튼 처리부터 종료 캡처까지 프로세스의 모든 스레드 CPU 합이며 UI·JIT·GC도 포함한다. 한 코어 기준으로100%를 넘을 수 있다. Android 구간별 할당은 측정하지 않았고, JVM ThreadMXBean 할당과 Android process allocation을 구분한다.

`SharedFlow.emit()` 반환은 subscriber의 cache 처리 완료를 보장하지 않는다. 따라서 원본 audit뿐 아니라 Hub vehicleCallbacks18,001(원본18,000+종료 무효화1), 채널 처리 수·관측 시각·실제 값까지 함께 대조했다.

## 적용한 변경

- Player는 load에서 원본 컨테이너를 복사·동결하고 기존 stamp와 같은 DEMO/SCENARIO/원본t metadata를 한 번 확정한다. canonical 품질 객체는 재사용한다. 재생 중 품질306,000개·map36,000개·채널 객체 복사를 반복하던 비용을 줄였다. 준비된 quality의 실제 수신시각은 **항상null**이며 Hub가 수신할 때 새로 부여한다.
- 값·원래null·availability·quality key 집합·speedUnit·updatedFields·원본 순서를 유지한다. samples/maps/set/배속 목록을 동결해 Repository나 공개Flow의 가변 alias가 재생을 바꾸지 못하게 했다.
- debug 자동 pump는 실제2ms의 soft budget과 최대4개 원본 예산을 함께 사용한다. 대기 명령이 생기면 첫 완전 관측 경계에서 반환한다. 잔여 원본은 yield 후 이어간다. **단일 emit 대기까지 강제 중단하는 절대 지연 상한은 아니다.**
- 원본과 발행 시한은 독립적이다. Hub가 `nextPublicationAtMs`와 그 시간축을 제공하고, Controller는 다음 원본과 Hub 시한 중 먼저 오는 쪽을 기다린다. 원본100±1ms에서도 Hub100ms 시한을 보존한다. 마지막1ms 미만의 대기는 최대64회 yield 후 반드시 delay로 돌아간다. 멈춘 시험 시계에서 무한 반복하지 않는다.
- 상세행이 저장될 때 비어 있는 계측 맵만 보충해100ms 통계 갱신/상세 표집의 위상차를 없앴다. replay 시작 단계 snapshot을 따로 저장해 세션별 차분을 얻는다.
- 시계 역행 회귀에서 Hub 종료 진단이 실패한 Clock을 다시 읽는 잠재 문제를 발견했다. 정지·부모 취소·시계 오류의 rate 진단은 실제 마지막 보관 시각의 `lastKnownSnapshot()`을 사용한다. 후보는 무효화하고 FAILED UI는 Player의 마지막 안전 위치를 사용한다. **정상 재생의 최신성 계산과 시간축은 그대로**다.

TTL1,000ms·원본 시각·REAL 우선·DEMO 좌표쌍 예외·원천 Hz는 바꾸지 않았다. 마지막 값으로 덮기·샘플 생략·과거 발행 프레임 보충도 하지 않았다.

## JVM 비교와 준비 비용

실기와 같은 `demo_perf_validation.json`(SHA256 `ac6a3a1c5d395778d74fcc35c48870987a28e0d43198abdee4f7cd69714be0de`)을 사용한다. 원본18,000개·시나리오1,800,000ms·60배다. mode×일반/취소×first/repeat의8run을 같은 가상1ms tick으로 비교했다. 이는 실제600Hz 성능을 증명하지 않는다. JVM의first는 새 Controller의 첫 재생이며, 독립 JVM cold start를 뜻하지 않는다.

V1의 반복 일반에서 세 emit 합36.84µs→완전 관측16.27µs, 수신 map 도착 합17.45→6.01µs, Hub 캐시 합6.38→6.07µs였다. 반복 취소도 emit33.22→14.27µs, 도착15.49→5.51µs, 캐시5.86→4.91µs였다. JVM에서는 cache 계산보다 인계·재개 비용 차이가 컸다. 이 경과 구간들은 중첩된다.

로드 준비는 최종 JVM에서 약20.9~62.5ms·21.67MB의 할당을 사용했고 replay의 DEMO_PREPARE 호출은0이다. map/list/set·차량/내비/샘플 사본을 재생 수명 동안 유지하므로 준비 비용과 유지 메모리 대가가 있다. 강제GC 없는 heap 차이는 GC 때문에 음수~100MB 이상으로 흔들려 정확한 유지 객체량으로 쓰지 않았다. 전체할당0·전체로드비용0이라고 표현하지 않는다.

## 같은 AVD의 기기 비교

창이 있는 동일 `Pleos_Connect_2_1_0` / emulator-5554 / API34 / RAM4GB / 사용자10을 계속 사용했다. 가짜REAL은OFF다. 각 mode×조건마다 **데이터를 보존한 새 프로세스 첫 재생→같은 Controller 재로드 반복**으로 측정했다. ART 설치/프로필 캐시는 지우지 않았다. 일반은 실제약30초, 취소는 시작5초 뒤 가짜 안내를 시작해 약1초 뒤 취소했다. 실제 유지 시간과1→60배 복귀는 상세에서 검증했다. 기기 재생 중 Gradle을 실행하지 않았다.

V1은 재생 중stamp와 두 전달 경로의 비교8run, V2는 load 준비+adaptive 대기+상세 계측 보완 조합의 비교8run이다. **V1→V2의 개별 변경 효과를 단독 인과로 해석하지 않는다.** V2 안의 두 전달 모드는 같은 준비/스케줄을 사용한다. 이후 최종 factory 경로도 추가 확인했다. 원본·화면·런타임 로그·환경 지표는 `src/android/app/build/reports/demo-transport-20261106/`에 있다.

V2의 실제null 발행과 Hub 발행/누락:

| 조건 | 세 Flow null발행 | 완전 관측 null발행 | 완전 관측 Hub 발행 / deadline누락 |
| --- | ---: | ---: | ---: |
| 일반 최초 | 10,999 | 3,879 | 13,613 / 4,387 |
| 일반 반복 | 850 | 261 | 12,767 / 5,233 |
| 질문취소 최초 | 9,556 | 3,281 | 13,693 / 4,307 |
| 질문취소 반복 | 2,617 | 267 | 13,003 / 4,996 |

모든 실행은 COMPLETED·차량/내비/전원 각18,000개·누락/중복/역행/미등록시각0이다. 세 채널 원본 시각 해시는 예상 `39f6168ad37c3def`와 같고 종료 무효화는 각1회다. Hub callback18,001과도 일치한다. 관측 시각의 격자·비미래·비역행, AVAILABLE 값과 해당 원본, 위경도 원자성도 대조했다. final tail0~1과 Hub missed deadline은 원본 누락으로 세지 않는다.

V2 반복 일반은 emit598.1→363.2µs, 반복 취소620.2→371.2µs로 줄었다. 프로세스 CPU는27.879→24.451초 /29.403→25.761초, 프로세스 할당은374.4→339.2MB /377.7→344.5MB였다. 그러나 완전 관측의 발행 횟수는 해당 세 Flow보다 적고 deadline 누락은 더 많았다. V1→V2 완전 관측의 warm null도192→261,252→267로 개선하지 못했다. **모든 지표가 개선됐다고 주장하지 않는다.**

최종 DEFAULT_LANE factory 재확인에서는 일반 최초/반복 실제null5,319/426회·deadline3,904/5,189회, 취소 최초/반복3,901/479회·deadline4,461/4,951회가 남았다. 핵심 경로가 같은 추가 코호트이며 이전 숫자와 합산하거나 최선 값만 고르지 않는다.

상세 타이머만 끈 최종 대조4run은 다음과 같다. 원본감사·null/deadline 계수·trace·100ms 화면 갱신·프로세스CPU/할당 측정은 유지했다. 모든 run은 COMPLETED이며 각 채널 원본18,000개, 누락/중복/역행0과 질문1→60배 복귀를 확인했다. 비어 있는 구간별 stageMap은 **미측정**이며 비용0이라는 뜻이 아니다.

| 조건 | 상세 타이머OFF 실제null발행 | Hub 발행 / deadline누락 |
| --- | ---: | ---: |
| 일반 최초 | 1,674 | 13,095 / 4,904 |
| 일반 반복 | 76 | 12,869 / 5,131 |
| 질문취소 최초 | 2,678 | 13,053 / 4,946 |
| 질문취소 반복 | 100 | 12,977 / 5,022 |

계측ON보다 실제null이 감소했지만 **OFF도0이 아니고 발행 누락도 남았다.** 전체 진단/trace/UI를 제거한 실험은 아니므로 이 결과로 각각의 부하 기여율을 구분할 수 없다. 두 코호트는 순차 실행이어서 ART/JIT 상태와 환경 차이도 섞인다. 기본 상세 계측은 계속ON으로 두고, OFF 대조를 성공 기준으로 대체하지 않았다.

프로세스 CPU는ON→OFF 일반 최초30.997→25.756초·반복25.060→21.640초, 취소 최초31.655→27.084초·반복26.381→22.491초였다. 할당은 일반355.02→316.47MB·339.77→316.80MB, 취소350.39→316.95MB·345.28→319.60MB였다. 최종ON 취소의 UI worker 전달+명령 잠금 대기+처리 합은 최초0.980ms·반복0.466ms다. Android 입력 큐에서 act 본문 진입 이전 대기는 포함하지 않으며, 단일emit/스케줄러 대기의 절대 상한을 보장하는 수치가 아니다. OFF의 해당 단계 지연은 미측정이다.

이전350개 기준의 반복 일반79/156회·취소135/147회와 비교해도 현재 기본 경로의 실제null이 개선됐다고 판정할 수 없다. 이번에는 단계 계측과 실행 통로가 추가되어 이전 수치와의 차이를 단일 코드 변경 효과로 분해할 수 없다. 동일 계측 내 세 Flow↔완전 관측 비교와 구분하여 보존한다. 최초 실행의 큰 지연과 반복 실행의 간헐STALE 모두 미해결이다.

## 전용 Handler 실행 통로 실험

기본값은 `DEFAULT_LANE`이다. `HANDLER_IMMEDIATE`는 debug 옵션이며 HandlerThread 기본 우선순위를 유지한다. 같은 Looper의 일반 재개는 추가post를 생략하지만 yield는 Handler.post로 재예약한다는 것을 실제 APK의 coroutines1.10.2와 로컬 JAR에서 확인했다. [Handler 구현](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/ui/kotlinx-coroutines-android/src/HandlerDispatcher.kt), [yield 구현](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Yield.kt), [SharedFlow 구현](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/flow/SharedFlow.kt).

같은 완전 관측의 Handler4run은 일반 실제null4,471/205회, 취소3,420/371회였다. DEFAULT 대비 네 칸 중 일반 반복만 줄었고 CPU·할당도 일관된 개선이 아니었다. 따라서 **Handler를 기본 경로로 채택하지 않았다.** 모든 세션/명령/저장 Job을 종료·join한 뒤 close(quitSafely)→IO에서thread join 순서로 정리한다. 스레드 종료 실기 확인 결과도 별도 산출물에 보존한다.

종료 검증은 **같은 살아 있는PID15927**의 `/proc/PID/task/TID/comm`을 읽어 수행했다. 새task의 기본 미로드 데모로 Handler 데모를 덮어 `onStop`을 유도했다. Room을 만들지 않으며 같은 프로세스를foreground에 유지하는 조건이다. 메인TID가 남은 정상 숫자TID 목록 안에서 Handler worker가1개(TID16043)→0개가 됐다. `handler-lifecycle-proc-comm.json`에 보존했다. 앞선 `ps NAME`은 프로세스명을 표시해 무효였고, Automotive의HOME/BACK은 화면을RESUMED로 유지했다. `finish()` 시도는 원래PID 자체가 사라져 worker의 정상 종료를 구분하지 못했다. 이 시도들도 무효 사유와 함께 별도로 보존했다.

## 코드 비용과 환경 영향의 구분

확인한 사실:

- V1 separate 최초는 약19.249초, complete 최초는9.579초 동안 수신을 계속하면서 뒤처졌다. 대표 관측 나이175,040/94,060 시나리오ms인데 수신 나이는1/4ms였다. 단일 입력 중단만의 형태가 아니다.
- 같은 first replay 로그의 명시적 GC 정지 합계는95/104µs였다. 초기 Choreographer/Surface 경고는 재생17초 이상 전이다. 이 로그만으로 수초 규모의 누적lag를 GC 탓이라고 설명할 수 없다.
- Host CPU PSI는first/repeat 비슷한 수준이고 메모리 PSI 평균은0.00이었다. 일부 run에는 작게 누적된stall이 있으므로 '모든 환경부하가 절대0'이라고 하지 않는다. Handler cancel반복에는 GC pause 약17.9ms도 있어 일부jitter 기여 가능성을 배제하지 않는다.
- V2 complete 반복 일반의 emit363µs 안에는cache184µs/도착대기82µs 등이 포함된다. 별도로 Hub publish 시도 평균247µs(총6.186초), snapshot 통로91.7µs(총1.915초)가 남는다. 이는 wall 경과이고 구간도 중첩된다. 합산 CPU 또는 특정 함수의 단독 인과라고 보지 않는다.

**[가설]** JIT/초기 실행, source/Hub의 정규화·후보·품질 객체/Clock 조회와 스케줄링·계측/화면 비용이 함께 기여한다. 각 기여율은 확정하지 못했다. Source handoff만 바꿔도 남는 비용이 크다는 것은 확인했으므로, 환경 탓만 하거나 TTL 확대/원본 재시각으로 성공 처리하지 않는다.

## 회귀·직접 확인

기존350개를 포함한 **engine149+app227=376개**, 실패/오류/skip0 및 `assembleDebug` 통과. 완전 관측/개별 동등성·소스 상태/실패·TTL/1119ms 원본·2ms 수신의STALE·원본감사·가변alias 차단·부분quality·준비후실제receipt·재로드·max4·첫표본경계취소FIFO·정지시계boundedwait·100/101ms 발행독립·시계오류정리를 검증했다. 성능목표 미달과 단위시험 통과는 별개다.

최종 로그는 `src/android/app/build/reports/demo-transport-20261106/final-build.log`, XML 집계와 설치APK 대조는 `final-validation.json`이다. 로컬 빌드와 기기 설치APK SHA256은 `1607b3f1558db316a8be3245587cdc68b2d8c3ece67a8a81e044a64fa27b25ee`로 같다. Room 전후 백업을 별도 디렉터리에 풀어 논리 덤프를 비교했으며 SHA256 `c9961514d35261a0aec539d34c3bb0123d8756c24f9f01625bef0a80dfd23d9f`로 같다. `trip_checkpoint`·`drive_segment` 각1개를 포함한 모든 테이블 행수가 같고 `integrity_check=ok`다. 원본 DB에 복원·덮어쓰기는 하지 않았다. 증거는 `room-preservation-final.json`, 보존본은 `/tmp/awakedrive-transport-preserve-20261106/`에 있다.

기본 화면은 기존대로 **JSON 데모·배속 검증 → 60배 장기 예제 로드 → 시작**이다. 질문 비교는 **① 가짜 안내 시작 → 질문 취소**, 완료 뒤 **성능 trace 저장**을 누른다. 실제TTS/STT·SDK 동시입력 시험은 아니다. 완료/실패 저장 상태는 화면에 남고 종료자료는 별도 자동 보존한다.

두 전달 경로를 지정하려면 다음 debug 진입을 쓴다. Room ViewModel을 만들기 전에 데모 화면으로 연결한다.

```bash
adb shell am start --user 10 -n com.sleepissue.awakedrive/.debug.DebugHarnessActivity --es demoTransport SEPARATE_FLOWS
adb shell am start --user 10 -n com.sleepissue.awakedrive/.debug.DebugHarnessActivity --es demoTransport COMPLETE_OBSERVATION
```

Handler 대조는 위 명령에 `--es demoWorker HANDLER_IMMEDIATE`를 붙인다. 기본은DEFAULT_LANE이다. 상세 구간 타이머만 끄는 대조는 `--es demoDetailedTiming off`를 붙인다. 이 경우 빈 stageMap은 **미측정**이고 원본감사·null/deadline·trace·UI·프로세스CPU/할당은 유지된다. 해당 대조는 상세 계측ON 결과와 분리한다.

수치·원본파일SHA·프로세스/세션·시작/종료경계·중간 실패·최종검증은 [기계 판독용 분석 JSON](2026-11-06-demo-transport.json)에 보관한다. 현재 판정은 **전달/스케줄 개선안과 검증은 수행했으나, 최초·반복의 최신성·완전발행 목표는 미달**이다.

마지막에는 기본 완전 관측·DEFAULT_LANE·상세 계측ON의 미로드 화면으로 복귀했다. 저장 버튼은 누르지 않아 cache의 마지막 수동 저장본은 질문취소 반복COMPLETED(원본18,000개) 그대로 남아 있다. 자동 종료 보존본과 호스트로 확보한 각 실행별 상세도 유지한다. 다음 성능 작업은 정상 경로의 정규화·후보/품질 생성과 발행/진단 계산의 남은 비용, 첫 실행 JIT/스케줄 지연을 더 분리해야 한다. 현재 증거로 코드 비용과 환경의 각 기여율을 확정하지 않았다.
