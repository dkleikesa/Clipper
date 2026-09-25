package com.qcmian.clipper.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * 原生侧没有 `System.getProperty`，主目录只能问环境变量。
 *
 * 这与 macOS 上 shell 的行为一致：`clipper` 从终端或 agent 拉起时 `HOME` 一定存在。
 * 取不到时退回 `.`——socket 路径会变成一个显然不对的相对路径，报错信息里一眼能看出来，
 * 比猜一个别的位置更安全。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun userHomeDirectory(): String =
    getenv("HOME")?.toKString()?.takeIf { it.isNotBlank() } ?: "."
