package com.qcmian.clipper.cli

import kotlin.system.exitProcess

/**
 * 原生可执行文件的入口（见 `cli/build.gradle.kts` 里的 `entryPoint`）。
 *
 * 只有装配：把 [DaemonClient] 交给 [CliRunner]，把它的返回值当退出码。
 */
fun main(args: Array<String>) {
    exitProcess(CliRunner.run(args.toList(), DaemonClient))
}
