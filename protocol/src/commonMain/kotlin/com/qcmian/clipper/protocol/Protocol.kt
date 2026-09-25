package com.qcmian.clipper.protocol

/**
 * `clipper` CLI 与 `Clipper.app` 之间的本地契约。
 *
 * 两侧共用同一份定义（`:cli` 与 `:shared` 都依赖本模块），因此命令名、字段名、
 * 错误码与退出码不会各自漂移。
 *
 * 为什么单独成一个模块而不是塞进 `:shared`：`:shared` 依赖 Compose / Room / JNA，
 * 任何依赖它的东西都会把这些一起带上运行时 classpath。CLI 只需要一份纯数据契约。
 */

/**
 * 线上协议版本。
 *
 * CLI 与 app 是**两个可能各自升级的产物**——用户升级了 app 但 PATH 上还是旧的 CLI
 * （或反过来）是完全正常的。因此请求与响应都带上它，便于双方判断对面是不是自己认识的版本。
 *
 * 递增规则：只在**字段语义改变**或**删字段**时递增。新增可选字段不算破坏性变更，
 * 因为两侧的解码器都是 `ignoreUnknownKeys`。
 */
const val PROTOCOL_VERSION: Int = 1

/** socket 文件名；与数据库同在 `~/.clipper/` 下。 */
const val SOCKET_FILE_NAME: String = "clipper.sock"

/**
 * 当前用户的主目录。
 *
 * 做成 `expect`：JVM 侧读 `user.home`，原生侧读 `HOME` 环境变量。放在契约里而不是各写一份，
 * 是因为两侧拼的是**同一个** socket 路径——它们必须落到同一个文件上，否则 CLI 永远连不上 app。
 */
expect fun userHomeDirectory(): String

/**
 * 服务端 socket 的路径。
 *
 * 返回 `String` 而不是 `java.nio.file.Path`：契约要能编到原生目标上，而 `Path` 是 JVM 独有的。
 * 需要路径对象的那一侧（`:shared` 的 `CliServer`）自己包一层。
 *
 * 分隔符写死 `/`：CLI 与应用都只跑 macOS，而 `systemFileSystem` 那类抽象在这里不带来任何好处。
 *
 * 参数化 `home` 是为了让测试与「指定另一份配置目录」的调用方不必去改全局状态。
 */
fun clipperSocketPath(home: String = userHomeDirectory()): String =
    "$home/.clipper/$SOCKET_FILE_NAME"

/**
 * [CliView.title] 的字符上限。
 *
 * 存储里的标题最长 1000 字符（`ClipItem.MAX_TITLE_LENGTH`），而列表一次可能回 20 条——
 * 不截断的话，一次 `list` 就能吃掉两万字符。
 *
 * 两侧共用：服务端按它截断，CLI 的 `--help` 里也要说明这个数量。放在契约里而不是某一侧，
 * 避免「服务端截到 200、文档写着 300」这种漂移。
 */
const val TITLE_CHAR_LIMIT: Int = 200

/**
 * `list` / `search` 未给 `limit` 时的返回条数。
 *
 * 上限存在是为了挡住「Agent 一次性把整份历史拖进上下文」。两侧共用：CLI 拿它做参数校验
 * 与默认值，服务端拿它兜底——**校验不能只在 CLI 侧做**，否则手写协议的调用方可以绕过。
 */
const val DEFAULT_LIMIT: Int = 20
const val MAX_LIMIT: Int = 200

/** `CliRequest.cmd` 的取值。用常量而不是枚举：线上契约对未知取值必须宽容。 */
object CliCommand {
    const val LIST: String = "list"
    const val SEARCH: String = "search"
    const val GET: String = "get"
    const val COPY: String = "copy"
    const val PIN: String = "pin"
    const val UNPIN: String = "unpin"
    const val DELETE: String = "delete"
    const val STATS: String = "stats"

    /** 探活。CLI 在连接后、以及「app 是否已就绪」的判断里用它。 */
    const val PING: String = "ping"

    val ALL: List<String> = listOf(LIST, SEARCH, GET, COPY, PIN, UNPIN, DELETE, STATS, PING)
}

/**
 * `CliResponse.error.code` 的取值。
 *
 * 刻意不用枚举：新版本 app 可能返回旧 CLI 没听说过的错误码，那时旧 CLI 应当把它
 * 当作「未知错误」照常打印，而不是解码失败、连错误信息都看不到。
 */
object CliErrorCode {
    /** 请求本身不合法（缺必填参数、参数取值非法）。 */
    const val BAD_REQUEST: String = "BAD_REQUEST"

    /** 命令名不认识。旧 CLI 打到新 app 上时最可能遇到的就是这个。 */
    const val UNKNOWN_COMMAND: String = "UNKNOWN_COMMAND"

    /** 目标条目不存在。 */
    const val NOT_FOUND: String = "NOT_FOUND"

    /** 命令认识，但当前状态做不到（例如平台不支持写剪贴板）。 */
    const val UNSUPPORTED: String = "UNSUPPORTED"

    /** 服务端内部异常。 */
    const val INTERNAL: String = "INTERNAL"

    // ---------------------------------------------------------------------------------
    // 以下三个由 CLI 侧产生：请求根本没送到服务端，因此不可能由服务端返回。
    // 它们仍放在这里，因为 Agent 看到的是同一套错误码。
    // ---------------------------------------------------------------------------------

    /** 连不上 app：没在运行，或 socket 权限不对。 */
    const val DAEMON_UNAVAILABLE: String = "DAEMON_UNAVAILABLE"

    /** 请求超时。 */
    const val TIMEOUT: String = "TIMEOUT"

    /** 连接在收到完整响应前断了。 */
    const val TRANSPORT: String = "TRANSPORT"
}

/**
 * 进程退出码。
 *
 * 与 JSON 信封是**两套并行**的反馈通道，都要有：shell 靠退出码做分支，
 * agent 靠 stdout 上的信封拿错误详情（agent 经常只读 stdout，丢掉 stderr）。
 */
object CliExitCode {
    /** 成功。空结果也算成功。 */
    const val OK: Int = 0

    /** 通用失败。 */
    const val ERROR: Int = 1

    /** 用法错误：参数缺失或非法。 */
    const val USAGE: Int = 2

    /** 目标不存在。 */
    const val NOT_FOUND: Int = 3

    /** 连不上 app（没在跑，或 socket 权限不对）。 */
    const val NO_DAEMON: Int = 4

    /** 请求超时。 */
    const val TIMEOUT: Int = 5

    /** 把错误码映射成退出码；两侧共用，避免映射表各写一份。 */
    fun forErrorCode(code: String): Int = when (code) {
        CliErrorCode.BAD_REQUEST, CliErrorCode.UNKNOWN_COMMAND -> USAGE
        CliErrorCode.NOT_FOUND -> NOT_FOUND
        CliErrorCode.DAEMON_UNAVAILABLE -> NO_DAEMON
        CliErrorCode.TIMEOUT -> TIMEOUT
        else -> ERROR
    }
}
