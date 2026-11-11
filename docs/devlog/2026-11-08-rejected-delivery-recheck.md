# 2026-11-08 — 거부 배송 보완 재현·재검증

이번 요청을 시작했을 때 `develop-jdw` 작업본에는 이미 `SdkSignalStore.receive(): Boolean`, `SdkStateDelivery`의 수락 시에만 `emit`하는 처리, 실제 구독 배송부터 Hub까지 연결한 회귀시험 6개와 [앞선 기록](2026-11-08-rejected-delivery.md)이 미커밋 상태로 있었다. 이를 덮어쓰거나 되돌리지 않고 확인했다. AGENTS.md, 현재기능 기획서 4-1~4-3·10장, 어댑터·저장소·상태 구독·Hub와 관련 시험을 읽었다. SDK API·판단 규칙 변경은 없다.

시작 시 수정·미추적 파일 33개의 원본과 SHA256, diff를 `/tmp/awakedrive-delivery-recheck-28ynenm2/`에 보관했다. 이번에 보강한 코드는 `SdkRetainedStateTest`의 수락 반환값 계약 시험 1개뿐이다. 네 상태 신호 모두에서 최초 조회·같은 값 재조회·null·오류 갱신은 수락되고, 과거 시각·같은 ms의 과거 배송 순번·권한 차단 뒤 배송은 거부되는지 확인한다. 거부 뒤에는 거부 계수를 제외한 `SdkReading` 전체가 동일해야 한다.

## 결함 재현과 전달 계약

Android 프로젝트를 별도 임시 디렉터리에 복사하고, 복사본의 `SdkStateDelivery`에서만 `if (accepted) emit(key)`를 `emit(key)`로 바꿨다. 원래 작업본의 수락 판단은 건드리지 않았다. 실제 어댑터가 사용하는 배송 판단 코드를 동일하게 실행하면서 과거의 무조건 전달 동작만 복원한 대조시험이다.

그 복사본에서 `:app:testDebugUnitTest --tests com.sleepissue.awakedrive.pleos.SdkStateDeliveryHubTest`를 실행하자 **6개 중 4개 실패**, Gradle 종료 코드 1로 결함이 재현됐다. 정상 재조회/null·오류/권한 상실 제어시험 2개는 통과했다.

| 경로 | 무조건 전달 복사본 callbacks / raw / emit | 현재 작업본 |
| --- | --- | --- |
| 최신 100ms 콜백을 먼저 반영한 뒤 과거 50ms 콜백 | 2 / 2 / 2 | 1 / 1 / 1 |
| 같은 100ms의 순번 2를 먼저 반영한 뒤 순번 1 | 2 / 2 / 2 | 1 / 1 / 1 |
| 최신 콜백 뒤 거부된 과거 재조회 | 2 / 2 / 2 | 1 / 1 / 1 |
| 콜백 뒤 무시된 최초 조회 | 2 / 2 / 2 | 1 / 1 / 1 |

값 `true`와 확인 시각 `100ms`는 양쪽에서 유지됐다. 저장소가 배송을 거부해도 직전 CALLBACK 상태를 다시 내보내기 때문에 관측만 중복되는 문제임을 확인했다. 현재 작업본은 `receive()`가 수락한 경우에만 전달하며, 명시적 오류·권한 상실의 `block → emit`은 유지한다. 수락된 재조회는 값 변경·null도 전달하되 `confirmationFields`로 새 관측/콜백에서 제외한다. 실제 콜백 null은 전달한다.

재현 [실행 로그](evidence/2026-11-08-rejected-delivery/recheck/negative-control.txt), [JUnit 원본](evidence/2026-11-08-rejected-delivery/recheck/negative-control.xml), 전후 수치 [results.json](evidence/2026-11-08-rejected-delivery/recheck/results.json)을 새로 보관했다. 이전 진단·증거 파일은 수정하지 않았다.

## 현재 작업본 전체 재실행

`src/android`에서 아래 명령을 실제 실행했다. 임시 결함 복사본도 동일한 JDK·JVM 옵션·worker 수를 사용했다.

```bash
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:-TieredCompilation' \
  --max-workers=2 --rerun-tasks :engine:test :app:testDebugUnitTest assembleDebug
```

**engine 153 + app 327 = 480개 전부 통과**, 실패·오류·skip 0, **assembleDebug 성공**, 종료 코드 0이다. 기존 자동시험 473개 + 기존 Hub 회귀 6개 + 이번 반환값 계약 보강 1개를 포함한다. 48개 Gradle task 모두 실행됐다. [전체 실행 로그](evidence/2026-11-08-rejected-delivery/recheck/full-tests-build.txt)와 [Hub 회귀 JUnit 원본](evidence/2026-11-08-rejected-delivery/recheck/hub-regression.xml)을 보관했다.

APK SHA256은 `968d57484e74aab327fa04b51b728386d3550b45a3004a2438d0701602bce1a8`이다. 이번 변경은 시험 보강뿐이어서 앞선 수정본과 APK 해시가 동일하다.

## 보존과 한계

시작 파일 중 시험 파일 1개 이외 32개는 바이트 해시가 그대로다. 브랜치 `develop-jdw`와 HEAD가 유지됐다([보존 대조](evidence/2026-11-08-rejected-delivery/recheck/preservation.json)). 신규 기록은 별도 파일/디렉터리에 작성했다. 브랜치 전환·커밋·push·앱 설치/삭제·데이터 초기화는 하지 않았다. 이번에는 adb·기기·기존 수동/REAL Room DB에 접근하지 않았으며 Room 스키마·운행 규칙·서비스 수명·다른 담당 모듈·60배 성능 코드를 변경하지 않았다.

검증 범위는 실제 공유 배송 판단·구독·저장소부터 Hub 계수/관측 통로까지의 JVM 회귀다. Android Handler/SDK IPC의 실제 경쟁 발생 빈도나 기기에서의 역순 배송은 새로 검증하지 않았다. 기기 Room 시험·60배 성능도 재실행하지 않았다.
