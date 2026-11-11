# 2026-11-10 — 1번 선별 커밋·push 검토

AGENTS.md, 최신 현황표·capability/TRP003 기록을 읽고 develop-jdw / 68696cf158f5cc94ea3b257b87cc2b01bba4a122의 추적17개+새219개=236개 파일(4,671,329bytes)을 검토했다. 시작 목록·해시·diff는 `/tmp/awakedrive-role1-push-gra39gsd/`에 별도 보존했다. 제품 코드 추가 수정 없이 검증된 내용을 선별한다. 브랜치 전환·병합·이력 재작성은 하지 않는다.

## 코드 커밋과 포함 범위

| 커밋 | 범위 |
| --- | --- |
| bb657c1e89e2b844e57d4ad827fdaa38a6f431ca | 신호 주기 계측과 capability 미지원 처리 보완. 예약/실행/준비/emit/소비 분리·OFF 포장/예외 잠금 보호·SDK 읽기 경계·공통 착석 로그·회귀/명시 모의,15개 파일 |
| e9815b6dff696f85c550beecea50d9268ea958f6 | 감시 서비스 격리와 실제 부팅 복원 시험 경로. 독립 알림/자기중지·실제 소비/저장 감사·제품 BOOT 경계·debug 격리/준비·기기시험,14개 파일 |
| 이 기록을 포함하는 후속 문서 커밋 | 현황표/개발 기록·선별된 원본/분석기/빌드/기기 근거. 최종 SHA는 작업 결과 또는 `git log -1 --format=%H`로 확인 |

각 커밋은 명시적 파일 목록의 `git add -- 경로...`만 사용한다. `git add .`는 사용하지 않는다. 원래236개 중234개를 포함하며, 아래2개는 로컬에 그대로 남긴다. 이 기록·선별/일치 대조 JSON2개·원시CSV 줄끝 보존용 .gitattributes1개를 검토 산출물로 추가한다(새 파일4개).

| 제외·로컬 보존 | 이유 |
| --- | --- |
| docs/devlog/evidence/2026-11-09-plt013/navigation-confirmed.marker | 내용 없는 시험 동기화 임시 marker. 화면·관찰·프레임 원본이 실질 근거 |
| docs/devlog/evidence/2026-11-09-role1-remaining/perfetto-start.txt | 단독 Perfetto 프로세스 PID. 실행 중 임시 프로세스 산출물 |

DB/WAL/SHM·APK·SDK 바이너리·환경/키 파일·build/임시 디렉터리를 포함하지 않는다. docs의 시험용 requested/arm XML은 비밀 없는 **격리 시험의 의사/종료 증거**이며 사용자 환경 설정 원본은 아니다. sched.pbtxt도 사용자 AVD 설정이 아닌 수집된 trace의 재현 조건이다. 원본 텍스트/압축 JSON·CSV·기기 화면3장은 필수 조건/실패·한계를 재검토할 근거로 선별했다. 실패 실험 로그를 성공으로 바꾸거나 원본을 삭제하지 않는다.

Git 기본 변환이 원시CSV5개의 CRLF를 LF로 바꾸려 해 해당 진단 폴더의 *-frames.csv에만 -text 규칙을 두었다. 원본 파일은 수정하지 않으며 Git blob도 시작SHA와 일치하게 보존한다. raw runner/javap17개 파일의 EOF 공백 경고는 진단 원문이라 정리하지 않는다. 코드/문서의 공백 오류와 구분한다.

## 최종 시험·APK와 소스 일치

[대조 JSON](evidence/2026-11-10-role1-publish/verification-match.json): 최종 TRP003의 코드/manifest 해시211개 전부 동일, 새 미검증 Kotlin 파일0. 실제 XML은 engine333+app413=746, 실패/오류/skip0이며 기존 build-results와 일치한다. 로컬 debug/test APK SHA도 실제 설치·재부팅 검증의 기록과 각각 동일하다.

- debug: 0ec263c098accba2607a4f605b725149b1f1f58bff75f4226ddee17adfff4c52
- AndroidTest: c5b01b5150814fb8c414eb50d15f656a2cbec81ca1dd88abe6ce31a379684343

소스가 변하지 않아 전체 Gradle 시험·30분·재부팅을 반복하지 않았다. 보존 원본의 읽기 전용 validate_boot.py만 재실행해 원본10종/전체checkpoint·자동시작1·중지후0·새입력전UNKNOWN을 다시 확인했다. [최종 기기/빌드 근거](2026-11-10-trp003-reboot.md), [capability 조건/한계](2026-11-10-role1-capability.md). 이번 커밋 검토에서는 기기를 조작하지 않았다. 과거의 사용자DB/진단 보존 결과를 이번 새 기기시험으로 세지 않는다.

## 원격과 PR 조회

일반 fetch 후 origin/develop-jdw는 f5c6ad9e179383ddb2b5cff3aad9ac4230afabe2이며 시작 HEAD보다62커밋 뒤, 원격에만 있는 커밋0이었다. 기존 로컬 이력에는 이미 수행한 main 병합이 포함된다. 이번에는 새 병합하지 않고 `git push origin develop-jdw:develop-jdw`로 기존 이력과 선별 커밋을 함께 반영한다. force/main push는 하지 않는다.

조회한 main은5195e258470de85a9fe4337a5abb439199e3f013이며 시작 HEAD의 조상이다. main 대상 develop-jdw PR은 [#6](https://github.com/sleepissue/sleepissue/pull/6) 병합 이력만 있고 열린 PR은 없었다. push 후에도 로컬/원격 SHA·main 대비 차이·PR 상태를 읽기 전용으로 확인한다. 새 PR 생성/수정·PR merge·메시지 전송은 하지 않는다. 최종 push 응답과 SHA는 작업 결과 보고를 따른다.

## 남은 요구

**1번 전체 기능 완료가 아니다.** SIG001 엄격110ms·SIG002 전체200ms는 미달 유지다. 이동 heading·동적 내비·현재온도 변경/크루즈 활성 입력은 미확인, 조향/착석 의미와 SIG010 정적 검사 범위는 보류, 실제 OFF→ON/STR은 환경 제한, SDK 원천Hz·다른 담당 제품 연결도 남아 있다. capability/30분/실제부팅의 해당 조건 검증을 다른 요구 전체 Pass로 확장하지 않는다. [31개 요구별 최종 표](../setup/ROLE1_COMPLETION_STATUS.md#요구별-최종-상태)를 따른다.
