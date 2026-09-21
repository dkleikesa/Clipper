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

        // 应用只活在菜单栏里（`LSUIElement`），不该出现在 Dock / ⌘Tab。
        // 让 AWT 初始化时把激活策略设成 `NSApplicationActivationPolicyAccessory`：
        // `run` 任务不经过 .app 包，读不到 Info.plist，只能靠这个 JVM 参数；
        // 打包产物两者都有，见下面 `nativeDistributions.macOS.infoPlist`。
        if (System.getProperty("os.name").orEmpty().startsWith("Mac")) {
            jvmArgs("-Dapple.awt.UIElement=true")
        }

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
                // 双击 .app 启动时 AWT 拿不到上面那个 JVM 参数，改成在 Info.plist 里
                // 声明「Application is agent (UIElement)」——否则 Dock 里会先闪一下图标。
                infoPlist {
                    extraKeysRawXml = """
                        <key>LSUIElement</key>
                        <true/>
                    """.trimIndent()
                }
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

/**
 * 开发用：往真实数据库里灌一批测试数据，见 `SeedData.kt`。
 *
 * 走应用自己的存储层而不是手工 SQL——手工拼的 INSERT 绕开了 Room 的 identity hash 校验，
 * 下次启动会被 `fallbackToDestructiveMigration` 当成旧库整个清掉。
 *
 * **会先清空现有历史。**
 */
tasks.register<JavaExec>("seedData") {
    group = "application"
    description = "向 ~/.clipper/clipper.db 写入 20000 条历史（含 50 张图片），并清空原有数据"
    mainClass.set("com.qcmian.clipper.desktop.SeedDataKt")
    classpath = sourceSets["main"].runtimeClasspath
}
