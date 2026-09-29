package com.qcmian.clipper.core.data.local

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
 * 多线程并发的增删改。
 *
 * 线上模型是**单连接 + `TRUNCATE` 回滚日志**（见 `createClipStorageDataSource`），而写入方不止
 * 一个：剪贴板轮询、图片识别的异步回写、CLI socket、界面删除，都可能同时落库。因此这里不测
 * 「一个线程里连续做几步」，而是把同一批操作摊到 `Dispatchers.IO` 上真并行地打进去，只断言
 * **最终不变量**——顺序不定，但结果必须自洽：
 *
 * - 每个 id 的元数据与载荷**要么都在、要么都不在**（跨表写是 `@Transaction`）；
 * - 单行并发改写不会把它拆成两行，同一个 `UPDATE` 里的两列必须来自同一次写入；
 * - 收尾时两表不能留下任何孤儿载荷。
 *
 * 所有的异常都会顺着协程作用域冒出来让用例失败——`SQLITE_BUSY` 之类本就不该在单连接模型下出现。
 */
class ClipHistoryDaoConcurrencyTest {

    private lateinit var database: ClipperDatabase
    private lateinit var dao: ClipHistoryDao

    @BeforeTest
    fun setUp() {
        database = openInMemoryDatabase()
        dao = database.clipHistoryDao()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun `并发插入每一行都恰好落一次`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val count = 64

            coroutineScope {
                repeat(count) { index ->
                    launch(Dispatchers.IO) {
                        dao.insert(metaRow("row-$index"), payloadRow("row-$index", text = "text-$index"))
                    }
                }
            }

            assertEquals(count, dao.countAll())
            repeat(count) { index ->
                val id = "row-$index"
                assertNotNull(dao.loadMeta(id), "元数据缺失：$id")
                assertNotNull(dao.loadPayload(id), "载荷缺失：$id")
            }
            assertEquals(0, dao.deleteOrphanPayloads(), "并发插入不该留下孤儿载荷")
        }
    }

    @Test
    fun `并发插入与删除不同 id 之后两表仍然一一对应`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val seeded = 40
            repeat(seeded) { index ->
                dao.insert(metaRow("seed-$index", lastCopiedAt = index.toLong()), payloadRow("seed-$index", text = "s$index"))
            }

            val toDelete = (0 until seeded step 2).map { "seed-$it" }
            val surviving = (1 until seeded step 2).map { "seed-$it" }
            val toInsert = (0 until seeded).map { "new-$it" }

            coroutineScope {
                toDelete.forEach { id -> launch(Dispatchers.IO) { dao.delete(listOf(id)) } }
                toInsert.forEach { id -> launch(Dispatchers.IO) { dao.insert(metaRow(id), payloadRow(id, text = id)) } }
                // 同时改写幸存的那些行：改与删、删与插混在一起。
                surviving.forEach { id -> launch(Dispatchers.IO) { dao.updateStats(id, copies = 7, lastCopiedAt = 999L) } }
            }

            val expected = (surviving + toInsert).toSet()
            assertEquals(expected.size, dao.countAll())
            expected.forEach { id ->
                assertNotNull(dao.loadMeta(id), "应该还在：$id")
                assertNotNull(dao.loadPayload(id), "载荷不该丢：$id")
            }
            toDelete.forEach { id ->
                assertNull(dao.loadMeta(id), "应该已被删：$id")
                assertNull(dao.loadPayload(id), "载荷应随元数据一起删：$id")
            }
            surviving.forEach { id ->
                assertEquals(7, assertNotNull(dao.loadMeta(id)).numberOfCopies, "幸存行的统计应被改写：$id")
            }
            assertEquals(0, dao.deleteOrphanPayloads(), "增删交错不该留下孤儿载荷")
        }
    }

    @Test
    fun `并发改写同一行不会把它拆成两行`() = runBlocking<Unit> {
        withTimeout(TIMEOUT_MILLIS) {
            dao.insert(metaRow("hot", numberOfCopies = 0, lastCopiedAt = 0L), payloadRow("hot", text = "hot"))

            val writers = 100
            coroutineScope {
                repeat(writers) { value ->
                    launch(Dispatchers.IO) {
                        // 两列取自同一个 value：写进去之后它们必须仍然对得上。
                        dao.updateStats("hot", copies = value, lastCopiedAt = value.toLong())
                    }
                }
            }

            assertEquals(1, dao.countAll(), "并发改写不该产出行副本")
            val meta = assertNotNull(dao.loadMeta("hot"))
            assertTrue(meta.numberOfCopies in 0 until writers, "统计值必须来自某一次写入，实际 ${meta.numberOfCopies}")
            assertEquals(
                meta.numberOfCopies.toLong(),
                meta.lastCopiedAt,
                "两列由同一条 UPDATE 写入，必须保持一致（不能出现半新半旧）",
            )
            assertNotNull(dao.loadPayload("hot"), "改写统计不该碰掉载荷")
        }
    }

    @Test
    fun `并发插入与删除同一批 id 不会留下半条记录`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val ids = (0 until 30).map { "race-$it" }

            coroutineScope {
                ids.forEach { id ->
                    // 对同一个 id 交替插入、删除、再插入：最终状态取决于谁在最后，但每一时刻
                    // 都必须是「两表都有」或「两表都没有」。
                    launch(Dispatchers.IO) { dao.insert(metaRow(id), payloadRow(id, text = id)) }
                    launch(Dispatchers.IO) { dao.delete(listOf(id)) }
                    launch(Dispatchers.IO) { dao.insert(metaRow(id, numberOfCopies = 3), payloadRow(id, text = "again-$id")) }
                }
            }

            ids.forEach { id ->
                val meta = dao.loadMeta(id)
                val payload = dao.loadPayload(id)
                assertEquals(
                    meta != null,
                    payload != null,
                    "第 $id 条出现了半条记录：元数据=${meta != null}、载荷=${payload != null}",
                )
            }
            assertEquals(0, dao.deleteOrphanPayloads(), "不该留下孤儿载荷")
        }
    }

    @Test
    fun `并发置顶 删除 插入之后各组各就各位`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val seeded = 30
            repeat(seeded) { index ->
                dao.insert(metaRow("seed-$index"), payloadRow("seed-$index", text = "s$index"))
            }

            // 三组互不相交：只置顶、只删除、保持不动。同一条两种操作会让结果依赖调度顺序。
            val toPin = (0 until seeded step 3).map { "seed-$it" }
            val toDelete = (1 until seeded step 3).map { "seed-$it" }
            val toKeep = (2 until seeded step 3).map { "seed-$it" }
            val toInsert = (0 until 15).map { "new-$it" }

            coroutineScope {
                toPin.forEachIndexed { index, id ->
                    launch(Dispatchers.IO) { dao.updatePinned(id, pinned = PINNED, pinnedAt = 1_000L + index) }
                }
                toDelete.forEach { id -> launch(Dispatchers.IO) { dao.delete(listOf(id)) } }
                toInsert.forEach { id -> launch(Dispatchers.IO) { dao.insert(metaRow(id), payloadRow(id, text = id)) } }
            }

            assertEquals(toPin.toSet(), dao.loadPinned().map { it.id }.toSet(), "置顶集合必须与请求一致")
            assertEquals(
                1_000L + toPin.size - 1,
                dao.maxPinnedAt(),
                "置顶时间戳是各自写进去的那个值，最大值应当对得上",
            )
            toKeep.forEach { id ->
                val meta = assertNotNull(dao.loadMeta(id), "不该被删：$id")
                assertEquals(UNPINNED, meta.pinned, "没请求置顶的行不该被置顶：$id")
            }
            toDelete.forEach { id ->
                assertNull(dao.loadMeta(id), "应该已被删：$id")
                assertNull(dao.loadPayload(id), "载荷应随元数据一起删：$id")
            }
            toInsert.forEach { id ->
                val meta = assertNotNull(dao.loadMeta(id), "新插入的行不该丢：$id")
                assertEquals(UNPINNED, meta.pinned)
                assertNotNull(dao.loadPayload(id), "新插入的载荷不该丢：$id")
            }
            assertEquals(toPin.size + toKeep.size + toInsert.size, dao.countAll())
            assertEquals(0, dao.deleteOrphanPayloads())
        }
    }

    @Test
    fun `并发保存偏好始终只留一行`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            val settings = database.appSettingsDao()
            val payloads = (0 until 50).map { "{\"v\":$it}" }

            coroutineScope {
                payloads.forEach { payload ->
                    launch(Dispatchers.IO) {
                        settings.save(AppSettingsEntity(AppSettingsEntity.SINGLE_ROW_ID, payload))
                    }
                }
            }

            val stored = settings.load()
            assertNotNull(stored, "并发保存之后必须能读到一行")
            assertTrue(stored in payloads, "读到的必须是某一次写入的内容，实际 $stored")
            // 单行表的主键是 0，REPLACE 语义下永远只有一行；能读回其内容即证明没有被拆成多行。
            assertEquals(stored, settings.load())
        }
    }

    private companion object {
        /** 并发用例一旦死锁，30 秒足够暴露，不必等整个测试任务超时。 */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
