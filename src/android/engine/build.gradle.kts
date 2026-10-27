plugins {
    // TODO(1·2번): 버전 지정 필요. app 모듈은 AGP 9.4.0의 내장 Kotlin 지원을 쓰지만,
    // 이 모듈은 Android 플러그인을 쓰지 않는 순수 JVM 모듈이라 Kotlin 버전을 직접 적어야 한다.
    // Android Studio에서 이 모듈을 열면 호환되는 버전을 알려 준다 — 그 값을 채우면 됨.
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(libs.junit)
}
