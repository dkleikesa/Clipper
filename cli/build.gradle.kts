plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint = "com.qcmian.clipper.cli.main"
                baseName = "clipper"

                /**
                 * 只装 Command Line Tools、没有完整 Xcode 的 macOS 上，Kotlin/Native 的链接会失败：
                 *
                 * ```
                 * Failed command: /usr/bin/xcrun xcodebuild -version
                 * xcrun: error: unable to find utility "xcodebuild", not a developer tool or in PATH
                 * ```
                 *
                 * 它要 Xcode 版本只是为了做一次版本核对——真正用的 sysroot 与工具链是 Konan 自己
                 * 下载的那两份（见 `konan.properties` 里的 `targetSysroot.*` / `targetToolchain.*`），
                 * **不依赖 Xcode 的 SDK**。所以跳过这次核对是安全的，`ignoreXcodeVersionCheck`
                 * 正是 Konan 为这种环境留的开关。
                 *
                 * 写在这里而不是手改 `~/.konan/.../konan.properties`：后者是机器级配置，同一份
                 * 代码换台机器就编译不了，而且「改过什么」在仓库里查不到。
                 * 装了完整 Xcode 的机器上留不留这一行都行，它只是省掉一次多余的核对。
                 */
                freeCompilerArgs += "-Xoverride-konan-properties=ignoreXcodeVersionCheck=true"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":protocol"))
        }
    }
}
