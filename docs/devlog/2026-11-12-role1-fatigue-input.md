# 1번 Hub → 졸음 엔진 입력 계약 확인 — 2026-11-12

## 사용자 범위와 결론

사용자는 조향 게이트·지표·위험 점수 설명을 제시한 뒤, 추가 범위를 **‘1번 입력·Hub 연결만 보완’**으로 선택했다. 간트 `AwakeDrive_일정_v1.gan` task10의 신호 허브·단위 변환 경계를 확인했다. 2번 `engine/fatigue`·설정·점수식, `app/fatigue` 연결기, 음성·다른 UI는 수정하지 않았다.

현재 경로는 `VehicleSignalInput/NaviState → SignalNormalizer → PeriodicSignalHub → SignalFrame.toDriveFrame → 기존 FatigueEngine`이다. 제품의 기존 `AwakeDrivePipeline`도 `hub.samples`를 `FatigueAssessmentRunner`에 연결하고 있다. 이번 결합 시험에서 전달 결함은 발견하지 못했으므로 **실행 코드는 바꾸지 않고 1번 계약 시험 9개를 추가**했다. 기능을 새로 구현했다거나 모든 REAL 입력이 엔진에 들어간다는 의미가 아니다.

사용자가 APK 작업을 중단했으므로 APK 생성·설치·서명·release 준비는 하지 않았다. 기존 인계의 APK 관련 다음 작업보다 이 지시를 우선한다. 커밋·push·PR도 하지 않는다.

## 확인한 경계

| 입력/조건 | Hub와 기존 엔진까지 확인한 내용 |
| --- | --- |
| 단위·조향 | 22.222223m/s가 약80km/h로 한 번 변환되고, ±0.5·3·5·6°와 실제0°의 크기·부호·시각을 보존한다. 조향비·부호 추정을 추가하지 않는다. |
| 게이트 입력 | 59.9/60km/h, D/P/R/N, 회전299.9/300m·거리null을 기존 게이트까지 전달한다. |
| 방향지시등·브레이크 | Hub 경유 이벤트의 전후10초·5초 제외를 기존 게이트로 확인한다. |
| 품질·null | 필수 입력의 미지원·권한 거부·오류·미수신·STALE을 실제0·false로 바꾸지 않는다. 원시 값이 있어도 불가 품질이면 게이트를 열지 않는다. |
| 만료 | TTL 경계 이후 오래된 차량·회전 거리가 null이 된다. 새 관측 시각을 꾸며 유지하지 않는다. |
| 원천 Hz | Hub 발행10Hz와 원천2/5/10Hz를 구분한다. 2Hz는 미세 수정 비활성, 5Hz는 측정 준비 뒤 활성이다. 발행 횟수를 원천 관측 수로 세지 않는다. |
| 시간창 | Hub의 연속1,001프레임을 실제 구독하고 기존 엔진의1분 창·30초 갱신 결과를 확인한다. 첫 프레임100ms 기준 결과 시각60,100/90,100ms다. 방향지시등 전10초를 소급 제외하므로 실제 결과 확정은 각각70,100/100,100ms 입력에서다. |
| SDK 조향 보류 | SDK 원시−10°는 진단에 남지만, 의미 미확정 상태는 Hub와 엔진 입력에서도 null이다. 정상 차속 등 다른 입력은 보존된다. 실제 SDK 수신 성공·물리량 해결 시험이 아니다. |

기획서4-3과 현재 엔진은 **‘다음 회전300m 이상’**이다. 사용자가 붙인 요약의 ‘300m 넘게’와 경계 표현이 다르다. 이번에는 기존 요구와 2번 규칙을 보존해 정확히300m를 통과시켰다. 엄격한 `>300`으로 바꾸지 않았다.

조향 지표0.5~3°·5° 이상·4초 초과 정지 후1초 내6°, 0.35/0.25/0.40 점수식은 이미 2번 순수 Kotlin 코드에 있다. 해당 구현과 기존 시험을 읽고 실행했으며 수정하지 않았다. 원천5Hz 미만일 때 미세 지표를 제외하고 가중치를 다시 나누는 기존 SIG_009 계약도 유지한다.

## 실제 실행

```bash
cd /home/a/StudioProjects/sleepissue/src/android
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew :engine:test --rerun-tasks
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest \
  --tests com.sleepissue.awakedrive.signal.FatigueInputHubContractTest
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest
```

- 엔진 전체351개 실제 재실행 통과. 이 중 `engine.fatigue`10개 클래스의81개 시험도 통과했다.
- 신규 `FatigueInputHubContractTest`9개 실제 실행 통과(실패·오류·skip0).
- 앱 전체436개 실제 실행 통과(실패·오류·skip0). 기존427개에 이번9개가 추가됐다. 엔진351개와 합쳐 **787개**다. 신규9개를 전체 합계에 중복 가산하지 않았다.

[시험 XML 집계](evidence/2026-11-12-role1-fatigue-input/tests.json), [엔진 실행 로그](evidence/2026-11-12-role1-fatigue-input/engine-tests.log), [신규9개 로그](evidence/2026-11-12-role1-fatigue-input/contract-tests.log), [앱 전체 로그](evidence/2026-11-12-role1-fatigue-input/app-tests.log).

이 시험은 가상 시계·가짜 수집원과 실제 Hub/기존 엔진을 결합한 JVM 시험이다. 에뮬레이터·카메라·실제 센서 없이 실행했다. **JVM의100ms 진행이 실제 AVD의110ms 발행 시한을 입증하지 않는다.** 이번에 기기/DB/권한을 조작하거나 이전 기기시험을 반복하지 않았다.

## 남는 제한과 다음 판단

1. REAL SDK 조향은 전륜각/운전대각·부호 의미가 미확정이라 엔진에 null로 간다. 공식 물리량/변환 근거 없이 조향비를 곱하거나 부호를 뒤집지 않는다. 유효한 JSON/정규 입력의 경로 확인과 REAL 졸음 판정 완성을 구분해야 한다.
2. 원천 시각이 없는 SDK 조향을 임의로 MEASURED/5Hz 이상으로 승격하지 않는다. 유효한 출처의 조향이라도 Hz가 미확인이면 기존 계약대로 미세 지표를 끈다.
3. 기존 SIG_001의110ms·SIG_002 전체200ms·SIG_016의1초 지연 미달은 해결 판정하지 않았다. 이전 측정은 `2026-11-12-role1-listed-items.md`를 따른다.
4. 이동 heading·동적 내비/실제 회전·현재온도 변경·실제 OFF/STR 등 기존 미확인 조건도 이번 범위에서 통과 처리하지 않는다.
5. 추가 실행 코드 수정은 재현되는 1번 전달 결함 또는 공식 SDK 의미 근거가 확보됐을 때 진행한다. 2번 점수식·기준선·게이트 수정은 이번 승인 범위 밖이다. APK는 사용자 재요청 전까지 진행하지 않는다.

## 보존

`develop-jdw` / HEAD `9144cfd7eee1b078b6a988c7692a97792e4f4407` 유지. 시작 시 존재하던 app/engine 소스395개 해시와 diff를 개인 보존 경로 `/home/a/.local/state/awakedrive-handoff/2026-11-12/role1-fatigue-input/`에 저장했다. **기존395개 전부 바이트 동일**, 추가 소스는 `app/src/test/.../signal/FatigueInputHubContractTest.kt`1개뿐이다. 문서는 이 기록·근거와 PROGRESS/현황표/CURRENT_HANDOFF의 최신 안내만 보강했다. [최종 범위 대조](evidence/2026-11-12-role1-fatigue-input/scope.json). 개인 경로의 기존 diff는 사용자 미커밋 자료이므로 공유/커밋하지 않는다.
