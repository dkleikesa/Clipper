plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
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
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.jna.platform)
            implementation(libs.androidx.sqlite.bundled)
            // 从附加表示（HTML / RTF）里提取可读文字，见 `RichTextExtraction.jvm.kt`：
            // HTML 用 Ksoup 解析；RTF 用 JDK 自带的 `RTFEditorKit`，不引入额外依赖。
            implementation(libs.ksoup)
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room 的代码生成通过 KSP 完成。
    add("kspJvm", libs.androidx.room3.compiler)
}
