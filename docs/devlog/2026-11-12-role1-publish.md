# 1번 현재 구현 공유·PR 준비 — 2026-11-12

사용자는 현재 로컬 상태, 데모 기준의 남은 일, 실제 완성품 기준의 남은 일을 정리하고 `develop-jdw`에push·main 대상PR 생성을 요청했다. 기능/미완료 구분은 [공유용 현황](../setup/ROLE1_DEMO_PRODUCT_STATUS.md), 파일별 차이는 [원격 비교 기록](2026-11-12-develop-compare-debug-cleanup.md)에 정리했다.

## 게시 기준

- 작업 시작 로컬HEAD `9144cfd7eee1b078b6a988c7692a97792e4f4407`, 원격develop-jdw `f59176fec44b8883545f9ddeef7d62335408b2c6`였다. PR19까지의 팀 조립3커밋은 이미 로컬에 포함돼 있었다.
- 이번fetch 후main은 `e44aae7ff91ae70921578e45e0c5a48ec6e996ab`이며5번 제품UI/45분10초 원본(PR20)을 포함한다. 이main을 병합하거나5번 코드를 덮어쓰지 않는다.
- 게시할 누적 실행 코드는 팀 JSON/tripPreset, 제품 데모 화면/Player/Hub 조립, 질문 lifecycle 배속, 데모 전용 저장/재실행, SDK 내비 갱신, 첫 확인 예약/저장 계약, 미사용 디버그UI 정리다. 새2번 점수식·게이트 구현이나3번 음성 판정 알고리즘 변경이 아니다.
- 과거 실험·실패·범위 원복 근거도 날짜별로 구분해 공유한다. `docs/devlog/evidence/`의 `.kt`는 감사 보관본이며 앱 실행 source set에 들어가지 않는다. 과거 성공을 현재 통합 완료로 합치지 않는다.

## 검증 근거

마지막 실제 실행은 앱461개(실패/오류/skip0)와 debug/release manifest 처리 성공이다. release의debug 구성요소0을 확인했다. 엔진351개는 같은 날 앞 실제 실행 근거를 재사용한다. 합계812개는 두 실행 범위의 합이며 이번 게시에서 전체를 다시 돌린 수치가 아니다.

게시 전 소스 해시를 마지막 검증 범위와 대조했다. 이후 변경은 문서뿐이므로 동일 전체시험을 반복하지 않았다. 최신 기기·음성·Room 재시험은 하지 않았다. APK 생성·설치·서명·DB/권한 조작도 하지 않는다. 원본6종 JVM 결합과 실제 AVD/60배 성능 시험은 다르다.

[마지막 앱/manifest 집계](evidence/2026-11-12-develop-compare-debug-cleanup/validation.json), [원본·예약 시험](evidence/2026-11-12-product-demo-usable/tests.json), [이전 엔진 실제 실행](evidence/2026-11-12-role1-fatigue-input/tests.json)을 따른다.

## 포함·제외와 보존

관련 소스·시험·manifest·Gradle assets 설정·schema/README·인계/상태표·실험/최종 근거를 선별한다. 문서가 참조하는 새 검증 로그는 `.gitignore`의 일반 `*.log` 예외로 해당 파일만 명시적으로 추가한다. 실제DB/WAL/SHM·APK/AAB·SDK 바이너리·키/CRN·local.properties·환경 파일은 포함하지 않는다. 비밀값 검사는 값 원문을 출력하지 않는 방식으로 진행한다.

처음부터 존재하던 임시2개는 삭제하거나 커밋하지 않고 이 PC에 남긴다.

- `docs/devlog/evidence/2026-11-09-plt013/navigation-confirmed.marker`
- `docs/devlog/evidence/2026-11-09-role1-remaining/perfetto-start.txt`

작업 전diff와파일1344개 해시는 개인 경로 `/home/a/.local/state/awakedrive-handoff/2026-11-12/role1-publish/`에 보존했다. 이 개인 경로의 diff/원문백업은 공유 대상이 아니다. 최종 선별 목록·해시/제외는 같은 경로의 `selection.json`과 아래공유집계를 따른다.

## PR에서 반드시 확인할 일

최신main의5번 작업과 AppNavigation/Activity, MonitoringPipeline, VoiceCheckPort, DemoJsonLoader, assets 등이 겹친다. 첫 확인은5번의DemoUiSession/DemoExecution 경로에 시간축을 맞춰 이관해야 하고, 로컬Room 프로세스 재실행 시연도 보존해야 한다. 단순 파일 덮어쓰기·자동merge로 통합하지 않는다. 원격 병합 가능 여부는 PR 생성 후 실제 상태를 확인한다.

남은 SDK 주기/지연·신호 의미·동적입력·전원/STR·음성FGS·엔진 학습창 복원·배포 조건은 [데모/완성품 구분표](../setup/ROLE1_DEMO_PRODUCT_STATUS.md)에 남겼다. 시연·완성품 전체 완료를 선언하지 않는다. PR은 검토용초안으로 만들고, main직접push·PR merge·auto-merge는 수행하지 않는다. 최종커밋/원격SHA·PR주소는 게시 결과와 GitHub를 확인한다.
