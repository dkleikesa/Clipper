plugins {
    // 这里必须如此，避免各子工程的 classloader 重复加载同一批插件
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false

    // 覆盖率：根项目作为**合并入口**（merging module），本身没有源码，只负责把下面几个模块的
    // 报告汇总成一份。各业务模块各自应用同名插件（不带版本）。见下方 `dependencies` / `kover`。
    alias(libs.plugins.kover)
}

/**
 * 覆盖率合并哪些模块。
 *
 * 只覆盖**生产代码**：`:testing` 是测试夹具（本身不该被度量），因此不入列——它也不会出现在
 * 报告里。采集只在 JVM target 上进行（Kover 不支持 JS / native）：`:shared` / `:cli` /
 * `:protocol` 的 `jvmTest`，以及 `:devTools` / `:desktopApp` 的 `test`；`:cli` 与 `:protocol`
 * 的 `macosArm64` 目标不参与。
 */
dependencies {
    kover(project(":shared"))
    kover(project(":devTools"))
    kover(project(":protocol"))
    kover(project(":cli"))
    kover(project(":desktopApp"))
}

/**
 * 过滤规则只有写在合并模块（就是这里）才生效，写在子模块里会被忽略。
 *
 * 排除两类：
 *
 * 1. **机器生成的代码**（不该由人负责）——Room（KSP）的 `*_Impl` 与 `ClipperDatabaseConstructor`、
 *    Compose 资源生成物。
 * 2. **在 CI 的 JVM 测试环境里根本跑不起来的东西**——`core.platform.macos.*` 是 JNA 直连
 *    AppKit / Carbon 的桥接，只在真实图形会话里才执行（对应测试本就由环境变量门控，见
 *    `LivePasteboardTest`）；以及进程入口 / 开发工具这类没有可断言逻辑面的文件。
 *
 * 刻意**不**排除 UI 与组合根：Compose 是能被 `compose-ui-test` 驱动的（`feature/history/ui`
 * 就靠 C 层测试覆盖），把 UI 一律排除会把「没写测试」伪装成「测不到」。
 */
kover {
    reports {
        filters {
            excludes {
                classes(
                    // Room（KSP）生成
                    "*_Impl",
                    "*_Impl${'$'}*",
                    "com.qcmian.clipper.core.data.local.ClipperDatabaseConstructor",
                    // Compose 资源生成
                    "clipper.shared.generated.resources.*",
                    // macOS 原生桥接：CI 里恒为 0
                    "com.qcmian.clipper.core.platform.macos.*",
                    // 进程入口与开发工具
                    "com.qcmian.clipper.MainKt",
                    "com.qcmian.clipper.desktop.SeedDataKt",
                )
            }
        }

        /**
         * 回归闸门：整体行覆盖不得跌破下限。
         *
         * 当前实测约 50%，这里取 45 留出余量——它要拦的是「大块可测代码被顺手删掉测试」这类
         * 真实回退，而不是每次加几行代码的正常波动。覆盖率提升后应当把这个数字**向上**收紧。
         *
         * 只影响 `koverVerify` 任务；`./gradlew :koverVerify` 或 CI 里显式调用。
         */
        verify {
            rule { minBound(45) }
        }
    }
}
