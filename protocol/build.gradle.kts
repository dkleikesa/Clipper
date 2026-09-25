plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)

    // 服务端（`Clipper.app`）跑在 JVM 上。
    jvm()

    // 客户端（`clipper` 命令）编译成原生二进制，不依赖 JVM。
    // 只声明 arm64：本项目的应用本就只支持 macOS，而 Kotlin/Native 出的是当前平台的原生代码。
    // 要支持 Intel Mac 加一行 `macosX64()` 即可（Kotlin/Native 支持 Apple 平台之间交叉编译）。
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            // `api` 而不是 `implementation`：`CliResponse.data` 的类型是 `JsonElement`，
            // 它出现在公开 API 上，使用方必须能在自己的源码里引用 kotlinx.serialization。
            api(libs.kotlinx.serialization.json)
        }
    }
}
