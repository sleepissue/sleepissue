# develop-jdw 비교·5번 통합 경계·디버그 정리 — 2026-11-12

## 비교 기준

사용자는 ‘develop-jdw에 있는 코드와 비교한 변경점 정리, 5번과 합치면 디버깅이 불필요한지 확인하고 불필요한 부분 제거’를 요청했다. 이 PC의 현재 브랜치 이름도 develop-jdw이므로, 비교 기준은 **GitHub의 `origin/develop-jdw`**로 명시했다.

이번 `git fetch origin`은 성공했다. 브랜치 전환·병합·커밋·push·PR은 하지 않았다.

| 기준 | SHA | 현재 HEAD와 관계 |
| --- | --- | --- |
| GitHub develop-jdw | `f59176fec44b8883545f9ddeef7d62335408b2c6` | 로컬에 커밋3개 추가, 뒤처짐0 |
| 현재 로컬 HEAD | `9144cfd7eee1b078b6a988c7692a97792e4f4407` | 기존 미커밋 작업 별도 보존 |
| 최신 GitHub main | `e44aae7ff91ae70921578e45e0c5a48ec6e996ab` | 로컬 HEAD보다6커밋 앞섬 |

최신 main에는 **PR #20(5번 `feat/ui-role5-rehearsal`)**이 이미 병합됐다. 이번 작업 트리에 적용하지 않았다. ‘main에 병합’과 ‘이 PC의 소스에 반영’은 다르다.

## GitHub develop-jdw와 현재 로컬의 차이

### 이미 로컬 커밋에 들어온 팀 변경

`82f8876`(PR18 병합), `8d045ae`(4번 앱 조립), `9144cfd`(PR19 병합)의3커밋이다. 감시 서비스→Hub→엔진→개입/음성/휴식 조립, 방향 추정, 통풍 동의, 휴식 상태 저장과 관련 시험 등이 포함된다. 이번 정리에서 새로 구현한 내용이 아니며 1번 작업으로 잘못 집계하지 않는다.

### 아직 커밋하지 않은 누적 변경

새 파일은 일반 `git diff --stat`에 나오지 않으므로 별도 조사했다. 아래는 이전 작업부터 이어진 전체 누적 차이이며, 모두 이번 턴에 고쳤다는 뜻이 아니다.

| 구분 | GitHub develop-jdw 대비 현재 로컬 |
| --- | --- |
| JSON 로딩·초기 운행 | 팀 `formatVersion/startAt/tMs` 형식과 기존 `schemaVersion/startEpochMs/t` 형식 수용, `tripPreset` 검증·초기 상태 반영, schema/작성 가이드 갱신 |
| 제품 데모 | 새 `ProductDemoController`·`ProductDemoActivity`, assets 원본 재생→Hub→운행→기존 판단/개입 연결, 재생 배지·원본/1/10/60배 선택·일시정지/중지 |
| 실제 음성과 배속 | 기존 음성 lifecycle에 연결한 DemoVoiceSpeedGate, 질문1배·종료/실패/취소 복귀, 종료 후 늦은 콜백 억제. 이 음성 소스 수정은 이전 누적 변경으로 이번 턴 미수정 |
| 시동 끔 대체 | 명시적 쉼터 P단 입력→데모 전용 저장→앱 종료→재진입 시+22분 복원. 실제 OFF/STR·엔진 학습 창 전체 복원과 구분 |
| 첫 확인 예약 | `FirstCheckScheduleChanged`, 엔진 예정 시각→AwakeDrivePipeline→1번 매니저, 중복·대상/단계 변경·중지/휴식 취소, checkpoint/codec v2·v1읽기 호환 |
| SDK 내비 | 조회 예약을 유지한 즉시 재조회와 수신 처리 분리, 관련 회귀. 기존 전체 지연 미달·공급자 내부 원인은 해결 판정하지 않음 |
| 입력 계약 시험 | Hub→기존 엔진 게이트/단위/품질/원천Hz 검증, 원본6종 재생 결합·예약·저장 호환 시험 등 |
| 문서 | README·상태표·인계·개발 기록·근거. APK 중단 지시는 과거 release 준비 문구보다 우선 |

작업 시작 때 새 main Kotlin 파일은8개, 새 단위/기기 Kotlin 시험은11개였다. 실행 소스8개는 DemoStorageContext, DemoVoiceSpeedGate, ProductDemoController, ProductDemoSpeed, SdkNaviRefreshLoop, DrivingRestrictionGate, ProductDemoActivity, DemoTripPreset이다. 변경 파일 원목록은 [비교 자료](evidence/2026-11-12-develop-compare-debug-cleanup/comparison.json)에 있다.

## 5번 최신 코드와 합칠 때 남는 일

원격 main의 `ROLE5_REHEARSAL_HANDOFF.md`, `LiveDriveActivity`, `ProductDemoSession`, `DemoUiSession`, `DemoExecution`, `MonitoringPipeline`과 manifest/원본을 읽었다. 원격 코드의 시험 결과는 그 담당 기록이며 이번 로컬 검증으로 세지 않는다.

| 항목 | 확인한 차이·통합 때 필요한 일 |
| --- | --- |
| 제품 화면·배속·배지 | 5번의 새 LiveDriveActivity/ProductDemoSession 경로와 로컬 별도 ProductDemoActivity가 겹친다. 대표 제품 진입점을 하나로 맞춘 뒤 중복 화면을 정리해야 함 |
| 원본 길이 | 로컬/원격 develop 원본은18,000개·30분. 최신 main은27,100개·45분10초다. 앞선30분 길이 부족은 새 원본에서 해소될 여지가 있으나 이 PC에는 아직 미반영이고 새 원본 첫 확인 결과는 미검증 |
| 원본 형식·assets | 최신 main은 초 단위 `t`·`startEpochMs` 형식 및 `scenarios/<이름>.json` assets 경로다. 로컬 제품 컨트롤러는 `<이름>.json`을 읽는다. 스트리밍 로더의 메모리 개선도 보존해야 함 |
| 첫 확인 시각 | 로컬은 AwakeDrivePipeline→FirstCheckScheduleChanged. 5번 데모는 별도 DemoUiSession→DemoExecution 경로라 이 연결이 자동 적용되지 않음. 엔진의 절대 시각과 실행기의 상대 시각 변환 경계 확인 필요 |
| 시동 끔/재실행 | 5번 JSON의 OFF/RESUME 신호 재생과 로컬의 프로세스 종료/전용 Room 복원은 다른 검증이다. 원격 제품 세션에 로컬과 같은 저장 후 프로세스 재실행 기능이 대체됐다고 확인하지 못했으므로 삭제 근거 없음 |
| 공통 API | MonitoringPipeline 생성 인자·VoiceCheckPort lifecycle/종료 처리·InterventionInput·AppNavigation·manifest가 겹친다. 파일을 한쪽으로 덮으면 다른 변경을 잃을 수 있음 |
| 시험 | 현재 로컬 원본 시험은30분/18,000개 기준이다. 5번 원본·UI가 반영되면 새 계약과 기대값으로 재실행해야 함. Git 텍스트 병합 성공만으로 실행 완료 판정 불가 |

따라서 **통합 후 검증은 여전히 필요**하다. 이번에는 통합 요청을 확대해 main을 병합하거나 다른 담당의 화면/엔진/음성 기능을 고치지 않았다.

## 디버그 삭제 판단과 실제 정리

`src/debug`의 기기 검증 화면·가짜 입력과 `src/test`/`src/androidTest`의 회귀시험은 기능이 다르다. 제품 화면이 생겨도 SDK 품질/TTL·원천Hz·권한·Room·부팅·시한을 확인하는 도구가 대체되지는 않는다.

| 대상 | 조치·이유 |
| --- | --- |
| 미사용 `AppNavigation.DebugButton` | **삭제**. 선언 외 호출0이며 기존 가짜 단계 조작 UI가 없어진 뒤 남은 함수 |
| debug application의 ‘AwakeDrive 개발 검증’ label override | **제거**. main의 앱 이름을 따르게 함. 개별 검증 Activity의 이름은 유지 |
| 수동/SDK/Hub/데모 검증 Activity·격리 서비스 | **보존**. 기존 androidTest들이 실제 클래스/명시적 진입·격리 DB 경로를 사용하며 미해결 신호 진단에도 필요 |
| DebugBootApplication/부팅 fixture | **보존**. 실제 부팅 복원 회귀 경로와 연결됨 |
| 3번 VoiceValidationActivity | **보존**. 다른 담당 검증 화면. 이번 정리 범위 밖 |
| 단위/결합/기기 시험·기존 진단 원본·DB | **보존**. UI 통합이 회귀 검증·증거를 대체하지 않음 |
| 로컬 ProductDemoController/Activity | **보존**. 최신 main을 병합해 예약/복원 기능 이관을 확인하기 전에는 완전 중복으로 판정 불가 |

release main manifest에는 debug 구성요소가 **0개**다. 개발용 화면을 보존해도 현재 release에 함께 등록되는 상황은 아니다. 이번 debug application 이름 변경은 기기에 설치하지 않았으므로 현재 설치 앱에 반영되지 않았다.

## 검증과 보존

```bash
cd /home/a/StudioProjects/sleepissue/src/android
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew \
  :app:testDebugUnitTest :app:processReleaseMainManifest
```

최종 앱461개 실제 재실행 통과, 실패·오류·skip0이다. main/test Kotlin 컴파일·debug/release manifest 처리도 성공했다. [실행 집계](evidence/2026-11-12-develop-compare-debug-cleanup/validation.json), [Gradle 로그](evidence/2026-11-12-develop-compare-debug-cleanup/validation.log). APK 생성·서명·설치·기기/DB/권한 조작은 하지 않았다. engine 소스 미변경이므로 전체 재실행하지 않고 이전351개 근거와 구분한다. release manifest 처리는 APK 빌드가 아니다.

시작 시1340개 파일 해시와 기존 diff를 개인 경로 `/home/a/.local/state/awakedrive-handoff/2026-11-12/develop-compare-debug-cleanup/`에 보존했다. 이번 실행 코드 변경은 **AppNavigation의 미사용 함수와 debug manifest의 앱 label만**이다. 비교 보고서·최신 인계 안내 외 기존 미커밋 내용은 삭제하지 않았다. [보존 범위](evidence/2026-11-12-develop-compare-debug-cleanup/scope.json).

다음 통합은 기존 미커밋을 먼저 보존한 상태에서 최신 main의 제품 경로를 기준으로1번 예약·품질·시동 끔/복원 계약을 이관하고 단위/결합 시험을 수행하는 순서다. APK/기기 검증은 사용자가 중단한 상태를 유지하며 재개 지시 전에는 진행하지 않는다.
