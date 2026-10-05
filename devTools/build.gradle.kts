plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // 覆盖率：汇总到根项目的报告里（见根 build.gradle.kts）。
    alias(libs.plugins.kover)
}

kotlin {
    // 与其它模块固定到同一 JDK，保证产物可复现；
    // 本机缺失时由 settings.gradle.kts 中的 foojay-resolver 自动下载。
    jvmToolchain(21)

    jvm()

    sourceSets {
        commonMain.dependencies {
            // `api` 而不是 `implementation`：本模块对外暴露的类型里带着 `:shared` 的东西
            // （`DevToolMetadata.icon` 是 `ClipperIconKind`，面板与工具的入参是 `ClipItem`），
            // 消费者的编译类路径上必须有它们。
            //
            // 方向是 **devTools → shared**，不是反过来：剪贴板那边完全不知道工具的存在，
            // 它只把一条 `ClipItem` 放进状态里（见 `ClipboardUiState.devToolsItem`）。
            api(project(":shared"))

            // 编辑框的第二种实现（见 `CodeFieldEngine`）。用 `implementation` 而不是 `api`：
            // 它只出现在 `ui/components/code/` 的内部实现里，工程对外暴露的类型上没有它。
            implementation(project(":kodemirror"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            // 状态提示的自动消失用了 `delay`。直接用到的东西就显式声明，不指望 Compose 的传递依赖。
            implementation(libs.kotlinx.coroutines.core)
            // JSON 工具的解析 / 排版直接用它，不引入第二个 JSON 实现。
            implementation(libs.kotlinx.serialization.json)
            // 读文件（文件类剪贴板条目）走它，读逻辑因此留在 commonMain，不必为文件系统开 expect/actual。
            implementation(libs.kotlinx.io.core)
            // XML 工具的解析 / 排版：供 `XML.v1.recommended()/compact()` 的缩进配置与底层的流式读写。
            // 取代原先 JVM-only 的 `javax.xml` 实现（见 `XmlFormat`）。
            implementation(libs.xmlutil.serialization)
            // 时间戳工具的时区换算与本地日期时间（`TimeZone` / `LocalDateTime` / `toLocalDateTime`）；
            // 绝对时刻用标准库的 `kotlin.time.Instant`，跨平台且不必为它开 expect/actual。
            implementation(libs.kotlinx.datetime)
            // Hash 工具的密码学原语（MD5 / SHA-1 / SHA-2 / SHA-3）。stdlib 没有这些算法，而自写
            // 密码学代码风险太大（再多测试也盖不住边界）；该库是 KMP 实现，直接在 commonMain 用。
            // CRC32 太小众、库里也没有，就地实现（见 `HashFormat`）。
            implementation(libs.kotlincrypto.hash.md)
            implementation(libs.kotlincrypto.hash.sha1)
            implementation(libs.kotlincrypto.hash.sha2)
            implementation(libs.kotlincrypto.hash.sha3)
        }

        // 代码显示层里能脱离组合环境的部分（JSON 扫描器、显示变换与偏移映射）直接单测：
        // 折叠与高亮的正确性全在这几个纯函数上，靠眼看界面是测不出来的。
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // 代码框的两种实现都只有「真跑一遍组合」才验得了渲染（见 `KodemirrorCodeFieldTest`），
            // 因此测试源集要带上 Compose 的 UI 测试设施。
            implementation(libs.compose.ui.test)
            // 离屏渲染用的 harness（`PanelRenderHarness` 等）要把 Compose 画进图片，因此需要
            // skiko 的原生库；公共的 `compose.ui` 只有跨平台部分，缺了它会在初始化
            // `org.jetbrains.skia.Surface` 时失败，报错完全不提「依赖缺失」。
            implementation(compose.desktop.currentOs)
        }
    }
}
