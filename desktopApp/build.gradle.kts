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
    // 开发者工具（插件框架 + 内置插件 + 主面板）。它自己依赖 :shared，这里显式声明是因为组合根
    // 要直接引用 `DevToolsRegistry` 与 `DevToolsPanel`，不靠传递依赖碰运气。
    implementation(project(":devTools"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.compose.material3)

    // 窗口几何的纯函数（`WindowSizing` / `WindowPlacement`）与 `WindowGeometryController` 的
    // 状态机直接单测；后者要驱动虚拟时钟与 `delay`（拖拽静默期 / 预览揭示动画）。
    // compose 与 :shared 由 `testImplementation` 继承 `implementation` 自动可见。
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
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
            //
            // `java.sql` 必须显式列出，不能指望自动分析出来：`main` 在很早期就会调
            // `isClipperDatabaseLocked()` 读库文件头（见 shared 的 `DatabaseEncryption.kt`），
            // 它引用 `java.sql.SQLException`；而 JDBC 那条链路是运行时反射加载驱动，
            // jdeps 静态分析不到。缺了它，release 包启动即
            // `NoClassDefFoundError: java/sql/SQLException` + `Failed to launch JVM`——
            // 因为崩在建窗口之前，界面上表现为「双击无反应、也没有任何日志」。
            // `gradlew run` / IDE 里跑不会复现：那边用的是完整 JDK，模块齐全。
            //
            // `java.xml` 是开发者工具的 XML 格式化插件（`XmlFormat.jvm.kt`，JDK 自带的
            // `javax.xml`）要的：它虽然有静态引用、jdeps 一般能分析到，但漏掉它同样是
            // 「双击无反应」这一种最难查的失败，显式列上更稳妥。
            modules("java.instrument", "jdk.unsupported", "java.sql", "java.xml")

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
 * 走应用自己的存储层而不是手工 SQL——手工改 schema 绕开了 Room 的 identity hash 校验，
 * 下次启动会因校验不通过而直接报错打不开。
 *
 * **会先清空现有历史。**
 */
tasks.register<JavaExec>("seedData") {
    group = "application"
    description = "向 ~/.clipper/clipper.db 写入 20000 条历史（含 50 张图片），并清空原有数据"
    mainClass.set("com.qcmian.clipper.desktop.SeedDataKt")
    classpath = sourceSets["main"].runtimeClasspath
}
