package com.qcmian.clipper.protocol

/**
 * JVM 侧读系统属性。
 *
 * 与服务端一致：`CliServer` 用同一个函数拼 socket 路径，因此 CLI 与 app 一定落在同一个文件上。
 * 取不到属性时返回 `.`（与原生侧同样的退路）。
 */
actual fun userHomeDirectory(): String =
    System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: "."
