plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // `state/StateSerialization.kt` 上有三个 `@Serializable`，因此编译器插件是必需的，
    // 不能只加运行时依赖（只加运行时会在编译期报 "Unresolved reference 'decodeFromString'"）。
    alias(libs.plugins.kotlinSerialization)
    // 刻意**不**应用 kover：这里是内联进来的第三方源码（见 README.md），
    // 把它计入覆盖率会直接冲掉根项目那条 45% 的回归闸门，且度量别人的代码没有意义。
}

kotlin {
    jvmToolchain(21)

    // 只保留 jvm：Clipper 目前只在桌面端落地，内联时也一并剔除了 wasmJs / iOS / macOS
    // 的源集与 `Platform.*.kt`。将来要加目标，从上游按同名源集拷回来即可。
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            // 编辑器状态（`EditorState` / `Facet`）的序列化：`StateSerialization.kt` 直接用它。
            implementation(libs.kotlinx.serialization.json)
        }

        // 本仓库补丁（多击选择）的行为测试：只喂位置与连击数、只读最终选区，用不上 Compose 的
        // 测试设施。**上游的测试没有拷进来**（见 README.md），这是本仓库自己那一个。
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
