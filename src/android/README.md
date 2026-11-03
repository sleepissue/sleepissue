# src/android — Gradle 루트

Android Studio에서 이 폴더를 연다. 앱 패키지는 `com.sleepissue.awakedrive`, 공통 계약은 `com.sleepissue.awakedrive.engine`이다.

## 현재 구현 범위

- `engine/`: Android 비의존 공통 모델, 세 시간축 `Clock`, `EngineConfig`, 정차·운행 규칙 `TripRules`, 최근 구간 합산 `RollingDriveWindow`, 단계 전환 `InterventionPolicy`, 원천 10초 `RateMeter`와 필드 최신성·선택 규칙. 조향 기반 졸음 점수 계산기는 후속 작업이다.
- `app/.../signal/`: `VehicleSignalHub`의 수동 관측 경로와 정책 기반 10Hz/REAL·DEMO 중재 경로, 공유 단위 정규화, 메모리 `TripStateStore`, 규칙 연결 `TripTracker`, 입출력 ports, `InterventionManager` 계약과 정책 연결 `PolicyInterventionManager`, 명시적 미연결 구현.
- `app/.../demo/`: `DemoPlayer` 계약과 명시적 미연결 구현. JSON 로드·배속·재생은 후속 작업이다.
- `app/.../data/`: 실제 `RoomTripRepository`·트랜잭션 DAO·스키마. 공개 운행 10종과 정밀 운행/개입 근거·DriveSegment를 함께 저장한다. `signal/TripSaveCoordinator`가 저장 주기·실패·삭제를 관리한다.
- `app/src/test/`: 결정적인 Fake, 연결·단위·품질·출처·중지·외부 요청 계약 시험과 실행 가능한 사용 예시. Fake는 배포 코드에 포함하지 않는다.
- `app/src/debug/`: 기존 Hub·운행·개입 로직을 수동 조작하는 개발 검증 화면·전용 런처. 가상 시간·기어·시동·위험 점수·확인·휴식 결과를 주입하고 상태·요청·시간순 기록·Room 저장/복원을 표시한다. 독립 가짜 REAL/DEMO Hub 패널로 주기·원천 Hz·만료·중재를 확인한다. `release`에는 포함되지 않는다.
- `app/src/testDebug/`: 디버그 화면의 조립·시간 진행·결과 주입·초기화 회귀 시험.
- `app/src/androidTest/.../data/`: 실제 Room round-trip·원자성·손상/실패/삭제·실제 경과 10초 저장 시험.

다른 담당의 연결 방법은 [A 기본 틀 가이드](../../docs/setup/ROLE1_SCAFFOLD.md), 실제 작업·검증 기록은 [2026-11-01 개발 기록](../../docs/devlog/2026-11-01.md), 모듈 전체 상태는 [PROGRESS](../../PROGRESS.md)를 본다.

2026-11-02에 추가한 [운행 규칙 가이드](../../docs/setup/ROLE1_TRIP_RULES.md)는 정책 선택·밀리초 정밀 상태·Hub 연결을 설명한다. **개발 대상은 Pleos Playground 가상 차량**이다. REAL은 SDK·Virtual Console 입력, DEMO는 앱 JSON 재생 입력이다. 이번 운행 규칙의 실행 결과는 [11-02 개발 기록](../../docs/devlog/2026-11-02.md)에 별도로 남긴다.

[디버그 화면 사용 가이드](../../docs/setup/DEBUG_HARNESS.md)에 Android Studio·ADB 실행 방법과 직접 따라 할 검증 순서를 정리했다. Android Studio에서 app의 `debug` 변형을 선택해 Run하면 `AwakeDrive 개발 검증`이 열린다. 실제 음성·차량·내비 SDK는 실행하지 않으며 가상 결과를 직접 주입한다.

## 빌드 환경

| 설정 | 값·근거 |
| --- | --- |
| Gradle / AGP | 9.6.0 / 9.4.0 유지. [AGP 공식 호환 표](https://developer.android.com/build/releases/agp-9-4-0-release-notes). |
| Kotlin JVM 플러그인 | 2.4.21, 버전 카탈로그에서 고정. [Kotlin 공식 Gradle 호환 표](https://kotlinlang.org/docs/gradle-configure-project.html). |
| app Kotlin | AGP 내장 Kotlin 유지. 별도 `kotlin-android` 플러그인은 적용하지 않는다. 루트에서 선택한 KGP를 공통 사용한다. |
| Gradle 데몬 JDK | 기존 25 유지. [Gradle 공식 Java 호환 표](https://docs.gradle.org/current/userguide/compatibility.html#java_runtime)에서 9.1 이상은 Java 25 실행 지원. |
| Java / Kotlin 바이트코드 | 두 모듈 모두 11. 빌드 실행용 JDK와 별개다. |
| Android SDK | min 28, compile/target 37 유지. Build Tools 기본값 36.0.0. 로컬에 부족했던 Platform 37·Build Tools 36을 설치했다. |
| Coroutines | core/test 1.10.2. Flow 전달과 가상 시간 시험에 사용. |
| Room / KSP2 | app에 runtime/compiler 2.8.5와 KSP 2.3.13 추가. [공식 근거와 적용 범위](../../docs/setup/ROLE1_PERSISTENCE.md#저장소와-호환성). |

KGP 표의 AGP 완전 지원 상한은 9.3.1이다. 이 프로젝트는 AGP 9.4의 내장 Kotlin과 JVM 모듈을 실제 로컬 빌드로 검증하며, 공식 표가 이 조합 전체를 보증한다고 해석하지 않는다.

이전 README는 JVM 플러그인의 버전 미기재를 반드시 발생하는 오류로 설명했지만, 실제로는 AGP가 전이 공급한 KGP가 해석됐다. 최초 실패는 Java 11 / Kotlin 24 타깃 불일치였다. 명시적인 플러그인 버전과 Kotlin JVM 타깃으로 실행 환경 의존성을 줄였다.

Android Studio의 Gradle JDK에 JBR 25를 선택하거나, 터미널에서는 설치한 JDK 25를 `JAVA_HOME`으로 지정한다. 이 PC에서 사용한 예시는 다음과 같다. 개인 절대 경로를 Gradle 설정에 커밋하지 않는다.

```bash
export JAVA_HOME=/home/a/.local/opt/android-studio/jbr
export ANDROID_HOME=/home/a/Android/Sdk
cd /home/a/StudioProjects/sleepissue/src/android
./gradlew :engine:test :app:testDebugUnitTest assembleDebug
```

SDK 경로는 로컬 환경 변수 또는 Git 제외된 `local.properties`로 제공한다. 기존 SDK 34만 설치되어 있어도 compile/target을 낮추지 않는다. 저장소에 Wrapper JAR가 있으며 Gradle·SDK 의존성 최초 다운로드에는 네트워크가 필요하다.

## 검증과 남은 작업

2026-11-01 실제 실행: `:engine:test` 10개, `:app:testDebugUnitTest` 27개(기존 예제 1개 포함), `assembleDebug` 모두 성공했다. 실패·오류·건너뜀은 0개다. 실행 가능한 사용 예시도 앱 테스트에 포함된다. 상세 실패 원인과 수정·검증 결과는 개발 기록에 남겼다.

2026-11-02 운행 규칙 추가 후 실제 실행: engine 41개·app 39개(합계 80개, 기존 예제 1개 포함) 모두 통과했고 `assembleDebug`도 성공했다. 정책 경계·24시간 부분 절단·Hub 연결·동시 갱신을 포함한다. 기기 SDK 수신·영속 복원·제품 정책 승인을 대신하지 않는다.

2026-11-02 디버그 화면 추가 후: engine 61개·app 81개(신규 조립 시험 12개 포함), 합계 142개가 통과했다. debug/release 빌드와 Lint 오류 0개를 확인했고 release APK·매니페스트에는 개발 화면이 없다. Pleos 에뮬레이터에서 운행·느림 두 번·휴식 인증/회복·전체 초기화 버튼 흐름을 확인했다. 상세 범위와 경고는 [디버그 검증 결과](../../docs/setup/DEBUG_HARNESS.md#2026-11-02-검증-결과)를 참고한다.

2026-11-03: 위 142개에 포함됐던 잘못된 사유 기대값을 고치고, 점수 3단계가 확인 대기 때문에 안전 안내로 연결되던 정책 결함을 수정했다. 전체 engine 65개·app 84개, 합계 149개와 `assembleDebug`가 통과했다. 수정 APK의 0/30/60초 버튼 시험에서 `SCORE`·일반 안내를 확인했고, 명시적 안전 우회는 자동 시험으로 분리했다. [이번 개발 기록](../../docs/devlog/2026-11-03.md)과 [직접 누를 버튼](../../docs/setup/DEBUG_HARNESS.md#높은-점수의-단계별-상승)을 본다. 이번에 release APK·Lint를 다시 실행한 것은 아니다.

`:engine:test`는 순수 Kotlin 계약 시험이며, `:app:testDebugUnitTest`도 에뮬레이터 없이 실행한다. 기기 시험·실제 API 호출·앱 첫 화면 실행을 대신하지 않는다. 생성물은 `app/build/outputs/apk/debug/app-debug.apk`이며 Git 제외 대상이다.

Pleos Maven과 Vehicle·NaviHelper·TextToSpeech·SpeechToText 의존성은 기존 설정을 유지한다. 이번 작업은 SDK 메서드를 호출하지 않으며 권한·지원·원단위·장치 연동이 검증된 것은 아니다.

2026-11-03 Room 추가 후: 전체 engine 69개·app 99개(합계 **168개**)와 `assembleDebug` 성공. Pleos 에뮬레이터의 전체 기기 시험 **9개**(실제 Room 8개·기존 앱 문맥 1개)도 통과했다. 저장·재실행·정차 보정·개입/인증/회복·삭제 버튼 시험은 [저장·복원 가이드](../../docs/setup/ROLE1_PERSISTENCE.md)와 [개발 기록](../../docs/devlog/2026-11-03.md#room-저장복원-구현)을 따른다. 기기 시험은 실행 중인 에뮬레이터가 필요하며 테스트 러너의 설치/제거로 대상 앱 DB가 지워질 수 있다.

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sleepissue.awakedrive.data.RoomTripRepositoryTest
```

현재 `debug` 빌드는 실제 Room DB에서 수동 가상 시각·정밀 계산·개입 상태를 복원한다. **전체 테스트 상태 초기화는 메모리만**, **저장 데이터 삭제는 DB만** 지우며 둘 다 자동 저장을 중지한다. 이전 음성·경유지는 자동 재실행하지 않는다. 닫힌 실제 시간을 운전 시간으로 더하지 않고 앱 종료를 OFF로 바꾸지 않는다.

2026-11-03 Hub 추가 후: engine **83개**·app **120개**, 합계 **203개** 통과. 기존 168개를 보존하고 주기·원천 Hz·최신성·중재·Hub 패널·수동 저장 세션 분리 시험 35개를 추가했다. 전체 Room/앱 문맥 기기 시험 **9개**와 `assembleDebug`도 성공했다. [주기·버퍼·생명주기 계약과 직접 누를 버튼](../../docs/setup/ROLE1_SIGNAL_HUB.md)을 참고한다.

최종 Compose 서비스 화면·감시 서비스·부팅 수신기·실제 재부팅 시간축은 후속 범위이며 TRP_003 전체 완료가 아니다. Hub의 가짜 입력 중재·주기 발행·원천 Hz·최신성은 구현했으며, 실제 SDK 원단위/관측 시간축 확인·콜백 어댑터와 JSON 재생·제품 최신성 정책 확정은 남아 있다. 디버그 조립은 비교용 정차 정책 `V03_RETROACTIVE`·`EXCLUDE`를 [가정]으로 명시하고, 정상 회복 후 상태 초기화 요청은 미연결로 표시한다. 실제 Playground SDK가 필요한 TC를 통과한 것으로 표시하지 않는다.
