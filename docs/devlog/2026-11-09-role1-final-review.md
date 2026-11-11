# 2026-11-09~10 — 1번 현재 변경 최종 점검

develop-jdw / `68696cf158f5cc94ea3b257b87cc2b01bba4a122`에서 시작해 11/10 최종 정리까지 같은 브랜치·HEAD를 유지했다. AGENTS.md, PLT013 기록, 남은 기기 검증 기록, 현황표와 현재 코드·시험을 대조했다. 새 기능·추가 성능 실험·30분 반복·설치·기기 입력은 하지 않았다. 커밋·stage·push·PR·브랜치 전환·앱 삭제/초기화는 없다.

## 검토 범위와 보존

시작 미커밋은 추적13개+새132개=145개, 4,264,561bytes였다. 추적 diff뿐 아니라 새 debug/AndroidTest/회귀 코드·기록·JSON/압축 진단·분석기도 검토했다. 시작 목록/해시는 [review-start-files.json](evidence/2026-11-09-role1-final-review/review-start-files.json)에 두고, 작업 전 코드/문서 사본과 diff는 `/tmp/awakedrive-role1-review-wxoygxvx/initial/`, `initial.diff`에 보존했다. 기존 계측을 다시 만들지 않았다. [preservation.json](evidence/2026-11-09-role1-final-review/preservation.json)은 기존 파일의 변경/동일 여부와 삭제0을 기록한다. 기존 진단 원본은 바이트 동일하다.

이번에는 adb·Console·앱을 조작하지 않아 사용자/격리 DB·권한·설정·내비 경로를 변경하지 않았다. 이전 PLT013 종료 시111개 기존 파일의 복구 확인은 [당시 final-checks.json](evidence/2026-11-09-plt013/final-checks.json)의 근거이며 이번 재시험으로 세지 않는다.

## 명확한 결함과 최소 수정

| 발견 | 변경 | 검증과 한계 |
| --- | --- | --- |
| 격리/사용자 서비스가 알림 ID4101·채널real_trip_monitor를 공유. SDK 소유권 거부 전 알림이 생성될 수 있어 알림 표시·채널 설정이 섞일 수 있음 | 기본 서비스 ID4101/채널 유지. protected override 지점으로 debug만 ID4102/role1_isolated_trip_monitor 사용. 기존 기기 알림 runner도 새 상수 조회 | 새 MonitorNotificationIsolationTest 1개로 두 ID/채널의 분리 확인. 이전 PendingIntent 기기회귀는 이전 코드 근거, 이번 ID/채널의 실제 기기 동작은 미재실행 |
| 계측 LOCK_ACQUIRED/LOCK_RELEASING callback 예외 시 mutex unlock이 생략될 수 있음 | 잠금 획득 이후 전체를 try/finally로 감싸고 release callback 실패에도 중첩finally에서unlock | 새 timingObserverFailureCannotLeaveTheStateMutexLocked 1개: 획득/해제 두 예외를 각각 전달한 뒤 stop이 잠기지 않는지 확인 |
| timingObserver=null이어도 호출부 nullable Long 인자로 due/sequence를 포장 | 내부 helper 인자를 primitive long으로 유지, observer 존재 확인 뒤 nullable 계측 메타데이터 구성 | 최종 javap의 helper `(HubTimingStage,String,long,long)`와 null→return 뒤 Clock/nanoTime/Long.valueOf/HubTimingPoint 순서 확인. 성능 개선 수치는 측정하지 않음 |

이번 수정은 타이머 교체·간격 단축·TTL/Clock 조작·과거 프레임 채우기가 아니다. 선택적 계측 API의 안전성과 OFF 경로만 보완했다. 정상 콜백에서의 수집·버스 소비·저장/TripRules·품질/시각 계약은 바뀌지 않았으므로 기존 단일30분 근거를 재사용한다. 새 알림 식별자의 기기 회귀 미실행은 별도로 남긴다.

## 일반 실행과 격리 경로 확인

- timingObserver가null이면 helper는 먼저 반환한다. 추가 계측점 객체·시계 읽기·인자 Long 포장은 없다. stage enum/상수 문자열·함수 호출과 null 분기는 남으므로 비용0이라고 주장하지 않는다. [최종 timing 바이트코드](evidence/2026-11-09-role1-final-review/timing-bytecode.txt).
- PlaygroundMonitorInput의 sampleObserver=null은 원래 hub.samples를 그대로 반환한다. onEach wrapper·추가 collector/Job·계측 파일 I/O를 만들지 않는다. 선택적 소비 계측은 별도 소비자가 아니라 실제 runtime collector에 붙는다. [samples 바이트코드](evidence/2026-11-09-role1-final-review/samples-bytecode.txt).
- 사용자 서비스의 기본 repository는 기존 RoomTripRepository, 입력은 기존 PlaygroundMonitorInput이다. debug override에서만 저장 감사 wrapper를 쓴다. 새로운 상시 루프/타이머는 없다.
- DebugIsolatedMonitorService.storage는 applicationContext도 자기 wrapper로 반환하고 DB/openOrCreateDatabase/SharedPreferences를 `role1-final-20261109-`로 분리한다. cache도 별도이며 연속성 감사는 새 `plt013-runId` 하위에 쓴다. 사용자 DB 이름·Room schema·설정은 바꾸지 않았다.
- 알림 중지는 Intent(this, javaClass)라 자기 서비스를 대상으로 한다. debug contentIntent는 Room 없는 DebugSdkProbeActivity, 기본은 기존 RealTripMonitorActivity다. 알림 식별자/채널도 이제 분리했다. SDK는 공통 PlaygroundSdkOwnership lease로 단일 소유권을 유지한다.
- debug manifest에만 DebugIsolatedMonitorService/계측 화면이 있다. 최종 release manifest에 `.debug.` 구성요소·시험 Activity가0개다. 실제 서비스는 specialUse이며 FOREGROUND_SERVICE/SPECIAL_USE 권한과 일치한다. 4번 제품 제어 권한은 이미 main에서 들어온 권한이므로 release에서 제거하지 않는다. [manifest 목록](evidence/2026-11-09-role1-final-review/manifests.json).

## 최종 회귀 결과

최종 primitive 인자 수정까지 반영한 코드에서 다음을 실제 실행했다. 첫 전체 성공 뒤 해당 수정이 생겨 전체를 한 번 더 실행했으며, 아래745는 마지막 실행 수다. 두 번의 합계로 세지 않는다.

```bash
cd src/android
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:-TieredCompilation' \
  --max-workers=2 --rerun-tasks \
  :engine:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:processReleaseMainManifest
```

| 대상 | 이번 최종 결과 | 이전 근거와 구분 |
| --- | --- | --- |
| engine JVM | 333, 실패0/오류0/skip0 | 이전333과 별개로 강제 재실행 |
| app JVM | 412, 실패0/오류0/skip0 | 이전410+새 회귀2 |
| 전체 | **745, 실패0/오류0/skip0** | 이전 전체743·선별31·집계기9는 이전 기록 |
| debug APK | 성공 | SHA256 `2307ea5b69c7e21493bf12970f81eecdccaa756bb0c532cd14ef9cdf8e1cb092` |
| AndroidTest APK | 성공(빌드만) | SHA256 `2693b020b348bf97d3b46051a022f4bfef0225cde0d2b99b48d35acc3582df8c` |
| release manifest | 성공, debug 구성0 | release APK 서명/제출 시험 아님 |
| 기기/성능 | 이번 실행0·설치0 | 이전30분1800.531초/18005프레임 결과 유지, 새 APK 기기검증으로 오인 금지 |

마지막 BUILD SUCCESSFUL은1분28초·81개 task 실제 실행이다. [전체 로그](evidence/2026-11-09-role1-final-review/full-tests-build.txt), [XML 집계·산출물 해시](evidence/2026-11-09-role1-final-review/test-results.json). SIG001의110ms·SIG002의전체200ms 미달과 SDK 입력/의미 보류는 자동시험 성공으로 바뀌지 않는다. 추가 lint·release APK 빌드·connectedDebugAndroidTest는 이번 요청 범위의 결과에 포함하지 않는다.

## 원문 TC와 추가 내구성의 경계

읽은 원본은 `/home/a/Downloads/AwakeDrive_SRS_TC_v0.2.xlsx`(SHA256 `2ea8c6d0baf3040f146fbbab0d87883f12108354411ffaad1f8e6fb1e95e69da`)의 SRS/TC/메시지 계약과 저장소 간트다. 공식 시트의 Not Run/초안 승인 상태는 바꾸지 않았다. 아래는 현재 코드 증거와의 대조다.

| 항목 | 원문 요구/방법 | 최종 구분 |
| --- | --- | --- |
| SIG001/002 | 실제 발행 간격≤110ms, 전체 차속 입력 반영≤200ms | 여전히 미달. 평균10Hz·누락0·SDK 이후46ms로 대체 판정하지 않음 |
| SIG007 | 가짜 SDK capability=false 에뮬레이터에서 조향·착석·통풍 각각null/비활성화로그1행/프로세스 유지 | 필수 TC의 실행 가능한 미검증 조건. 다음 작업으로 선정 |
| SIG010 | 중앙 외부 단위 정규화+engine 변환식0건 코드 검사 | 정규화 시험은 완료. TripRules/TripSnapshotValidator의 내부ms→sec와 음성 DistancePhrase의 표시m→km가 존재해 문자 그대로0건 기준은 미충족이다(범위 해석 대기). 외부 경계 변환과 내부 투영/표시의 검사 범위 의미 확인 필요. 3번 표시 코드를 임의 재설계하지 않음 |
| TRP001/009 | 10초±1초 저장·10종, 저장 복원. TC는 app 단위/메모리Room·Clock 허용 | 기존 자동/기기 근거와30분저장으로 해당 조건 확인. 정확한fsync 순간·모든 외부 최신 모듈 상태 전체제품은 별도 |
| TRP002 | 시동OFF→ON 절전복귀, 동일PID·단계 유지·운행규칙 처리. API 시트에는 Suspend-to-RAM 명시 | 필수 실제입력은 현재 AVD config없음. SRS 비고는 에뮬레이터 재현 불가 시 DMO005 대체시험 허용(5번 연결). 화면꺼짐/논리Clock을 실제STR로 표시하지 않음. 별도 STR 강제명령/반복횟수 요구를 추가하지 않음 |
| TRP003 | 상태 저장 뒤 실제adb reboot, BOOT_COMPLETED 시작1회·저장10종 일치 | 실제 실행 가능한 필수 기기 TC. 과거 부팅 보조/force-stop 복원만으로 정확한 최신 일괄 대조 Pass 아님. 절차의‘7개’와 기대값‘10종’ 불일치는 SRS/기대값10종을 보수적으로 적용 |
| TRP005~008·010~012 | JVM Clock/논리 입력 TC. 2시간 경계·날짜/최근24h 창 포함 | 기존 회귀/Room 경계와 실제5/15분 표본을 구분. 실제2시간/24시간 대기·모든 논리 경계의 벽시계 대기는 추가내구성이며 새 필수 완료조건 아님 |
| PLT013 | 내비 화면 뒤30분 SignalFrame누락0·유형/권한 일치 | 이전 단일30분의 순번/실제 소비/deadline 누락0·manifest로1번 조건 확인. 판단/마이크 제품연결은 별도. 시작/타이머 개수로 누락을 대신하지 않았음 |
| OOM/메모리 압력 | 원본 전체에서 OOM/메모리 부족을 별도 역할1 TC로 지정하지 않음 | 추가내구성. 아직 안 했다는 이유로PLT013 필수 실패/새 완료조건으로 만들지 않음 |

31개 ID의 최신 분류·실제 근거·남은 조치는 [요구별 최종 표](../setup/ROLE1_COMPLETION_STATUS.md#요구별-최종-상태)에 모두 기록했다. 과거 기록의‘실제24시간/2시간 미수행’은 사실이지만 그 자체가 JVM TC 미완료를 뜻하지 않는다.

간트 task10은SIG001~017, task11은TRP005~008·010~012, task12는TRP001~004·009/PLT012013으로 대조했다. task12의‘FGS·부팅 PR15 머지 대기’, task10의‘SIG00160초 TC 남음’은 현재 코드/기기 근거와 맞지 않는 낡은 메모다. main5195e25는 이미 c39bcca로 병합돼 있다. 간트의 개입(task32)/데모(task53) 제목·allocation에는1번 배정이 남아 있지만 현재 사용자 지시에 따라 개입3번·DemoPlayer/60배5번으로 인계한다. 간트 원본·다른 담당 완료율을 임의 수정하지 않았다.

## 다음 작업 하나: TC_SIG_007의 capability=false 기기 모의

**이번에는 시작하지 않았다.** 현재 환경에서 공개 실제 이동/전원 입력이나 다른 담당 제품 조립 없이1번 혼자 끝낼 수 있는 가장 작은 미검증 TC다. 현재 권한 철회 시험은 capability=false 세 신호 시험의 대체가 아니다.

- 입력 조건: AVD v2.1.0/API34, P/속도0, 기존SDK/감시 정상중지·소유권 해제. 명시적 debug AndroidTest에서 가짜 checkXXXCapabilities의 조향·착석·통풍 각각false를 주입한다. 사용자/REAL DB를 열지 않고 실제 권한·Console 값은 바꾸지 않는다.
- 필요한 준비: 기존 wrapper/허브 회귀를 재사용해 기기용 가짜 SDK fixture와 명시적 runner만 보완한다. **현행 UI에는 capability=false 버튼/해당 runner가 없다.** 존재하는 명령인 것처럼 안내하지 않는다.
- 완료 기준: 세 하위조건 각각 capability=false 호출 증거, getter/구독 미호출, 해당 Hub필드null·UNSUPPORTED, 비활성화로그1행, 같은 앱PID·크래시0. 실제 Hub 프레임을 확인하고 조향/착석의 의미 보류null을 성공으로 세지 않는다. 종료 후 fixture/SDK소유권 정리와 DB·설정 보존 확인. 이를 실제 SDK 미지원으로 일반화하지 않는다.
- 직접 확인 순서(다음 작업 준비 후): **P/0 → 켜진 감시의 감시 중지 → SDK 화면 중지 → 명시적 capability 모의 runner 실행 → 조향/착석/통풍 세 결과의 raw false·null/UNSUPPORTED·로그1행·PID 대조 → 정상 종료**. 가짜값 입력은 Console 버튼이 아니므로 버튼만으로 현재 TC를 실행할 수 없다.

## 현재 직접 확인 가능한 버튼과 기대값

이번 리뷰는 새 APK를 설치하지 않았다. 아래는 기존 설치본의 읽기/정상종료 확인이며 이번 수정의 기기 Pass를 뜻하지 않는다. 새 APK 교체설치가 필요한 때에도 기존 DB·진단 보존과 정상중지부터 수행한다.

1. Console에서 **P/속도0**과 현재 사용자 경로/감시 상태를 먼저 확인한다. 기존 사용자 경로는 건드리지 않는다.
2. 감시가 켜져 있으면 해당 감시 화면의 **감시 중지**를 누르고 수집/타이머0·SDK소유권 해제를 확인한다. 사용자 DB 초기화 버튼을 누르지 않는다.
3. **AwakeDrive 개발 검증 → Playground SDK 실시간 검증 → 차속 권한(필요할 때만) → 연결**. Room 없이 SDK READ/품질을 확인한다. 기본 최초조회/재조회는 원천 관측 카운터를 늘리지 않는다.
4. **CABIN_TEMP_C**의 현재온도 READ·AVAILABLE을 확인한다. Temperature Set을 바꿔 현재온도 변경 시험을 대신하지 않는다. 새 경로 요청·이동·성능측정은 이 리뷰 확인 절차에 없다.
5. SDK 화면 **중지**. 최종 수집/Hub중지·표시null이 기대값이다. 알림 ID분리/예외회귀는 이번745개와 소스 검토 근거이며 새 APK의 기기 회귀는 아직 미실행이다.

## 커밋할 변경 범위(이번에는 커밋하지 않음)

| 범위 | 포함할 내용 |
| --- | --- |
| 기존SIG 계측 | HubTimingPoint·Periodic/VehicleSignalHub·SdkPublicationAudit 및 회귀·debug 선택계측 화면/recorder. 실패한 전용타이머는 없음 |
| 기존 격리/장시간 검증 | DebugIsolatedMonitorService/ContinuityAudit·PlaygroundMonitorInput·RealTripMonitorService·debug manifest·Role1Remaining/Plt013 AndroidTest |
| 이번 최소 보완 | 알림ID/채널분리·MonitorNotificationIsolationTest·계측예외 mutex회귀·OFF primitive인자. 다른 담당 판단/음성/휴식/UI/60배 변경 없음 |
| 문서/근거 | PROGRESS·현황표·main병합 문구 정정·SIG timing/remaining/PLT013/이번review 기록 및 선별된 텍스트/JSON/압축 원본·분석기 |
| 제외 | CRN/.env/키/local.properties·실제/격리 DB와WAL/SHM·APK·SDK바이너리·Gradle build·/tmp 사본·실험 임시 산출물 |

추적/새 파일을 포함한 선별 검토이지 일괄 git add 제안이 아니다. 기존 로컬 main 병합은 이미 HEAD의 이력이며 이번에 재병합하지 않았다. 향후 커밋 시에도 원본 진단을 보존하고 비밀/DB/바이너리를 다시 확인해야 한다.
