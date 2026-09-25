package com.qcmian.clipper.host.cli

import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.protocol.CliCodec
import com.qcmian.clipper.protocol.CliErrorCode
import com.qcmian.clipper.protocol.clipperSocketPath
import com.qcmian.clipper.protocol.readCliFrame
import com.qcmian.clipper.protocol.writeCliFrame
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * 监听 `~/.clipper/clipper.sock`，把 CLI 的请求转给 [AppContainer]。
 *
 * **这是瘦客户端方案的 app 侧。** 数据、业务规则、剪贴板能力全都只有一份，就在这里；
 * `clipper` 那个进程除了拼参数和打印结果什么都不做。于是 CLI 不必带 Room、不必打开数据库、
 * 也就不会和正在运行的应用抢锁——SQLite 用的是 TRUNCATE 回滚日志，本质是单写者模型。
 *
 * 生命周期跟着应用走：[start] 在组合根调用一次，[close] 在退出路径调用。它自己不起线程池
 * 之外的任何东西，也不碰 UI。
 *
 * **实例是一次性的**：两个线程池在 [close] 里会被关掉，之后 [start] 不再可用
 * （`watchdog` 会抛 `RejectedExecutionException`）。重启进程会新建一个实例，那才是正常路径。
 *
 * @param socketPath 可覆盖，便于测试与「让另一个实例跑在别处」。
 */
class CliServer(
    private val container: AppContainer,
    private val appVersion: String = clipperAppVersion(),
    /** 对外可读：宿主在日志与「怎么连上它」的提示里都要用到。 */
    val socketPath: Path = Path.of(clipperSocketPath()),
) : AutoCloseable {

    private val handler = CliRequestHandler(container, appVersion)
    private val running = AtomicBoolean(false)

    /**
     * 固定大小的工作线程池。
     *
     * 为什么不「accept 到一个就顺序处理完再 accept」：`copy` 会等一个剪贴板轮询周期
     * （约 600ms）才返回，顺序处理会让这期间的每一次调用都排队。也不做成无界的缓存池——
     * 请求方是本机的命令行，几条并发就到顶了，无界池只会把「某个调用方失控」变成资源问题。
     */
    private val workers: ExecutorService = Executors.newFixedThreadPool(WORKER_THREADS) { runnable ->
        Thread(runnable, "clipper-cli-worker").apply { isDaemon = true }
    }

    /**
     * 兜底闹钟：每条连接到点还没处理完就关掉它。
     *
     * 单线程就够——它只做「到点关一个 channel」，没有排队的工作量。见
     * [CONNECTION_DEADLINE_MILLIS] 说明为什么必须有它。
     */
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "clipper-cli-watchdog").apply { isDaemon = true }
    }

    @Volatile
    private var server: ServerSocketChannel? = null

    /**
     * 自己挂上 shutdown hook，而不是让宿主记得去挂。
     *
     * 退出路径不止一条（按钮退出、Ctrl+C、被信号终止），而漏挂一次的后果是具体的：
     * socket 文件会留在磁盘上。所以这条职责属于资源本身——[start] 建立的东西，
     * 由它自己负责在进程结束时收掉。
     */
    private val shutdownHook = Thread(::close, "clipper-cli-shutdown")

    /**
     * 启动失败会打印一行日志并清掉半成品，但不会把应用带崩——CLI 是附加能力，不是必需品。
     *
     * 唯一的例外是权限收不紧（见 [prepareDirectory] 与 [restrictPermissions]）：那时拒绝启动
     * 是刻意的。「CLI 不可用」与「剪贴板历史可被同机任何用户读取」不是一个量级的问题。
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            prepareDirectory()
            clearStaleSocket()
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.bind(UnixDomainSocketAddress.of(socketPath))
            server = channel
            restrictPermissions()
            Thread({ acceptLoop(channel) }, "clipper-cli-accept").apply { isDaemon = true }.start()
            Runtime.getRuntime().addShutdownHook(shutdownHook)
        } catch (e: Exception) {
            running.set(false)
            runCatching { server?.close() }
            server = null
            // 失败路径也要清掉 socket 文件，否则它会以「陈迹」的身份留给下一次启动。
            runCatching { Files.deleteIfExists(socketPath) }
            System.err.println("[clipper] CLI 服务未启动：${e.message}")
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        // 正常退出时先把 hook 摘掉，免得它在 JVM 收尾阶段再跑一遍。
        // 已经在关闭中（hook 自己触发的这次）会抛 IllegalStateException，忽略即可。
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        // 关掉 server 会让阻塞中的 accept 抛 IOException，接受循环据此退出。
        runCatching { server?.close() }
        server = null
        workers.shutdownNow()
        watchdog.shutdownNow()
        // 删掉 socket 文件：留着的话，下次启动会看到它，并要花一次连接尝试才能判定是陈迹。
        runCatching { Files.deleteIfExists(socketPath) }
    }

    private fun acceptLoop(channel: ServerSocketChannel) {
        while (running.get()) {
            val connection = try {
                channel.accept()
            } catch (e: IOException) {
                // close() 是退出路径；只有还在运行时的失败才值得报。
                if (running.get()) System.err.println("[clipper] CLI 接受连接失败：${e.message}")
                return
            } ?: continue

            // 给这条连接挂一个兜底闹钟：到点还没处理完就关掉它，让阻塞中的读抛出来。
            // 没有它的话，一条连上却不发数据的连接会永久占住一个工作线程。
            val deadline = watchdog.schedule(
                Runnable { runCatching { connection.close() } },
                CONNECTION_DEADLINE_MILLIS,
                TimeUnit.MILLISECONDS,
            )

            workers.execute {
                try {
                    serve(connection)
                } finally {
                    // 正常处理完就撤掉闹钟，免得它对已经关闭的连接再关一次（无害但没必要）。
                    deadline.cancel(false)
                }
            }
        }
    }

    private fun serve(connection: SocketChannel) {
        try {
            connection.use { channel ->
                val request = Channels.newInputStream(channel).readCliFrame() ?: return
                val response = try {
                    // 每个请求在自己的线程上跑一个阻塞的协程：仓库是用挂起函数写的，
                    // 而这里没有别的事件循环可以挂靠。
                    runBlocking { handler.handle(CliCodec.decodeRequest(request)) }
                } catch (e: Exception) {
                    // 含解码失败：对面可能是更旧或更新的版本，回一条说得清的错误，
                    // 而不是把栈追踪丢给一个只想拿到结果的调用方。
                    System.err.println("[clipper] CLI 请求处理失败：$e")
                    CliCodec.failure(CliErrorCode.INTERNAL, e.message ?: e::class.java.simpleName)
                }
                Channels.newOutputStream(channel).writeCliFrame(CliCodec.encodeResponse(response))
            }
        } catch (e: IOException) {
            // 客户端提前断开（典型是 `clipper list | head`）是常态，不是故障。
        }
    }

    /**
     * 上一次退出如果没能清掉 socket 文件（崩溃、被强杀），它会留在那里，
     * 让下一次 `bind` 直接失败。
     *
     * 判据是**能不能连上**，而不是文件在不在：能连上说明真有一个实例在跑，那是
     * 不该抢占的；连不上才是陈迹。单看文件存在与否会把这两种情况混为一谈。
     */
    private fun clearStaleSocket() {
        if (!Files.exists(socketPath)) return
        val alive = runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use {
                it.connect(UnixDomainSocketAddress.of(socketPath))
                true
            }
        }.getOrDefault(false)
        if (alive) error("已有另一个实例在监听 $socketPath")
        Files.deleteIfExists(socketPath)
    }

    /**
     * 收紧 socket 所在目录。
     *
     * 解决的是 `bind` 与 `chmod` 之间的**竞态窗口**：socket 文件是 `bind` 那一刻按 umask
     * 建出来的（默认 0755），到 [restrictPermissions] 生效之间有一段间隙。窗口很短，但
     * `connect` 只需要目录的 `x` 位就能穿过去——先把目录收到 0700，这个窗口就不存在了。
     *
     * **为什么不用 `umask(0177)`**：那是进程级状态，而这段代码跑在应用启动路径上，同一时刻
     * 还有别的线程在建文件（SQLite 的 journal、缓存、临时文件）。用全局状态去护一段局部
     * 代码，风险大于它解决的问题。
     *
     * 顺带纠正一件事：`~/.clipper` 目前是存储层用 `mkdirs()` 建的，权限 0755，里面的
     * `clipper.db` 是 0644——**同机其他用户可以直接读走整份历史**。这里把它收到 0700
     * 会挡住那条路，但根治要在存储层建目录时就直接建成 0700（那次改动要迁移已有文件权限，
     * 另开一轮）。
     */
    private fun prepareDirectory() {
        val directory = socketPath.parent ?: return
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
    }

    /**
     * 把 socket 权限收紧到只有当前用户能连。
     *
     * **失败时抛异常、拒绝启动，不降级运行。** 这个 socket 上跑的是完整的剪贴板历史读取接口，
     * 一个世界可读的 socket 等于把历史交给同机所有用户。既然权限位是它唯一的防线，
     * 收不紧就宁可不启动——CLI 是附加能力，而数据泄漏不是可以「先跑起来再说」的事。
     */
    private fun restrictPermissions() {
        try {
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------"))
        } catch (e: Exception) {
            throw IOException(
                "无法把 socket 权限收紧到 0600（${e.message}）。" +
                    "它承载的是完整剪贴板历史，拒绝以可被同机其他用户连接的状态运行",
                e,
            )
        }
    }

    private companion object {
        const val WORKER_THREADS = 4

        /**
         * 一条连接从建立到被强制关闭的上限。
         *
         * 为什么必须有：工作池是固定 4 个线程，而读一个请求帧是**阻塞**的（NIO 的
         * `SocketChannel` 没有 `SO_TIMEOUT` 一类的阻塞读超时）。没有这条上限时，
         * 任何连上却不发数据的客户端——挂住的脚本、中途被杀掉的进程——都会**永久**
         * 占住一个工作线程，四条这样的连接就让 `clipper` 彻底失去响应。
         *
         * 为什么给 30 秒而不是几秒：这条上限的职责是「不让线程被永久占住」，不是替代
         * 客户端自己的 `--timeout`（那个才管用户体验）。最慢的正常请求是 `copy`，约
         * 0.8 秒；30 秒是三十多倍余量，不会误伤。
         *
         * 它挡不住「同用户进程蓄意反复占用」——那种对手本来就能直接读 `~/.clipper/`，
         * 这个 socket 的信任边界从来不是它。
         */
        const val CONNECTION_DEADLINE_MILLIS = 30_000L
    }
}

/**
 * 运行期的应用版本号，来自构建时生成、打进 classpath 的属性文件（见 `:shared` 的
 * `generateBuildInfo` 任务）。
 *
 * 读不到时返回 `unknown`：宁可让 `ping` 报一个明显不对的值，也不要为了兜底去读
 * 某个猜测的位置。`.app` 的 Info.plist 只在打包后才存在，`./gradlew run` 时没有它。
 */
fun clipperAppVersion(): String {
    val stream = CliServer::class.java.getResourceAsStream("/clipper-build.properties")
        ?: return "unknown"
    return stream.use { Properties().apply { load(it) }.getProperty("version") } ?: "unknown"
}
