pluginManagement {
    repositories {
        // 阿里云 Google 镜像
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // 阿里云 Maven Central 镜像
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // 阿里云 Gradle 插件镜像
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 备用：原始仓库
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // Google 官方仓库（用于 beta/alpha 版本依赖）
        google()
        mavenCentral()
        // Google androidx-dev 仓库（用于 renderscript-intrinsics-replacement-toolkit 等）
        maven { url = uri("https://androidx.dev/storage/compose-compiler/repository/") }
    }
}

rootProject.name = "LumaCamera"
include(":app")
