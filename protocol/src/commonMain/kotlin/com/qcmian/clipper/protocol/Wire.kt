// `explicitNulls` 仍是实验 API（`prettyPrintIndent` 同理）。在这里一次性放行，
// 免得每个配置块各写一遍 @OptIn——与 `core/util/ClipperJson.kt` 的做法一致。
@file:OptIn(ExperimentalSerializationApi::class)

package com.qcmian.clipper.protocol

import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * CLI 的一次请求。
 *
 * **刻意做成扁平结构，而不是 sealed class 层次。** 类型安全的层次在版本错配时会变成灾难：
 * 旧 CLI 发来一个 `cmd` 是新 app 不认识的 sealed 子类，反序列化直接抛异常，
 * 于是用户看到的是一段栈追踪，而不是「不支持的命令」。扁平结构里 `cmd` 只是一个字符串，
 * 不认识的取值可以被正常解码，再回一条 [CliErrorCode.UNKNOWN_COMMAND]——
 * 错误信息能好好传达出去。
 *
 * 各命令实际用到的字段见 [CliCommand]；未用到的保持 `null`，编码时会被省略。
 */
@Serializable
data class CliRequest(
    /** 见 [CliCommand]。没有默认值，因此一定会被编码。 */
    val cmd: String,
    /** 见 [PROTOCOL_VERSION]。与默认值相同时会被省略——两侧语义一致，省略无碍。 */
    val v: Int = PROTOCOL_VERSION,

    /** 单条操作的 id（`get` / `copy` / `pin` / `unpin`）。 */
    val id: String? = null,
    /** 批量操作的 id（`delete`）。 */
    val ids: List<String>? = null,

    /** 搜索词（`search`）。 */
    val query: String? = null,

    /** 类型筛选（`list`）：`text` / `image` / `file` / `richtext`。 */
    val kind: String? = null,
    /** 排序字段（`list`）：对应 `SortBy` 的取值。 */
    val sort: String? = null,
    /** 排序方向（`list`）：`asc` / `desc`。 */
    val order: String? = null,
    /** 只看置顶或只看未置顶（`list`）；`null` 表示两者都要。 */
    val pinned: Boolean? = null,
    /** 返回条数上限（`list` / `search`）。 */
    val limit: Int? = null,

    /**
     * 取哪种附加表示（`get`）：`html` / `rtf` / `pdf`，或完整 UTI（`public.html`）。
     *
     * 只在需要**原始源码**时使用——默认返回的 `text` 已经是从这些表示里提取好的可读文字。
     */
    val format: String? = null,
)

/**
 * 服务端的一次响应。
 *
 * `data` 用 [JsonElement] 而不是泛型：信封对**所有**命令都是同一个形状，
 * 解成具体类型由各自的调用点决定。泛型化的 `CliResponse<T>` 在 kotlinx.serialization 里
 * 需要把序列化器一路传下去，收益不抵复杂度。
 */
@Serializable
data class CliResponse(
    /** 没有默认值，因此一定被编码。 */
    val ok: Boolean,
    val v: Int = PROTOCOL_VERSION,
    val data: JsonElement? = null,
    val error: CliError? = null,
)

/** 失败时的详情。[code] 取值见 [CliErrorCode]。 */
@Serializable
data class CliError(
    val code: String,
    val message: String,
    /** 可选的结构化补充信息，例如哪个参数非法。 */
    @SerialName("hint")
    val hint: String? = null,
)

/**
 * 线上 JSON 编解码器。
 *
 * 三个配置项都是为「两个可能不同版本的产物互相对话」定的：
 *
 * - `ignoreUnknownKeys`：对面多出来的字段直接忽略，而不是解码失败。
 * - `encodeDefaults = false`：等于默认值的字段不写出去。这不只是省字节——
 *   [CliView] 里大量布尔与空列表字段（`hasImage`、`hasOcr`、`files`…）默认值就是
 *   「没有」，省略掉正好。列表里 20 条记录乘下来，省的是实打实的 token。
 * - `explicitNulls = false`：`null` 同样不写。因此「字段不存在」与「字段为 null」
 *   在线上是同一件事 —— 契约里因此避免用 `null` 表达语义，改用显式的布尔字段。
 */
object CliCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * 同样配置、只是多缩进的版本，供 `--pretty` 与人阅读时使用。
     *
     * 单独一个实例而不是在 [json] 上开关：`Json` 是不可变的配置对象，
     * 每次输出都重建一个会把「配置」变成运行期开销。
     */
    val pretty: Json = Json(json) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encodeRequest(request: CliRequest): ByteArray =
        json.encodeToString(CliRequest.serializer(), request).encodeToByteArray()

    fun decodeRequest(bytes: ByteArray): CliRequest =
        json.decodeFromString(CliRequest.serializer(), bytes.decodeToString())

    fun encodeResponse(response: CliResponse): ByteArray =
        json.encodeToString(CliResponse.serializer(), response).encodeToByteArray()

    fun decodeResponse(bytes: ByteArray): CliResponse =
        json.decodeFromString(CliResponse.serializer(), bytes.decodeToString())

    /** 构造成功响应。 */
    inline fun <reified T> success(data: T): CliResponse =
        CliResponse(ok = true, data = json.encodeToJsonElement(data))

    /** 构造失败响应。 */
    fun failure(code: String, message: String, hint: String? = null): CliResponse =
        CliResponse(ok = false, error = CliError(code = code, message = message, hint = hint))

    /**
     * 把 [CliResponse.data] 解成具体类型。
     *
     * `data` 缺失（或对面给的结构对不上）时返回 `null` 而不是抛异常：CLI 侧永远不该
     * 因为「服务端返回了意料之外的结构」而崩掉——它至少要把信封里的信息打印给用户。
     */
    inline fun <reified T> dataAs(response: CliResponse): T? {
        val payload = response.data ?: return null
        return runCatching { json.decodeFromJsonElement<T>(payload) }.getOrNull()
    }
}

/**
 * 分帧：`4 字节大端长度 + JSON 字节`。
 *
 * 为什么不用「一行一个 JSON」（NDJSON）：那样虽然更省事，但把「一条消息」和
 * 「一次写出的字节」绑死了——写一半被打断、或将来某个字段里出现未转义的换行，
 * 都会让两侧对不上。长度前缀与内容无关，读多少字节是明确的，也天然支持
 * 将来 `watch` 那样由服务端连续推送多条消息。
 *
 * 这两侧都是本地进程、单条消息也不大，因此没有引入任何缓冲池或压缩。
 *
 * **这里只有格式，没有 IO。** 帧怎么读进来、怎么送出去，JVM 与原生两边用的是不同的机制
 * （`InputStream` / POSIX 文件描述符），但对**字节的解释**必须是同一份——否则两侧对不上
 * 时不会有任何编译错误，只会在运行时静默地读歪。所以长度只在这里解析一次。
 */
object CliFraming {

    /** 单条消息的上限。正常请求/响应都在几 KB 量级，这个值只用来挡住明显的垃圾输入。 */
    const val MAX_FRAME_BYTES: Int = 64 * 1024 * 1024

    /** 帧头长度：一个大端 `Int`。 */
    const val HEADER_BYTES: Int = 4

    /** 把载荷长度编成帧头。 */
    fun frameHeader(size: Int): ByteArray {
        require(size in 0..MAX_FRAME_BYTES) { "frame too large: $size" }
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        )
    }

    /** 从帧头读出载荷长度；越界时抛异常，由调用方当作协议错误处理。 */
    fun frameSize(header: ByteArray): Int {
        require(header.size >= HEADER_BYTES) { "short frame header: ${header.size}" }
        val size = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        require(size in 0..MAX_FRAME_BYTES) { "bad frame size: $size" }
        return size
    }
}
