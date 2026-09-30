package com.qcmian.clipper.cli

import com.qcmian.clipper.core.data.source.ClipboardDataSource
import com.qcmian.clipper.core.data.source.NativeDataSource
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.FILE_URL_CONTENT_TYPE
import com.qcmian.clipper.core.domain.model.PNG_CONTENT_TYPE
import com.qcmian.clipper.core.domain.model.toMeta
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.host.cli.CliServer
import com.qcmian.clipper.protocol.CliAffectedView
import com.qcmian.clipper.protocol.CliCodec
import com.qcmian.clipper.protocol.CliErrorCode
import com.qcmian.clipper.protocol.CliExitCode
import com.qcmian.clipper.protocol.CliListView
import com.qcmian.clipper.protocol.CliPingView
import com.qcmian.clipper.protocol.CliRequest
import com.qcmian.clipper.protocol.CliResponse
import com.qcmian.clipper.protocol.CliStatsView
import com.qcmian.clipper.protocol.CliView
import com.qcmian.clipper.protocol.DEFAULT_LIMIT
import com.qcmian.clipper.protocol.PROTOCOL_VERSION
import com.qcmian.clipper.protocol.readCliFrame
import com.qcmian.clipper.protocol.writeCliFrame
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * F1（B 层）：CLI 端到端。
 *
 * **两侧都是真的**：客户端侧走 `ArgParser` → `CliRunner`（本模块的纯逻辑），服务端侧走
 * `CliServer` + `CliRequestHandler` + 真实的 Room / SQLCipher 存储（落在临时 `user.home`）。
 * 中间的进程边界是真 socket，分帧用 `:protocol` 里两侧共用的那一份实现。因此这一条覆盖了
 * 「argv → 请求 → 线上字节 → 业务 → 响应 → stdout + 退出码」的整条链路，而不是任何一侧的镜像。
 *
 * 唯一被替换的是**平台剪贴板与原生能力**：那是 `:shared` 的宿主能力，真跑会覆盖开发机上的
 * 粘贴板、还会去动登录项（`SMAppService`）。`AppContainer` 因此开了一个可注入的缝，只影响
 * 这两条支路；存储仍是真实现（见 `AppContainer`）。
 *
 * 与 D 层（真启动 app）的分工：这里不装托盘、不注册全局热键、不合成粘贴按键——那些只在
 * 有图形会话时才有意义，默认跳过。
 */
class CliEndToEndTest {

    private lateinit var home: File
    private var originalHome: String? = null
    private lateinit var cluster: TestCluster

    @BeforeTest
    fun setUp() {
        // 库文件与 socket 都由 `user.home` 拼出（见 `clipperDatabaseFile()` / `clipperSocketPath()`），
        // 因此改这一处就把整条链路搬到了临时目录，绝不碰真实的 `~/.clipper`。
        //
        // 特意建在 `/tmp` 而不是系统临时目录：socket 路径受 `sockaddr_un.sun_path` 的 104 字节
        // 硬限制，而 macOS 的 `java.io.tmpdir`（`/var/folders/<很长的哈希>/T/`）加上
        // `/.clipper/clipper.sock` 正好越界，`bind` 会以 `Unix domain path too long` 失败。
        home = Files.createTempDirectory(Path.of("/tmp"), "cl").toFile()
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)

        cluster = TestCluster(home.toPath())
        cluster.start()
    }

    @AfterTest
    fun tearDown() {
        cluster.close()
        home.deleteRecursively()
        if (originalHome != null) System.setProperty("user.home", originalHome) else System.clearProperty("user.home")
    }

    // -------------------------------------------------------------------------------------
    // 信封与退出码
    // -------------------------------------------------------------------------------------

    @Test
    fun `信封形状与各退出码`() = runBlocking<Unit> {
        seedText("t1", "甲", lastCopiedAt = 1)

        // 成功：`{"ok":true,"data":…}`，退出码 0。
        val ping = runCli("ping")
        assertEquals(CliExitCode.OK, ping.exitCode)
        val pingEnvelope = ping.envelope()
        assertTrue(pingEnvelope.ok, "成功时 ok 必须为 true")
        assertNotNull(pingEnvelope.data, "成功时必须带 data")
        assertEquals(PROTOCOL_VERSION, CliCodec.dataAs<CliPingView>(pingEnvelope)?.protocolVersion)

        // 用法错误：未知命令 → `BAD_REQUEST` + hint，退出码 2。
        val usage = runCli("nope")
        assertEquals(CliExitCode.USAGE, usage.exitCode)
        assertEquals(CliErrorCode.BAD_REQUEST, usage.errorCode())
        assertFalse(usage.envelope().ok, "失败时 ok 必须为 false")

        // 未找到：`get` 一个不存在的 id → `NOT_FOUND`，退出码 3。
        val notFound = runCli("get", "missing-id")
        assertEquals(CliExitCode.NOT_FOUND, notFound.exitCode)
        assertEquals(CliErrorCode.NOT_FOUND, notFound.errorCode())
        assertNotNull(notFound.failHint(), "失败信封要带上下一步怎么做")
        assertNotNull(notFound.failMessage(), "失败信封要带上原因")

        // 连不上：socket 根本不存在 → `DAEMON_UNAVAILABLE`，退出码 4。
        val offline = runCli(cluster.transportTo(home.toPath().resolve("no-daemon.sock")), "list")
        assertEquals(CliExitCode.NO_DAEMON, offline.exitCode)
        assertEquals(CliErrorCode.DAEMON_UNAVAILABLE, offline.errorCode())

        // 超时：监听在，但永远不应答 → `TIMEOUT`，退出码 5。
        val timedOut = exchangeWithStuckServer()
        assertEquals(CliExitCode.TIMEOUT, timedOut.exitCode)
        assertEquals(CliErrorCode.TIMEOUT, timedOut.errorCode())
    }

    @Test
    fun `平台表示不了时退出码是 1`() = runBlocking<Unit> {
        seedText("t1", "甲", lastCopiedAt = 1)
        // 写剪贴板失败 → 服务端回 UNSUPPORTED；它既不是用法错误也不是未找到，退出码落回 1。
        cluster.clipboard.writeSucceeds = false

        val copy = runCli("copy", "t1")
        assertEquals(CliExitCode.ERROR, copy.exitCode)
        assertEquals(CliErrorCode.UNSUPPORTED, copy.errorCode())
    }

    // -------------------------------------------------------------------------------------
    // list：筛选 / 排序 / 默认与上限
    // -------------------------------------------------------------------------------------

    @Test
    fun `list 的筛选 排序与 limit 口径`() = runBlocking<Unit> {
        // 25 条文本（复制次数有高有低），外加图片 / 文件 / 富文本各一条，共 28 条。
        for (index in 1..25) {
            seedText(
                id = "t%02d".format(index),
                text = "文本 $index",
                lastCopiedAt = index.toLong(),
                copies = when (index) {
                    1 -> 3
                    2 -> 1
                    3 -> 5
                    else -> 1
                },
            )
        }
        seedImage("img", lastCopiedAt = 50)
        seedFile("file", lastCopiedAt = 51)
        seedRichText("rich", lastCopiedAt = 52)

        // 默认 limit：DEFAULT_LIMIT(20) 条 + total 是筛选后的总数 + truncated 标记被切短了。
        val default = runCli("list").listView()
        assertEquals(DEFAULT_LIMIT, default.items.size, "未给 --limit 时默认返回 $DEFAULT_LIMIT 条")
        assertEquals(28, default.total)
        assertTrue(default.truncated)

        // 上限 200：一次拿全。
        val all = runCli("list", "--limit", "200").listView()
        assertEquals(28, all.items.size)
        assertFalse(all.truncated)

        // 类型筛选。
        val images = runCli("list", "--kind", "image").listView()
        assertEquals(listOf("img"), images.items.map { it.id })
        assertEquals(1, images.total)
        assertEquals("image", images.items.single().kind)

        val files = runCli("list", "--kind", "file").listView()
        assertEquals(listOf("file"), files.items.map { it.id })

        // 排序：`--sort copies --order asc` 的第一条是复制次数最少的（同次数时按 id 升序，
        // 因此限定在文本条目里，t02 是唯一的「1 次」里 id 最小的）。
        val byCopiesAsc = runCli("list", "--kind", "text", "--sort", "copies", "--order", "asc").listView()
        assertEquals("t02", byCopiesAsc.items.first().id, "升序应当从复制次数最少的开始")
        assertEquals(1, byCopiesAsc.items.first().copies)
        // 降序的第一条是 t03（5 次）。
        val byCopiesDesc = runCli("list", "--kind", "text", "--sort", "copies", "--order", "desc").listView()
        assertEquals("t03", byCopiesDesc.items.first().id)

        // 置顶筛选：置顶一条之后 `--pinned` 只回它，`--unpinned` 回其余全部。
        assertEquals(CliExitCode.OK, runCli("pin", "t01").exitCode)
        assertEquals(listOf("t01"), runCli("list", "--pinned").listView().items.map { it.id })
        assertEquals(27, runCli("list", "--unpinned", "--limit", "200").listView().items.size)
    }

    @Test
    fun `limit 越界在解析阶段就被拒绝`() = runBlocking<Unit> {
        // 上限 200：201 超出 spec 声明的范围，退出码 2，且不进服务端。
        assertEquals(CliExitCode.USAGE, runCli("list", "--limit", "201").exitCode)
        // 下限 1：0 同样非法。
        assertEquals(CliExitCode.USAGE, runCli("list", "--limit", "0").exitCode)
    }

    // -------------------------------------------------------------------------------------
    // search：多词 AND 与 --deep
    // -------------------------------------------------------------------------------------

    @Test
    fun `search 多词是 AND，deep 追加正文命中`() = runBlocking<Unit> {
        seedText("t-a", "发票 报销 单据", lastCopiedAt = 1)
        seedText("t-b", "发票 存根", lastCopiedAt = 2)
        seedText("t-c", "报销 说明", lastCopiedAt = 3)

        // 两个词必须同时命中：只含其中一个的两条都不该出现。
        val and = runCli("search", "发票 报销").listView()
        assertEquals(listOf("t-a"), and.items.map { it.id })
        assertFalse(and.deepSearch, "没带 --deep 时不该声称扫过正文")

        // 单个词：两条都命中。
        assertEquals(
            setOf("t-a", "t-b"),
            runCli("search", "发票").listView().items.map { it.id }.toSet(),
        )

        // 正文命中：一条标题里有「量子纠缠」，另一条只有正文深处才有（标题是正文按
        // MAX_TITLE_LENGTH 截断后落库的，前 1000 字符里没有这个词）。
        seedText("t-title", "量子纠缠 笔记", lastCopiedAt = 4)
        val bodyOnly = "甲".repeat(ClipItem.MAX_TITLE_LENGTH) + " 量子纠缠"
        seedLongText("t-body", bodyOnly, lastCopiedAt = 5)

        // 只搜标题：只有 t-title 命中，t-body 的正文没被碰。
        assertEquals(listOf("t-title"), runCli("search", "量子纠缠").listView().items.map { it.id })

        // 带 --deep：正文命中**追加**在标题命中之后。
        val deep = runCli("search", "量子纠缠", "--deep").listView()
        assertEquals(listOf("t-title", "t-body"), deep.items.map { it.id })
        assertTrue(deep.deepSearch, "带 --deep 时响应要标记 deepSearch")
        assertFalse(deep.deepSearchTruncated, "预算远没用完，不该报 truncated")
    }

    // -------------------------------------------------------------------------------------
    // get：--raw / --ocr / --format
    // -------------------------------------------------------------------------------------

    @Test
    fun `get 的 raw 原样直出 不补换行`() = runBlocking<Unit> {
        val text = "第一行\n第二行"
        seedText("t-text", text, lastCopiedAt = 1)

        // 不带 --raw：走 JSON 信封，text 是全文。
        val envelope = runCli("get", "t-text").envelope()
        assertEquals(text, CliCodec.dataAs<CliView>(envelope)?.text)

        // 带 --raw：stdout 就是原文本身，**不补换行**（否则 `| shasum` 一律对不上）。
        val raw = runCli("get", "t-text", "--raw")
        assertEquals(CliExitCode.OK, raw.exitCode)
        assertEquals(text, raw.stdout, "--raw 必须逐字节等于原文，末尾不能多出换行")
    }

    @Test
    fun `get 的 ocr 直出识别原文`() = runBlocking<Unit> {
        val recognized = "识别出的原文\n第二段"
        seedImage("img", lastCopiedAt = 1, recognizedText = recognized)

        val ocr = runCli("get", "img", "--ocr")
        assertEquals(CliExitCode.OK, ocr.exitCode)
        assertEquals(recognized, ocr.stdout)

        // 文本条目没有识别结果：明确报 NOT_FOUND，而不是给一段空字符串。
        seedText("t-text", "没有识别", lastCopiedAt = 2)
        val missing = runCli("get", "t-text", "--ocr")
        assertEquals(CliExitCode.NOT_FOUND, missing.exitCode)
        assertEquals(CliErrorCode.NOT_FOUND, missing.errorCode())
    }

    @Test
    fun `get 的 format 把附加表示落盘并给出路径`() = runBlocking<Unit> {
        val html = "<h1>你好世界</h1>"
        seedRichText("rich", lastCopiedAt = 1, html = html)

        val exported = runCli("get", "rich", "--format", "html").envelope()
        val path = assertNotNull(
            CliCodec.dataAs<CliView>(exported)?.path,
            "带 --format 时响应里必须给落盘路径",
        )
        assertEquals(html, File(path).readText(), "落盘内容必须是该附加表示的原始字节")

        // 要一种它没有的表示：回 NOT_FOUND，并说明它实际有哪些。
        val absent = runCli("get", "rich", "--format", "pdf")
        assertEquals(CliExitCode.NOT_FOUND, absent.exitCode)
        assertEquals(CliErrorCode.NOT_FOUND, absent.errorCode())
        assertContains(absent.failHint().orEmpty(), "public.html")
    }

    // -------------------------------------------------------------------------------------
    // pin / unpin / delete / copy / stats
    // -------------------------------------------------------------------------------------

    @Test
    fun `pin unpin delete copy stats 都真的生效`() = runBlocking<Unit> {
        seedText("t1", "甲", lastCopiedAt = 1)
        seedText("t2", "乙", lastCopiedAt = 2)
        seedText("t3", "丙", lastCopiedAt = 3)

        // pin / unpin
        assertTrue(runCli("list", "--pinned").listView().items.isEmpty())
        assertEquals(CliExitCode.OK, runCli("pin", "t1").exitCode)
        assertEquals(listOf("t1"), runCli("list", "--pinned").listView().items.map { it.id })
        assertEquals(CliExitCode.OK, runCli("unpin", "t1").exitCode)
        assertTrue(runCli("list", "--pinned").listView().items.isEmpty())

        // copy：内容逐字节写进剪贴板，且这一条被记成「又复制了一次」。
        val beforeCopies = assertNotNull(cluster.repository.meta("t3")).numberOfCopies
        val copyRun = runCli("copy", "t3")
        assertEquals(CliExitCode.OK, copyRun.exitCode)
        assertEquals(listOf("t3"), CliCodec.dataAs<CliAffectedView>(copyRun.envelope())?.ids)
        assertEquals("丙", cluster.clipboard.written?.text, "写回的是这条的文本表示")
        assertEquals(
            beforeCopies + 1,
            assertNotNull(cluster.repository.meta("t3")).numberOfCopies,
            "写回要计入复制次数",
        )

        // stats：总数 / 置顶数 / 存储占用。
        val stats = assertNotNull(CliCodec.dataAs<CliStatsView>(runCli("stats").envelope()))
        assertEquals(3, stats.total)
        assertEquals(0, stats.pinned)
        assertNotNull(stats.storageBytes, "真存储应当量得出库文件大小")
        assertFalse(stats.paused)

        // delete：删掉两条，剩下的那条还在。
        assertEquals(CliExitCode.OK, runCli("delete", "t1", "t2").exitCode)
        assertEquals(CliExitCode.NOT_FOUND, runCli("get", "t1").exitCode)
        assertEquals(CliExitCode.OK, runCli("get", "t3").exitCode)
        assertEquals(
            1,
            assertNotNull(CliCodec.dataAs<CliStatsView>(runCli("stats").envelope())).total,
        )
    }

    // -------------------------------------------------------------------------------------
    // 授权开关
    // -------------------------------------------------------------------------------------

    @Test
    fun `关掉授权后除 ping 一律 UNSUPPORTED`() = runBlocking<Unit> {
        seedText("t1", "甲", lastCopiedAt = 1)
        cluster.repository.setSettings(AppSettings(allowCliAccess = false))

        // ping 是 CLI 区分「用户不允许」与「app 没在运行」的唯一依据，必须照旧放行。
        assertEquals(CliExitCode.OK, runCli("ping").exitCode)

        listOf(
            arrayOf("list"),
            arrayOf("search", "甲"),
            arrayOf("get", "t1"),
            arrayOf("copy", "t1"),
            arrayOf("pin", "t1"),
            arrayOf("unpin", "t1"),
            arrayOf("delete", "t1"),
            arrayOf("stats"),
        ).forEach { argv ->
            val outcome = runCli(*argv)
            assertEquals(CliErrorCode.UNSUPPORTED, outcome.errorCode(), "${argv.first()} 应当被拒绝")
            assertEquals(CliExitCode.ERROR, outcome.exitCode, "${argv.first()} 的退出码应为 1")
        }
    }

    // -------------------------------------------------------------------------------------
    // 夹具
    // -------------------------------------------------------------------------------------

    /** 起一个真服务端 + 真存储，并把「怎么连上它」交给测试。 */
    private class TestCluster(home: Path) : AutoCloseable {

        /** 只记账的剪贴板：真要真读写粘贴板，会把开发机上当前的内容覆盖掉。 */
        val clipboard = RecordingClipboard()

        val socketPath: Path = home.resolve(".clipper").resolve("clipper.sock")

        private val container = AppContainer(
            clipboardDataSource = clipboard,
            // 空的原生能力：`setLaunchAtLogin` 会真的去注册 / 注销登录项，测试不该有这种副作用。
            nativeDataSource = object : NativeDataSource {},
        )

        private val server = CliServer(container, appVersion = TEST_APP_VERSION, socketPath = socketPath)

        private val watchdog: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "cli-e2e-watchdog").apply { isDaemon = true }
            }

        val repository get() = container.repository

        val transport: DaemonTransport = UnixSocketTransport(socketPath, watchdog)

        fun transportTo(path: Path): DaemonTransport = UnixSocketTransport(path, watchdog)

        fun start() {
            container.repository.start()
            // 偏好（含 allowCliAccess）就绪才算装配完成；此前读到的都是内存默认值。
            runBlocking { withTimeout(START_TIMEOUT_MILLIS) { container.repository.settingsLoaded.first { it } } }
            server.start()
        }

        override fun close() {
            server.close()
            runBlocking { container.repository.close() }
            watchdog.shutdownNow()
        }
    }

    /** 与原生 `DaemonClient` 同语义的 JVM 客户端：一次请求一条连接，超时由看门狗关连接来兑现。 */
    private class UnixSocketTransport(
        private val socketPath: Path,
        private val watchdog: ScheduledExecutorService,
    ) : DaemonTransport {

        override fun exchange(request: CliRequest, timeoutMillis: Long): Exchange {
            val channel = try {
                SocketChannel.open(StandardProtocolFamily.UNIX)
            } catch (e: IOException) {
                return Exchange.Unavailable(socketPath.toString(), reasonOf(e))
            }

            // 原生侧靠内核的 `SO_RCVTIMEO`；JVM 的 `SocketChannel` 没有阻塞读超时，只能自己
            // 关连接把读叫醒——与 `CliServer` 兜底闹钟同一套做法，语义因此一致。
            val expired = AtomicBoolean(false)
            val deadline = watchdog.schedule({
                expired.set(true)
                runCatching { channel.close() }
            }, timeoutMillis, TimeUnit.MILLISECONDS)

            try {
                channel.connect(UnixDomainSocketAddress.of(socketPath))
            } catch (e: IOException) {
                deadline.cancel(false)
                runCatching { channel.close() }
                // 连不上最常见的原因就是 app 没在跑，因此与原生侧一样归到 DAEMON_UNAVAILABLE。
                return Exchange.Unavailable(socketPath.toString(), reasonOf(e))
            }

            try {
                Channels.newOutputStream(channel).writeCliFrame(CliCodec.encodeRequest(request))
                val payload = Channels.newInputStream(channel).readCliFrame()
                    ?: return Exchange.Broken("连接在收到响应前被对方关闭")
                return Exchange.Answer(CliCodec.decodeResponse(payload))
            } catch (e: IOException) {
                return if (expired.get()) Exchange.TimedOut(timeoutMillis) else Exchange.Broken(reasonOf(e))
            } finally {
                deadline.cancel(false)
                runCatching { channel.close() }
            }
        }

        private fun reasonOf(e: IOException): String = e.message ?: e::class.java.simpleName
    }

    private class RecordingClipboard(var writeSucceeds: Boolean = true) : ClipboardDataSource {
        var written: ClipboardSnapshot? = null
            private set

        override fun write(snapshot: ClipboardSnapshot): Boolean {
            if (!writeSucceeds) return false
            written = snapshot
            return true
        }

        override fun start(onChange: (ClipboardSnapshot) -> Unit) {}

        override fun stop() {}
    }

    /** 一次 CLI 调用的可观察结果：退出码 + stdout。 */
    private class CliRun(val exitCode: Int, val stdout: String) {
        /** stdout 上的 JSON 信封。只对「输出信封」的那些调用有意义（`--raw` / `--ocr` 除外）。 */
        fun envelope(): CliResponse = CliCodec.decodeResponse(stdout.trim().encodeToByteArray())

        fun errorCode(): String? = envelope().error?.code

        fun failMessage(): String? = envelope().error?.message

        fun failHint(): String? = envelope().error?.hint

        fun listView(): CliListView =
            assertNotNull(CliCodec.dataAs<CliListView>(envelope()), "响应里应当是可解析的列表视图")
    }

    // -------------------------------------------------------------------------------------
    // 断言辅助
    // -------------------------------------------------------------------------------------

    /** 跑一次 CLI：捕获 stdout（它才是 agent 读的那一路），返回退出码与原文。 */
    private fun runCli(vararg argv: String): CliRun = runCli(cluster.transport, *argv)

    private fun runCli(transport: DaemonTransport, vararg argv: String): CliRun {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        val exitCode = try {
            CliRunner.run(argv.toList(), transport)
        } finally {
            System.setOut(original)
        }
        return CliRun(exitCode, buffer.toString(Charsets.UTF_8))
    }

    /**
     * 绑一个只监听、永不应答的 socket，驱动「等待响应超时」这一条路。
     *
     * 不必 `accept`：连接会被内核放进 backlog，客户端照样能写完请求，然后卡在读响应上——
     * 那正是要复现的状态。
     */
    private fun exchangeWithStuckServer(): CliRun {
        val path = home.toPath().resolve("stuck.sock")
        val stuck = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        stuck.bind(UnixDomainSocketAddress.of(path))
        return try {
            runCli(cluster.transportTo(path), "ping", "--timeout", "200")
        } finally {
            stuck.close()
        }
    }

    // -------------------------------------------------------------------------------------
    // 造数据
    // -------------------------------------------------------------------------------------

    /** 文本条目：标题就是正文（线上就是这个口径，`toMeta` 从正文派生标题）。 */
    private suspend fun seedText(
        id: String,
        text: String,
        lastCopiedAt: Long,
        copies: Int = 1,
    ) = seed(
        ClipItem(
            id = id,
            text = text,
            contents = listOf(ClipboardContent("public.utf8-plain-text", text.encodeToByteArray())),
            firstCopiedAt = lastCopiedAt,
            lastCopiedAt = lastCopiedAt,
            numberOfCopies = copies,
        ),
    )

    /**
     * 造一条「标题里没有、正文里才有」的条目。
     *
     * 走的正是线上口径：标题是正文按 `MAX_TITLE_LENGTH` 截断后落库的，正文另存一份。
     */
    private suspend fun seedLongText(id: String, text: String, lastCopiedAt: Long) = seed(
        ClipItem(
            id = id,
            text = text,
            contents = listOf(ClipboardContent("public.utf8-plain-text", text.encodeToByteArray())),
            firstCopiedAt = lastCopiedAt,
            lastCopiedAt = lastCopiedAt,
            title = text,
        ),
    )

    private suspend fun seedImage(id: String, lastCopiedAt: Long, recognizedText: String? = null) = seed(
        ClipItem(
            id = id,
            contents = listOf(ClipboardContent(PNG_CONTENT_TYPE, PNG_BYTES.copyOf())),
            firstCopiedAt = lastCopiedAt,
            lastCopiedAt = lastCopiedAt,
            title = "图片条目",
            hasRecognizedText = recognizedText != null,
            recognizedText = recognizedText,
        ),
    )

    private suspend fun seedFile(id: String, lastCopiedAt: Long) = seed(
        ClipItem(
            id = id,
            files = listOf("/tmp/不存在的文件.txt"),
            contents = listOf(
                ClipboardContent(FILE_URL_CONTENT_TYPE, "file:///tmp/不存在.txt".encodeToByteArray()),
            ),
            firstCopiedAt = lastCopiedAt,
            lastCopiedAt = lastCopiedAt,
        ),
    )

    private suspend fun seedRichText(id: String, lastCopiedAt: Long, html: String = "<p>你好世界</p>") = seed(
        ClipItem(
            id = id,
            contents = listOf(ClipboardContent("public.html", html.encodeToByteArray())),
            firstCopiedAt = lastCopiedAt,
            lastCopiedAt = lastCopiedAt,
        ),
    )

    /** 走仓库的公开写入路径，因此落盘口径与真实捕获完全一致。 */
    private suspend fun seed(item: ClipItem) = cluster.repository.insert(
        item.toMeta(),
        // 不能直接用 `item.toPayload()`：它会丢掉识别原文，而 `--ocr` 正是靠那一列。
        ClipPayload(
            contents = item.contents,
            text = item.text,
            recognizedText = item.recognizedText,
        ),
    )

    private companion object {
        const val TEST_APP_VERSION = "0.0.0-test"
        const val START_TIMEOUT_MILLIS = 15_000L

        /** 一串带 PNG 魔数的字节：`imageFormatOf` 只需魔数就能认出格式。 */
        val PNG_BYTES: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02)
    }
}
