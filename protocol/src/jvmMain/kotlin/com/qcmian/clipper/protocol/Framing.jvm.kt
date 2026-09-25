package com.qcmian.clipper.protocol

import java.io.InputStream
import java.io.OutputStream

/**
 * 流上的分帧读写，只有 JVM 侧需要。
 *
 * 帧**格式**（4 字节大端长度 + 载荷）定义在 commonMain 的 [CliFraming] 里，两侧共用；
 * 这里只负责「怎么从一个流里凑够 N 个字节」。原生客户端那边用的是文件描述符，
 * 自己实现同样的事情，但读出来的长度仍然交给 [CliFraming.frameSize] 解析——
 * 于是格式只有一个出处，两侧不可能对不上。
 */

/** 写一条消息。 */
fun OutputStream.writeCliFrame(payload: ByteArray) {
    write(CliFraming.frameHeader(payload.size))
    write(payload)
    flush()
}

/**
 * 读一条消息；对端已经正常关闭时返回 `null`。
 *
 * 用 [InputStream.readNBytes] 而不是自己循环：它会一直读到凑够字节数或 EOF，
 * 于是短读在协议层就被处理掉了，调用方不必再管。
 */
fun InputStream.readCliFrame(): ByteArray? {
    val header = readNBytes(CliFraming.HEADER_BYTES)
    if (header.size < CliFraming.HEADER_BYTES) return null
    val size = CliFraming.frameSize(header)
    require(size in 0..CliFraming.MAX_FRAME_BYTES) { "bad frame size: $size" }
    val body = readNBytes(size)
    return if (body.size < size) null else body
}
