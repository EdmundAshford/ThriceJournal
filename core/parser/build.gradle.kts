// core:parser —— 纯 Kotlin JVM 模块，零 Android 依赖，可脱离 Android 单独跑测试。
plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
