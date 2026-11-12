# 제품 데모 재생과 첫 확인 예약 연결 — 2026-11-12

## 이번 요청과 작업 경계

사용자 요청은 ‘데모가 동작하는 데 필요한 일을 확인하고 그것부터 진행’이다. 간트 `docs/plan/AwakeDrive_일정_v1.gan`의 **task53 DemoPlayer**, **task32 단계 전환 상태 머신**은 모두 resource0(1번) 배정이다. 앞선 ‘1번만 수정’과 ‘APK 하지마’를 유지한다. 2번 판단·기준선 알고리즘, 3번 음성 구현, 다른 제품 UI, 5번 원본 시나리오 신호는 수정하지 않는다.

처음 확인한 필요한 일은 다음과 같다.

| 순서 | 필요한 일 | 이번 상태 |
| --- | --- | --- |
| 1 | 원본6종을 제품 Player→Hub→운행→기존 판단·개입으로 끝까지 전달 | JVM 결합 시험7개로 확인. 실제 Android 음성/SDK 포함 시연은 별도 |
| 2 | 원본 배속을 보존하면서 짧은 시연용 전체10배를 명시적으로 선택 | 제품 데모 전용 화면·컨트롤러에 구현 |
| 3 | 출발 피로 엔진의 첫 확인 예정 시각을 1번 개입 관리자에 전달 | 제품 조립 연결·중복 방지·취소·저장 계약 구현 |
| 4 | 출발 피로 원본이 첫 확인 장면까지 이어지는지 확인 | **현재 원본 길이 부족**. 원본/예정 시각을 조작하지 않고 인계 조건 기록 |
| 5 | 실제 음성 중1배·원래 배속 복귀, 앱 종료/복원 직접 시연 | 이전 구현 보존. 이번 변경 APK 생성/설치·기기 재검증 안 함 |

## 변경한 파일과 이유

기존8개 파일만 변경하고 새 Kotlin4개를 추가했다. 앞서 존재한 미커밋 작업은 유지했다.

| 파일 (`src/android/app/src/` 기준) | 이번 변경 |
| --- | --- |
| `main/java/com/sleepissue/awakedrive/demo/ProductDemoSpeed.kt` (새 파일) | JSON 구간·전체1/10/60배의 재생 정책. 선택 배속은 이번 재생 사본에만 적용 |
| `main/java/com/sleepissue/awakedrive/demo/ProductDemoController.kt` | 로드한 시나리오의 재생 사본에 선택 정책 적용. 기존 호출 기본값은 JSON 구간 |
| `main/java/com/sleepissue/awakedrive/ui/ProductDemoActivity.kt` | 1번 제품 데모 전용 배속 선택. 기본값 JSON 구간·60배 시험용 안내. 일반 홈/주행 UI 변경 없음 |
| `main/java/com/sleepissue/awakedrive/service/AwakeDrivePipeline.kt` | 기존 `runner.snapshots.baseline.firstCheckDueMs`를 운행ID와 함께 매니저에 전달. 새 타이머는 만들지 않음 |
| `main/java/com/sleepissue/awakedrive/signal/InterventionManager.kt` | `FirstCheckScheduleChanged(tripId,dueAtMs)` 계약 |
| `main/java/com/sleepissue/awakedrive/signal/PolicyInterventionManager.kt` | 예정 시각의 확인 요청1회, 기존 확인 시작과의 중복 방지, 대상/단계 변경·휴식·중지·새 운행 취소, 늦은 결과 거부 |
| `main/java/com/sleepissue/awakedrive/signal/InterventionCheckpoint.kt` | 예정·요청 여부·중단 상태 저장 및 유효성 검사 |
| `main/java/com/sleepissue/awakedrive/signal/TripSessionCodec.kt` | owner payload v2. 이전 v1 읽기 보존. 실제 DB 스키마 변경/초기화 없음 |
| `test/java/com/sleepissue/awakedrive/demo/ProductDemoSpeedTest.kt` (새 파일) | 재생 사본·원본 보존·질문1배/종료복귀·일시정지4개 |
| `test/java/com/sleepissue/awakedrive/demo/ProductDemoPipelineContractTest.kt` (새 파일) | 원본6종 전체10배와 출발 피로 원본구간 재생1종, 총7개 |
| `test/java/com/sleepissue/awakedrive/signal/FirstCheckScheduleTest.kt` (새 파일) | 경계·중복·취소·휴식·대상·장치실패 구분·저장/복원·v1호환13개 |
| `test/java/com/sleepissue/awakedrive/service/AwakeDrivePipelineTest.kt` | 기존 시험 유지, 실제 runner 결과→제품 조립→확인 포트 연결 시험1개 추가 |

첫 확인의15분은 **2번이 이미 계산한 예정 시각을 그대로 사용**한다. 매니저가 다시15분을 더하지 않는다. 다른 운행ID·새 운행 시작 전/이미 인증된 휴식 전의 예약은 거부한다. [해석] 예약만으로 위험 점수나 단계를 올리지 않고 기존 확인 포트에 요청한다. 첫 질문 중 다음 Tick이 질문을 단계 전환 근거로 다시 해석해 취소하지 않도록 일반 위험 확인과 구분했다. 안내 단계 변경으로 취소한 미완료 첫 확인은 완료로 소모하지 않고 새 단계의 요청으로 잇는다. 실제 느림·무응답 결과는 기존 정책으로 처리하고 장치 권한 오류를 사람의 무응답으로 만들지 않는다.

미래 예약과 중단된 첫 질문은 codec 왕복에서 보존한다. 중단 질문 복원은 기존 명시적 재개 경로에서 새 요청ID를 발급한다. **이 시험은 저장 계약 확인이며 제품 재실행 후 2번 엔진 내부 학습 창까지 복원했다는 의미가 아니다.**

## 배속과 원본 시나리오

처음 ‘원본에 배속이 없다/1배’라고 잘못 읽어 사용자에게 설명했으나, 실제 파일을 대조해 정정했다. 최종 기본값은 원본 JSON의 구간 배속이다.

| 시나리오 | 원본 구간 배속 | 질문 없는 원본 재생 시간 | 명시적 전체10배 |
| --- | --- | --- | --- |
| 정상·대화 | 0~1800초10배 | 약3분 | 약3분 |
| 딴짓·졸음·보조 주행·출발 피로 | 0~900초10배, 900~1800초1배 | 약16분30초 | 약3분 |

전체1/10/60배 선택은 **관측값·원본 시각·tripPreset·게이트 문턱을 변경하지 않는다**. 기존 DemoClock과 DemoVoiceSpeedGate가 실제 질문 lifecycle 동안1배를 우선한다. 질문/일시정지가 있으면 실제 완료 시간은 위 계산보다 길어진다. 60배의 기존 신호 공백·deadline 성능은 해결하지 않았으며 화면에도 시험용으로 표시한다.

## 실제 실행한 검증

```bash
cd /home/a/StudioProjects/sleepissue/src/android
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest \
  --tests com.sleepissue.awakedrive.demo.ProductDemoSpeedTest \
  --tests com.sleepissue.awakedrive.demo.ProductDemoPipelineContractTest
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest \
  --tests com.sleepissue.awakedrive.signal.FirstCheckScheduleTest \
  --tests com.sleepissue.awakedrive.signal.InterventionPersistenceTest \
  --tests com.sleepissue.awakedrive.signal.PolicyInterventionManagerTest \
  --tests com.sleepissue.awakedrive.service.AwakeDrivePipelineTest
JAVA_HOME=/home/a/.local/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest
```

최종 앱 **461개 실제 실행**, 실패·오류·skip0. 시작 시436개에서 신규25개가 추가됐다(배속4+원본7+예약13+제품 연결1). 같은 날 앞 작업에서 실제 재실행한 엔진351개는 소스·시험이 바뀌지 않아 재사용했다. 합계 **812개**, 이번 엔진 전체 재실행을 뜻하지 않는다. 앱 main/test Kotlin 컴파일도 통과했다. **assembleDebug·package APK·install·release·Lint·AndroidTest는 이번 실행하지 않았다.**

초기 새 시험에서 잘못 쓴 TripRules 자료형/생성자, 원본 배속 기대값, 포트 반환 자료형과 첫 D단의100ms를 빠뜨린 기대값 때문에 컴파일/기대값 실패가 있었다. 실제 코드 정의·원본 파일·엔진 출력에 맞춰 시험을 수정했다. 엔진의 예정 시각을 시험에 맞춰 변경하지 않았다. 실패 로그와 최종 로그를 근거 폴더에 구분해 보존했다.

[XML 집계와 실행 경계](evidence/2026-11-12-product-demo-usable/tests.json), [최종 앱 실행](evidence/2026-11-12-product-demo-usable/app-final-tests-2.log).

### 원본 전체 입력 결합 시험 결과

각 원본18,000개를 COMPLETE_OBSERVATION 경로로 전달했다. 첫 t=0 관측은 첫 Hub100ms 발행 전이므로 발행 프레임은17,999개다. 조향/차속/기어의 예상외null0을 확인했다. 중지 뒤 마지막 frame은null이다.

| 원본 (전체10배) | 유효 계산 수 | 최대 평활 위험 | 최대 단계 | 추가 관찰 |
| --- | ---: | ---: | ---: | --- |
| 정상 | 58 | 0 | 1 | 오후 취약 시간대의 맥락 안내. 졸음 점수로 올린 단계가 아님 |
| 대화 | 58 | 0 | 1 | 정상과 같은 점수, 원본 음성은 실제 재생하지 않음 |
| 딴짓 | 58 | 21.62706 | 2 | 기존 패턴/안전 이벤트 처리 결과 |
| 졸음 | 58 | 90.62792 | 3 | 기존 엔진→개입의 휴식 유도 도달 |
| 보조 주행 | 47 | 4.425382 | 1 | 보조 주행 모드 인식. 전체 인계/주기 질문 완료 판정 아님 |
| 출발 피로 | 58 | 55.646294 | 2 | 민감 모드 감지, 첫 확인 예정은1810100ms |

출발 피로는 원본10→1배에서도 같은 결과를 확인했다. 전체10배에서 가상 실제180,000ms, 원본 구간에서는990,000ms에 정상 완료했다. 점수·단계·사람 응답을 주입하지 않고 기존 판단을 호출했다.

이 시험은 **JVM 가상 시계**다. 원본 전달/상태 연결을 확인하며 실제 AVD에서 같은 시간·성능·음성 완료를 보증하지 않는다. 요청은 기록용 포트로 받았고, 실제 TTS/STT·음성 실패 대체·쉼터 검색·제어·보상은 실행하지 않았다. 단계 도달은 실제 사람 확인을 끝냈다는 뜻이 아니다. 보조 주행 원본의 cruise 구간은 약5분이라10분/20분 주기 확인과 인계 실패 전체를 입증하지 않는다.

## 남은 조건과 정확한 다음 작업

1. **출발 피로 원본 길이**: 현재 총1800000ms인데 엔진 첫 확인 예정은1810100ms다. 종료보다10.1초 뒤다. 충분한 원본 관측을 시나리오 담당이 추가해야 한다. 보간/마지막 값 복제로 원천 관측을 만들거나15분 규칙을 줄이지 않았다. 3회 평소 기준선 이력 조건도 이번 원본 시험은 사용하지 않아 `TC_DMO_006` 전체Pass가 아니다.
2. **실제 제품 재검증**: APK 중단 상태라 새 선택 화면과 예약 변경은 설치 기기에 반영되지 않았다. APK 재개를 사용자가 요청한 후에만 새 빌드·데이터 유지 설치를 진행한다. 그 뒤 [제품 직접 확인 순서](../setup/ROLE1_DEMO_RUNBOOK.md)를 따른다. 기존11/11~12 기기 근거를 이번 코드의 재검증으로 세지 않는다.
3. **60배·REAL 신호 제한**: 기존 SIG 주기/전체 지연 미달, 조향 물리량·착석 의미 보류, 동적 내비/현재온도 입력 미확인은 유지한다. 먼저 SDK 우선 입력을 끈 JSON 시연으로 기능을 확인하고, 실제 SDK 중재 시험은 따로 구분한다.

## 보존과 종료 상태

`develop-jdw` / HEAD `9144cfd7eee1b078b6a988c7692a97792e4f4407` 유지. 원격 네트워크 조회·커밋·push·PR·병합 없음. 시작 시406개 파일 중398개 바이트 동일, 변경8개는 위 목록과 일치했다. engine 소스/시험121개, app/fatigue3개, app/voice18개, data/scenarios10개는 전부 동일하다. [범위 해시 대조](evidence/2026-11-12-product-demo-usable/scope.json).

기존 diff와 변경 전 두 제품 데모 파일은 개인 경로 `/home/a/.local/state/awakedrive-handoff/2026-11-12/product-demo-usable/`에 보존했다. 이 작업은 adb·에뮬레이터·감시 서비스·SDK 소유권·권한·사용자DB를 읽거나 조작하지 않았다. 현재 기기 상태를 새로 확인한 것처럼 보고하지 않는다. 앱 JVM 시험 프로세스는 정상 종료했다. Gradle 공유 데몬을 임의 종료하지 않는다. 기존 미커밋 및 이전 다른 담당 수정도 지우지 않았다.
