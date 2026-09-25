package com.qcmian.clipper.cli

import com.qcmian.clipper.protocol.CliRequest
import com.qcmian.clipper.protocol.CliResponse

/**
 * 这个模块里唯一平台相关的东西：**怎么连 socket**。
 *
 * 输出那边没有任何抽象——全程用 Kotlin 自带的 `print` / `println`。这不是省事，而是因为
 * **这个命令的输出只有一种形态：文本。** 二进制（图片、PDF）从不经过 stdout，由 app 落到
 * 临时目录后回一个路径（见 `CliView.path`），调用方自己去读那个文件。既然不存在「原始字节」
 * 这条通道，就不需要一套绕过 stdio 的输出机制，也不需要为它抽接口。
 */

/** 与 `Clipper.app` 的一次交互。 */
internal fun interface DaemonTransport {
    fun exchange(request: CliRequest, timeoutMillis: Long): Exchange
}

/**
 * 交互结果。
 *
 * 四态分开而不是用一个布尔加字符串，是因为每一种都要对应**一个明确的退出码与错误码**：
 * `NO_DAEMON=4` / `TIMEOUT=5` / `1` / 服务端自己的错误码。合成一种就没法区分了。
 */
internal sealed interface Exchange {

    data class Answer(val response: CliResponse) : Exchange

    /** 连不上：app 没在跑，或 socket 权限不对。 */
    data class Unavailable(val path: String, val reason: String) : Exchange

    data class TimedOut(val millis: Long) : Exchange

    /** 连上了但没拿到完整响应。 */
    data class Broken(val reason: String) : Exchange
}
