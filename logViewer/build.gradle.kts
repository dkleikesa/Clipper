plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    // 覆盖率：本模块 jvmTest 的覆盖率汇总到根项目报告里（见根 build.gradle.kts）。
    alias(libs.plugins.kover)
}

/**
 * 日志查看器：从独立应用 LogViewer 搬进来的核心与界面，作为开发者工具的一个插件使用。
 *
 * 只保留 `jvm` 腿——原项目还有 android / ios / js / wasmJs 三套入口，本模块不需要，
 * `expect` 声明在 `jvmMain` 里都已有一一对应的 `actual`。
 *
 * 版本口径跟着宿主的版本目录走（Compose 比上游高一个次版本），因此少数 API 需要就地改；
 * 改动处都在各自文件里注明。
 */
kotlin {
    jvmToolchain(21)

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            // 日志源的读取走 kotlinx-io（`Source`），大文件按需解码也用它。
            implementation(libs.kotlinx.io.core)
            // 会话 / 偏好 / 过滤器集合的序列化。
            implementation(libs.kotlinx.serialization.json)
        }

        jvmMain.dependencies {
            // AWT 文件对话框、系统剪贴板、右键菜单与 `KeyEvent.awtEvent` 都只有桌面端给得出。
            implementation(compose.desktop.currentOs)
        }

        // 行偏移、过滤管道与查找规格都是纯函数：这些地方错了界面只会「看起来有点怪」，
        // 只能靠单测挡住（`FilterEngineTest` 里每一条用例都对应一次真实踩过的坑）。
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
