# 계정 인계 재개·최종 빌드·서명 전 APK 정리 — 2026-11-11

## 시작 상태와 이번 범위

`CURRENT_HANDOFF.md`, AGENTS/CLAUDE, PROGRESS, 기능 현황, 11/11 제품 데모 기록과 실제 diff를 먼저 읽었다. 저장소 간트 작업10·11·12·32·53·60·70·73, 관련 기획서의 제품 시연·시동 재현·제출 절도 대조했다. 제품 데모 코드가 이미 미커밋 상태로 구현됐으므로 처음부터 다시 작성하지 않았다. 이번 우선 작업은 인계6-B의 마지막 전체 시험/빌드와6-E의 서명 전 파일·README 정리다.

- 브랜치 `develop-jdw`, HEAD `9144cfd7eee1b078b6a988c7692a97792e4f4407` 유지.
- `git fetch origin` 후 origin/main 대비0/0, origin/develop-jdw 대비3/0. 새 main 변경이 없어 로컬 병합을 추가하지 않았다.
- 기존 미커밋 코드·처음부터 있던 marker/Perfetto 파일2개를 보존했다. 신규 source 구현을 완료했다고 주장하지 않는다. 기존 작성자의 구현을 이어 검증하고 배포·인계 문서를 마무리했다.
- 개인 작업트리 hash/patch·기기190파일 백업은 `/home/a/.local/state/awakedrive-handoff/2026-11-11/resume-20261111-230500/`에 보관했다. DB·원시 앱 파일은 Git에 추가하지 않는다.

## 이번에 실제 실행한 검증

```bash
cd /home/a/StudioProjects/sleepissue/src/android
export JAVA_HOME=/home/a/.local/opt/android-studio/jbr
export ANDROID_HOME=/home/a/Android/Sdk
./gradlew :engine:test :app:testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease
```

`BUILD SUCCESSFUL in 3m 46s`, 126 tasks(20 executed/106 up-to-date). JVM의 두 test task는 이번에 실행됐고 결과 XML을 집계했다. 캐시된 빌드 task까지 모두 새 실행이었다고 표현하지 않는다.

| 구분 | 결과 | 이번 실행 여부 |
| --- | --- | --- |
| engine JVM | **351개**, 실패/오류/skip0 | 실행. 마지막 추가24시간 만료 경계 포함 |
| app JVM | **422개**, 실패/오류/skip0 | 실행. 마지막 check.close 변경 포함 |
| 합계 | **773개 통과** | 이전350+422/748/746과 구분 |
| debug·AndroidTest APK | 빌드 성공 | 최종 소스 기준 |
| release APK | R8·패키징 성공 | 최종 소스 기준, unsigned |
| release Lint vital | 성공 | 전체 `lint`를 실행한 결과는 아님 |
| release manifest | 제품 DemoActivity 있음, debug/test/Isolated 구성요소0 | 이번 정적 대조 |
| release assets | 원본6종 각각18,000샘플·원본 JSON 바이트 일치 | 이번 APK 내용 대조 |
| debug 교체 설치 | `adb install -r -t`: Success | 데이터 유지. 삭제/초기화 없음 |

[수치·산출물·원본 동일성](evidence/2026-11-11-resume-final/validation.json).

## 기기시험의 재사용 범위

이전 인계의 실제 제품 기기시험3건(80.937초), TTS 실제 시작에서1배/종료 뒤10배, 제품 버튼6종, 실제 PID 종료→새PID에서22분 후 복원·인증1·연속0을 다시 실행하지 않았다. 새 debug APK와 개인 보존 `app-debug-verified-2100.apk`의 모든 ZIP entry를 비교했다. 차이는 `assets/README.md`와 `assets/demo.schema.json`뿐이고 DEX·manifest·화면 리소스·시나리오6종은 같다. 따라서 실행 내용에 영향을 주는 새 변경이 없는 상태에서 같은 기기시험을 반복하지 않고 기존 근거를 재사용했다. **이번 기기시험 개수는0**이다.

이 동일성은 이전 시험이 다루지 않은 DMO006 전체·동적 내비·현재온도 변경·실제STR·60배 성능을 검증한 근거가 아니다. release는 R8 산출물이므로 debug의 실행 동일성을 release 설치 성공으로 확대하지 않는다.

## 최종 산출물

| 파일(저장소 기준) | SHA-256 | 상태 |
| --- | --- | --- |
| `src/android/app/build/outputs/apk/debug/app-debug.apk` | `f2c1ddc8304920ca6e6f163551a070e049fce17991e3bc6587bbdcf0b1a44b3e` | 최신 교체 설치 APK와 일치 |
| `src/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `dcf3a9cb58c517c9d1ce19f3d05ba2e3d9502afecf3c943e34d19040382e51d3` | 이전 실제3건의 test APK와 동일, 이번 재설치/재실행 없음 |
| `src/android/app/build/outputs/apk/release/app-release-unsigned.apk` | `4b1425c51ee4f1a25bc94339f27ce3b6b980859fd658cf497347a48ce0ba792d` | 최종 소스 빌드, 서명 없음 |
| `apk/sleepissue_AwakeDrive_release-unsigned.apk` | 위 release와 같음 | 로컬 보관·Git ignore. 최종 서명 제출물 아님 |

`apksigner verify`는 exit1·서명 없음으로 판정했다. 팀 키/signingConfig가 없어 서명 및 release 설치/첫 화면≤5초 검증은 실행하지 못했다. 임의 키를 만들거나 debug 서명으로 제출 서명을 대신하지 않았다. [README 서명 절차](../../README.md#제출-apk서명)를 따른다. 인증서가 다른 기존 앱을 삭제하지 않고 별도 검증 기기에 설치해야 한다.

## 데이터·기기·프로세스 보존

교체 설치 전후 앱 databases/shared_prefs/cache **190개 파일 모두 바이트 동일**, 누락·추가·변경0이었다. 원래 사용자 DB3개는 별도 로컬 사본에서 논리 행·무결성도 대조해 동일했다. 사용자 DB를 열어 쓰거나 백업으로 덮어써 복구한 것이 아니다. 기존 DMO 수동 복원 결과와 demo checkpoint도 그대로 보존됐다.

- AVD `emulator-5554`, 운전자user10. 기존 AVD 실행 유지, Wipe/새 AVD 없음.
- 앱 PID 없음, 서비스 `(nothing)`, adb forward 빈 목록.
- 알림/차속 권한true, 마이크false 등 기존 권한 동일. 이번 마이크 허용/기기 조작 없음.
- Gradle daemon은 `./gradlew --stop`으로 정상 정리했다. 다른 Codex/Studio 프로세스를 종료하지 않았다.
- 커밋·push·PR·브랜치 전환·reset·clean·앱 삭제·DB 초기화 없음.

[전후190파일·사용자 DB3개·설치 해시·권한](evidence/2026-11-11-resume-final/preservation.json).

## 사용자가 확인하는 순서

```bash
/home/a/Android/Sdk/platform-tools/adb shell am start --user 10 \
  -n com.sleepissue.awakedrive/.ui.DrivingPreviewActivity
```

1. 실제 Console P/속도0, 기존 SDK·감시 중지 상태에서 **데모 → 시나리오 선택·재생**.
2. **정상/딴짓/졸음/대화/보조 주행/출발 피로** 선택 후 최초 JSON 검증을 기다린다. 제품 배지·원본 수·Hub 값이 실제로 나타나야 한다.
3. **일시정지→재개→중지**의 배지·시간·정리를 확인한다. 실제 질문 TTS는1배, 완료/실패/취소 후 원래 배속이다. SDK 우선 입력은 별도 선택한다.
4. **졸음 → 쉼터 P단 준비(모의) → 시동 끔(저장 후 앱 종료)**. 앱 아이콘으로 다시 열면22분 이동·인증1·연속0과 기존 ID/누적을 확인한다. 실제 시동/STR로 표현하지 않는다.
5. 확인 종료 후 **제품 홈으로** 돌아간다. 데모의 초기화 버튼은 사용자 REAL DB 초기화가 아니다.

## 남은 일

- 팀 서명 키로 release 서명·별도 기기 설치/기동, 실제 시연 후 TC 결과 기록.
- DMO006의 평소3회/15분 판정 조건, FatigueBridge 내부 학습 창 export/import 계약. 현재 복원은 기준선 참조까지이며 학습 창 전체 복원은 아니다.
- REAL 백그라운드 microphone FGS·CabinMonitor·녹음 fallback 리소스. 제품 데모의 화면 수명 정리와 구분한다.
- 조향 물리량·착석 UNKNOWN 의미 보류, 원천Hz·동적 위치/heading·현재온도 변경·실제 회전/TBT 입력 확인.
- SIG001110ms·SIG002전체200ms·SIG016 목적지1초 및60배 성능의 기존 미달/기점 불확실성. TTL·timestamp·기점 조작이나 호스트 원인 확정으로 해결 처리하지 않는다.

크루즈 활성은 11/11 공개 입력에서 실제 SDK ACTIVATED→Hub true를 확인했으므로 과거의 ‘활성 입력 미확인’은 현재 상태가 아니다. 그 기록을200ms 시한 통과나 모든 enum 검증으로 확대하지 않는다. [최신 현황](../setup/ROLE1_COMPLETION_STATUS.md)과 [현재 인계](../setup/CURRENT_HANDOFF.md)를 우선한다.
