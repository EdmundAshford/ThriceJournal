// 叁省 Thrice —— 项目设置
// 依赖仓库统一走阿里云镜像（国内直连 google()/mavenCentral() 会超时），
// google()/mavenCentral() 作为兜底放在最后。

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "Sanxing"

include(":app")
include(":core:parser")
include(":core:data")
