// 공통 플러그인 버전을 한 곳에서 선택한다. app은 AGP의 내장 Kotlin을 유지한다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
