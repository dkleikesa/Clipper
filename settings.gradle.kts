rootProject.name = "Clipper"

pluginManagement {
    repositories {
        // androidx.room3 / androidx.sqlite 的 KMP 变体（js / wasm klib）阿里云镜像尚未同步，
        // 这两个组绕过镜像直接回源 Google 官方仓库。
        google {
            content {
                includeGroupAndSubgroups("androidx.room3")
                includeGroupAndSubgroups("androidx.sqlite")
            }
        }
        // 国内镜像优先（阿里云），显著改善国内网络下的插件解析
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google") {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        // 官方仓库兜底：镜像尚未同步的构件仍可回源
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // androidx.room3 / androidx.sqlite 的 KMP 变体（js / wasm klib）阿里云镜像尚未同步，
        // 这两个组绕过镜像直接回源 Google 官方仓库。
        google {
            content {
                includeGroupAndSubgroups("androidx.room3")
                includeGroupAndSubgroups("androidx.sqlite")
            }
        }
        // 国内镜像优先（阿里云）
        maven("https://maven.aliyun.com/repository/google") {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        // 官方仓库兜底
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":desktopApp")
include(":shared")