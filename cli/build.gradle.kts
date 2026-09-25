plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("distribution")
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

/** 版本号与桌面端同源（`gradle.properties` 的 `appVersion`），免得包名版本与 tag 脱节。 */
version = providers.gradleProperty("appVersion").get()

/**
 * 分发包内容：release 可执行文件 + AI 用的 skill。
 *
 * 归档这一步用 Gradle 自带的 `distribution` 插件（`distZip` / `distTar` / `installDist`），
 * 它只负责把 `contents` 摆成 `<名字>-<版本>/` 再归档，与 JVM 无关，原生二进制同样适用。
 * Kotlin 插件自己只到 `link*Executable*` 为止：链接出裸二进制之后，归档、执行位、
 * 版本号进文件名这些它都不管。
 */
distributions {
    main {
        distributionBaseName.set("clipper")
        contents {
            from(layout.buildDirectory.file("bin/macosArm64/releaseExecutable/clipper.kexe")) {
                // `.kexe` 只是 KGP 给原生可执行文件加的后缀，分发时用 `clipper` 这个名字。
                rename { "clipper" }
                // 放进 skill 目录**内部**，skill 才是自包含的：二进制随 skill 一起安装，用户不必
                // 预先把它放到 PATH。若落在 skill/ 外面（如 skill/script/），只拷 skill/clipper
                // 就会把它落下。
                into("skill/clipper/script/")
                filePermissions { unix("0755") }
            }
            // skill 与 CLI 同版本发布：解压后把 `skill/clipper` 整个放进 ~/.codebuddy/skills/ 即可。
            from("skill") { into("skill") }
        }
    }
}

// `contents` 里的路径要到执行期才解析，因此显式让归档任务等链接完成。
tasks.matching { it.name in listOf("distZip", "distTar", "installDist") }
    .configureEach { dependsOn("linkReleaseExecutableMacosArm64") }
