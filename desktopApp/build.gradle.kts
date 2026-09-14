import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.qcmian.clipper.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Clipper"
            packageVersion = "1.0.0"
            description = "Compose Multiplatform clipboard history manager"
            vendor = "qcmian"

            // 保持最小化运行时：`suggestModules` 基于当前桌面端依赖分析出的补充模块。
            // 不启用 includeAllModules，避免把完整 JDK 一并打入发行包。
            modules("java.instrument", "jdk.unsupported")

            // 打包产物（.dmg/.msi/.deb）的图标；与 shared 里 `ClipperAppIcon` 同一份设计稿。
            macOS {
                bundleID = "com.qcmian.clipper"
                dockName = "Clipper"
                iconFile.set(project.file("icons/clipper.icns"))
            }
            windows {
                iconFile.set(project.file("icons/clipper.ico"))
            }
            linux {
                iconFile.set(project.file("icons/clipper.png"))
            }
        }
    }
}