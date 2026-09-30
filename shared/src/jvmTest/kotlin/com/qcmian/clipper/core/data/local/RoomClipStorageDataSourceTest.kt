package com.qcmian.clipper.core.data.local

import com.qcmian.clipper.core.domain.model.ClipText
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.PNG_CONTENT_TYPE
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ClipFilterType
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [RoomClipStorageDataSource]：领域模型与 Room 之间的唯一适配层。
 *
 * 这一层不重复测 DAO 已经覆盖的 SQL（那是 [ClipHistoryDaoCrudTest] 的事），只测**它自己加的东西**：
 *
 * - 领域模型 ⇄ 实体的完整往返（含文件列表、来源应用、CBOR 载荷）；
 * - `loadTexts` 的展开（一行载荷 → 最多两条 `ClipText`）、空载荷不落行的跳过；
 * - 取消置顶时把 `pinnedAt` 归零、识别结果跨表成对写入；
 * - 偏好的 JSON 编码与坏数据回落；
 * - `initialise` 的幂等、`closed` 之后的全量短路；
 * - `rekey` 的会话口令写回与备份 / 回滚编排。
 *
 * 用**文件库**而不是内存库：`initialise` 的 PRAGMA + VACUUM、`reclaimFreePages` 的空闲页回收与
 * `rekey` 的整库重写都只对落盘的库有意义。
 *
 * 并发路径见 [RoomClipStorageDataSourceConcurrencyTest]。
 */
class RoomClipStorageDataSourceTest {

    private lateinit var directory: File
    private lateinit var databaseFile: File
    private lateinit var database: ClipperDatabase
    private lateinit var source: RoomClipStorageDataSource

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("clipper-datasource").toFile()
        databaseFile = File(directory, "clipper.db")
        database = openFileDatabase(databaseFile)
        source = RoomClipStorageDataSource(database, databaseBytes = { STORAGE_BYTES })
    }

    @AfterTest
    fun tearDown() {
        runCatching { source.close() }
        runCatching { database.close() }
        directory.deleteRecursively()
    }

    // -----------------------------------------------------------------------------------
    // 增删改查：领域模型往返
    // -----------------------------------------------------------------------------------

    @Test
    fun `insert 之后元数据与载荷都能原样读回`() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val contents = listOf(
            ClipboardContent("public.utf8-plain-text", "正文".encodeToByteArray()),
            ClipboardContent(PNG_CONTENT_TYPE, png),
        )
        val meta = clipMeta(
            id = "m1",
            title = "标题",
            kind = ClipFilterType.FILE,
            files = listOf("/tmp/a.txt", "/tmp/b.txt"),
            application = SourceApplication("Safari", "com.apple.Safari"),
            firstCopiedAt = 11L,
            lastCopiedAt = 22L,
            numberOfCopies = 3,
            payloadBytes = 99L,
            contentKey = "ck-1",
            hasRecognizedText = true,
            hasImage = true,
        )
        val payload = clipPayload(text = "正文", recognizedText = "识别", contents = contents)

        source.insert(meta, payload)

        assertEquals(meta, assertNotNull(source.loadMeta("m1")), "元数据的每个字段都应当往返无损")
        val loaded = assertNotNull(source.loadPayload("m1"))
        assertEquals("正文", loaded.text)
        assertEquals("识别", loaded.recognizedText)
        assertEquals(contents, loaded.contents, "CBOR 载荷应当无损往返")
        assertEquals(png.toList(), loaded.image?.toByteArray()?.toList(), "图片应当能从载荷里派生出来")
        assertEquals(1, source.countUnpinned())
    }

    @Test
    fun `空载荷不写载荷行`() = runBlocking {
        // 「只带文件路径」的条目就是这种形态：查询与列表都靠元数据，载荷行根本不该存在。
        source.insert(clipMeta("m1", kind = ClipFilterType.FILE, files = listOf("/tmp/a.txt")), clipPayload())

        assertNotNull(source.loadMeta("m1"))
        assertNull(source.loadPayload("m1"), "空载荷不该落行")
        assertEquals(emptyList(), source.loadTexts(listOf("m1")))
    }

    @Test
    fun `loadTexts 把两段正文展开成最多两条`() = runBlocking {
        source.insert(clipMeta("t1"), clipPayload(text = "正文一"))
        source.insert(clipMeta("t2"), clipPayload(recognizedText = "识别二"))
        source.insert(clipMeta("t3"), clipPayload(text = "正文三", recognizedText = "识别三"))
        source.insert(clipMeta("t4"), clipPayload())
        // 空串不是「有正文」：SQL 会把它查出来（非 NULL），但展开时应当被丢掉。
        source.insert(clipMeta("t5"), clipPayload(text = ""))

        val texts = source.loadTexts(listOf("t1", "t2", "t3", "t4", "t5"))

        assertEquals(
            setOf(
                ClipText("t1", "正文一"),
                ClipText("t2", "识别二"),
                ClipText("t3", "正文三"),
                ClipText("t3", "识别三"),
            ),
            texts.toSet(),
            "同一个条目的两段正文是两个独立条目，空串与没有载荷的都不出现",
        )
        assertEquals(emptyList(), source.loadTexts(emptyList()), "空 id 列表不该查库")
    }

    @Test
    fun `分页与裁剪按排序透传`() = runBlocking {
        source.insert(clipMeta("a", lastCopiedAt = 100L), null)
        source.insert(clipMeta("b", lastCopiedAt = 300L), null)
        source.insert(clipMeta("c", lastCopiedAt = 200L), null)

        assertEquals(
            listOf("b", "c", "a"),
            source.loadUnpinned(SortBy.LAST_COPIED_AT, SortOrder.DESCENDING, limit = 10, offset = 0)
                .map { it.id },
        )
        assertEquals(
            listOf("c", "a"),
            source.loadUnpinned(SortBy.LAST_COPIED_AT, SortOrder.DESCENDING, limit = 2, offset = 1)
                .map { it.id },
        )
        assertEquals(setOf("c", "a"), source.overflowIds(maxCount = 1).toSet())
    }

    @Test
    fun `findIdByContentKey 与 emptyTitleIds 走的是同一个库`() = runBlocking {
        source.insert(clipMeta("k1", contentKey = "same"), null)
        source.insert(clipMeta("k2", title = ""), null)

        assertEquals("k1", source.findIdByContentKey("same"))
        assertNull(source.findIdByContentKey("missing"))
        assertEquals(listOf("k2"), source.emptyTitleIds())
    }

    @Test
    fun `updatePinned 取消置顶时把 pinnedAt 归零`() = runBlocking {
        source.insert(clipMeta("p"), null)

        source.updatePinned("p", pinned = true, pinnedAt = 1234L)
        assertEquals(listOf("p"), source.loadPinned().map { it.id })
        val pinned = assertNotNull(database.clipHistoryDao().loadMeta("p"))
        assertEquals(1, pinned.pinned)
        assertEquals(1234L, pinned.pinnedAt)

        // 取消置顶必须清零：留着旧值会让「先取消、再重新置顶」排到过期位置上。
        source.updatePinned("p", pinned = false, pinnedAt = 1234L)
        assertTrue(source.loadPinned().isEmpty())
        val unpinned = assertNotNull(database.clipHistoryDao().loadMeta("p"))
        assertEquals(0, unpinned.pinned)
        assertEquals(0L, unpinned.pinnedAt, "pinnedAt 该由这一层清零，而不是听调用方传什么")
    }

    @Test
    fun `updateRecognizedText 同时写进标题与完整原文`() = runBlocking {
        // 识别针对的是图片条目，所以载荷里带着图片表示（非空载荷才会落行）。
        source.insert(
            clipMeta("r", title = "", hasRecognizedText = false),
            clipPayload(contents = listOf(ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(1, 2, 3)))),
        )

        source.updateRecognizedText("r", fullText = "完整原文", title = "标题片段")

        val meta = assertNotNull(source.loadMeta("r"))
        assertEquals("标题片段", meta.title)
        assertTrue(meta.hasRecognizedText)
        assertEquals("完整原文", source.loadPayload("r")?.recognizedText)
    }

    @Test
    fun `删除与清空`() = runBlocking {
        source.insert(clipMeta("keep"), clipPayload(text = "k"))
        source.updatePinned("keep", pinned = true, pinnedAt = 1L)
        source.insert(clipMeta("gone"), clipPayload(text = "g"))

        source.delete(listOf("gone"))
        assertNull(source.loadMeta("gone"))
        assertNull(source.loadPayload("gone"))

        source.insert(clipMeta("gone2"), clipPayload(text = "g2"))
        source.deleteAllUnpinned()
        assertNotNull(source.loadMeta("keep"), "清空未置顶不该动置顶项")
        assertNull(source.loadMeta("gone2"))

        source.deleteAll()
        assertEquals(0, source.countUnpinned())
        assertEquals(emptyList(), source.loadPinned())
    }

    // -----------------------------------------------------------------------------------
    // 偏好
    // -----------------------------------------------------------------------------------

    @Test
    fun `偏好往返并能在没有数据时回落默认值`() = runBlocking {
        assertEquals(AppSettings(), source.loadSettings(), "空库应当回落默认偏好")

        val settings = AppSettings(
            historyMaxCount = 42,
            sortBy = SortBy.NUMBER_OF_COPIES,
            sortOrder = SortOrder.ASCENDING,
            showInStatusBar = false,
        )
        source.saveSettings(settings)
        assertEquals(settings, source.loadSettings(), "偏好应当无损往返")

        // 坏数据（旧版本写的、被截断的）不能让启动路径抛出去，只能回落默认值。
        database.appSettingsDao().save(AppSettingsEntity(AppSettingsEntity.SINGLE_ROW_ID, "{ 不是合法 json"))
        assertEquals(AppSettings(), source.loadSettings())
    }

    // -----------------------------------------------------------------------------------
    // 初始化与维护
    // -----------------------------------------------------------------------------------

    @Test
    fun `initialise 清理孤儿载荷，而且只跑一次`() = runBlocking<Unit> {
        // 绕过事务路径直接塞一条没有元数据的载荷，伪造「上一次会话异常退出留下的孤儿」。
        // 这一步顺带守着一个回归：先写过再 initialise，内部的 VACUUM 不能因为「有语句没收尾」被拒。
        database.clipHistoryDao().insertPayloads(listOf(payloadRow("orphan", text = "x")))

        source.initialise()
        assertNull(database.clipHistoryDao().loadPayload("orphan"), "第一次 initialise 应当清掉孤儿")

        // 再放一条：第二次 initialise 必须直接返回（`prepared` 已置位），否则它会在启动路径上
        // 反复跑 PRAGMA 与整库 VACUUM。
        database.clipHistoryDao().insertPayloads(listOf(payloadRow("orphan2", text = "x")))
        source.initialise()
        assertNotNull(
            database.clipHistoryDao().loadPayload("orphan2"),
            "第二次 initialise 不该再动库——清掉它就说明这条一次性的保护失效了",
        )
    }

    @Test
    fun `storageBytes 用宿主给的口径`() = runBlocking {
        assertEquals(STORAGE_BYTES, source.storageBytes())

        val withoutSize = RoomClipStorageDataSource(database)
        assertNull(withoutSize.storageBytes(), "宿主测不了大小时应当给 null，而不是 0")
    }

    @Test
    fun `reclaimFreePages 只在空闲页够多时才动手`() = runBlocking {
        // 先 initialise，让 auto_vacuum = INCREMENTAL 落定（否则 incremental_vacuum 是空操作）。
        source.initialise()
        repeat(200) { index ->
            source.insert(clipMeta("big-$index"), clipPayload(text = "x".repeat(1_024)))
        }
        source.deleteAll()

        val before = databaseFile.length()
        source.reclaimFreePages(Long.MAX_VALUE)
        assertEquals(before, databaseFile.length(), "阈值没到就不该有任何动作")

        source.reclaimFreePages(0L)
        assertTrue(
            databaseFile.length() <= before,
            "回收之后文件不该变大（before=$before, after=${databaseFile.length()}）",
        )
    }

    // -----------------------------------------------------------------------------------
    // 关闭后的短路
    // -----------------------------------------------------------------------------------

    @Test
    fun `close 之后所有读写都安全短路`() = runBlocking {
        source.insert(clipMeta("before"), clipPayload(text = "在关闭之前"))
        source.close()

        // 读：一律给空值，不抛异常——进程退出路径上的落盘不需要感知「已经关了」。
        assertEquals(0, source.countUnpinned())
        assertEquals(emptyList(), source.loadPinned())
        assertEquals(emptyList(), source.loadUnpinned(SortBy.LAST_COPIED_AT, SortOrder.DESCENDING, 10, 0))
        assertNull(source.loadMeta("before"))
        assertNull(source.findIdByContentKey("key-before"))
        assertEquals(emptyList(), source.overflowIds(1))
        assertNull(source.loadPayload("before"))
        assertEquals(emptyList(), source.loadTexts(listOf("before")))
        assertEquals(0L, source.maxLastCopiedAt())
        assertEquals(0L, source.maxPinnedAt())
        assertEquals(emptyList(), source.emptyTitleIds())
        assertEquals(0, source.deleteOrphanPayloads())
        assertEquals(AppSettings(), source.loadSettings())
        // 大小是宿主现测的，与库开没开无关——这一条刻意保留，界面在关闭后仍要能显示数字。
        assertEquals(STORAGE_BYTES, source.storageBytes())

        // 写：静默忽略，不抛异常。
        source.insert(clipMeta("after"), clipPayload(text = "在关闭之后"))
        source.updateStats("before", numberOfCopies = 9, lastCopiedAt = 9L)
        source.updateTitle("before", "改标题", fromRecognition = false)
        source.updateRecognizedText("before", fullText = "原文", title = "标题")
        source.updatePinned("before", pinned = true, pinnedAt = 1L)
        source.delete(listOf("before"))
        source.deleteAllUnpinned()
        source.deleteAll()
        source.saveSettings(AppSettings(historyMaxCount = 1))
        source.reclaimFreePages(0L)
        source.initialise()
    }

    // -----------------------------------------------------------------------------------
    // 加密
    // -----------------------------------------------------------------------------------

    @Test
    fun `supportsEncryption 与 isEncrypted 跟随会话密钥`() = runBlocking {
        assertFalse(source.supportsEncryption, "没注入会话密钥的平台应当自报不支持")
        assertFalse(source.isEncrypted)

        val key = FakeSessionKey(encrypted = true)
        val withKey = RoomClipStorageDataSource(database, sessionKey = key)
        assertTrue(withKey.supportsEncryption)
        assertTrue(withKey.isEncrypted)
    }

    @Test
    fun `rekey 在全新库上成功并丢弃备份`() = runBlocking {
        val key = FakeSessionKey()
        val backup = FakeBackup()
        val encrypting = RoomClipStorageDataSource(database, sessionKey = key, backup = backup)

        val result = encrypting.rekey("secret")

        assertTrue(result.isSuccess, "全新库上没有写过任何东西，换钥应当成功：${result.exceptionOrNull()}")
        assertEquals("secret", key.passphrase, "成功后必须把新口令写回会话")
        assertEquals(1, key.updates)
        assertEquals(1, backup.snapshots, "重写整库之前要先备份")
        assertEquals(1, backup.discards, "成功了就丢掉备份")
        assertEquals(0, backup.restores)
    }

    @Test
    fun `rekey 传 null 表示解密回明文库`() = runBlocking {
        val key = FakeSessionKey()
        val backup = FakeBackup()
        val encrypting = RoomClipStorageDataSource(database, sessionKey = key, backup = backup)

        assertTrue(encrypting.rekey(null).isSuccess, "明文库上换「空口令」应当是解密方向")

        assertNull(key.passphrase, "解密成功后会话里不该再留着口令")
        assertEquals(1, key.updates)
    }

    @Test
    fun `verifyPassphrase 由宿主的口令校验器回答，没接校验器时一律不通过`() = runBlocking {
        // 没注入校验器：不能因为「没有异议」就放行——关掉加密是不可逆的保护降级。
        assertFalse(source.verifyPassphrase("secret"))

        val asked = mutableListOf<String>()
        val verifying = RoomClipStorageDataSource(
            database = database,
            sessionKey = FakeSessionKey(encrypted = true),
            passphraseVerifier = { passphrase ->
                asked += passphrase
                passphrase == "secret"
            },
        )

        assertTrue(verifying.verifyPassphrase("secret"))
        assertFalse(verifying.verifyPassphrase("wrong"), "口令不对时不能放行")
        assertEquals(listOf("secret", "wrong"), asked, "判据由宿主给，这一层只负责转发")

        verifying.close()
        assertFalse(verifying.verifyPassphrase("secret"), "关闭之后不该再碰库")
    }

    @Test
    fun `rekey 在平台不支持时直接失败`() = runBlocking {
        // `source` 没注入会话密钥。
        val result = source.rekey("secret")

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull() is UnsupportedOperationException,
            "不支持加密的平台应当报「不支持」，而不是一个 SQL 错误：${result.exceptionOrNull()}",
        )
    }

    @Test
    fun `rekey 在已关闭时失败`() = runBlocking {
        val encrypting = RoomClipStorageDataSource(database, sessionKey = FakeSessionKey())
        encrypting.close()

        val result = encrypting.rekey("secret")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `备份没做成时不做回滚也不丢弃`() = runBlocking {
        val backup = FakeBackup(snapshotSucceeds = false)
        val encrypting = RoomClipStorageDataSource(database, sessionKey = FakeSessionKey(), backup = backup)

        assertTrue(encrypting.rekey("secret").isSuccess, "备份失败只是少一层保险，不该拦住换钥")

        assertEquals(1, backup.snapshots)
        assertEquals(0, backup.restores, "压根没备份成功，回滚会把库改坏")
        assertEquals(0, backup.discards)
    }

    @Test
    fun `写回会话口令失败时用备份回滚`() = runBlocking {
        // 真实口令换钥在这条路径上不好造失败；而「会话口令写回失败」正好是 `runCatching` 里
        // 会走到回滚的那一支，用它来验证备份 / 回滚的编排。
        val backup = FakeBackup()
        val encrypting = RoomClipStorageDataSource(
            database = database,
            sessionKey = FakeSessionKey(failOnUpdate = true),
            backup = backup,
        )

        val result = encrypting.rekey("secret")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(1, backup.snapshots)
        assertEquals(1, backup.restores, "换钥失败必须把备份换回去")
        assertEquals(0, backup.discards)
    }

    /**
     * 回归：`rekey` 曾经在「这条连接上执行过任意一次写」之后**必然**失败
     * （`cannot VACUUM - SQL statements in progress`），而关库重开（新连接）就正常。
     *
     * 根因在驱动：sqlite-jdbc 只在「下一次执行前」或「关闭结果集」时 `sqlite3_reset`，而写语句
     * 两条路都走不到，于是长期挂在 `nVdbeActive` 上，`PRAGMA rekey` 内部那次 VACUUM 被拒。
     * 当时四种写都复现，所以这里四种都过一遍——复制过内容的库才是常态。
     */
    @Test
    fun `写过数据之后 rekey 仍然成功`() = runBlocking<Unit> {
        val key = FakeSessionKey()
        val encrypting = RoomClipStorageDataSource(database, sessionKey = key, backup = FakeBackup())
        encrypting.insert(clipMeta("written"), clipPayload(text = "写过"))
        encrypting.updateStats("written", numberOfCopies = 2, lastCopiedAt = 5L)
        encrypting.updateTitle("written", title = "改过标题", fromRecognition = false)
        encrypting.updatePinned("written", pinned = true, pinnedAt = 7L)
        encrypting.insert(clipMeta("doomed"), clipPayload(text = "待删"))
        encrypting.delete(listOf("doomed"))

        val result = encrypting.rekey("secret")

        assertTrue(result.isSuccess, "复制过内容的库才是常态，这条路必须能走通：${result.exceptionOrNull()}")
        assertEquals("secret", key.passphrase)
    }

    @Test
    fun `rekey 之后要用新口令才打得开库`() = runBlocking<Unit> {
        // 本用例自己开库，别让 setUp 那个实例占着同一个文件。
        database.close()
        // 与线上同构：驱动每次建连接都现读会话口令（`DatabaseKey::current`）。
        val key = MutableSessionKey()
        val encrypting = RoomClipStorageDataSource(
            database = openFileDatabase(databaseFile, passphrase = { key.current }),
            sessionKey = key,
            backup = FakeBackup(),
        )
        encrypting.insert(clipMeta("written"), clipPayload(text = "写过"))
        assertTrue(encrypting.rekey("secret").isSuccess)
        encrypting.close()

        val withNewKey = openFileDatabase(databaseFile, passphrase = { "secret" })
        try {
            assertEquals("写过", withNewKey.clipHistoryDao().loadPayload("written")?.text, "数据应当在新口令的库里")
        } finally {
            withNewKey.close()
        }

        // 旧口令（空）必须打不开——否则「加密」就只是把口令记在了内存里。
        val withOldKey = openFileDatabase(databaseFile, passphrase = { null })
        try {
            assertFailsWith<Exception> { withOldKey.clipHistoryDao().countAll() }
        } finally {
            withOldKey.close()
        }
    }

    private companion object {
        const val STORAGE_BYTES = 4_096L
    }
}
