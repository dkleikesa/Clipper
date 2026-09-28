plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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
        }
    }
}
