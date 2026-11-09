# 2026-11-08 — 거부된 상태 배송의 Hub 재전달 차단

이번 범위는 `PlaygroundSignalAdapter.stateSubscription`의 TURN_SIGNAL/BRAKE_ON/CRUISE_ACTIVE/CRUISE_TARGET_KPH 배송 경계다. 시작 브랜치 `develop-jdw`, HEAD `f5c6ad9e179383ddb2b5cff3aad9ac4230afabe2`와 기존 미커밋 파일23개를 기록했다([시작 상태](evidence/2026-11-08-rejected-delivery/start.json)). AGENTS.md·관련 저장소/어댑터/구독/Hub·기존 회귀를 읽었다. 변경 전 patch·미추적 파일 포함 보관본·해시는 `/tmp/awakedrive-rejected-delivery-20261108/`에 보존했다. 앞선 상태 신호473개와 실제 AVD 시험은 [이전 기록](2026-11-08-state-signals.md)이며 이번 결과와 구분한다.

## 실제 코드 경로의 재현

저장소는 과거 수신 시각/배송 순번을 거부해도 반환값이 없었다. 어댑터는 그 뒤 무조건 `emit(key)`를 호출하므로 `store.vehicleInput(key)`가 **직전 CALLBACK 상태**를 다시 내보냈다. `confirmationFields`도 직전 CALLBACK에 해당하는 빈 집합이어서 Hub의 `vehicleCallbacks`와 `rawVehicleObservations`가 증가했다. 값과 확인 시각은 그대로여도 센서 관측을 중복 전달하는 결함이다.

먼저 어댑터의 기존 정규화·저장·emit·오류 전달 코드를 **동작 변경 없이** `SdkStateDelivery`로 추출해 실제 어댑터와 시험이 동일한 배송 판단을 사용하게 했다. 시험은 `SdkStateSubscription`의 실제 콜백 배송을 큐에 받은 뒤 반영 순서만 제어하고, 실제 `SdkSignalStore`·`VehicleSignalHub`에 연결한다. 테스트가 `if accepted then emit` 판단을 별도로 구현하지 않는다.

수정 전 신규6개 중 **4개 실패, 정상 제어2개 통과**였다([실패 실행 로그](evidence/2026-11-08-rejected-delivery/before-fix-tests.txt), [상세 재현 수치](evidence/2026-11-08-rejected-delivery/reproduction.json)).

| 경로 | 저장소 값/확인 시각 | 수정 전 Hub callbacks / raw 관측 | 수정 후 |
| --- | --- | --- | --- |
| 수신50ms 콜백보다 수신100ms 콜백을 먼저 반영 | 최신true/100ms 유지, 과거 콜백 거부1회 | **1→2 /1→2** | **1 /1 유지** |
| 같은100ms의 배송순번2를1보다 먼저 반영 | 최신true/100ms 유지, 과거 순번 거부1회 | **1→2 /1→2** | **1 /1 유지** |
| 최신 콜백 뒤 과거 재조회 | 최신true/100ms 유지, 조회 거부1회 | **1→2 /1→2** | **1 /1 유지** |
| 콜백 뒤 최초 조회를 무시 | 최신true/100ms 유지, 조회 진단 수만 증가 | **1→2 /1→2** | **1 /1 유지** |

앞 두 경우에는 실제 어댑터 emit에 해당하는 전송 수도2회였고 수정 후1회다. 정상 조회/콜백·오류/권한 무효화 제어시험은 수정 전에도 통과했다. 이 재현은 공유된 실제 전달 코드의 자동시험이며 Android Handler/SDK IPC에서 실제 기기 경쟁을 새로 발생시킨 시험은 아니다.

## 수정과 유지한 계약

- **`SdkSignalStore.receive(): Boolean`**으로 반영 여부를 반환한다. 현재 값/품질/확인을 수락한 경우 true다. 명시 null/오류 상태도 수락한 갱신이면 true다. 오래된 배송·권한/구독 차단·무시된 최초 조회는 false이며 이전 값/확인 상태를 새 배송처럼 전달하면 안 된다. 기존 조회/거부 진단 계수 정책은 유지한다.
- `SdkStateDelivery.deliver()`는 저장소가 true를 반환했을 때만 emit한다. 반영 전 세션 유효성 검사·정규화·원천/수신/확인 시각 정책은 그대로다. 정상 콜백의 false 값과 배송 거부 결과 false는 별개다.
- `SdkStateDelivery.failed()`의 명시적 `store.block→emit`은 유지한다. 오류·권한 상실을 현재 모름으로 전달하며, 대기 중/이후의 이전 콜백은 기존 구독 가드가 차단한다.
- 수락된 재조회는 값이 달라지거나 null이어도 전달한다. 기존 `confirmationFields`에 따라 새 센서 관측/콜백으로 세지 않는다. 실제 콜백의 명시 null은 정상 갱신으로 전달한다. 이 구분과 기존 TTL/lease는 변경하지 않았다.

이번 후속에서 Hub 구현·서비스 수명·운행 규칙·Room 스키마·다른 담당 모듈·음성 Lint·DemoPlayer·60배 성능을 추가로 변경하지 않았다. 이전 미커밋 상태 신호 구현은 보존했다.

## 최종 실행 결과

```bash
JAVA_HOME=/home/a/.local/opt/android-studio/jbr src/android/gradlew -p src/android \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:-TieredCompilation' \
  --max-workers=2 --rerun-tasks :engine:test :app:testDebugUnitTest assembleDebug
```

**engine153+app326=479개**, 실패/오류/skip0, **assembleDebug 성공**이다. 기존473개와 신규6개를 모두 포함해 강제 재실행했고 신규6개도 전부 통과했다. 48개 Gradle task 모두 실행됐다([실행 로그](evidence/2026-11-08-rejected-delivery/after-fix-tests-build.txt), [시험 수치·수정 후 카운터·APK SHA](evidence/2026-11-08-rejected-delivery/test-build-result.json)).

신규 회귀는 최신/과거 콜백, 같은 ms 순번 역전, 거부된 재조회/무시된 최초 조회, 값/확인/유효 끝 유지, callbacks/raw 중복 없음, 수락된 값 변경 재조회·조회 null·실제 콜백 null, 오류/권한 상실 무효화와 대기 콜백 차단을 포함한다. 최종 APK SHA256은 `968d57484e74aab327fa04b51b728386d3550b45a3004a2438d0701602bce1a8`이다.

Room 기기10개는 이전 통과 기록으로 유지하며 이번에 재실행하지 않았다. 새 APK를 기기에 설치하거나 실제 SDK 경쟁·Handler 지연을 주입하지 않았다. Lint·60배 성능도 재실행/수정하지 않았다. 기존 원본 기어 STALE 원인 미확정과 앞선 실제 기기 미검증 범위는 그대로다.

## 보존과 한계

AVD에서는 Room 읽기 보존 대조만 수행했다. 두 DB의 모든 논리 행 해시가 전후 같고 integrity ok, 기존 `/tmp/awakedrive-user-*.json` 원본 해시도 동일하다([대조 결과](evidence/2026-11-08-rejected-delivery/preservation-final.json)). DB/WAL 복사본은 `/tmp`에만 두었다. 앱·서비스·Console을 조작하지 않았고 앱 삭제/데이터 초기화·브랜치 전환·커밋·push도 하지 않았다.

이번 자동시험은 실제 상태 배송 판단부터 Hub 계수/관측 통로까지 검증한다. SDK/OS 스레드에서 이 역순이 얼마나 자주 발생하는지, 실제 기기에서 해당 경쟁이 재현되는지까지 입증하지는 않는다. 이번 수정은 확인한 상태 배송 재전달 결함의 차단이며 다른 미검증 항목의 완료 선언이 아니다.
