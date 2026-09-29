package com.qcmian.clipper.core.data.local

import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `clip_meta` / `clip_payload` 两张表的单线程增删改。
 *
 * 这里守的是数据层**最容易被后续改动破坏**的几条约定：
 *
 * - 跨表写必须成对落地（`insert` / `delete` 是 `@Transaction`），不能出现「列表里有、点开是空的」；
 * - 统计、标题、置顶各自只改自己那几列，不顺手重写整行；
 * - 分页与裁剪走的是 `indexed ORDER BY`，排序口径要与 SQL 里写死的那 8 条语句一致。
 *
 * 并发路径见 [ClipHistoryDaoConcurrencyTest]。
 */
class ClipHistoryDaoCrudTest {

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

    // -----------------------------------------------------------------------------------
    // 增
    // -----------------------------------------------------------------------------------

    @Test
    fun `insert 把元数据与载荷落在同一个事务里`() = runBlocking {
        dao.insert(metaRow("a"), payloadRow("a", text = "hello"))

        assertEquals(1, dao.countAll())
        assertEquals("a", dao.loadMeta("a")?.id)
        assertEquals("hello", dao.loadPayload("a")?.text)
    }

    @Test
    fun `没有载荷的条目只写元数据`() = runBlocking {
        // 只带文件路径的条目就是这种形态：查询、列表都靠元数据，载荷行根本不存在。
        dao.insert(metaRow("b"), payload = null)

        assertEquals(1, dao.countAll())
        assertNotNull(dao.loadMeta("b"))
        assertNull(dao.loadPayload("b"))
    }

    @Test
    fun `insert 对同 id 是替换而非新增`() = runBlocking {
        dao.insert(metaRow("dup", title = "旧"), payloadRow("dup", text = "旧正文"))
        dao.insert(metaRow("dup", title = "新"), payloadRow("dup", text = "新正文"))

        assertEquals(1, dao.countAll(), "同 id 的 REPLACE 不该变成两行")
        assertEquals("新", dao.loadMeta("dup")?.title)
        assertEquals("新正文", dao.loadPayload("dup")?.text)
    }

    // -----------------------------------------------------------------------------------
    // 改
    // -----------------------------------------------------------------------------------

    @Test
    fun `updateStats 只改统计列`() = runBlocking {
        dao.insert(
            metaRow("c", title = "原标题", firstCopiedAt = 100L, lastCopiedAt = 100L, numberOfCopies = 1),
            payload = null,
        )

        dao.updateStats("c", copies = 5, lastCopiedAt = 900L)

        val updated = assertNotNull(dao.loadMeta("c"))
        assertEquals(5, updated.numberOfCopies)
        assertEquals(900L, updated.lastCopiedAt)
        assertEquals(100L, updated.firstCopiedAt, "首次复制时间不该被动")
        assertEquals("原标题", updated.title, "统计更新不该连带改标题")
    }

    @Test
    fun `updateTitle 同时翻转识别标记`() = runBlocking {
        dao.insert(metaRow("d", title = "", hasRecognizedText = false), payloadRow("d"))

        dao.updateTitle("d", title = "识别出的标题", fromRecognition = true)

        val updated = assertNotNull(dao.loadMeta("d"))
        assertEquals("识别出的标题", updated.title)
        assertTrue(updated.hasRecognizedText, "来自识别的改写必须置位标记，否则工具栏不给「复制图片文字」")
    }

    @Test
    fun `updateRecognizedText 一次写齐完整原文与标题`() = runBlocking {
        dao.insert(metaRow("e", title = "", hasRecognizedText = false), payloadRow("e"))

        dao.updateRecognizedText("e", fullText = "完整原文", title = "标题片段")

        val meta = assertNotNull(dao.loadMeta("e"))
        assertEquals("标题片段", meta.title)
        assertTrue(meta.hasRecognizedText)
        assertEquals(
            "完整原文",
            dao.loadPayload("e")?.recognizedText,
            "标题与完整原文必须一起落地，否则会出现「标题有、复制却拿不到」",
        )
    }

    @Test
    fun `updatePinned 进出置顶区并维护 pinnedAt`() = runBlocking {
        dao.insert(metaRow("f"), payload = null)
        dao.insert(metaRow("g"), payload = null)

        dao.updatePinned("f", pinned = PINNED, pinnedAt = 1234L)

        assertEquals(listOf("f"), dao.loadPinned().map { it.id })
        assertEquals(1234L, dao.loadPinned().single().pinnedAt)
        assertEquals(1, dao.countUnpinned(), "置顶项不该计入未置顶数")
        assertEquals(1234L, dao.maxPinnedAt())

        dao.updatePinned("f", pinned = UNPINNED, pinnedAt = NOT_PINNED_AT)

        assertTrue(dao.loadPinned().isEmpty())
        assertEquals(2, dao.countUnpinned())
        assertEquals(
            NOT_PINNED_AT,
            assertNotNull(dao.loadMeta("f")).pinnedAt,
            "取消置顶必须清零，否则「取消再置顶」会排到过期位置",
        )
        // 查询带 `WHERE pinned = 1`：未置顶行的占位 0 不再参与，因此「没有任何置顶」就是 null。
        // 上层 `ClipStorageDataSource.maxPinnedAt()` 用 `?: 0L` 兜底，对调用方仍是 0。
        assertNull(dao.maxPinnedAt())
    }

    @Test
    fun `updatePinned 对已删除的条目作用在 0 行上`() = runBlocking {
        // 识别是异步的：条目可能在识别期间被删掉，这条 UPDATE 必须安全地什么都不做。
        dao.updatePinned("ghost", pinned = PINNED, pinnedAt = 1L)
        dao.updateStats("ghost", copies = 9, lastCopiedAt = 9L)
        dao.updateTitle("ghost", "x", fromRecognition = false)

        assertEquals(0, dao.countAll())
    }

    // -----------------------------------------------------------------------------------
    // 删
    // -----------------------------------------------------------------------------------

    @Test
    fun `delete 同时删掉元数据与载荷`() = runBlocking {
        dao.insert(metaRow("g"), payloadRow("g", text = "g"))
        dao.insert(metaRow("h"), payloadRow("h", text = "h"))

        dao.delete(listOf("g"))

        assertNull(dao.loadMeta("g"))
        assertNull(dao.loadPayload("g"), "载荷必须随元数据一起删，不留孤儿")
        assertNotNull(dao.loadMeta("h"))
        assertNotNull(dao.loadPayload("h"))
        assertEquals(1, dao.countAll())
    }

    @Test
    fun `delete 空列表是空操作`() = runBlocking {
        dao.insert(metaRow("i"), payloadRow("i", text = "i"))
        dao.delete(emptyList())
        assertEquals(1, dao.countAll())
    }

    @Test
    fun `deleteAllUnpinned 保留置顶项`() = runBlocking {
        dao.insert(metaRow("p"), payloadRow("p", text = "p"))
        dao.updatePinned("p", pinned = PINNED, pinnedAt = 1L)
        dao.insert(metaRow("u1"), payloadRow("u1", text = "u1"))
        dao.insert(metaRow("u2"), payloadRow("u2", text = "u2"))

        dao.deleteAllUnpinned()

        assertNotNull(dao.loadMeta("p"))
        assertNotNull(dao.loadPayload("p"))
        assertNull(dao.loadMeta("u1"))
        assertNull(dao.loadPayload("u1"))
        assertNull(dao.loadPayload("u2"))
        assertEquals(1, dao.countAll())
    }

    @Test
    fun `deleteAll 清空两张表`() = runBlocking {
        dao.insert(metaRow("x"), payloadRow("x", text = "x"))
        dao.insert(metaRow("y"), payloadRow("y", text = "y"))

        dao.deleteAll()

        assertEquals(0, dao.countAll())
        assertNull(dao.loadPayload("x"))
        assertNull(dao.loadPayload("y"))
        assertEquals(0, dao.deleteOrphanPayloads())
    }

    @Test
    fun `deleteOrphanPayloads 清掉没有元数据的载荷行`() = runBlocking {
        // 直接插一条载荷来伪造「异常退出留下的孤儿」。
        dao.insertPayloads(listOf(payloadRow("orphan", text = "孤儿")))

        assertEquals(1, dao.deleteOrphanPayloads())
        assertNull(dao.loadPayload("orphan"))
        assertEquals(0, dao.deleteOrphanPayloads(), "清过一次之后就应该没有了")
    }

    // -----------------------------------------------------------------------------------
    // 读：分页、裁剪与派生查询
    // -----------------------------------------------------------------------------------

    @Test
    fun `pageUnpinned 按各排序字段与方向给出正确的页`() = runBlocking {
        // lastCopiedAt / firstCopiedAt / copies / bytes 四个维度各自独立，好确定期望顺序。
        dao.insert(metaRow("a", firstCopiedAt = 10L, lastCopiedAt = 100L, numberOfCopies = 1, payloadBytes = 1000L), null)
        dao.insert(metaRow("b", firstCopiedAt = 40L, lastCopiedAt = 300L, numberOfCopies = 2, payloadBytes = 200L), null)
        dao.insert(metaRow("c", firstCopiedAt = 30L, lastCopiedAt = 200L, numberOfCopies = 3, payloadBytes = 3000L), null)
        dao.insert(metaRow("d", firstCopiedAt = 20L, lastCopiedAt = 400L, numberOfCopies = 4, payloadBytes = 500L), null)
        // 置顶项永远不进未置顶分页——哪怕它的时间戳最大。
        dao.insert(metaRow("p", lastCopiedAt = 999L, pinned = PINNED, pinnedAt = 1L), null)

        assertEquals(
            listOf("d", "b", "c", "a"),
            ids(dao.pageUnpinned(SortBy.LAST_COPIED_AT, SortOrder.DESCENDING, limit = 10, offset = 0)),
        )
        assertEquals(
            listOf("a", "c", "b", "d"),
            ids(dao.pageUnpinned(SortBy.LAST_COPIED_AT, SortOrder.ASCENDING, limit = 10, offset = 0)),
        )
        assertEquals(
            listOf("a", "d", "c", "b"),
            ids(dao.pageUnpinned(SortBy.FIRST_COPIED_AT, SortOrder.ASCENDING, limit = 10, offset = 0)),
        )
        assertEquals(
            listOf("d", "c", "b", "a"),
            ids(dao.pageUnpinned(SortBy.NUMBER_OF_COPIES, SortOrder.DESCENDING, limit = 10, offset = 0)),
        )
        assertEquals(
            listOf("b", "d", "a", "c"),
            ids(dao.pageUnpinned(SortBy.FILE_SIZE, SortOrder.ASCENDING, limit = 10, offset = 0)),
        )
        // limit + offset 就是「翻页」：跳过新的那条，取接下来的两条。
        assertEquals(
            listOf("b", "c"),
            ids(dao.pageUnpinned(SortBy.LAST_COPIED_AT, SortOrder.DESCENDING, limit = 2, offset = 1)),
        )
    }

    @Test
    fun `overflowIds 按最后复制时间丢掉超出的那些`() = runBlocking {
        dao.insert(metaRow("a", lastCopiedAt = 100L), null)
        dao.insert(metaRow("b", lastCopiedAt = 300L), null)
        dao.insert(metaRow("c", lastCopiedAt = 200L), null)
        dao.insert(metaRow("d", lastCopiedAt = 400L), null)
        // 置顶项不受条数上限约束，永远不该出现在裁剪名单里。
        dao.insert(metaRow("p", lastCopiedAt = 50L, pinned = PINNED, pinnedAt = 1L), null)

        // 保留最近 2 条：d(400) 与 b(300)，其余都是溢出。
        assertEquals(setOf("c", "a"), dao.overflowIds(maxCount = 2).toSet())
        assertEquals(emptyList(), dao.overflowIds(maxCount = 10))
    }

    @Test
    fun `findIdByContentKey 命中已存在的摘要`() = runBlocking {
        dao.insert(metaRow("k1", contentKey = "same"), null)

        assertEquals("k1", dao.findIdByContentKey("same"))
        assertNull(dao.findIdByContentKey("missing"))
    }

    @Test
    fun `idsWithEmptyTitle 只给出空标题的条目`() = runBlocking {
        dao.insert(metaRow("t1", title = ""), null)
        dao.insert(metaRow("t2", title = "有标题"), null)

        assertEquals(listOf("t1"), dao.idsWithEmptyTitle())
    }

    @Test
    fun `maxLastCopiedAt 与 maxPinnedAt 在空库上返回 null`() = runBlocking {
        assertNull(dao.maxLastCopiedAt())
        assertNull(dao.maxPinnedAt())

        dao.insert(metaRow("m1", lastCopiedAt = 70L), null)
        dao.insert(metaRow("m2", lastCopiedAt = 30L), null)
        assertEquals(70L, dao.maxLastCopiedAt())
    }

    @Test
    fun `loadPayloadTexts 返回两段正文并跳过没有正文的行`() = runBlocking {
        dao.insertPayloads(
            listOf(
                payloadRow("t1", text = "正文一"),
                payloadRow("t2", recognizedText = "识别二"),
                payloadRow("t3", text = "正文三", recognizedText = "识别三"),
                payloadRow("t4"),
            ),
        )

        val rows = dao.loadPayloadTexts(listOf("t1", "t2", "t3", "t4"))

        assertEquals(setOf("t1", "t2", "t3"), rows.map { it.id }.toSet(), "两段正文都为空的条目不该出现")
        val t3 = assertNotNull(rows.singleOrNull { it.id == "t3" })
        assertEquals("正文三", t3.text)
        assertEquals("识别三", t3.recognizedText)
    }

    @Test
    fun `loadMeta 对不存在的 id 返回 null`() = runBlocking {
        assertNull(dao.loadMeta("nothing"))
        assertNull(dao.loadPayload("nothing"))
        assertEquals(emptyList(), dao.loadPayloadTexts(listOf("nothing")))
    }

    // -----------------------------------------------------------------------------------
    // 偏好单行
    // -----------------------------------------------------------------------------------

    @Test
    fun `偏好保存后再保存会覆盖同一行`() = runBlocking {
        val settings = database.appSettingsDao()
        assertNull(settings.load())

        settings.save(AppSettingsEntity(AppSettingsEntity.SINGLE_ROW_ID, "{\"a\":1}"))
        assertEquals("{\"a\":1}", settings.load())

        settings.save(AppSettingsEntity(AppSettingsEntity.SINGLE_ROW_ID, "{\"b\":2}"))
        assertEquals("{\"b\":2}", settings.load())
    }

    // -----------------------------------------------------------------------------------
    // 持久化
    // -----------------------------------------------------------------------------------

    @Test
    fun `关掉再打开，写入的数据仍然在`() {
        val directory = Files.createTempDirectory("clipper-db-test").toFile()
        try {
            runBlocking {
                val first = openFileDatabase(File(directory, "test.db"))
                first.clipHistoryDao().insert(metaRow("persist"), payloadRow("persist", text = "持久化"))
                first.close()
            }

            runBlocking {
                val second = openFileDatabase(File(directory, "test.db"))
                try {
                    assertEquals("持久化", second.clipHistoryDao().loadPayload("persist")?.text)
                    assertEquals(1, second.clipHistoryDao().countAll())
                } finally {
                    second.close()
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun ids(rows: List<ClipMetaEntity>): List<String> = rows.map { it.id }
}
