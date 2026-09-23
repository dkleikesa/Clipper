rootProject.name = "Clipper"

pluginManagement {
    // 依赖仓库开关：true = 阿里云镜像优先（国内网络快），false = 只用官方仓库。
    // 覆盖方式：-PuseChinaMirrors=false，或环境变量 ORG_GRADLE_PROJECT_useChinaMirrors=false。
    // CI 与海外网络用 false：镜像回源不及时或超时会直接让解析失败——典型是 Gradle 插件，
    // 例如 KSP 并不在 Google Maven 上，却会被 com.google 的 content 过滤器指到镜像上去。
    val useChinaMirrors = (providers.gradleProperty("useChinaMirrors").orNull ?: "true").toBoolean()

    repositories {
        // androidx.room3 / androidx.sqlite 的 KMP 变体（js / wasm klib）阿里云镜像尚未同步，
        // 这两个组绕过镜像直接回源 Google 官方仓库。
        google {
            content {
                includeGroupAndSubgroups("androidx.room3")
                includeGroupAndSubgroups("androidx.sqlite")
            }
        }
        if (useChinaMirrors) {
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
        }
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
    // 与 pluginManagement 同名的开关，说明见文件顶部
    val useChinaMirrors = (providers.gradleProperty("useChinaMirrors").orNull ?: "true").toBoolean()

    repositories {
        // androidx.room3 / androidx.sqlite 的 KMP 变体（js / wasm klib）阿里云镜像尚未同步，
        // 这两个组绕过镜像直接回源 Google 官方仓库。
        google {
            content {
                includeGroupAndSubgroups("androidx.room3")
                includeGroupAndSubgroups("androidx.sqlite")
            }
        }
        if (useChinaMirrors) {
            // 国内镜像优先（阿里云）
            maven("https://maven.aliyun.com/repository/google") {
                mavenContent {
                    includeGroupAndSubgroups("androidx")
                    includeGroupAndSubgroups("com.android")
                    includeGroupAndSubgroups("com.google")
                }
            }
            maven("https://maven.aliyun.com/repository/public")
        }
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

include(":desktopApp")
include(":shared")