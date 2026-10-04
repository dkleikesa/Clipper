plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    // 覆盖率：本模块的 jvmTest 覆盖率汇总到根项目的报告里（见根 build.gradle.kts）。
    alias(libs.plugins.kover)
}

kotlin {
    // 与 desktopApp 固定到同一 JDK，保证产物可复现；
    // 本机缺失时由 settings.gradle.kts 中的 foojay-resolver 自动下载。
    jvmToolchain(21)

    compilerOptions {
        // Room 的 KMP 构造函数是一个 `expect object ... : RoomDatabaseConstructor<T>`。
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            // 附加表示（HTML / RTF / PDF）以 CBOR 存进 BLOB 列：JSON 只能把 ByteArray 编成
            // 数字数组（膨胀约 3.6 倍），CBOR 是二进制，原样落盘。
            implementation(libs.kotlinx.serialization.cbor)
            // Room：唯一持久化层。
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite)
            // 代码框（预览面板与开发者工具共用同一份）跑在 KodeMirror 上。它只依赖 compose
            // 与 kotlinx，是叶子模块，因此 `:shared -> :kodemirror` 不成环。
            implementation(project(":kodemirror"))
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.jna.platform)
            // 桌面端的 SQLite 驱动换成带加密能力的 JDBC 实现（见 `SqlCipherDriver`）：
            // androidx 自带的 `sqlite-bundled` 没有注入密钥的入口，加密就无从下手。
            implementation(libs.willena.sqlite.jdbc)
            // CLI / app 之间的线上契约。只进 `jvmMain`：`:cli` 永远不该看到这个模块的
            // 其余依赖，而 `:protocol` 也刻意不含任何重依赖。
            implementation(project(":protocol"))
            // 从附加表示（HTML / RTF）里提取可读文字，见 `RichTextExtraction.jvm.kt`：
            // HTML 用 Ksoup 解析；RTF 用 JDK 自带的 `RTFEditorKit`，不引入额外依赖。
            implementation(libs.ksoup)
        }

        jvmTest.dependencies {
            // 与 :cli 的 jvmTest 共用的测试夹具（假剪贴板 / 假原生能力 / 造数助手）。
            // 两边的测试源集彼此看不见，夹具集中在这里才不会各自漂移出一份。
            implementation(project(":testing"))
            // 剪贴板这条路（类型过滤、item 分组、`writeObjects:` 的语义）没有编译期能验证的东西，
            // 只能对着真实粘贴板跑一遍——见 `MacPasteboardTest`。
            implementation(kotlin("test"))
            // 全文搜索（`ClipDeepSearch`）的批次 / 预算语义要驱动挂起函数——见 `ClipDeepSearchTest`。
            implementation(libs.kotlinx.coroutines.test)
            // `rememberImage` 的状态语义（换图必须换状态）只有真跑一遍组合才验证得了——
            // 见 `RememberImageTest`。
            implementation(libs.compose.ui.test)
            // 图片解码走 Skiko，而 `compose.ui` 只带它的 API——native 运行时在那个单独的平台
            // artifact 里。少了它，测试里 `decodeToImageBitmap()` 会以
            // `NoClassDefFoundError: Could not initialize class org.jetbrains.skia.Image` 收场
            // （见 `ImageCacheTest`）。运行时由 desktopApp 的 `compose.desktop.currentOs` 提供。
            implementation(compose.desktop.currentOs)
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

/**
 * 真实粘贴板测试是否启用由环境变量决定，而 Gradle 看不见环境变量。不把它声明成任务输入的话，
 * 切换开关之后任务会被判成 UP-TO-DATE 而根本不执行——开关也就形同虚设。
 */
tasks.named<Test>("jvmTest") {
    inputs.property(
        "livePasteboardTests",
        providers.environmentVariable("CLIPPER_PASTEBOARD_TESTS").orElse("0"),
    )
}

dependencies {
    // Room 的代码生成通过 KSP 完成。
    add("kspJvm", libs.androidx.room3.compiler)
}

/**
 * 生成构建信息资源，供 CLI 的 `ping` 应答报出版本号。
 *
 * 版本号的唯一来源仍是 `gradle.properties` 的 `appVersion`（与 desktopApp 的打包配置同源），
 * 但它在**运行期**读不到——`.app` 包里没有 Gradle 属性表，`.app` 自己的 Info.plist 又只在
 * 打包后才存在（`./gradlew run` 时没有）。因此在这里落成一份 classpath 上的属性文件。
 *
 * 不写死常量：发布时 CI 会用 `-PappVersion=x.y.z` 覆盖，写死就意味着 `ping` 报出来的版本
 * 永远不等于实际发布的那一版。
 */
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val appVersion = providers.gradleProperty("appVersion")
    val outputDir = layout.buildDirectory.dir("generated/buildInfo")
    inputs.property("version", appVersion)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("clipper-build.properties").writeText("version=${appVersion.get()}\n")
    }
}

kotlin.sourceSets["jvmMain"].resources.srcDir(generateBuildInfo)
