import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 버전을 적지 않는다. app 모듈의 AGP 9.4.0이 Kotlin Gradle 플러그인을 classpath에 함께 넣어서,
    // 여기서 버전을 지정하면 "이미 classpath에 있는 플러그인" 충돌이 난다. (2026-11-03 빌드로 확인)
    id("org.jetbrains.kotlin.jvm")
}

// Java와 Kotlin 모두 11 바이트코드로 맞춘다. app 모듈(compileOptions 11)이 이 모듈을 그대로 쓰기 때문.
// Kotlin을 지정하지 않으면 Gradle 데몬 JDK(25)를 따라가서 Java(11)와 어긋나고, 소스가 생기는 순간 빌드가 멈춘다.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(libs.junit)
}
