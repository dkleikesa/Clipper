package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.data.local.clipMeta
import com.qcmian.clipper.core.data.local.clipPayload
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * `createClipStorageDataSource()` 的**整栈冒烟**：真实 [SqlCipherDriver] + Room + TRUNCATE 回滚
 * 日志 + 单连接池，与桌面端组合根走的是同一条装配路径（其余用例都用测试自己的库工厂，绕开了
 * `~/.clipper` 与 `DatabaseKey` 这两处线上才有的接线）。
 *
 * 它守的是「装配」而不是某条 SQL：偏好落盘（线上崩掉的就是这条路径）、历史读写、并发写、换钥
 * 之后用新口令重开——任何一环接错，用户侧的表现都是「应用起不来或东西没了」。
 *
 * 库落在临时 `user.home` 下，绝不碰真实的 `~/.clipper`；退出时把 `user.home` 还原，
 * 免得影响同一 JVM 里的其它用例。
 */
class ClipStorageDataSourceSmokeTest {

    @Test
    fun `线上存储栈端到端`() = runBlocking<Unit> {
        val home = Files.createTempDirectory("clipper-smoke-home").toFile()
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        println("[smoke] 临时 HOME = ${home.absolutePath}")
        try {
            val storage = createClipStorageDataSource()
            storage.initialise()
            println("[smoke] 库已打开：${clipperDatabaseFile()} (${storage.storageBytes()} 字节)")

            // ① 偏好：线上崩掉的就是这条路径（performSuspending → BEGIN / INSERT / END）
            storage.saveSettings(AppSettings(historyMaxCount = 123))
            assertEquals(123, storage.loadSettings().historyMaxCount, "偏好必须写进去再读回来")
            repeat(50) { storage.saveSettings(AppSettings(historyMaxCount = 100 + it)) }
            println("[smoke] 偏好写入 51 次：ok")

            // ② 历史：增删改查 + 事务里的多步写
            storage.insert(clipMeta("v1", title = "验证", numberOfCopies = 1), clipPayload(text = "正文一"))
            storage.insert(clipMeta("v2", title = "第二条", lastCopiedAt = 2L), clipPayload(text = "正文二"))
            assertEquals(2, storage.countUnpinned())
            assertEquals("正文一", storage.loadPayload("v1")?.text)
            storage.updateStats("v1", numberOfCopies = 5, lastCopiedAt = 9L)
            storage.updateTitle("v1", "改过标题", fromRecognition = false)
            storage.updatePinned("v2", pinned = true, pinnedAt = 100L)
            assertEquals(listOf("v2"), storage.loadPinned().map { it.id })
            assertEquals(listOf("v1"), storage.loadUnpinned(SortBy.LAST_COPIED_AT, SortOrder.DESCENDING, 10, 0).map { it.id })
            assertEquals(1, storage.loadTexts(listOf("v1")).size)

            // ③ 并发写：单连接下应当排队而不是报锁
            coroutineScope {
                repeat(32) { index ->
                    launch(Dispatchers.IO) {
                        storage.insert(clipMeta("c-$index"), clipPayload(text = "x$index"))
                    }
                }
            }
            // v2 已置顶，不计入「未置顶」：1（v1）+ 32 条并发写入。
            assertEquals(33, storage.countUnpinned())
            assertEquals(0, storage.deleteOrphanPayloads())
            println("[smoke] 历史读写 + 32 路并发写：ok")

            // ④ 维护与删除
            storage.reclaimFreePages(0)
            storage.delete(listOf("c-0"))
            storage.deleteAllUnpinned()
            assertNull(storage.loadMeta("v1"), "清空未置顶不该留下 v1")
            assertNotNull(storage.loadMeta("v2"), "置顶项必须留着")
            println("[smoke] 维护与清空：ok")

            // ⑤ 加密往返：换钥 → 关库 → 解锁 → 用新口令重开并读回
            val rekey = storage.rekey("secret")
            assertTrue(rekey.isSuccess, "换钥应当成功：${rekey.exceptionOrNull()}")
            assertTrue(storage.isEncrypted)
            // 换钥之后继续写：新口令下的连接必须照常工作
            storage.saveSettings(AppSettings(historyMaxCount = 7))
            storage.close()

            assertTrue(isClipperDatabaseLocked(), "换钥之后文件头不该还是明文")
            assertTrue(unlockClipperDatabase("secret"), "新口令必须能解锁")
            val reopened = createClipStorageDataSource()
            try {
                assertEquals(7, reopened.loadSettings().historyMaxCount, "加密库里读出的偏好应当是最新那份")
                assertEquals("正文二", reopened.loadPayload("v2")?.text)
                reopened.saveSettings(AppSettings(historyMaxCount = 8))
                println("[smoke] 换钥 + 用新口令重开读写：ok")
            } finally {
                reopened.close()
            }

            assertFalse(unlockClipperDatabase("wrong"), "错口令必须被拒")
            println("[smoke] 全部通过")
        } finally {
            home.deleteRecursively()
            if (originalHome != null) System.setProperty("user.home", originalHome)
        }
    }
}
