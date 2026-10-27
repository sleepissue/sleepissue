# src/android — Gradle 루트

Android Studio에서 **이 폴더(`src/android`)를 연다.** 원래 저장소 루트의 `android/`에 있던 프로젝트(Gradle 9.6.0, AGP 9.4.0, minSdk 28, 패키지 `com.example.sleepissue`)를 CLAUDE.md 10장 구조에 맞춰 여기로 옮기고, 패키지를 `com.sleepissue.awakedrive`로 바꾼 뒤 `:engine` 모듈을 추가했습니다.

- **`app/`** — Android/Pleos SDK에 의존하는 쪽. SDK 호출은 `pleos/` 래퍼 뒤로 숨기고, `engine`에는 정규화된 `SignalFrame`만 넘긴다(`pleos`, `signal`, `voice`, `cabin`, `navi`, `climate`, `session`, `data`, `ui`, `demo`). `settings.gradle.kts`에 Pleos Maven 저장소를, `app/build.gradle.kts`에 Vehicle·NaviHelper·TextToSpeech·SpeechToText 의존성을 이미 추가해 뒀습니다(CLAUDE.md 6장 좌표).
- **`engine/`** — Android에 의존하지 않는 순수 Kotlin/JVM 모듈. 판단 로직만 들어간다(`model`, `config`, `fatigue`, `baseline`, `pattern`, `assist`, `forecast`, `intervention`, `rest`). `./gradlew :engine:test`로 에뮬레이터 없이 검증하는 게 이 모듈을 따로 둔 이유입니다.

## 아직 안 끝난 것

- **`engine/build.gradle.kts`의 Kotlin 버전이 비어 있습니다.** `app` 모듈은 AGP 9.4.0의 내장 Kotlin 지원으로 버전을 안 적어도 되지만, Android 플러그인을 쓰지 않는 `engine`은 `org.jetbrains.kotlin.jvm` 버전을 직접 지정해야 합니다. Android Studio에서 이 프로젝트를 열면 호환 버전을 제안해 주니 그 값을 채우세요(제가 Gradle 9.6.0·AGP 9.4.0과 맞는 Kotlin 버전을 확신할 수 없어 임의로 채우지 않았습니다 — 잘못된 버전은 조용히 깨지는 대신 분명한 에러로 알려 주는 쪽이 낫다고 판단했습니다).
- Jetpack Compose 의존성은 아직 추가 안 했습니다(CLAUDE.md 기술 스택에는 있는데, 지금 `app`은 View 템플릿 기본값 그대로). Compose BOM 버전도 같은 이유로 비워 뒀습니다.
- `app/src/main/java/.../*` 10개 패키지와 `engine/src/main/java/.../*` 9개 패키지는 전부 `.gitkeep`만 있는 빈 폴더입니다.

진행 상황은 코드가 아니라 [`../../PROGRESS.md`](../../PROGRESS.md)에서 관리합니다.
