import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // 固定构建用 JDK，避免「谁的机器上是什么 JDK 就编出什么产物」；
    // 本机缺失时由 settings.gradle.kts 中的 foojay-resolver 自动下载。
    jvmToolchain(21)
}

/**
 * 版本号唯一来源：`gradle.properties` 的 `appVersion`，可用 `-PappVersion=x.y.z` 覆盖。
 * 发布时由 `.github/workflows/release.yml` 从 tag 注入，避免包名版本与 tag 脱节。
 */
val appVersion: String = providers.gradleProperty("appVersion").get()

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
            // Compose Desktop 要求 x.y.z 形式且各段为数字，来源见上方 appVersion
            packageVersion = appVersion
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

/**
 * 开发用：**无界面**启动数据层 + CLI 服务端，见 `CliServerDev.kt`。
 *
 * `user.home` 被指到 `build/devhome`，于是数据库与 socket 都落在这个一次性目录里，
 * 不碰用户真实的 `~/.clipper/`。CLI 侧加同样一条即可连上：
 *
 * ```
 * CLIPPER_OPTS="-Duser.home=$PWD/desktopApp/build/devhome" \
 *   cli/build/install/clipper/bin/clipper list
 * ```
 */
tasks.register<JavaExec>("devCliServer") {
    group = "application"
    description = "无界面启动 CLI 服务端（数据落在 desktopApp/build/devhome）"
    mainClass.set("com.qcmian.clipper.desktop.CliServerDevKt")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("user.home", layout.buildDirectory.dir("devhome").get().asFile.absolutePath)
}
