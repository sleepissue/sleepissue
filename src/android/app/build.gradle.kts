plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    useLibrary("android.car")
    namespace = "com.sleepissue.awakedrive"
    buildFeatures {
        compose = true
    }

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sleepissue.awakedrive"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        // 4번 쉼터 표(REQ_RST_001): 전처리본 폴더를 그대로 assets로 묶는다. 복사본을 두지 않기 위해서다.
        getByName("main").assets.srcDir(rootProject.file("../../data/rest_areas/processed"))
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    // Jetpack Compose UI (5번 담당)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)

    // Compose 화면을 Activity에서 실행하기 위한 라이브러리
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose Preview (화면 미리보기)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 기존 엔진 및 코루틴
    implementation(project(":engine"))
    implementation(libs.kotlinx.coroutines.core)
    // JSON 로더는 Android 밖의 JVM 단위 시험에서도 같은 엄격 파서를 사용한다.
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // 기존 Android 라이브러리
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Pleos Connect SDK — 좌표·버전은 CLAUDE.md 6장 기준.
    // 설명회에서 바뀌면 거기부터 갱신.
    // 저장소는 settings.gradle.kts의 nexus-playground.pleos.ai에서 받는다.
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    // 테스트 라이브러리
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
