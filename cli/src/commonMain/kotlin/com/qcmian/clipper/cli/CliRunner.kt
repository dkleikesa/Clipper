package com.qcmian.clipper.cli

import com.qcmian.clipper.protocol.CliCodec
import com.qcmian.clipper.protocol.CliCommand
import com.qcmian.clipper.protocol.CliErrorCode
import com.qcmian.clipper.protocol.CliExitCode
import com.qcmian.clipper.protocol.CliResponse
import com.qcmian.clipper.protocol.CliView

/**
 * `clipper` 的全部逻辑，与平台无关。
 *
 * 这个类**不碰数据库、不碰剪贴板、不碰 CBOR**——它把请求交给 app，把响应打印出来。
 * 那才是瘦客户端的全部含义：数据与业务规则只有一份，在 app 里。
 *
 * 输出只用 `print` / `println`：这个命令的输出形态只有「文本」一种，二进制一律走 [CliView.path]。
 */
internal object CliRunner {

    fun run(argv: List<String>, transport: DaemonTransport): Int {
        return when (val outcome = ArgParser.parse(argv)) {
            is ParseOutcome.Help -> {
                // print 而非 println：帮助文本自带结尾换行，再补一个会多出一行空行。
                print(outcome.command?.let(Help::forCommand) ?: Help.overview())
                CliExitCode.OK
            }

            // 参数不合法：信封走 stdout（Agent 只读 stdout 也能看到原因），退出码走 2。
            is ParseOutcome.Failure -> {
                emit(CliCodec.failure(CliErrorCode.BAD_REQUEST, outcome.message, outcome.hint), pretty = false)
                CliExitCode.USAGE
            }

            is ParseOutcome.Parsed -> dispatch(outcome.args, outcome.global, transport)
        }
    }

    private fun dispatch(args: ParsedArgs, global: GlobalOptions, transport: DaemonTransport): Int {
        val request = args.toRequest()

        return when (val exchange = transport.exchange(request, global.timeoutMillis)) {
            is Exchange.Unavailable -> fail(
                pretty = global.pretty,
                code = CliErrorCode.DAEMON_UNAVAILABLE,
                message = "连不上 Clipper：${exchange.path}",
                hint = "确认 Clipper 正在运行（未运行时可以 `open -a Clipper` 启动）；socket 权限应为 0600",
                exitCode = CliExitCode.NO_DAEMON,
            )

            is Exchange.TimedOut -> fail(
                pretty = global.pretty,
                code = CliErrorCode.TIMEOUT,
                message = "等待 Clipper 响应超过 ${exchange.millis} 毫秒",
                hint = "可用 --timeout 放宽",
                exitCode = CliExitCode.TIMEOUT,
            )

            is Exchange.Broken -> fail(
                pretty = global.pretty,
                code = CliErrorCode.TRANSPORT,
                message = "与 Clipper 的通信中断：${exchange.reason}",
                hint = null,
                exitCode = CliExitCode.ERROR,
            )

            is Exchange.Answer -> render(args, exchange.response, global)
        }
    }

    private fun render(args: ParsedArgs, response: CliResponse, global: GlobalOptions): Int {
        if (!response.ok) {
            emit(response, global.pretty)
            return CliExitCode.forErrorCode(response.error?.code.orEmpty())
        }

        // `--raw` / `--ocr` 只改 CLI 打印什么，不改服务端返回什么：响应里本来就带着完整正文。
        val wantsRaw = args.command.name == CliCommand.GET && (args.flag("raw") || args.flag("ocr"))
        if (!wantsRaw) {
            emit(response, global.pretty)
            return CliExitCode.OK
        }

        val view = CliCodec.dataAs<CliView>(response)
        if (view == null) {
            return fail(
                global.pretty, CliErrorCode.TRANSPORT,
                "响应里没有可读的条目数据", null, CliExitCode.ERROR,
            )
        }

        if (args.flag("ocr")) {
            val text = view.ocr?.text
            if (text.isNullOrEmpty()) {
                return fail(
                    global.pretty, CliErrorCode.NOT_FOUND,
                    "这条没有图片识别文字", "只有图片条目才可能带识别结果", CliExitCode.NOT_FOUND,
                )
            }
            return writeText(text)
        }

        // **不补换行**：`--raw` 的语义是「原样」，补一个换行会让
        // `clipper get <id> --raw | shasum` 这类校验对不上。要换行由调用方自己加。
        view.text?.let { return writeText(it) }

        // 文件条目的内容就是路径本身，按行给出是它最有用的「原文」形态。
        if (view.files.isNotEmpty()) return writeText(view.files.joinToString("\n") { it.path })

        // 剩下的是纯二进制（图片、PDF 等）：它的内容从不走 stdout，服务端已经落盘并给了路径。
        // 这里不假装能输出，而是把路径明确指出来——否则调用方只会看到一句「没有内容」。
        val path = view.path
        return fail(
            pretty = global.pretty,
            code = CliErrorCode.NOT_FOUND,
            message = "这条没有可直出的文本（内容是二进制）",
            hint = if (path != null) {
                "内容已落盘，直接读这个文件：$path"
            } else {
                "试试不带 --raw，看它的完整结构"
            },
            exitCode = CliExitCode.NOT_FOUND,
        )
    }

    private fun writeText(text: String): Int {
        print(text)
        return CliExitCode.OK
    }

    private fun fail(pretty: Boolean, code: String, message: String, hint: String?, exitCode: Int): Int {
        emit(CliCodec.failure(code, message, hint), pretty)
        return exitCode
    }

    private fun emit(response: CliResponse, pretty: Boolean) {
        val json = if (pretty) CliCodec.pretty else CliCodec.json
        println(json.encodeToString(CliResponse.serializer(), response))
    }
}
