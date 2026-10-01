plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    // 与其它模块固定到同一 JDK，保证产物可复现；本机缺失时由 settings.gradle.kts 里的
    // foojay-resolver 自动下载。
    jvmToolchain(21)
}

/**
 * 跨模块共享的**测试夹具**。
 *
 * 存在的理由：`:shared` 与 `:cli` 的 jvmTest 都要造剪贴板假实现与历史条目，而两者的测试源集
 * 彼此看不见——`:cli` 只依赖 `:shared` 的**主产物**，拿不到它的 `jvmTest`。夹具放在这里，
 * 两边引用的是同一份，就不会各自演化出第二套 `RecordingClipboard` / `seedText`。
 *
 * 只被测试源集依赖，既不进任何分发包，也不出现在生产代码的 classpath 上。
 */
dependencies {
    // 夹具实现的是 :shared 的公开契约（`ClipboardDataSource` / `NativeDataSource` /
    // `ClipboardRepository`），用 `api` 是为了让使用方不必再显式声明 :shared。
    api(project(":shared"))
    // `RecordingClipboard.awaitListening` 用到 `withTimeout` / `delay`。
    implementation(libs.kotlinx.coroutines.core)
}
