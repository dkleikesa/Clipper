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

            // 代码框（KodeMirror）的直接依赖：渲染那一套在 `:shared`，但本模块的 `jvmTest`
            // （`GutterLineNumberGuardTest`）要直接触到 KodeMirror 的节点，所以这里显式声明。
            // 用 `implementation` 而不是 `api`：工程对外暴露的类型上没有它。
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
            // 条码工具的二维码制（编码器 + 统一的 `MatrixBarcodePainter`）与一维码制
            // （`BarcodeType` / `BarcodePainter`）。PNG 导出用的 `ImageBitmap.toByteArray`
            // 来自它们共同传递依赖的 qrose-core。全是 KMP，工具因此没有一行平台代码。
            implementation(libs.qrose.matrix)
            implementation(libs.qrose.oned)
        }

        // 二维码 / 条码**解码**：ZXing 是 Java 库，只能落在 jvmMain。
        //
        // 这是本模块唯一一处平台代码，而且被刻意收成**一个 `expect` 函数**（收 ARGB 像素、还一串
        // 认出来的码，见 `BarcodeScan`）：解图片、缩放、取像素都留在 commonMain，工具那一层照旧
        // 不认识平台。将来要接别的平台，补一个 actual 即可，界面一行都不用动。
        jvmMain.dependencies {
            implementation(libs.zxing.core)

            // APK 签名 / 验签（见 `tools/apksign/`）。与 ZXing 同类：apksig 是 Java 库（AOSP 的
            // `apksigner` 内核），没有 KMP 制品，只能落在 jvmMain；而它签名要用 `java.util.zip`
            // 与 `java.security`，本来也只有 JVM 给得出。零传递依赖，不把 Android SDK 拖进来。
            implementation(libs.apksig)

            // 新建密钥库时签那张自签证书。同样只有 JVM 版本（bcprov / bcpkix 都不出 KMP 制品），
            // 而且**刻意不用全局注册提供方**（`Security.addProvider`）：那会改掉整个进程里
            // `Signature.getInstance` 的解析顺序，为一个功能改全局不划算。用法见 `ApkSign.jvm.kt`。
            implementation(libs.bcpkix)
        }

        // 代码显示层里能脱离组合环境的部分（JSON 扫描器、显示变换与偏移映射）直接单测：
        // 折叠与高亮的正确性全在这几个纯函数上，靠眼看界面是测不出来的。
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // 代码框的渲染只有「真跑一遍组合」才验得了（见 `KodemirrorCodeFieldTest`），
            // 因此测试源集要带上 Compose 的 UI 测试设施。
            implementation(libs.compose.ui.test)
            // 离屏渲染用的 harness（`PanelRenderHarness` 等）要把 Compose 画进图片，因此需要
            // skiko 的原生库；公共的 `compose.ui` 只有跨平台部分，缺了它会在初始化
            // `org.jetbrains.skia.Surface` 时失败，报错完全不提「依赖缺失」。
            implementation(compose.desktop.currentOs)
        }
    }
}
