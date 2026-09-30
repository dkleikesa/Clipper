plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("distribution")
}

kotlin {
    // 与其它模块固定到同一 JDK，保证产物可复现；本机缺失时由 settings.gradle.kts 里的
    // foojay-resolver 自动下载。
    jvmToolchain(21)

    /**
     * JVM 目标**只为测试存在**，不参与分发：`clipper` 命令本身仍是下面的原生二进制。
     *
     * 为什么必须有它：F1 的 B 层端到端要在 JVM 上同时拿到两侧——一侧是 `:shared` 的
     * `CliServer`（Room / JNA / Compose，只能跑在 JVM 上），另一侧是本模块的 `ArgParser` /
     * `CliRunner`（纯 Kotlin）。没有这个目标，两侧就只有原生/JVM 各一半，无法在同一个进程里
     * 拼成一条完整的请求链路。见 `cli/src/jvmTest` 的 `CliEndToEndTest`。
     */
    jvm()

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

        jvmTest.dependencies {
            implementation(kotlin("test"))
            // 服务端（`CliServer` / 真 Room + SQLCipher 存储）与 fake 数据源都在这里。
            // 这是**测试专用**的依赖：分发包里只有上面那份原生可执行文件，CLI 的运行时
            // classpath 不会因此带上 Compose / Room / JNA。
            implementation(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
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
        // 压缩包与解压后的顶层目录都叫 `clipper-skill-<版本>`：包里整体就是一个自包含的 skill
        // （`skill/clipper/` 下有 SKILL.md、references，二进制在里面），CLI 是它的载荷而不是
        // 主体，名字照着内容走。顺带也把 Release 页上的三份东西分开——`Clipper-<版本>.dmg`、
        // 本包、GitHub 自动生成的 `Source code (zip)`，前两者只差大小写时很容易拿错。
        // 里面的命令本身仍叫 `clipper`（见上面的 rename）。
        distributionBaseName.set("clipper-skill")
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
