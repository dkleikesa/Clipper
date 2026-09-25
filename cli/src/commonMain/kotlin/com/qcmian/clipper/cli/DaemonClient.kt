package com.qcmian.clipper.cli

import com.qcmian.clipper.protocol.CliCodec
import com.qcmian.clipper.protocol.CliFraming
import com.qcmian.clipper.protocol.CliRequest
import com.qcmian.clipper.protocol.clipperSocketPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.AF_UNIX
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_SNDTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.socklen_t
import platform.posix.socket
import platform.posix.strerror
import platform.posix.timeval
import platform.posix.write

/**
 * 与 `Clipper.app` 上那个 socket 服务端的一次交互：每条请求开一条连接，用完即关。
 *
 * **不复用连接**：CLI 进程本身就一次性，跨进程缓存 socket 要额外守护进程与失效逻辑，
 * 换来的只是微秒级握手，不划算。超时交给内核的 `SO_RCVTIMEO` / `SO_SNDTIMEO`——
 * 原生侧没有「关 channel 解除阻塞」这种手段，内核超时会让阻塞的 `read` 带 `EAGAIN` 返回。
 */
@OptIn(ExperimentalForeignApi::class)
internal object DaemonClient : DaemonTransport {

    override fun exchange(request: CliRequest, timeoutMillis: Long): Exchange {
        val path = clipperSocketPath()

        val descriptor = socket(AF_UNIX, SOCK_STREAM, 0)
        if (descriptor < 0) return Exchange.Unavailable(path, errnoMessage())

        try {
            applyTimeout(descriptor, timeoutMillis)

            connectUnix(descriptor, path)?.let { reason ->
                // 连不上是**最常见**的一种失败：app 根本没在跑。因此错误码走
                // DAEMON_UNAVAILABLE（退出码 4），而不是笼统的传输错误。
                return Exchange.Unavailable(path, reason)
            }

            return perform(descriptor, request, timeoutMillis)
        } finally {
            close(descriptor)
        }
    }

    private fun perform(descriptor: Int, request: CliRequest, timeoutMillis: Long): Exchange {
        val payload = CliCodec.encodeRequest(request)
        val frame = CliFraming.frameHeader(payload.size) + payload

        return when (val written = writeFully(descriptor, frame)) {
            is IoResult.Failed -> Exchange.Broken(written.reason)
            IoResult.Timeout -> Exchange.TimedOut(timeoutMillis)
            IoResult.Closed -> Exchange.Broken("连接在写出请求时被对方关闭")
            is IoResult.Ok -> readAnswer(descriptor, timeoutMillis)
        }
    }

    private fun readAnswer(descriptor: Int, timeoutMillis: Long): Exchange {
        val header = when (val result = readFully(descriptor, CliFraming.HEADER_BYTES)) {
            is IoResult.Ok -> result.bytes
            is IoResult.Failed -> return Exchange.Broken(result.reason)
            IoResult.Timeout -> return Exchange.TimedOut(timeoutMillis)
            IoResult.Closed -> return Exchange.Broken("连接在收到响应前被对方关闭")
        }

        // 帧头发过来但不合法，只可能是对端不是我们认识的版本（或根本不是我们的服务端）。
        // 这里解码失败不当作崩溃：至少要把「为什么」说清楚。
        val size = runCatching { CliFraming.frameSize(header) }.getOrElse {
            return Exchange.Broken("响应帧头不合法：${it.message}")
        }

        val body = when (val result = readFully(descriptor, size)) {
            is IoResult.Ok -> result.bytes
            is IoResult.Failed -> return Exchange.Broken(result.reason)
            IoResult.Timeout -> return Exchange.TimedOut(timeoutMillis)
            IoResult.Closed -> return Exchange.Broken("响应不完整：对端只发了一半就关闭了连接")
        }

        return runCatching { CliCodec.decodeResponse(body) }
            .fold(
                onSuccess = { Exchange.Answer(it) },
                onFailure = { Exchange.Broken("响应不是合法的 JSON 信封：${it.message}") },
            )
    }

    // -------------------------------------------------------------------------------------
    // POSIX
    // -------------------------------------------------------------------------------------

    /** 构造 `sockaddr_un` 并连接；返回 `null` 表示成功，否则是失败原因（布局见文件末尾）。 */
    private fun connectUnix(descriptor: Int, path: String): String? {
        val bytes = path.encodeToByteArray()
        if (bytes.size >= SUN_PATH_CAPACITY) {
            // 超长路径不拦的话会被静默截断，于是可能连到另一个 socket 上——那比失败危险得多。
            return "socket 路径过长（${bytes.size} 字节，上限 ${SUN_PATH_CAPACITY - 1}）：$path"
        }

        val raw = ByteArray(SOCKADDR_UN_BYTES)
        raw[SUN_LEN_OFFSET] = SOCKADDR_UN_BYTES.toByte()
        raw[SUN_FAMILY_OFFSET] = AF_UNIX.toByte()
        bytes.copyInto(raw, SUN_PATH_OFFSET)

        return raw.usePinned { pinned ->
            val address = pinned.addressOf(0).reinterpret<sockaddr>()
            if (connect(descriptor, address, SOCKADDR_UN_BYTES.convert<socklen_t>()) != 0) {
                errnoMessage()
            } else {
                null
            }
        }
    }

    private fun applyTimeout(descriptor: Int, timeoutMillis: Long) = memScoped {
        val timeout = alloc<timeval>()
        timeout.tv_sec = (timeoutMillis / 1000).convert()
        timeout.tv_usec = ((timeoutMillis % 1000) * 1000).toInt().convert()
        val size = sizeOf<timeval>().convert<socklen_t>()
        setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, size)
        setsockopt(descriptor, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, size)
    }

    private fun writeFully(descriptor: Int, data: ByteArray): IoResult {
        if (data.isEmpty()) return IoResult.Ok(data)
        return data.usePinned { pinned ->
            var offset = 0
            while (offset < data.size) {
                val written = write(descriptor, pinned.addressOf(offset), (data.size - offset).convert())
                when {
                    written > 0L -> offset += written.toInt()
                    written == 0L -> return@usePinned IoResult.Closed
                    isTimeout() -> return@usePinned IoResult.Timeout
                    else -> return@usePinned IoResult.Failed(errnoMessage())
                }
            }
            IoResult.Ok(data)
        }
    }

    private fun readFully(descriptor: Int, count: Int): IoResult {
        if (count == 0) return IoResult.Ok(ByteArray(0))
        val buffer = ByteArray(count)
        return buffer.usePinned { pinned ->
            var offset = 0
            while (offset < count) {
                val got = read(descriptor, pinned.addressOf(offset), (count - offset).convert())
                when {
                    got > 0L -> offset += got.toInt()
                    // 0 表示对端正常关闭。
                    got == 0L -> return@usePinned IoResult.Closed
                    isTimeout() -> return@usePinned IoResult.Timeout
                    else -> return@usePinned IoResult.Failed(errnoMessage())
                }
            }
            IoResult.Ok(buffer)
        }
    }

    /**
     * 超时在 errno 上的表现是 `EAGAIN` / `EWOULDBLOCK`（两者在 macOS 上是同一个值），
     * 与「真的读错了」必须分开——前者对应退出码 5，后者对应 1。
     */
    private fun isTimeout(): Boolean = errno == EAGAIN || errno == EWOULDBLOCK

    private fun errnoMessage(): String {
        val code = errno
        return strerror(code)?.toKString()?.let { "$it（errno=$code）" } ?: "errno=$code"
    }

    private sealed interface IoResult {
        class Ok(val bytes: ByteArray) : IoResult

        data object Timeout : IoResult

        /** 对端关闭：读到 EOF，或写出了一个零长度。 */
        data object Closed : IoResult

        data class Failed(val reason: String) : IoResult
    }

}

/**
 * macOS 的 `sockaddr_un` 布局（见 `<sys/un.h>`，BSD 系与 Linux 不同：
 * `sun_len` 在前、`sun_family` 在后，路径偏移同为 2，但 Linux 没有 `sun_len`）：
 *
 * | 偏移 | 字段 | 宽度 |
 * | --- | --- | --- |
 * | 0 | `sun_len` | 1 |
 * | 1 | `sun_family` | 1 |
 * | 2 | `sun_path` | 104 |
 *
 * 结构总长 106，`sun_len` 填的就是它。手工拼字节而非 cinterop `<sys/un.h>`：
 * `platform.posix` 不含该头文件，自己 cinterop 要多一份 `.def` 与 Gradle 配置，
 * 并让构建链依赖完整 Xcode（cinterop 会调 `xcrun`），为一段稳定 ABI 不值得。
 *
 * 将来要加 Linux 目标的话，那边没有 `sun_len`，这段得按平台分开写。
 */
private const val SUN_LEN_OFFSET = 0
private const val SUN_FAMILY_OFFSET = 1
private const val SUN_PATH_OFFSET = 2
private const val SUN_PATH_CAPACITY = 104
private const val SOCKADDR_UN_BYTES = SUN_PATH_OFFSET + SUN_PATH_CAPACITY
