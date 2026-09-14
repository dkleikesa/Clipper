plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

kotlin {
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
            // Room：唯一持久化层。
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.jna.platform)
            implementation(libs.androidx.sqlite.bundled)
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
