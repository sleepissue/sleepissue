# 2026-11-07 — 기어 STALE 원인 조사·재조회 경로 보완

시작 브랜치 `develop-jdw`, HEAD `6451ed8f519c707e4253f039efb5166c1d8a3b54`, 작업 트리 clean을 먼저 기록했다([시작 기록](evidence/2026-11-07-gear-stale/start.json)). AGENTS.md·PROGRESS·서비스 가이드·직전 수동 검증 기록·관련 운행 기획/코드를 읽었다. 이번에는 브랜치 전환·커밋·push·앱 삭제·데이터 초기화를 하지 않았다. 다른 담당의 판단·개입·음성·DemoPlayer·60배 성능 코드는 수정하지 않았다.

## 원본으로 확정한 것과 남은 원인

원본 `/tmp/awakedrive-user-monitor-20261107.json`은 변경하지 않았다. SHA256은 `ef11dfecfa20225ae8f98d0ded6e052e1b713e0f5741e18dd4ab5c55351d932d`다. [분석 요약](evidence/2026-11-07-gear-stale/original-analysis.json):

| 항목 | 원본 관찰 |
| --- | --- |
| UNKNOWN 전환 | `1794050565043`, gear=null/STALE |
| D 복귀 | `1794050565136`, gear=D/AVAILABLE |
| 전환 로그 간격 | 93ms |
| 직전 유효 운전 구간 끝 | `1794050564934` |
| 다음 운전 구간 시작 | `1794050565136` |
| **실제 운전 누적에서 제외된 구간** | **202ms** |

양쪽 운전 구간은 `[1794050528534,1794050564934]`, `[1794050565136,1794050568233]`이다. 전체 span 39,699ms에서 두 구간 36,400+3,097ms를 빼면 202ms다. UNKNOWN 전환 프레임에 이르는 직전 간격도 합산하지 않는 기존 운행 계약 때문에 로그 간격과 다르다. 두 전환 로그의 연속값은 모두 1,017,040ms이며 복귀 시 공백을 소급 합산하지 않았다.

원본의 최근 발행률10Hz·누적 deadline 누락0은 Hub 발행 상태다. 재조회가 제때 실행·반영됐다는 증거는 아니다. 원본에는 당시 재조회 예약/실행/응답/Store 반영 및 해당 STALE 프레임의 확인 시각·lease 끝이 없어서 **실제 조회 공백인지, 스레드 전달 지연인지, 확인 시각 역행인지 확정할 수 없다.** 원본의 거부 응답 수도0이며, 아래 순서 결함이 이 사건에서 발생했다고 주장하지 않는다.

## 확인한 결함과 수정

기존 흐름은 Main의 `delay(1초)` → IO의 동기 `getProperty` → 응답 시각 채집 → Main으로 Store 반영 전달 → 다음 Main 타이머였다. Store는 원천 변경/실수신/재조회 확인 시각을 분리하고, Hub는 조회 확인 lease를 현재 단조 시각으로 평가해 REAL 기어를 선택한다. 동기 `CarPropertyManager.getProperty`는 지연될 수 있어 Main 밖에서 호출해야 한다([공식 API](https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager)). Dispatcher 주입과 Main 안전성은 [공식 코루틴 지침](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)을 따른다.

1. **재조회 예약이 Main 진행에 의존했다.** 가상 Main 스케줄러를 멈추면 기존 루프의 조회도 시작하지 못함을 재현했다. `GearRequeryLoop`로 재조회 예약·직렬 실행을 IO에 두었다. Main에서 처리하는 SDK 콜백·Store·발행 경로는 유지한다. 다음 조회가 Main 복귀를 기다리지 않으며, 느린 조회가 겹치거나 따라잡기 폭주를 만들지 않는다. 연결 세대와 취소 검사를 유지한다.
2. **늦은 같은/과거 원천 응답이 새 확인 시각을 되돌릴 수 있었다.** 수정 전 회귀에서 확인 시각2,000ms가 과거 재조회1,000ms 또는 중복 콜백1,500ms로 되돌아가는 두 실패를 확인했다. `SdkSignalStore`는 같은/더 오래된 원천의 과거 배송을 거부한다. 실제 더 새로운 원천 D→P 콜백은 보존하며 시각을 clamp하거나 현재 시각으로 덮지 않는다. 기존 오류/권한/세대/조회 시작 이후 변경 보호도 유지한다.

**TTL 3,000ms·재조회1,000ms를 늘리지 않았다.** 재조회는 새 센서 관측이 아니며 원천 변경/실수신/관측 순번을 늘리지 않는다. 확인 시각은 실제 응답 수신 시각이다. 반영 지연을 lease 연장으로 보상하지 않는다. 실제 지연·오류·권한 상실·연결 해제·관측 공백에서 UNKNOWN 처리와 공백 제외를 유지한다. 이 결함 수정과 원본 사건의 원인 확정은 별개다.

## 재발을 구분할 최소 계측

`GearCollectionAudit`는 예약/기한/재개/IO 시작/응답/Store 반영, 변경 전후 확인 시각·유효 끝·원천/수신 시각·관측 순번, Hub 판정 전후 단조 시각·기어/품질·선택 이유·프레임 시각/순번·스레드를 기록한다. 조회 ID와 CarService 연결 세대를 함께 둔다.

- 메모리는 최근512행, 최초 STALE 사건3건(각 앞64+전환1+뒤32행), 단계별 최댓값5개로 제한한다. 수집/Hub 경로에는 화면 갱신·JSON 변환·파일 I/O가 없다. 스냅샷은 제한된 행만 복사한다.
- `cache/sdk-trip-probe.json`의 `gearAudit`는 자동/수동 모두 작은 누계 요약이다. **진단 저장**의 상세 타임라인은 별도 `cache/gear-collection-audit.json`에 IO에서 임시 파일→rename으로 기록한다. 자동 요약이 상세 파일을 덮지 않는다. 중지 직전 계측을 남기며, 새 입력이 없는 복원 서비스에서는 과거 상세 파일을 보존한다.
- 상세 수동 요청은 `adb shell dumpsys activity service com.sleepissue.awakedrive/.service.RealTripMonitorService gear-audit`로도 가능하다. 출력은 내보내기 예약 결과다. 파일의 `capturedAtElapsedMs`를 확인하고 비동기 저장 완료 후 읽는다.
- `staleBracketMs`는 Hub가 관찰한 마지막 AVAILABLE부터 복귀까지의 프레임 구간이다. 소비 누락이 있으면 Tracker의 실제 제외 누적과 같다고 단정하지 않는다. 실제 제외값은 정밀 운전 구간으로 대조한다.

[자동/상세 분리 실측](evidence/2026-11-07-gear-stale/capture-mode-check.json)에서 자동 파일 갱신 중 상세 파일은 그대로였고 자동 rows0/timelineIncluded=false, 수동 rows421/true였다. IO 조회·Main Store 반영·별도 Hub 작업 스레드도 확인했다. 최초 조회 예약은 Main에서 시작할 수 있지만 반복 조회 예약은 IO에서 진행한다.

## 자동 회귀·빌드

기준 커밋의 **433개(engine153+app280)·Room10개**는 과거 통과 기록이다. 이번 실행과 합치거나 과거 시험을 재실행으로 표시하지 않는다.

- 신규17개: 계측5·재조회 루프4·기어 순서/유효 경계7·Hub→운행/저장복원1. 3,000ms 포함/3,001ms STALE, 늦은 조회·중복 콜백·D→P, 오류/권한 무효화 뒤 과거 응답 차단, 반영 지연이 수신 시각을 갱신하지 않음, Main 정체·직렬 조회·세대/취소, UNKNOWN 공백 제외·같은 ID 정밀 복원을 검증했다.
- [수정 전 선택 회귀](evidence/2026-11-07-gear-stale/regression-before-fix.txt)의 실패3건 중2건이 위 확인 시각 역행이다. 나머지1건은 최초 UNKNOWN→D 프레임 간격100ms를 시험 기대값이 잘못 포함한 fixture 오류다. 기대값을 기존 계약에 맞췄고 제품 운행 계산은 수정하지 않았다.
- 최종 **engine153+app297=450개**, 실패/오류/skip0. `--rerun-tasks :engine:test :app:testDebugUnitTest`로 실제 전부 다시 실행했다([수치](evidence/2026-11-07-gear-stale/fresh-tests.json), [실행 로그](evidence/2026-11-07-gear-stale/fresh-tests.txt)).
- `assembleDebug assembleDebugAndroidTest processReleaseMainManifest` 성공([빌드 로그](evidence/2026-11-07-gear-stale/final-build.txt), [결과/APK SHA](evidence/2026-11-07-gear-stale/final-build.json)). JBR25, 기존 daemon 기준을 유지하며 `-XX:-TieredCompilation`, workers2를 사용했다.
- 실제 AVD에서 기존 `RoomTripRepositoryTest,RealTripRoomTest`를 직접 runner로 재실행해 **10개 통과,22.007초**([로그](evidence/2026-11-07-gear-stale/room-final.txt)). 시험 자체의 메모리/임시 DB와 사용자 수동/REAL DB를 구분했다. 앱 교체 설치만 했고 UTP/앱 제거/초기화를 사용하지 않았다.
- Lint는 이번에 재실행하지 않았다. 기존 음성 `PleosSpeechRecognizer.kt:129`의 MissingPermission 오류 기록과 60배 기기 성능 미해결 기록을 그대로 유지한다. 빌드/450개 통과를 Lint·60배 합격으로 표시하지 않는다.

## 실제 AVD 비교 — 재현되지 않음, 원본 해결 판정 아님

기존 API34 Pleos AVD/user10·실제 SDK·동일 운행 DB를 사용했다. 속도0, P에서 버튼을 누르고 Console D 입력을 유지한 뒤 P로 복귀했다. D단 OS 화면 제한을 우회하지 않았다. “화면 열림”은 Activity를 끝내지 않은 조건이며 D단에 OS가 내용을 가릴 수 있다. “닫힘”은 **돌아가기**로 Activity를 끝내고 다른 Activity가 topResumed인 조건이다. 각 조건1회씩이며, 아래 D 구간 동안 반복 dump/파일 읽기를 하지 않고 끝에서 계측했다.

| 구분 | D 유지 요청/캡처까지 실제 경과 | STALE 전환/프레임 | 수집 시작 누계/활성 타이머 | D→P / 명시 중지 |
| --- | --- | --- | --- | --- |
| 수정 전 계측·열림 | 120초 /120,156ms | 0/0 | 1/1 | PAUSED /UNKNOWN·0개 |
| 수정 전 계측·닫힘 | 120초 /120,126ms | 0/0 | 2/1 | PAUSED /UNKNOWN·0개 |
| 수정 후·열림 | 120초 /120,147ms | 0/0 | 1/1 | PAUSED /UNKNOWN·0개 |
| 수정 후·닫힘 | 120초 /120,110ms | 0/0 | 2/1 | PAUSED /UNKNOWN·0개 |
| 최종 APK·자동 진단 켬·열림 | 60초 /60,123ms | 0/0 | 1/1 | PAUSED /UNKNOWN·0개 |

수정 전 비교는 수정 전 Main 루프에 제한된 계측을 추가한 빌드다. 수정 후120초 두 표본은 IO 루프·순서 보호 수정본이며, 최종 APK에는 이후 진단 요약/상세 분리·오류 처리·복원 시 상세 파일 보존까지 포함한다. [전 열림](evidence/2026-11-07-gear-stale/baseline-open-summary.json), [전 닫힘](evidence/2026-11-07-gear-stale/baseline-closed-summary.json), [후 열림](evidence/2026-11-07-gear-stale/fixed-open-summary.json), [후 닫힘](evidence/2026-11-07-gear-stale/fixed-closed-summary.json), [최종60초](evidence/2026-11-07-gear-stale/final-auto-capture-summary.json).

모든 표본의 최근 Hub 발행은10Hz다. 처음4개 명시 D 구간 내부의 저장 운전 구간 단절은0ms였다. 시작 누계2는 중지 후 재시작을 포함한 서비스 수명 누계이며 동시 수집기2개가 아니다. 시작 연타·회전의 별도 실제 시험은 앞선 서비스 검증 기록이며 이번에 새로 연타/회전했다고 표시하지 않는다. 이번에는 중지/재시작·화면 종료/재진입과 활성 타이머1→0을 대조했다.

| 입력 세대 전체 최댓값(ms) | 전 열림 | 전 닫힘 | 후 열림 | 후 닫힘 | 최종60초 |
| --- | --- | --- | --- | --- | --- |
| 예약 지연 | 692 | 1,315 | 21 | 11 | 55 |
| IO 전달 | 21 | 46 | 2 | 1 | 3 |
| 조회 실행 | 35 | 10 | 21 | 13 | 4 |
| 응답→Store 반영 | 76 | 103 | 1,169 | 1,191 | 1,021 |
| 확인 시각 간격 | 1,693 | 2,243 | 1,025 | 1,014 | 1,056 |

이 누계/최댓값은 D 이전 P 준비시간도 포함한다. 특히 전 열림의 queryStarts677은 D120초의 조회 횟수가 아니다. 서로 준비시간·부하가 달라 통계적 성능 비교나 개선율로 사용하지 않는다. 수정 후에도 응답→반영 지연이 약1.2초까지 관찰됐다. 이는 전달 구간 측정이며 정확한 Main 정체 원인은 확정하지 않았다. 반영이 늦어도 확인 시각/유효 끝은 실제 응답 기준이며, 다음 IO 재조회 확인 간격은 약1초로 이어졌다. 향후 반영 지연·IO/OS 정지·실제 조회 지연이 lease를 넘으면 STALE/UNKNOWN은 계속 필요한 보호 동작이다.

## 보존·최종 복원·남은 범위

[보존 대조](evidence/2026-11-07-gear-stale/preservation-final.json)에서 수동 DB 전체 논리 해시는 전후 `c9961514d35261a0aec539d34c3bb0123d8756c24f9f01625bef0a80dfd23d9f`로 동일, 두 DB integrity ok, REAL 같은 ID와 원래11개 운전 구간 그대로임을 확인했다. REAL 누적은 이번 정상 입력 시험으로 증가했다. 시작 전 DB/WAL·논리 dump는 `/tmp/awakedrive-gear-stale-20261107/before/`에 보존하며 실제 DB/APK/SDK 바이너리를 저장소에 추가하지 않았다.

Room 시험 뒤 앱 화면을 **자동 연결 없이** 다시 열어 같은 ID `4e5db99c-bae6-4990-bdfd-39c53d5f9145`, 정밀 누적 **1,798,726ms**, 최근708초, 복원 RESTORED·현재UNKNOWN·타이머0을 확인했다([복원](evidence/2026-11-07-gear-stale/restoration-check.json)). 복원/재진단만으로 DB가 바뀌지 않았고 과거 상세 계측 파일도 그대로였다. 설치 APK SHA는 최종 빌드 `1b303da6308061d3b726e85b9010a071a5473d4c26a34543f495741ad1ece722`와 같다. 마지막 **감시 중지·requested=false·running=false·타이머0·SDK 소유자없음·현재UNKNOWN**([서비스](evidence/2026-11-07-gear-stale/final-service.txt)), Console P/0으로 두고 이번 시험의 heartbeat만 종료했다.

- **완료:** 확인 시각 역행 회귀 수정, Main 의존 재조회 예약 분리, 제한된 단계 계측,450개/Room10개 재실행·빌드, 위 실제 AVD5회·보존/복원 대조.
- **미해결:** 원본 일시 STALE의 직접 원인. 당시 단계 시각이 없어 확정할 수 없고 이번 AVD에서도 재현되지 않았다. 장시간 관찰 후 발생 사건 상세를 대조해야 한다.
- **이번 미검증:** 실제 기어 권한 철회·CarService 해제·조회 강제오류/지연·실제 STR·장기 유지·물리 차량, 이번 수정본에서의 프로세스 강제 종료/실제 재부팅. 오류/순서/lease 경계의 자동시험과 구별한다. 앞선 서비스의 재생성/강제 종료/재부팅 기록은 과거 검증으로 유지한다.
- **범위 보존/대기:** 실제 전원은 미지원 UNKNOWN,2/3/4/5번 연결 대기, 음성 Lint·60배 성능은 미수정/미해결이다. UNKNOWN/서비스 종료를 시동 OFF로 바꾸지 않으며 관측 공백을 운전·정차·휴식으로 합산하지 않는다.

## 사용자가 다시 확인할 순서

1. Console **AVD Enabled·P·속도0** → 앱 **Playground SDK 운행·Room** → 복원 RESTORED/현재UNKNOWN·같은 ID/누적을 확인한다.
2. **권한 확인 → 감시 시작**을 여러 번 누른다. 새 실행의 수집 시작 증가1회·활성 타이머1개를 확인한다. 수집 시작은 수명 누계라 중지 후 재개하면 증가한다.
3. Console **D**로 유지한다. Activity를 열어 둔 경우와 **돌아가기**로 끝낸 경우를 따로 일정 시간 관찰한다. OS 제한은 그대로 둔다. P/0으로 돌아와 화면에 재진입하면 같은 ID/단일 타이머·정상 입력의 누적/10초 저장이 이어져야 한다.
4. **즉시 저장 → 진단 저장** 후 새 시각의 `sdk-trip-probe.json`과 `gear-collection-audit.json`을 보관한다. STALE이 있으면 `incidents`의 조회/반영/Hub 판정 시각·이유와 정밀 운전 구간을 함께 대조한다. 원본 보관본을 덮어쓰지 않는다.
5. **감시 중지** → UNKNOWN·타이머0·마지막 저장 성공을 확인한다. 화면을 다시 열어도 자동 수집하지 않고 같은 운행 ID/누적을 유지해야 한다. 다음 시작은 사용자의 **감시 시작**이다.

## 2026-11-08 사용자 직접 검증과 커밋 준비

사용자가 직접 수행한 후속 시험을 아래 두 진단 원본과 대조했다. 원본은 `/tmp`에 그대로 보존하고, 공유에 필요한 시각·누계·전환/저장 로그만 [검토 요약](evidence/2026-11-07-gear-stale/user-check-20261108.json)에 남긴다. 앞선 AVD120초/60초 시험과 별도 결과다.

| 근거 | SHA256 |
| --- | --- |
| `/tmp/awakedrive-user-gear-audit-20261108.json` | `c85c14e44af00596f08443f5c7a34fcbcf28b6d674a5919c0137ad1bfeff38b1` |
| `/tmp/awakedrive-user-monitor-latest-20261108.json` | `f6b26bd660be323cb694813b692b921d56e6e8588ff9c27097482eb5adb3a0ff` |

두 파일의 서비스 인스턴스는 `4228e799-43cd-4d27-b8ce-3b3676c9c3b9`, 운행 ID는 `4e5db99c-bae6-4990-bdfd-39c53d5f9145`로 같다. 그러나 같은 입력 세션의 동시 스냅샷은 아니다.

- **상세 기어 기록:** `capturedAtElapsedMs=36518893`, 입력 세대1의 명시적 중지 후 세대2, starts1·running=false·타이머0·현재UNKNOWN이다. 마지막 저장 wall 시각은 **2026-11-08 00:46:12.803 KST**다. 이 파일의 누계가 아래 D 유지 시험을 포함한다.
- **최신 진단:** `capturedAtElapsedMs=36609783`, 앞 파일보다 **90,890ms 뒤**이며 재시작한 입력 세대3, starts2·running=true·타이머1·기어P/PAUSED다. logical 시각은 **2026-11-08 00:47:43.710 KST**다. 여기의 queryStarts10/hubFrames99와 첫 세션의 queryStarts143/hubFrames1431을 섞지 않는다. 앞선 “최종 감시 중지”는 11/7 에이전트 시험 종료 상태이며, 이 최신 원본은 사용자가 재시작한 별도 상태다. 이번 커밋 준비에서는 기기를 조작하지 않았다.

### 직접 검증 결과와 진단 대조

1. **D 유지129,494ms가 하나의 연속 운전 구간으로 저장됐다.** 두 파일의 정밀 운전 구간 배열은 같고 마지막 한 구간은 `[1794066235532,1794066365026]`, 즉 **00:43:55.532~00:46:05.026 KST**다. 기존16개 구간에 해당1개가 추가돼 총17개이며 D 구간 내부 분할이 없다.
2. **최근 누적708초→838초 증가**를 확인했다. 이전 복원본의 정밀 운전 합708,722ms에 새129,494ms를 더하면838,216ms다. 전후 누적에서 각각 초 미만을 절삭하므로 표시 차이는130초지만 실제 새 구간은129,494ms다. 운전 시간을130,000ms로 바꾸지 않는다.
3. **해당 완료 세션의 기어 STALE0회·조회 오류0회**다. 상세 파일에서 queryStarts/queryResponses143/143, queryErrors0, staleTransitions/staleFrames0/0, noRenewalResponses0, incidents0을 확인했다. maxConfirmedGap1,032ms·응답→반영 최대700ms이며 해당 입력 세대 전체(P 준비·D·P·중지 전)를 포함한 누계다. 최신 재시작 세션도 별도로 STALE0/조회 오류0이지만 이를 D129,494ms 시험의 누계로 사용하지 않는다.
4. **대기 중 실제10초 주기 저장 성공**이 이어졌다. 완료 세션의 D 구간 내 `실제 경과 10초 저장 성공` 로그12건, `1794066245551~1794066355811`을 확인했다. 인접 성공 로그 간격은10,012~10,037ms이며 저장 완료 로그의 간격이다. 실제 타이머는 `ELAPSED_REALTIME`, 상태 전환/중지의 즉시 저장도 별도로 성공했다. 로그 간격을 타이머 실행 시각이나 새 센서 관측 간격으로 해석하지 않는다.
5. **실제 Room에서도 같은 운행 ID·해당 구간을 확인했다는 사용자 직접 검증 결과**를 기록한다. 두 진단의 같은 ID/정밀 구간과 저장 성공 로그는 이에 일치한다. 이번 커밋 준비 시 `adb devices`에 연결 기기가 없고 제공된 두 JSON은 raw Room DB가 아니므로, 에이전트가 이번에 Room 행을 다시 조회했다고 표시하지 않는다.

**원본 STALE의 직접 원인은 여전히 미확정이다.** 이번 D 유지 시험에서 재발하지 않았다는 결과이며 원본 사건 해결의 확정 근거는 아니다. 기존 전체 자동450개(engine153+app297), 실제 Room10개와 빌드 통과 기록은 앞선 실행 결과로 유지한다. 이번 커밋 준비에서는 시험을 재실행하지 않았다. 음성 Lint 오류·60배 성능 문제·실제 기어 권한/CarService 장애·STR/장기/물리 차량 등 앞서 구분한 미검증 범위도 유지한다.

이번 준비는 `develop-jdw`·HEAD `6451ed8f519c707e4253f039efb5166c1d8a3b54`에서 기존 미커밋 코드/시험/문서를 보존한 상태로 시작했다. 추적 변경 patch·미추적 파일을 포함한 보관본과 파일 해시는 `/tmp/awakedrive-gear-commit-20261108/`에 두었다. 코드/시험과 제한된 검증 근거를 검토해 요청된 커밋 대상으로 삼고, 진단 원본 전체·실제 DB·APK·SDK 바이너리·비밀값·임시 산출물은 포함하지 않는다. main 병합·PR·force push·브랜치 전환·데이터 초기화는 하지 않는다.
