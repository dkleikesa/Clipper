package com.qcmian.clipper.core.data.local

import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [RoomClipStorageDataSource] 的并发行为。
 *
 * 这一层**没有任何自己的锁**——串行化完全依赖仓储层的 `metadataLock` 与底层的单连接池。而真实
 * 调用方并不只有界面：剪贴板轮询、图片识别的异步回写、CLI 的 socket 请求都可能同时打进来。
 * 所以这里把它们摊到 `Dispatchers.IO` 上真并行地发，只断言**最终不变量**：
 *
 * - 每一条写进去的记录都能完整读回，没有「元数据有、载荷空」的半条；
 * - 增删改交错之后两表仍然一一对应，收尾不留孤儿载荷；
 * - 读写交错不抛异常（`TRUNCATE` 单连接的正常表现是排队，而不是 `SQLITE_BUSY`）；
 * - 偏好单行表在并发保存之后仍然只有一行。
 *
 * 库用内存库：驱动与日志模式（`TRUNCATE`）与线上完全一致，只是不落盘，省掉临时目录。
 * 这里刻意**不调用** `initialise()`——它含一次整库 VACUUM，对内存库没有意义。
 *
 * 单线程行为见 [RoomClipStorageDataSourceTest]。
 */
class RoomClipStorageDataSourceConcurrencyTest {

    private lateinit var database: ClipperDatabase
    private lateinit var source: RoomClipStorageDataSource

    @BeforeTest
    fun setUp() {
        database = openInMemoryDatabase()
        source = RoomClipStorageDataSource(database, databaseBytes = { null })
    }

    @AfterTest
    fun tearDown() {
        runCatching { source.close() }
    }

    @Test
    fun `并发插入后每一条都能完整读回`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val count = 64

            coroutineScope {
                repeat(count) { index ->
                    launch(Dispatchers.IO) {
                        source.insert(
                            meta = clipMeta("row-$index", title = "标题 $index"),
                            payload = clipPayload(
                                text = "正文 $index",
                                recognizedText = "识别 $index",
                                contents = listOf(textContent("正文 $index")),
                            ),
                        )
                    }
                }
            }

            assertEquals(count, source.countUnpinned())
            repeat(count) { index ->
                val id = "row-$index"
                val meta = assertNotNull(source.loadMeta(id), "元数据缺失：$id")
                assertEquals("标题 $index", meta.title)
                val payload = assertNotNull(source.loadPayload(id), "载荷缺失：$id")
                assertEquals("正文 $index", payload.text)
                assertEquals("识别 $index", payload.recognizedText)
                assertEquals(listOf(textContent("正文 $index")), payload.contents, "$id 的载荷被改写了")
            }
            assertEquals(0, source.deleteOrphanPayloads(), "并发插入不该留下孤儿载荷")
        }
    }

    @Test
    fun `并发增删改交错之后两表仍然一一对应`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val seeded = 40
            repeat(seeded) { index ->
                source.insert(clipMeta("seed-$index", lastCopiedAt = index.toLong()), clipPayload(text = "s$index"))
            }
            // 三组互不相交：只删、只改、只新增。同一条上有两种操作会让结果取决于调度顺序。
            val toDelete = (0 until seeded step 2).map { "seed-$it" }
            val toUpdate = (1 until seeded step 2).map { "seed-$it" }
            val toInsert = (0 until seeded).map { "new-$it" }

            coroutineScope {
                toDelete.forEach { id -> launch(Dispatchers.IO) { source.delete(listOf(id)) } }
                toUpdate.forEach { id ->
                    launch(Dispatchers.IO) { source.updateStats(id, numberOfCopies = 7, lastCopiedAt = 999L) }
                }
                toInsert.forEach { id ->
                    launch(Dispatchers.IO) {
                        source.insert(clipMeta(id), clipPayload(text = id))
                    }
                }
            }

            val expected = (toUpdate + toInsert).toSet()
            assertEquals(expected.size, source.countUnpinned())
            expected.forEach { id ->
                assertNotNull(source.loadMeta(id), "应该还在：$id")
                assertNotNull(source.loadPayload(id), "载荷不该丢：$id")
            }
            toDelete.forEach { id ->
                assertNull(source.loadMeta(id), "应该已被删：$id")
                assertNull(source.loadPayload(id), "载荷应随元数据一起删：$id")
            }
            toUpdate.forEach { id ->
                assertEquals(7, assertNotNull(source.loadMeta(id)).numberOfCopies, "幸存行的统计应被改写：$id")
            }
            assertEquals(0, source.deleteOrphanPayloads(), "增删改交错不该留下孤儿载荷")
        }
    }

    @Test
    fun `读写交错不抛异常`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val writers = 24
            val readers = 6
            val rounds = 20

            coroutineScope {
                repeat(writers) { index ->
                    launch(Dispatchers.IO) {
                        source.insert(clipMeta("w-$index"), clipPayload(text = "w$index"))
                    }
                }
                repeat(readers) { reader ->
                    launch(Dispatchers.IO) {
                        repeat(rounds) {
                            // 这些读全都可能和写入撞在一起：单连接下应当排队，而不是报 SQLITE_BUSY。
                            source.countUnpinned()
                            source.loadUnpinned(SortBy.LAST_COPIED_AT, SortOrder.DESCENDING, limit = 10, offset = 0)
                            source.loadPinned()
                            source.maxLastCopiedAt()
                            source.loadTexts(listOf("w-$reader", "w-${reader + 1}"))
                            source.emptyTitleIds()
                        }
                    }
                }
            }

            assertEquals(writers, source.countUnpinned())
            assertEquals(0, source.deleteOrphanPayloads())
        }
    }

    @Test
    fun `并发保存偏好始终只留一行`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val values = (1..50).toList()

            coroutineScope {
                values.forEach { value ->
                    launch(Dispatchers.IO) {
                        source.saveSettings(AppSettings(historyMaxCount = value))
                    }
                }
            }

            val stored = source.loadSettings()
            assertTrue(
                stored.historyMaxCount in values,
                "读到的必须是某一次写入的值，实际 ${stored.historyMaxCount}",
            )
            // 单行表的键固定为 0：能稳定读回同一份，就说明并发保存没有把它拆成多行。
            assertEquals(stored, source.loadSettings())
        }
    }

    @Test
    fun `并发写入收尾之后 close 再读应当是空的`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            coroutineScope {
                repeat(32) { index ->
                    launch(Dispatchers.IO) {
                        source.insert(clipMeta("c-$index"), clipPayload(text = "c$index"))
                    }
                }
            }
            assertEquals(32, source.countUnpinned())

            // 关闭必须发生在上面的协程全部收尾之后——数据源自己没有针对并发 close 的保护。
            source.close()

            assertEquals(0, source.countUnpinned())
            assertNull(source.loadMeta("c-0"))
            assertEquals(emptyList(), source.loadTexts(listOf("c-0")))
        }
    }

    private fun textContent(text: String) = ClipboardContent(
        type = "public.utf8-plain-text",
        value = text.encodeToByteArray(),
    )

    private companion object {
        /** 并发用例一旦死锁，30 秒足够暴露，不必等整个测试任务超时。 */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
