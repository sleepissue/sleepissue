# src/android — Gradle 루트

Android Studio에서 이 폴더를 연다. 앱 패키지는 `com.sleepissue.awakedrive`, 공통 계약은 `com.sleepissue.awakedrive.engine`이다.

## 현재 구현 범위

- `engine/`: Android 비의존 공통 모델, 세 시간축 `Clock`, 최소 `EngineConfig`. 판단·정차·단계 알고리즘은 아직 없다.
- `app/.../signal/`: 단일 입력을 정규화하는 `VehicleSignalHub`, 메모리 `TripStateStore`, 입출력 ports, `InterventionManager` 계약과 명시적 미연결 구현.
- `app/.../demo/`: `DemoPlayer` 계약과 명시적 미연결 구현. JSON 로드·배속·재생은 후속 작업이다.
- `app/src/test/`: 결정적인 Fake, 연결·단위·품질·출처·중지·외부 요청 계약 시험과 실행 가능한 사용 예시. Fake는 배포 코드에 포함하지 않는다.

다른 담당의 연결 방법은 [A 기본 틀 가이드](../../docs/setup/ROLE1_SCAFFOLD.md), 실제 작업·검증 기록은 [2026-11-01 개발 기록](../../docs/devlog/2026-11-01.md), 모듈 전체 상태는 [PROGRESS](../../PROGRESS.md)를 본다.

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

`:engine:test`는 순수 Kotlin 계약 시험이며, `:app:testDebugUnitTest`도 에뮬레이터 없이 실행한다. 기기 시험·실제 API 호출·앱 첫 화면 실행을 대신하지 않는다. 생성물은 `app/build/outputs/apk/debug/app-debug.apk`이며 Git 제외 대상이다.

Pleos Maven과 Vehicle·NaviHelper·TextToSpeech·SpeechToText 의존성은 기존 설정을 유지한다. 이번 작업은 SDK 메서드를 호출하지 않으며 권한·지원·원단위·장치 연동이 검증된 것은 아니다.

Activity·런처·Compose 화면·Room·서비스·부팅 수신기는 아직 없다. APK 생성 성공도 실행 화면 완성을 뜻하지 않는다. 실제/데모 입력 중재, 주기 발행·원천 Hz·최신성 판단, 운행 규칙·단계 규칙·실제 재생은 B/C 후속 범위다.
