// core:data —— Android library：Room / Repository / DataStore。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "cn.sanxing.thrice.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:parser"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // OkHttp —— AI 问网络客户端（仅在 core:data 引入；不添加日志拦截器，避免泄漏 API Key）
    implementation(libs.okhttp)

    // Room（KSP）—— 用 api()：app 模块的小组件 / 提醒会直接访问 AppDatabase（DatabaseProvider）
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore Preferences
    implementation(libs.androidx.datastore.preferences)

    // Hilt（hilt-android + hilt-compiler via KSP）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // PDF 文本提取（pdfbox-android，实现 parser 的 PdfTextExtractor）
    implementation(libs.pdfbox.android)
}
